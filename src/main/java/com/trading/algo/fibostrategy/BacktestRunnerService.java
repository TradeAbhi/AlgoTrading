package com.trading.algo.fibostrategy;

import com.trading.algo.config.BacktestConfig;
import com.trading.algo.dtos.BacktestSummaryDTO;
import com.trading.algo.dtos.Candle;
import com.trading.algo.entity.BacktestTrade;
import com.trading.algo.entity.BacktestTrade.Outcome;
import com.trading.algo.repo.BacktestTradeRepository;
import com.trading.algo.service.UniverseService;
import com.trading.algo.upstox.UpstoxHistoricalCandleService;
import com.trading.algo.upstox.UpstoxInstrumentMasterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Orchestrates the backtest with parallel symbol processing and a
 * proper token-bucket rate limiter shared across all threads.
 *
 * Optimisations applied (functionality unchanged):
 *   1. Batch DB writes   — collect all trades per day, saveAll() once instead of save() per trade
 *   2. Remove inner API loop — existsBySymbolAndTradeDate() was firing one SELECT per symbol;
 *                              replaced with a Set<String> lookup built once per date
 *   3. Parallel processing — already in place via CompletableFuture thread pool
 *   4. Remove BigDecimal  — not used here; confirmed all math is already primitive double
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestRunnerService {

    private final UpstoxHistoricalCandleService  candleService;
    private final UpstoxInstrumentMasterService  instrumentMaster;
    private final OpeningCandleStrategyService   strategy;
    private final BacktestTradeRepository        tradeRepo;
    private final BacktestConfig                 config;

    /**
     * Tracks the earliest time (nanoTime) the next API call is allowed.
     * Each thread that acquires a slot pushes this forward by minGapNs.
     * This guarantees global spacing — not per-thread spacing.
     */
    private final AtomicLong nextAllowedCallNs = new AtomicLong(0);

    // =========================================================================
    // Main run
    // =========================================================================

    public BacktestSummaryDTO run(LocalDate fromDate, LocalDate toDate, boolean clearOld) {
        log.info("Backtest START — from={} to={} clearOld={}", fromDate, toDate, clearOld);

        if (clearOld) {
            // OPTIMIZATION (Remove API loop): deleteAllInBatch is faster than deleteAll
            // OLD: tradeRepo.deleteAll(old)
            // NEW: deleteAllInBatch skips the per-entity lifecycle callbacks — much faster for bulk deletes
            List<BacktestTrade> old = tradeRepo.findByTradeDateBetweenOrderByTradeDateAsc(fromDate, toDate);
            tradeRepo.deleteAllInBatch(old);
            log.info("Cleared {} existing trades in date range", old.size());
        }

        List<String> fnoSymbols = UniverseService.NIFTY_FNO_SYMBOLS;

        // Use map-based resolution — avoids index misalignment when some symbols
        // are not found in the master (old list-based approach caused wrong symbol
        // getting wrong instrument key, resulting in incorrect price data)
        Map<String, String> symbolKeyMap = instrumentMaster.resolveToInstrumentKeyMap(fnoSymbols);

        log.info("Resolved {}/{} F&O symbols to instrument keys", symbolKeyMap.size(), fnoSymbols.size());
        log.info("Rate limit: {} req/sec ({}ms min gap between requests)",
                config.getRequestsPerSecond(),
                1000 / config.getRequestsPerSecond());

        List<LocalDate> tradingDays = getTradingDays(fromDate, toDate);
        log.info("Trading days to process: {}", tradingDays.size());

        // Reset rate limiter for this run
        nextAllowedCallNs.set(System.nanoTime());

        AtomicInteger totalSignals   = new AtomicInteger(0);
        AtomicInteger totalProcessed = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(config.getThreadPoolSize());

        try {
            for (int d = 0; d < tradingDays.size(); d++) {
                LocalDate date = tradingDays.get(d);
                log.info("Processing date: {} ({}/{})", date, d + 1, tradingDays.size());

                // OPTIMIZATION (Remove API loop): build a Set of already-processed symbols
                // for this date in ONE query instead of firing existsBySymbolAndTradeDate()
                // (one SELECT per symbol) inside every thread.
                // OLD: tradeRepo.existsBySymbolAndTradeDate(symbol, date) — called 228 times per day
                // NEW: one query → Set → O(1) contains() check per symbol
                java.util.Set<String> alreadyProcessed = tradeRepo
                        .findByTradeDateBetweenOrderByTradeDateAsc(date, date)
                        .stream()
                        .map(BacktestTrade::getSymbol)
                        .collect(java.util.stream.Collectors.toSet());

                // OPTIMIZATION (Batch DB): thread-safe list to collect all trades found this day
                // OLD: synchronized(tradeRepo) { tradeRepo.save(trade) } — one INSERT per trade, serialised
                // NEW: collect into CopyOnWriteArrayList, then saveAll() once after all threads finish
                CopyOnWriteArrayList<BacktestTrade> dayTrades = new CopyOnWriteArrayList<>();

                List<CompletableFuture<Void>> futures = symbolKeyMap.entrySet().stream()
                        .map(entry -> CompletableFuture.runAsync(() ->
                                processSymbol(entry.getKey(), entry.getValue(), date,
                                        alreadyProcessed, dayTrades,
                                        totalSignals, totalProcessed), pool))
                        .collect(Collectors.toList());

                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

                // OPTIMIZATION (Batch DB): single saveAll() per day instead of one save() per trade
                // OLD: tradeRepo.save(trade) called individually inside each thread
                // NEW: saveAll() issues one batched INSERT for all trades found today
                if (!dayTrades.isEmpty()) {
                    tradeRepo.saveAll(dayTrades);
                    log.info("Date {} done — {} signals saved (batch), total so far: {}",
                            date, dayTrades.size(), totalSignals.get());
                } else {
                    log.info("Date {} done — no signals, signals so far: {}", date, totalSignals.get());
                }
            }
        } finally {
            pool.shutdown();
            try {
                pool.awaitTermination(10, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        log.info("Backtest COMPLETE — processed={} signals={}", totalProcessed.get(), totalSignals.get());
        return buildSummary(fromDate, toDate, fnoSymbols.size());
    }

    // =========================================================================
    // Rate limiter — token bucket via CAS on AtomicLong (unchanged)
    // =========================================================================

    private void acquireRateLimit() throws InterruptedException {
        long minGapNs = TimeUnit.MILLISECONDS.toNanos(1000L / config.getRequestsPerSecond());

        while (true) {
            long now      = System.nanoTime();
            long current  = nextAllowedCallNs.get();
            long mySlot   = Math.max(current, now);
            long nextSlot = mySlot + minGapNs;

            if (nextAllowedCallNs.compareAndSet(current, nextSlot)) {
                long waitNs = mySlot - System.nanoTime();
                if (waitNs > 0) {
                    Thread.sleep(waitNs / 1_000_000, (int)(waitNs % 1_000_000));
                }
                return;
            }
        }
    }

    // =========================================================================
    // Per-symbol processing
    // =========================================================================

    private void processSymbol(String symbol, String instrumentKey, LocalDate date,
                                // OPTIMIZATION (Remove API loop): replaced existsBySymbolAndTradeDate()
                                // DB call with O(1) Set lookup — same functionality, no DB hit per symbol
                                java.util.Set<String> alreadyProcessed,
                                // OPTIMIZATION (Batch DB): collect trades here, saveAll() after all threads done
                                CopyOnWriteArrayList<BacktestTrade> dayTrades,
                                AtomicInteger totalSignals, AtomicInteger totalProcessed) {
        try {
            // OPTIMIZATION (Remove API loop): was tradeRepo.existsBySymbolAndTradeDate(symbol, date)
            // Now O(1) Set.contains() — no DB round-trip per symbol
            if (alreadyProcessed.contains(symbol)) {
                return;
            }

            // Block until rate limiter grants permission (unchanged)
            acquireRateLimit();

            List<Candle> candles = candleService.fetchDayCandles(instrumentKey, date);

            if (candles.isEmpty()) {
                log.debug("{} {} — no candles (holiday or no data)", symbol, date);
                return;
            }

            Optional<BacktestTrade> trade = strategy.evaluate(symbol, date, candles);

            if (trade.isPresent()) {
                // OPTIMIZATION (Batch DB): was synchronized(tradeRepo) { tradeRepo.save(trade) }
                // Now just add to thread-safe list — actual DB write happens in batch after this date completes
                dayTrades.add(trade.get());
                totalSignals.incrementAndGet();
                log.info("  ✅ {} {} {} → {}  P&L={:.2f}pts",
                        symbol, date,
                        trade.get().getDirection(),
                        trade.get().getOutcome(),
                        trade.get().getPnlPoints());
            }

            totalProcessed.incrementAndGet();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Error processing {} on {}: {}", symbol, date, e.getMessage());
        }
    }

    // =========================================================================
    // Summary builder (unchanged)
    // =========================================================================

    public BacktestSummaryDTO buildSummary(LocalDate fromDate, LocalDate toDate, int totalSymbols) {
        List<BacktestTrade> trades = tradeRepo.findByTradeDateBetweenOrderByTradeDateAsc(fromDate, toDate);

        if (trades.isEmpty()) {
            return BacktestSummaryDTO.builder()
                    .fromDate(fromDate).toDate(toDate)
                    .totalSignals(0).totalSymbolsScanned(totalSymbols)
                    .build();
        }

        long wins       = trades.stream().filter(t -> t.getOutcome() == Outcome.TARGET_HIT).count();
        long losses     = trades.stream().filter(t -> t.getOutcome() == Outcome.SL_HIT).count();
        long beExits    = trades.stream().filter(t -> t.getOutcome() == Outcome.BREAKEVEN_EXIT).count();
        long eodExits   = trades.stream().filter(t -> t.getOutcome() == Outcome.EOD_EXIT).count();
        long volFlagged = trades.stream().filter(t -> Boolean.TRUE.equals(t.getVolumeFlag())).count();

        double totalPnl       = trades.stream().mapToDouble(BacktestTrade::getPnlPoints).sum();
        double totalPnlRupees = trades.stream().mapToDouble(BacktestTrade::getPnlRupees).sum();
        double avgRiskRupees  = trades.stream().mapToDouble(BacktestTrade::getRiskRupees).average().orElse(0);

        double avgWin = trades.stream()
                .filter(t -> t.getOutcome() == Outcome.TARGET_HIT)
                .mapToDouble(BacktestTrade::getPnlPoints)
                .average().orElse(0);

        double avgLoss = trades.stream()
                .filter(t -> t.getOutcome() == Outcome.SL_HIT)
                .mapToDouble(t -> Math.abs(t.getPnlPoints()))
                .average().orElse(0);

        double avgRR = trades.stream()
                .filter(t -> t.getOutcome() != Outcome.OPEN)
                .mapToDouble(BacktestTrade::getActualRR)
                .average().orElse(0);

        double winRate    = (double) wins / trades.size() * 100.0;
        double lossRate   = 100.0 - winRate;
        double expectancy = (winRate / 100.0 * avgWin) - (lossRate / 100.0 * avgLoss);

        long symbolsWithSignal = trades.stream()
                .map(BacktestTrade::getSymbol).distinct().count();

        List<Object[]> symbolData = tradeRepo.symbolSummary(fromDate, toDate);
        List<BacktestSummaryDTO.SymbolStat> symbolStats = symbolData.stream()
                .map(row -> {
                    String sym   = (String) row[0];
                    long   total = (long)   row[1];
                    long   w     = (long)   row[2];
                    double pnl   = row[3] != null ? ((Number) row[3]).doubleValue() : 0.0;
                    return BacktestSummaryDTO.SymbolStat.builder()
                            .symbol(sym)
                            .totalTrades((int) total)
                            .wins((int) w)
                            .winRate(total > 0 ? (double) w / total * 100 : 0)
                            .totalPnlPoints(pnl)
                            .build();
                })
                .collect(Collectors.toList());

        List<BacktestSummaryDTO.SymbolStat> top10 = symbolStats.stream()
                .sorted((a, b) -> Double.compare(b.getTotalPnlPoints(), a.getTotalPnlPoints()))
                .limit(10).collect(Collectors.toList());

        List<BacktestSummaryDTO.SymbolStat> worst10 = symbolStats.stream()
                .sorted((a, b) -> Double.compare(a.getTotalPnlPoints(), b.getTotalPnlPoints()))
                .limit(10).collect(Collectors.toList());

        return BacktestSummaryDTO.builder()
                .fromDate(fromDate).toDate(toDate)
                .totalSignals(trades.size())
                .totalWins((int) wins)
                .totalLosses((int) losses)
                .totalBreakevenExits((int) beExits)
                .totalEodExits((int) eodExits)
                .totalHighVolumeSignals((int) volFlagged)
                .winRate(winRate)
                .avgWinPoints(avgWin)
                .avgLossPoints(avgLoss)
                .avgRR(avgRR)
                .totalPnlPoints(totalPnl)
                .totalPnlRupees(totalPnlRupees)
                .avgRiskRupees(avgRiskRupees)
                .expectancy(expectancy)
                .totalSymbolsScanned(totalSymbols)
                .totalSymbolsWithSignal((int) symbolsWithSignal)
                .topSymbols(top10)
                .worstSymbols(worst10)
                .build();
    }

    // =========================================================================
    // Helpers (unchanged)
    // =========================================================================

    private List<LocalDate> getTradingDays(LocalDate from, LocalDate to) {
        List<LocalDate> days = new ArrayList<>();
        LocalDate d = from;
        while (!d.isAfter(to)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                days.add(d);
            }
            d = d.plusDays(1);
        }
        return days;
    }
}