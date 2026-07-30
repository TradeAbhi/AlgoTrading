package com.trading.algo.fibostrategy;

import com.trading.algo.config.BacktestConfig;
import com.trading.algo.dtos.Candle;
import com.trading.algo.entity.BacktestTrade;
import com.trading.algo.entity.BacktestTrade.Direction;
import com.trading.algo.entity.IndexBacktestTrade.IndexName;
import com.trading.algo.service.UniverseService;
import com.trading.algo.service.MarketSentimentService;
import com.trading.algo.service.MomentumStockSnapshotService;
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
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Live market strategy alert service.
 *
 * Runs the Opening Candle Strategy on TODAY's live 9:15 and 9:30 candles
 * fetched from Upstox v3 intraday candle API at 9:46 AM.
 *
 * Flow:
 *   9:46 AM scheduler fires
 *     → fetch today's 15-min candles for all F&O stocks from Upstox
 *     → run OpeningCandleStrategyService.evaluate() on each
 *     → send Telegram alert with all valid setups (BUY + SELL)
 *
 * NOTE: Uses the same UpstoxHistoricalCandleService with today's date —
 * Upstox v3 intraday endpoint returns candles for the current trading day.
 * No separate intraday API needed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiveStrategyAlertService {

    private static final LocalTime C1_TIME = LocalTime.of(9, 15);
    private static final LocalTime C2_TIME = LocalTime.of(9, 30);

    private final UpstoxHistoricalCandleService candleService;
    private final UpstoxInstrumentMasterService instrumentMaster;
    private final OpeningCandleStrategyService  strategy;
    private final TelegramService               telegramService;
    private final BacktestConfig                config;
    private final MomentumStockSnapshotService  momentumStockSnapshotService;
    private final MarketSentimentService        marketSentimentService;

    // =========================================================================
    // Main entry point — called by scheduler at 9:46 and by manual endpoint
    // =========================================================================

    public int scanAndAlert() {
        LocalDate today = LocalDate.now();
        log.info("LiveStrategyAlert START — date={}", today);

        // Resolve all F&O symbols to instrument keys
        List<String> momentumSymbols = momentumStockSnapshotService.getLatestMomentumSymbols().stream()
                .filter(UniverseService.NIFTY_FNO_SYMBOLS::contains)
                .distinct()
                .collect(Collectors.toList());
        if (momentumSymbols.isEmpty()) {
            log.warn("No momentum-stock snapshot is available; skipping Fibonacci scan");
            return 0;
        }
        Map<String, String> symbolKeyMap = instrumentMaster
                .resolveToInstrumentKeyMap(momentumSymbols);

        log.info("Scanning {} momentum snapshot symbols for Fibonacci setups", symbolKeyMap.size());

        // Thread-safe list to collect all valid setups found
        CopyOnWriteArrayList<BacktestTrade> signals = new CopyOnWriteArrayList<>();

        // Parallel scan — same thread pool approach as backtest
        ExecutorService pool = Executors.newFixedThreadPool(config.getThreadPoolSize());

        try {
            List<CompletableFuture<Void>> futures = symbolKeyMap.entrySet().stream()
                    .map(entry -> CompletableFuture.runAsync(() ->
                            scanSymbol(entry.getKey(), entry.getValue(), today, signals), pool))
                    .collect(Collectors.toList());

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        } finally {
            pool.shutdown();
            try { pool.awaitTermination(5, TimeUnit.MINUTES); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        log.info("Stock scan COMPLETE — {} setups found", signals.size());

        // Scan indexes sequentially (only 3 — no need for thread pool)
        // Breadth is the final filter, after the Fibonacci filter has created
        // candidates from the momentum snapshot.
        int fibonacciCandidateCount = signals.size();
        double adRatio = fetchAdRatio();
        List<BacktestTrade> adFilteredSignals = signals.stream()
                .filter(signal -> momentumStockSnapshotService.isTradeDirectionAllowed(
                        adRatio, signal.getDirection().name()))
                .collect(Collectors.toList());
        signals.clear();
        signals.addAll(adFilteredSignals);
        log.info("Fibonacci candidates={} A/D-approved={} ratio={}",
                fibonacciCandidateCount, signals.size(), adRatio);

        // Indexes are intentionally excluded: the configured universe is the
        // 15-minute momentum snapshot only.
        List<BacktestTrade> indexSignals = List.of();

        log.info("LiveStrategyAlert COMPLETE — stocks={} indexes={}", 
                signals.size(), indexSignals.size());

        if (signals.isEmpty() && indexSignals.isEmpty()) {
            // Telegram is reserved for symbols that passed all three filters.
            return 0;
            /*
            telegramService.sendMessage(
                "📊 *Opening Candle Strategy — 9:46 AM Scan*\n" +
                "━━━━━━━━━━━━━━━━━━━━\n" +
                "_No valid setups found today._\n" +
                "Date: " + today
            );
            return 0;
            */
        }

        sendTelegramAlert(signals, indexSignals, today);
        return signals.size() + indexSignals.size();
    }

    // =========================================================================
    // Index scan — Nifty 50, Bank Nifty, Fin Nifty
    // =========================================================================

    private List<BacktestTrade> scanIndexes(LocalDate today) {
        List<BacktestTrade> indexSignals = new ArrayList<>();

        for (IndexName index : IndexName.values()) {
            try {
                List<Candle> candles = candleService.fetchDayCandles(index.instrumentKey, today);

                if (candles.isEmpty()) {
                    log.debug("{} — no candles today", index.displayName);
                    continue;
                }

                boolean hasC1 = candles.stream()
                        .anyMatch(c -> c.getTimestamp().toLocalTime().equals(C1_TIME));
                boolean hasC2 = candles.stream()
                        .anyMatch(c -> c.getTimestamp().toLocalTime().equals(C2_TIME));

                if (!hasC1 || !hasC2) {
                    log.debug("{} — C1 or C2 not yet available", index.displayName);
                    continue;
                }

                // Reuse same strategy — pass displayName as symbol for logging
                Optional<BacktestTrade> trade = strategy.evaluate(index.displayName, today, candles);

                if (trade.isPresent()) {
                    indexSignals.add(trade.get());
                    log.info("  🔔 INDEX SIGNAL: {} {} Entry={} SL={} Target={}",
                            index.displayName,
                            trade.get().getDirection(),
                            String.format("%.2f", trade.get().getEntryPrice()),
                            String.format("%.2f", trade.get().getStopLoss()),
                            String.format("%.2f", trade.get().getTarget()));
                }

            } catch (Exception e) {
                log.error("Error scanning index {} live: {}", index.displayName, e.getMessage());
            }
        }

        log.info("Index scan complete — {} signals", indexSignals.size());
        return indexSignals;
    }

    // =========================================================================
    // Per-symbol scan
    // =========================================================================

    private void scanSymbol(String symbol, String instrumentKey, LocalDate today,
                             CopyOnWriteArrayList<BacktestTrade> signals) {
        try {
            // Fetch today's 15-min candles — same API as backtest, just with today's date
            List<Candle> candles = candleService.fetchDayCandles(instrumentKey, today);

            if (candles.isEmpty()) {
                log.debug("{} — no candles today", symbol);
                return;
            }

            // Verify we have at least C1 (9:15) and C2 (9:30) candles
            boolean hasC1 = candles.stream()
                    .anyMatch(c -> c.getTimestamp().toLocalTime().equals(C1_TIME));
            boolean hasC2 = candles.stream()
                    .anyMatch(c -> c.getTimestamp().toLocalTime().equals(C2_TIME));

            if (!hasC1 || !hasC2) {
                log.debug("{} — C1 or C2 candle not yet available", symbol);
                return;
            }

            // Run the exact same strategy logic as backtest
            Optional<BacktestTrade> trade = strategy.evaluate(symbol, today, candles);

            if (trade.isPresent()) {
                signals.add(trade.get());
                log.info("  🔔 LIVE SIGNAL: {} {} Entry={} SL={} Target={}",
                        symbol,
                        trade.get().getDirection(),
                        String.format("%.2f", trade.get().getEntryPrice()),
                        String.format("%.2f", trade.get().getStopLoss()),
                        String.format("%.2f", trade.get().getTarget()));
            }

        } catch (Exception e) {
            log.error("Error scanning {} live: {}", symbol, e.getMessage());
        }
    }

    // =========================================================================
    // Telegram alert
    // =========================================================================

    private void sendTelegramAlert(List<BacktestTrade> signals, List<BacktestTrade> indexSignals, LocalDate today) {
        // Split into BUY and SELL
        List<BacktestTrade> buys = signals.stream()
                .filter(s -> s.getDirection() == Direction.BUY)
                .sorted((a, b) -> Double.compare(b.getC1WickRatio(), a.getC1WickRatio()))
                .collect(Collectors.toList());

        List<BacktestTrade> sells = signals.stream()
                .filter(s -> s.getDirection() == Direction.SELL)
                .sorted((a, b) -> Double.compare(b.getC1WickRatio(), a.getC1WickRatio()))
                .collect(Collectors.toList());

        StringBuilder sb = new StringBuilder();
        sb.append("📊 *Opening Candle Strategy — 9:46 AM*\n");
        sb.append("📅 ").append(today).append("\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━\n\n");

        // BUY setups
        if (!buys.isEmpty()) {
            sb.append("🟢 *BUY Setups* (").append(buys.size()).append(")\n");
            for (BacktestTrade t : buys) {
                sb.append(formatSignal(t));
            }
            sb.append("\n");
        }

        // SELL setups
        if (!sells.isEmpty()) {
            sb.append("🔴 *SELL Setups* (").append(sells.size()).append(")\n");
            for (BacktestTrade t : sells) {
                sb.append(formatSignal(t));
            }
            sb.append("\n");
        }

        // Index setups — Nifty 50, Bank Nifty, Fin Nifty
        if (!indexSignals.isEmpty()) {
            sb.append("━━━━━━━━━━━━━━━━━━━━\n\n");
            sb.append("📈 *Index Setups* (").append(indexSignals.size()).append(")\n");
            for (BacktestTrade t : indexSignals) {
                sb.append(formatSignal(t));
            }
            sb.append("\n");
        }

        sb.append("━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("Stocks: ").append(signals.size())
          .append(" | Indexes: ").append(indexSignals.size())
          .append(" | SL at 3:15 PM if not triggered");

        telegramService.sendMessage(sb.toString());
        log.info("Live strategy alert sent — stocks={} indexes={}", 
                signals.size(), indexSignals.size());
    }

    private double fetchAdRatio() {
        try {
            Map<String, Object> breadth = marketSentimentService.fetchAdvanceDeclineData();
            int advances = (int) breadth.getOrDefault("advances", 0);
            int declines = (int) breadth.getOrDefault("declines", 0);
            return declines > 0 ? (double) advances / declines : (advances > 0 ? advances : -1.0);
        } catch (Exception e) {
            log.warn("Fibonacci A/D ratio unavailable: {}", e.getMessage());
            return -1.0;
        }
    }

    private String formatSignal(BacktestTrade t) {
        // Volume flag (Point 2) — highlight strong-volume setups with 🔥
        String volTag = Boolean.TRUE.equals(t.getVolumeFlag()) ? " 🔥" : "";

        // Breakeven level (Point 7) — show 1.5R level so trader knows where SL moves
        double be1_5R = t.getDirection() == com.trading.algo.entity.BacktestTrade.Direction.BUY
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
}
