package com.trading.algo.earning;

import com.trading.algo.config.BacktestConfig;
import com.trading.algo.dtos.Candle;
import com.trading.algo.entity.BacktestTrade;
import com.trading.algo.entity.Earnings;
import com.trading.algo.fibostrategy.OpeningCandleStrategyService;
import com.trading.algo.repo.EarningsRepository;
import com.trading.algo.service.UniverseService;
import com.trading.algo.telegram.TelegramService;
import com.trading.algo.upstox.UpstoxHistoricalCandleService;
import com.trading.algo.upstox.UpstoxInstrumentMasterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Fibonacci (Opening Candle) Strategy service for stocks in earnings window.
 * 
 * Runs daily to apply Fibonacci strategy to stocks that are in the earnings window
 * (pre-10 days to post-3 days around their earnings date).
 * 
 * Earnings window is a high-volatility period where Fibonacci strategies can be
 * particularly effective due to increased volume and price movement.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EarningsWindowFiboService {

    private static final LocalTime C1_TIME = LocalTime.of(9, 15);
    private static final LocalTime C2_TIME = LocalTime.of(9, 30);
    private static final int PRE_EARNINGS_DAYS = 10;
    private static final int POST_EARNINGS_DAYS = 3;

    private final EarningsRepository earningsRepository;
    private final UpstoxHistoricalCandleService candleService;
    private final UpstoxInstrumentMasterService instrumentMaster;
    private final OpeningCandleStrategyService strategy;
    private final TelegramService telegramService;
    private final BacktestConfig config;

    /**
     * Main entry point - called by scheduler daily
     */
    public int processEarningsWindowFibo() {
        LocalDate today = LocalDate.now();
        log.info("Earnings Window Fibonacci scan START — date={}", today);

        // Calculate earnings window: today - 10 days to today + 3 days
        LocalDate windowStart = today.minusDays(PRE_EARNINGS_DAYS);
        LocalDate windowEnd = today.plusDays(POST_EARNINGS_DAYS);

        log.info("Earnings window: {} to {}", windowStart, windowEnd);

        // Get symbols with earnings in the window
        List<String> earningsSymbols = earningsRepository.findSymbolsInEarningsWindow(windowStart, windowEnd);

        if (earningsSymbols.isEmpty()) {
            log.info("No symbols in earnings window");
            return 0;
        }

        log.info("Found {} symbols in earnings window", earningsSymbols.size());

        // Filter to F&O symbols only
        List<String> fnoSymbols = earningsSymbols.stream()
                .filter(UniverseService.NIFTY_FNO_SYMBOLS::contains)
                .distinct()
                .collect(Collectors.toList());

        if (fnoSymbols.isEmpty()) {
            log.warn("No F&O symbols in earnings window");
            return 0;
        }

        log.info("Filtered to {} F&O symbols in earnings window", fnoSymbols.size());

        // Resolve symbols to instrument keys
        Map<String, String> symbolKeyMap = instrumentMaster.resolveToInstrumentKeyMap(fnoSymbols);

        log.info("Scanning {} earnings window symbols for Fibonacci setups", symbolKeyMap.size());

        // Thread-safe list to collect all valid setups found
        CopyOnWriteArrayList<BacktestTrade> signals = new CopyOnWriteArrayList<>();

        // Parallel scan
        ExecutorService pool = Executors.newFixedThreadPool(config.getThreadPoolSize());

        try {
            List<java.util.concurrent.CompletableFuture<Void>> futures = symbolKeyMap.entrySet().stream()
                    .map(entry -> java.util.concurrent.CompletableFuture.runAsync(() ->
                            scanSymbol(entry.getKey(), entry.getValue(), today, signals), pool))
                    .collect(Collectors.toList());

            java.util.concurrent.CompletableFuture.allOf(futures.toArray(new java.util.concurrent.CompletableFuture[0])).join();

        } finally {
            pool.shutdown();
            try { pool.awaitTermination(5, TimeUnit.MINUTES); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        log.info("Earnings Window Fibonacci scan COMPLETE — {} setups found", signals.size());

        if (signals.isEmpty()) {
            log.info("No Fibonacci setups found in earnings window");
            return 0;
        }

        sendTelegramAlert(signals, today, windowStart, windowEnd);
        return signals.size();
    }

    /**
     * Per-symbol scan
     */
    private void scanSymbol(String symbol, String instrumentKey, LocalDate today,
                             CopyOnWriteArrayList<BacktestTrade> signals) {
        try {
            // Fetch today's 15-min candles
            List<Candle> candles = candleService.fetchDayCandles(instrumentKey, today);

            if (candles.isEmpty()) {
                log.debug("Earnings Window Fibo: {} — no candles today", symbol);
                return;
            }

            // Verify we have at least C1 (9:15) and C2 (9:30) candles
            boolean hasC1 = candles.stream()
                    .anyMatch(c -> c.getTimestamp().toLocalTime().equals(C1_TIME));
            boolean hasC2 = candles.stream()
                    .anyMatch(c -> c.getTimestamp().toLocalTime().equals(C2_TIME));

            if (!hasC1 || !hasC2) {
                log.debug("Earnings Window Fibo: {} — C1 or C2 candle not yet available", symbol);
                return;
            }

            // Run the strategy logic
            Optional<BacktestTrade> trade = strategy.evaluate(symbol, today, candles);

            if (trade.isPresent()) {
                signals.add(trade.get());
                log.info("  🔔 EARNINGS WINDOW FIBO SIGNAL: {} {} Entry={} SL={} Target={}",
                        symbol,
                        trade.get().getDirection(),
                        String.format("%.2f", trade.get().getEntryPrice()),
                        String.format("%.2f", trade.get().getStopLoss()),
                        String.format("%.2f", trade.get().getTarget()));
            }

        } catch (Exception e) {
            log.error("Earnings Window Fibo scan error for {}: {}", symbol, e.getMessage());
        }
    }

    /**
     * Send Telegram alert with Fibonacci setups for earnings window stocks
     */
    private void sendTelegramAlert(List<BacktestTrade> signals, LocalDate today,
                                    LocalDate windowStart, LocalDate windowEnd) {
        // Split BUY into gap-down BUY and regular BUY
        List<BacktestTrade> gapDownBuys = signals.stream()
                .filter(s -> s.getDirection() == BacktestTrade.Direction.BUY && Boolean.TRUE.equals(s.getC1AbovePrevHigh()))
                .sorted((a, b) -> Double.compare(b.getC1WickRatio(), a.getC1WickRatio()))
                .collect(Collectors.toList());

        List<BacktestTrade> regularBuys = signals.stream()
                .filter(s -> s.getDirection() == BacktestTrade.Direction.BUY && !Boolean.TRUE.equals(s.getC1AbovePrevHigh()))
                .sorted((a, b) -> Double.compare(b.getC1WickRatio(), a.getC1WickRatio()))
                .collect(Collectors.toList());

        // Split SELL into gap-up SELL and regular SELL
        List<BacktestTrade> gapUpSells = signals.stream()
                .filter(s -> s.getDirection() == BacktestTrade.Direction.SELL && Boolean.TRUE.equals(s.getC1AbovePrevLow()))
                .sorted((a, b) -> Double.compare(b.getC1WickRatio(), a.getC1WickRatio()))
                .collect(Collectors.toList());

        List<BacktestTrade> regularSells = signals.stream()
                .filter(s -> s.getDirection() == BacktestTrade.Direction.SELL && !Boolean.TRUE.equals(s.getC1AbovePrevLow()))
                .sorted((a, b) -> Double.compare(b.getC1WickRatio(), a.getC1WickRatio()))
                .collect(Collectors.toList());

        StringBuilder sb = new StringBuilder();
        sb.append("📊 *Earnings Window Fibonacci Strategy*\n");
        sb.append("📅 ").append(today).append("\n");
        sb.append("🎯 Window: ").append(windowStart).append(" to ").append(windowEnd).append("\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━\n\n");

        // Gap-down BUY setups
        if (!gapDownBuys.isEmpty()) {
            sb.append("🟢 *Gap Down BUY Setups* (").append(gapDownBuys.size()).append(")\n");
            for (BacktestTrade t : gapDownBuys) {
                sb.append(formatSignal(t));
            }
            sb.append("\n");
        }

        // Regular BUY setups
        if (!regularBuys.isEmpty()) {
            sb.append("🟢 *BUY Setups* (").append(regularBuys.size()).append(")\n");
            for (BacktestTrade t : regularBuys) {
                sb.append(formatSignal(t));
            }
            sb.append("\n");
        }

        // Gap-up SELL setups
        if (!gapUpSells.isEmpty()) {
            sb.append("🔴 *Gap Up SELL Setups* (").append(gapUpSells.size()).append(")\n");
            for (BacktestTrade t : gapUpSells) {
                sb.append(formatSignal(t));
            }
            sb.append("\n");
        }

        // Regular SELL setups
        if (!regularSells.isEmpty()) {
            sb.append("🔴 *SELL Setups* (").append(regularSells.size()).append(")\n");
            for (BacktestTrade t : regularSells) {
                sb.append(formatSignal(t));
            }
            sb.append("\n");
        }

        sb.append("━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("Total setups: ").append(signals.size());
        sb.append(" | SL at 3:15 PM if not triggered");

        telegramService.sendMessage(sb.toString());
        log.info("Earnings Window Fibonacci alert sent — {} setups", signals.size());
    }

    private String formatSignal(BacktestTrade t) {
        // Volume flag — highlight strong-volume setups with 🔥
        String volTag = Boolean.TRUE.equals(t.getVolumeFlag()) ? " 🔥" : "";

        // Breakeven level — show 1.5R level so trader knows where SL moves
        double be1_5R = t.getDirection() == BacktestTrade.Direction.BUY
                ? t.getEntryPrice() + (t.getRiskPoints() * 1.5)
                : t.getEntryPrice() - (t.getRiskPoints() * 1.5);

        return String.format(
            "`%-12s`%s Entry: *%.2f*\n" +
            "  SL: %.2f  |  BE at: %.2f  |  Target: %.2f\n" +
            "  Risk: %.1f pts  |  Wick: %.2f\n",
            t.getSymbol(), volTag,
            t.getEntryPrice(),
            t.getStopLoss(), be1_5R, t.getTarget(),
            t.getRiskPoints(),
            t.getC1WickRatio()
        );
    }

    /**
     * Manual trigger for testing
     */
    public int manualTrigger() {
        return processEarningsWindowFibo();
    }
}
