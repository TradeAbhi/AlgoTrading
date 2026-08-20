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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
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

    /** Setups identified at 9:46, awaiting a close beyond the C1/C2 range. */
    private final ConcurrentMap<String, PendingBreakout> pendingBreakouts = new ConcurrentHashMap<>();
    private volatile LocalDate pendingBreakoutDate;

    // =========================================================================
    // FNO universe scan — scans ALL Nifty F&O stocks (original logic)
    // =========================================================================

    public int scanAndAlertFno() {
        LocalDate today = LocalDate.now();
        log.info("LiveStrategyAlert FNO START — date={}", today);

        Map<String, String> symbolKeyMap = instrumentMaster
                .resolveToInstrumentKeyMap(UniverseService.NIFTY_FNO_SYMBOLS);

        log.info("Scanning {} F&O symbols for live strategy setups", symbolKeyMap.size());

        CopyOnWriteArrayList<BacktestTrade> signals = new CopyOnWriteArrayList<>();
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

        log.info("FNO stock scan COMPLETE — {} setups found", signals.size());

        List<BacktestTrade> indexSignals = scanIndexes(today);

        log.info("LiveStrategyAlert FNO COMPLETE — stocks={} indexes={}",
                signals.size(), indexSignals.size());

        if (signals.isEmpty() && indexSignals.isEmpty()) {
            telegramService.sendMessage(
                "📊 *Opening Candle Strategy — 9:46 AM Scan*\n" +
                "━━━━━━━━━━━━━━━━━━━━\n" +
                "_No valid setups found today._\n" +
                "Date: " + today
            );
            return 0;
        }

        registerPendingBreakouts(today, signals, symbolKeyMap);
        sendTelegramAlert(signals, indexSignals, today);
        return signals.size() + indexSignals.size();
    }

    // =========================================================================
    // Momentum snapshot scan — called by scheduler at 9:46 and by manual endpoint
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
        // double adRatio = fetchAdRatio();
        double adRatio = -1.0;
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

        registerPendingBreakouts(today, signals, symbolKeyMap);
        sendTelegramAlert(signals, indexSignals, today);
        return signals.size() + indexSignals.size();
    }

    /**
     * Checks the 9:46 candidates after each newly closed 15-minute candle.
     * BUY requires a close above max(C1 high, C2 high); SELL requires a close
     * below min(C1 low, C2 low). Triggered symbols are removed immediately so
     * they cannot alert again during the same trading day.
     */
    @Scheduled(cron = "0 1,16,31,46 10-13 * * MON-FRI", zone = "Asia/Kolkata")
    @Scheduled(cron = "0 1,16,31 14 * * MON-FRI", zone = "Asia/Kolkata")
    public void checkPendingBreakouts() {
        checkPendingBreakouts(LocalDate.now(), LocalTime.now());
    }

    /** Enables a manual endpoint to run the same confirmation check. */
    public int checkPendingBreakoutsNow() {
        return checkPendingBreakouts(LocalDate.now(), LocalTime.now());
    }

    private int checkPendingBreakouts(LocalDate today, LocalTime now) {
        if (!today.equals(pendingBreakoutDate) || pendingBreakouts.isEmpty()) {
            return 0;
        }

        List<TriggeredBreakout> triggered = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(config.getThreadPoolSize());
        try {
            List<CompletableFuture<Void>> futures = pendingBreakouts.entrySet().stream()
                    .map(entry -> CompletableFuture.runAsync(() -> checkPendingBreakout(entry, today, now, triggered), pool))
                    .collect(Collectors.toList());
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } finally {
            pool.shutdown();
        }

        if (!triggered.isEmpty()) {
            sendTriggeredBreakoutAlert(triggered, today, now);
        }
        return triggered.size();
    }

    @Scheduled(cron = "0 46 14 * * MON-FRI", zone = "Asia/Kolkata")
    public void clearExpiredPendingBreakouts() {
        pendingBreakouts.clear();
        pendingBreakoutDate = null;
    }

    private void registerPendingBreakouts(LocalDate today, List<BacktestTrade> signals,
                                          Map<String, String> symbolKeyMap) {
        if (!today.equals(pendingBreakoutDate)) {
            pendingBreakouts.clear();
            pendingBreakoutDate = today;
        }
        for (BacktestTrade signal : signals) {
            String instrumentKey = symbolKeyMap.get(signal.getSymbol());
            if (instrumentKey != null) {
                pendingBreakouts.putIfAbsent(signal.getSymbol(), new PendingBreakout(instrumentKey, signal));
            }
        }
        log.info("Opening-candle trigger monitor armed: {} pending setups", pendingBreakouts.size());
    }

    private void checkPendingBreakout(Map.Entry<String, PendingBreakout> entry, LocalDate today,
                                       LocalTime now, List<TriggeredBreakout> triggered) {
        PendingBreakout pending = entry.getValue();
        try {
            List<Candle> candles = candleService.fetchDayCandles(pending.instrumentKey(), today);
            Candle latestClosed = candles.stream()
                    .filter(c -> !c.getTimestamp().toLocalTime().plusMinutes(15).isAfter(now))
                    .max(Comparator.comparing(Candle::getTimestamp))
                    .orElse(null);
            if (latestClosed == null) {
                return;
            }

            BacktestTrade setup = pending.setup();
            double breakoutLevel = setup.getDirection() == Direction.BUY
                    ? Math.max(setup.getC1High(), setup.getC2High())
                    : Math.min(setup.getC1Low(), setup.getC2Low());
            boolean triggeredNow = setup.getDirection() == Direction.BUY
                    ? latestClosed.getClose() > breakoutLevel
                    : latestClosed.getClose() < breakoutLevel;

            if (triggeredNow && pendingBreakouts.remove(entry.getKey(), pending)) {
                triggered.add(TriggeredBreakout.from(setup, latestClosed, breakoutLevel, config));
                log.info("Opening-candle breakout triggered: {} {} close={} level={}",
                        setup.getSymbol(), setup.getDirection(), latestClosed.getClose(), breakoutLevel);
            }
        } catch (Exception e) {
            log.error("Opening-candle trigger check failed for {}: {}", entry.getKey(), e.getMessage());
        }
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
                List<BacktestTrade> trades = strategy.evaluate(index.displayName, today, candles);

                trades.forEach(t -> {
                    indexSignals.add(t);
                    log.info("  🔔 INDEX SIGNAL: {} {} Entry={} SL={} Target={}",
                            index.displayName,
                            t.getDirection(),
                            String.format("%.2f", t.getEntryPrice()),
                            String.format("%.2f", t.getStopLoss()),
                            String.format("%.2f", t.getTarget()));
                });

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
            List<Candle> candles = candleService.fetchDayCandles(instrumentKey, today);

            if (candles.isEmpty()) {
                log.debug("{} — no candles today", symbol);
                return;
            }

            boolean hasC1 = candles.stream()
                    .anyMatch(c -> c.getTimestamp().toLocalTime().equals(C1_TIME));
            boolean hasC2 = candles.stream()
                    .anyMatch(c -> c.getTimestamp().toLocalTime().equals(C2_TIME));

            if (!hasC1 || !hasC2) {
                log.debug("{} — C1 or C2 candle not yet available", symbol);
                return;
            }

            // Fetch previous day OHLC for categorization
            List<Candle> prevDay = candleService.fetchDailyCandles(instrumentKey, today.minusDays(3), today.minusDays(1));
            Double prevDayHigh  = prevDay.isEmpty() ? null : prevDay.get(prevDay.size() - 1).getHigh();
            Double prevDayLow   = prevDay.isEmpty() ? null : prevDay.get(prevDay.size() - 1).getLow();
            Double prevDayClose = prevDay.isEmpty() ? null : prevDay.get(prevDay.size() - 1).getClose();
            Double prevDayOpen  = prevDay.isEmpty() ? null : prevDay.get(prevDay.size() - 1).getOpen();

            List<BacktestTrade> trades = strategy.evaluate(symbol, today, candles, -1.0, 0.0, 0,
                    config.getFixedRiskRupees(), prevDayOpen, prevDayHigh, prevDayLow, prevDayClose,
                    0.0, 0.0, 0.0, 0.0);

            trades.forEach(t -> {
                signals.add(t);
                log.info("  🔔 LIVE SIGNAL: {} {} Entry={} SL={} Target={}",
                        symbol,
                        t.getDirection(),
                        String.format("%.2f", t.getEntryPrice()),
                        String.format("%.2f", t.getStopLoss()),
                        String.format("%.2f", t.getTarget()));
            });

        } catch (Exception e) {
            log.error("Error scanning {} live: {}", symbol, e.getMessage());
        }
    }

    // =========================================================================
    // Telegram alert
    // =========================================================================

    private void sendTelegramAlert(List<BacktestTrade> signals, List<BacktestTrade> indexSignals, LocalDate today) {
        // BUY: above prev day high vs below prev day high
        List<BacktestTrade> buyAbovePrevHigh = signals.stream()
                .filter(s -> s.getDirection() == Direction.BUY && Boolean.TRUE.equals(s.getC1AbovePrevHigh()))
                .sorted((a, b) -> Double.compare(b.getC1WickRatio(), a.getC1WickRatio()))
                .collect(Collectors.toList());
        List<BacktestTrade> buyBelowPrevHigh = signals.stream()
                .filter(s -> s.getDirection() == Direction.BUY && !Boolean.TRUE.equals(s.getC1AbovePrevHigh()))
                .sorted((a, b) -> Double.compare(b.getC1WickRatio(), a.getC1WickRatio()))
                .collect(Collectors.toList());

        // SELL: above prev day low vs below prev day low
        List<BacktestTrade> sellAbovePrevLow = signals.stream()
                .filter(s -> s.getDirection() == Direction.SELL && Boolean.TRUE.equals(s.getC1AbovePrevLow()))
                .sorted((a, b) -> Double.compare(b.getC1WickRatio(), a.getC1WickRatio()))
                .collect(Collectors.toList());
        List<BacktestTrade> sellBelowPrevLow = signals.stream()
                .filter(s -> s.getDirection() == Direction.SELL && !Boolean.TRUE.equals(s.getC1AbovePrevLow()))
                .sorted((a, b) -> Double.compare(b.getC1WickRatio(), a.getC1WickRatio()))
                .collect(Collectors.toList());

        StringBuilder sb = new StringBuilder();
        sb.append("📊 *Opening Candle Strategy — 9:46 AM*\n");
        sb.append("📅 ").append(today).append("\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━\n\n");

        if (!buyAbovePrevHigh.isEmpty()) {
            sb.append("🟢 *BUY — Above Prev Day High* (").append(buyAbovePrevHigh.size()).append(")\n");
            for (BacktestTrade t : buyAbovePrevHigh) sb.append(formatSignal(t));
            sb.append("\n");
        }
        if (!buyBelowPrevHigh.isEmpty()) {
            sb.append("🟢 *BUY — Below Prev Day High* (").append(buyBelowPrevHigh.size()).append(")\n");
            for (BacktestTrade t : buyBelowPrevHigh) sb.append(formatSignal(t));
            sb.append("\n");
        }
        if (!sellAbovePrevLow.isEmpty()) {
            sb.append("🔴 *SELL — Above Prev Day Low* (").append(sellAbovePrevLow.size()).append(")\n");
            for (BacktestTrade t : sellAbovePrevLow) sb.append(formatSignal(t));
            sb.append("\n");
        }
        if (!sellBelowPrevLow.isEmpty()) {
            sb.append("🔴 *SELL — Below Prev Day Low* (").append(sellBelowPrevLow.size()).append(")\n");
            for (BacktestTrade t : sellBelowPrevLow) sb.append(formatSignal(t));
            sb.append("\n");
        }

        if (!indexSignals.isEmpty()) {
            sb.append("━━━━━━━━━━━━━━━━━━━━\n\n");
            sb.append("📈 *Index Setups* (").append(indexSignals.size()).append(")\n");
            for (BacktestTrade t : indexSignals) sb.append(formatSignal(t));
            sb.append("\n");
        }

        sb.append("━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("Stocks: ").append(signals.size())
          .append(" | Indexes: ").append(indexSignals.size())
          .append(" | SL at 3:15 PM if not triggered");

        telegramService.sendMessage(sb.toString());
        log.info("Live strategy alert sent — stocks={} indexes={}", signals.size(), indexSignals.size());
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

    private void sendTriggeredBreakoutAlert(List<TriggeredBreakout> triggered, LocalDate date, LocalTime scanTime) {
        List<TriggeredBreakout> buyAbovePrevHigh = triggered.stream()
                .filter(t -> t.direction() == Direction.BUY && t.entryAbovePrevHigh())
                .toList();
        List<TriggeredBreakout> buyBelowPrevHigh = triggered.stream()
                .filter(t -> t.direction() == Direction.BUY && !t.entryAbovePrevHigh())
                .toList();
        List<TriggeredBreakout> sellAbovePrevLow = triggered.stream()
                .filter(t -> t.direction() == Direction.SELL && t.entryAbovePrevLow())
                .toList();
        List<TriggeredBreakout> sellBelowPrevLow = triggered.stream()
                .filter(t -> t.direction() == Direction.SELL && !t.entryAbovePrevLow())
                .toList();

        StringBuilder message = new StringBuilder();
        message.append("⚡ *Opening Candle Breakout Triggered*\n")
                .append("📅 ").append(date).append(" | Checked: ").append(scanTime.withSecond(0).withNano(0)).append("\n")
                .append("━━━━━━━━━━━━━━━━━━━━\n\n");
        appendTriggeredCategory(message, "🟢 BUY — Above Prev Day High", buyAbovePrevHigh);
        appendTriggeredCategory(message, "🟢 BUY — Below Prev Day High", buyBelowPrevHigh);
        appendTriggeredCategory(message, "🔴 SELL — Above Prev Day Low", sellAbovePrevLow);
        appendTriggeredCategory(message, "🔴 SELL — Below Prev Day Low", sellBelowPrevLow);
        message.append("━━━━━━━━━━━━━━━━━━━━\n")
                .append("Triggered now: ").append(triggered.size())
                .append(" | Still monitoring: ").append(pendingBreakouts.size());

        telegramService.sendMessage(message.toString());
    }

    private void appendTriggeredCategory(StringBuilder message, String heading, List<TriggeredBreakout> signals) {
        if (signals.isEmpty()) {
            return;
        }
        message.append(heading).append(" (").append(signals.size()).append(")\n");
        for (TriggeredBreakout signal : signals) {
            message.append(String.format(
                    "`%-12s` Trigger: *%.2f* (close)\n  Range: %.2f | SL: %.2f | Target: %.2f\n  Risk: %.1f pts | Wick: %.2f\n",
                    signal.symbol(), signal.entryPrice(), signal.breakoutLevel(), signal.stopLoss(), signal.target(),
                    signal.riskPoints(), signal.wickRatio()));
        }
        message.append('\n');
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

    private record PendingBreakout(String instrumentKey, BacktestTrade setup) { }

    private record TriggeredBreakout(String symbol, Direction direction, double entryPrice, double breakoutLevel,
                                    double stopLoss, double target, double riskPoints, double wickRatio,
                                    boolean entryAbovePrevHigh, boolean entryAbovePrevLow) {
        private static TriggeredBreakout from(BacktestTrade setup, Candle triggerCandle,
                                             double breakoutLevel, BacktestConfig config) {
            double entry = triggerCandle.getClose();
            double marginFactor = config.getSlMarginPercent() / 100.0;
            double stopLoss = setup.getDirection() == Direction.BUY
                    ? setup.getC2Low() * (1 - marginFactor)
                    : setup.getC2High() * (1 + marginFactor);
            double risk = Math.abs(entry - stopLoss);
            double target = setup.getDirection() == Direction.BUY
                    ? entry + risk * config.getTargetRR()
                    : entry - risk * config.getTargetRR();
            return new TriggeredBreakout(setup.getSymbol(), setup.getDirection(), entry, breakoutLevel,
                    stopLoss, target, risk, setup.getC1WickRatio(),
                    setup.getPrevDayHigh() != null && entry > setup.getPrevDayHigh(),
                    setup.getPrevDayLow() != null && entry > setup.getPrevDayLow());
        }
    }
}
