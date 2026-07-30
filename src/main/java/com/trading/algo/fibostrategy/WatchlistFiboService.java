package com.trading.algo.fibostrategy;

import com.trading.algo.config.BacktestConfig;
import com.trading.algo.dtos.Candle;
import com.trading.algo.entity.BacktestTrade;
import com.trading.algo.entity.WatchlistAlert;
import com.trading.algo.service.UniverseService;
import com.trading.algo.service.WatchlistAlertService;
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
 * Fibonacci (Opening Candle) Strategy service for watchlist stocks.
 * 
 * Runs once per day after the first watchlist alert is received.
 * Applies Fibonacci strategy only to stocks from the latest watchlist alert.
 * 
 * Key differences from standard Fibonacci:
 * - Universe is limited to watchlist stocks (not momentum snapshot)
 * - Runs once per day (not every 15 minutes)
 * - Processes alerts as they come in
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WatchlistFiboService {

    private static final LocalTime C1_TIME = LocalTime.of(9, 15);
    private static final LocalTime C2_TIME = LocalTime.of(9, 30);

    private final WatchlistAlertService watchlistAlertService;
    private final UpstoxHistoricalCandleService candleService;
    private final UpstoxInstrumentMasterService instrumentMaster;
    private final OpeningCandleStrategyService strategy;
    private final TelegramService telegramService;
    private final BacktestConfig config;

    /**
     * Main entry point - called by scheduler once per day
     */
    public int processWatchlistFibo() {
        LocalDate today = LocalDate.now();
        log.info("Watchlist Fibonacci scan START — date={}", today);

        // Get the latest unprocessed watchlist alert
        WatchlistAlert alert = watchlistAlertService.getLatestUnprocessedFiboAlert();
        if (alert == null) {
            log.debug("No unprocessed watchlist alert for Fibonacci");
            return 0;
        }

        log.info("Processing Fibonacci for watchlist alert {} with {} symbols", 
                alert.getId(), alert.getTotalSymbols());

        // Filter to F&O symbols only
        List<String> fnoSymbols = alert.getSymbols().stream()
                .filter(UniverseService.NIFTY_FNO_SYMBOLS::contains)
                .distinct()
                .collect(Collectors.toList());

        if (fnoSymbols.isEmpty()) {
            log.warn("No F&O symbols in watchlist alert");
            watchlistAlertService.markFiboProcessed(alert.getId());
            return 0;
        }

        log.info("Filtered to {} F&O symbols from watchlist", fnoSymbols.size());

        // Resolve symbols to instrument keys
        Map<String, String> symbolKeyMap = instrumentMaster.resolveToInstrumentKeyMap(fnoSymbols);

        log.info("Scanning {} watchlist symbols for Fibonacci setups", symbolKeyMap.size());

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

        log.info("Watchlist Fibonacci scan COMPLETE — {} setups found", signals.size());

        // Mark alert as processed
        watchlistAlertService.markFiboProcessed(alert.getId());

        if (signals.isEmpty()) {
            log.info("No Fibonacci setups found in watchlist");
            return 0;
        }

        sendTelegramAlert(signals, today);
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
                log.debug("Watchlist Fibo: {} — no candles today", symbol);
                return;
            }

            // Verify we have at least C1 (9:15) and C2 (9:30) candles
            boolean hasC1 = candles.stream()
                    .anyMatch(c -> c.getTimestamp().toLocalTime().equals(C1_TIME));
            boolean hasC2 = candles.stream()
                    .anyMatch(c -> c.getTimestamp().toLocalTime().equals(C2_TIME));

            if (!hasC1 || !hasC2) {
                log.debug("Watchlist Fibo: {} — C1 or C2 candle not yet available", symbol);
                return;
            }

            // Run the strategy logic
            Optional<BacktestTrade> trade = strategy.evaluate(symbol, today, candles);

            if (trade.isPresent()) {
                signals.add(trade.get());
                log.info("  🔔 WATCHLIST FIBO SIGNAL: {} {} Entry={} SL={} Target={}",
                        symbol,
                        trade.get().getDirection(),
                        String.format("%.2f", trade.get().getEntryPrice()),
                        String.format("%.2f", trade.get().getStopLoss()),
                        String.format("%.2f", trade.get().getTarget()));
            }

        } catch (Exception e) {
            log.error("Watchlist Fibo scan error for {}: {}", symbol, e.getMessage());
        }
    }

    /**
     * Send Telegram alert with Fibonacci setups
     */
    private void sendTelegramAlert(List<BacktestTrade> signals, LocalDate today) {
        // Split into BUY and SELL
        List<BacktestTrade> buys = signals.stream()
                .filter(s -> s.getDirection() == BacktestTrade.Direction.BUY)
                .sorted((a, b) -> Double.compare(b.getC1WickRatio(), a.getC1WickRatio()))
                .collect(Collectors.toList());

        List<BacktestTrade> sells = signals.stream()
                .filter(s -> s.getDirection() == BacktestTrade.Direction.SELL)
                .sorted((a, b) -> Double.compare(b.getC1WickRatio(), a.getC1WickRatio()))
                .collect(Collectors.toList());

        StringBuilder sb = new StringBuilder();
        sb.append("📊 *Watchlist Fibonacci Strategy*\n");
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

        sb.append("━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("Total setups: ").append(signals.size());
        sb.append(" | SL at 3:15 PM if not triggered");

        telegramService.sendMessage(sb.toString());
        log.info("Watchlist Fibonacci alert sent — {} setups", signals.size());
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
        return processWatchlistFibo();
    }
}
