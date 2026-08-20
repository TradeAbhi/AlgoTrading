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

    /** Earnings-window setups identified at 9:47, waiting for a range breakout close. */
    private final ConcurrentMap<String, PendingBreakout> pendingBreakouts = new ConcurrentHashMap<>();
    private volatile LocalDate pendingBreakoutDate;

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

        registerPendingBreakouts(today, signals, symbolKeyMap);
        sendTelegramAlert(signals, today, windowStart, windowEnd);
        return signals.size();
    }

    /** Checks pending earnings setups after every completed 15-minute candle, 10:01–14:31 IST. */
    @Scheduled(cron = "0 1,16,31,46 10-13 * * MON-FRI", zone = "Asia/Kolkata")
    @Scheduled(cron = "0 1,16,31 14 * * MON-FRI", zone = "Asia/Kolkata")
    public void checkPendingBreakouts() {
        checkPendingBreakouts(LocalDate.now(), LocalTime.now());
    }

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
                    .toList();
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

    /**
     * End-of-day summary alert at 3:31 PM Monday-Friday
     * Shows all setups, triggered breakouts, and pending setups for the day
     */
    @Scheduled(cron = "0 31 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void sendEndOfDaySummary() {
        LocalDate today = LocalDate.now();
        log.info("Earnings Window Fibonacci end-of-day summary — date={}", today);

        // Calculate earnings window
        LocalDate windowStart = today.minusDays(PRE_EARNINGS_DAYS);
        LocalDate windowEnd = today.plusDays(POST_EARNINGS_DAYS);

        // Get symbols with earnings in the window
        List<String> earningsSymbols = earningsRepository.findSymbolsInEarningsWindow(windowStart, windowEnd);

        if (earningsSymbols.isEmpty()) {
            log.info("No symbols in earnings window for EOD summary");
            return;
        }

        // Filter to F&O symbols only
        List<String> fnoSymbols = earningsSymbols.stream()
                .filter(UniverseService.NIFTY_FNO_SYMBOLS::contains)
                .distinct()
                .collect(Collectors.toList());

        if (fnoSymbols.isEmpty()) {
            log.info("No F&O symbols in earnings window for EOD summary");
            return;
        }

        // Resolve symbols to instrument keys
        Map<String, String> symbolKeyMap = instrumentMaster.resolveToInstrumentKeyMap(fnoSymbols);

        // Scan for final day status
        CopyOnWriteArrayList<BacktestTrade> finalSignals = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<DayOutcome> outcomes = new CopyOnWriteArrayList<>();

        ExecutorService pool = Executors.newFixedThreadPool(config.getThreadPoolSize());
        try {
            List<java.util.concurrent.CompletableFuture<Void>> futures = symbolKeyMap.entrySet().stream()
                    .map(entry -> java.util.concurrent.CompletableFuture.runAsync(() ->
                            scanSymbolForEOD(entry.getKey(), entry.getValue(), today, finalSignals, outcomes), pool))
                    .collect(Collectors.toList());
            java.util.concurrent.CompletableFuture.allOf(futures.toArray(new java.util.concurrent.CompletableFuture[0])).join();
        } finally {
            pool.shutdown();
            try { pool.awaitTermination(5, TimeUnit.MINUTES); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        sendEODSummaryAlert(finalSignals, outcomes, today, windowStart, windowEnd);
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
        log.info("Earnings Fibonacci trigger monitor armed: {} pending setups", pendingBreakouts.size());
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
            double breakoutLevel = setup.getDirection() == BacktestTrade.Direction.BUY
                    ? Math.max(setup.getC1High(), setup.getC2High())
                    : Math.min(setup.getC1Low(), setup.getC2Low());
            boolean breakoutTriggered = setup.getDirection() == BacktestTrade.Direction.BUY
                    ? latestClosed.getClose() > breakoutLevel
                    : latestClosed.getClose() < breakoutLevel;
            if (breakoutTriggered && pendingBreakouts.remove(entry.getKey(), pending)) {
                triggered.add(TriggeredBreakout.from(setup, latestClosed, breakoutLevel, config));
                log.info("Earnings Fibonacci breakout triggered: {} {} close={} level={}",
                        setup.getSymbol(), setup.getDirection(), latestClosed.getClose(), breakoutLevel);
            }
        } catch (Exception e) {
            log.error("Earnings Fibonacci trigger check failed for {}: {}", entry.getKey(), e.getMessage());
        }
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
            List<BacktestTrade> trades = strategy.evaluate(symbol, today, candles);

            trades.forEach(t -> {
                signals.add(t);
                log.info("  🔔 EARNINGS WINDOW FIBO SIGNAL: {} {} Entry={} SL={} Target={}",
                        symbol,
                        t.getDirection(),
                        String.format("%.2f", t.getEntryPrice()),
                        String.format("%.2f", t.getStopLoss()),
                        String.format("%.2f", t.getTarget()));
            });

        } catch (Exception e) {
            log.error("Earnings Window Fibo scan error for {}: {}", symbol, e.getMessage());
        }
    }

    /** Scan one earnings-window symbol and record its final end-of-day outcome. */
    private void scanSymbolForEOD(String symbol, String instrumentKey, LocalDate today,
                                  CopyOnWriteArrayList<BacktestTrade> signals,
                                  CopyOnWriteArrayList<DayOutcome> outcomes) {
        try {
            List<Candle> candles = candleService.fetchDayCandles(instrumentKey, today);
            if (candles.isEmpty()) {
                return;
            }

            boolean hasC1 = candles.stream().anyMatch(c -> c.getTimestamp().toLocalTime().equals(C1_TIME));
            boolean hasC2 = candles.stream().anyMatch(c -> c.getTimestamp().toLocalTime().equals(C2_TIME));
            if (!hasC1 || !hasC2) {
                return;
            }

            List<BacktestTrade> trades = strategy.evaluate(symbol, today, candles);
            if (trades.isEmpty()) {
                return;
            }

            BacktestTrade setup = trades.get(0);
            signals.add(setup);
            double breakoutLevel = setup.getDirection() == BacktestTrade.Direction.BUY
                    ? Math.max(setup.getC1High(), setup.getC2High())
                    : Math.min(setup.getC1Low(), setup.getC2Low());
            Candle lastCandle = candles.stream().max(Comparator.comparing(Candle::getTimestamp)).orElse(null);
            if (lastCandle == null) {
                return;
            }

            boolean breakoutTriggered = setup.getDirection() == BacktestTrade.Direction.BUY
                    ? lastCandle.getClose() > breakoutLevel
                    : lastCandle.getClose() < breakoutLevel;
            double highOfDay = candles.stream().mapToDouble(Candle::getHigh).max().orElse(0);
            double lowOfDay = candles.stream().mapToDouble(Candle::getLow).min().orElse(0);
            outcomes.add(new DayOutcome(symbol, setup.getDirection(), setup.getEntryPrice(),
                    setup.getStopLoss(), setup.getTarget(), breakoutLevel, breakoutTriggered,
                    highOfDay, lowOfDay, lastCandle.getClose()));
        } catch (Exception e) {
            log.error("Earnings Window Fibo EOD scan error for {}: {}", symbol, e.getMessage());
        }
    }

    /** Send the final daily status of earnings-window Fibonacci setups. */
    private void sendEODSummaryAlert(List<BacktestTrade> signals, List<DayOutcome> outcomes,
                                     LocalDate today, LocalDate windowStart, LocalDate windowEnd) {
        List<DayOutcome> triggered = outcomes.stream().filter(DayOutcome::breakoutTriggered).toList();
        List<DayOutcome> pending = outcomes.stream().filter(outcome -> !outcome.breakoutTriggered()).toList();

        StringBuilder message = new StringBuilder();
        message.append("📊 *Earnings Window Fibonacci - End of Day Summary*\n")
                .append("📅 ").append(today).append("\n")
                .append("🎯 Window: ").append(windowStart).append(" to ").append(windowEnd).append("\n")
                .append("━━━━━━━━━━━━━━━━━━━━\n\n");
        appendEODOutcomes(message, "✅ *Triggered Breakouts*", triggered);
        appendEODOutcomes(message, "⏳ *Pending Setups*", pending);
        if (outcomes.isEmpty()) {
            message.append("_No Fibonacci setups identified today._\n\n");
        }
        message.append("━━━━━━━━━━━━━━━━━━━━\n")
                .append("Total setups: ").append(signals.size())
                .append(" | Triggered: ").append(triggered.size())
                .append(" | Pending: ").append(pending.size());
        telegramService.sendMessage(message.toString());
        log.info("Earnings Window Fibonacci EOD summary sent — {} setups", signals.size());
    }

    private void appendEODOutcomes(StringBuilder message, String heading, List<DayOutcome> outcomes) {
        if (outcomes.isEmpty()) {
            return;
        }
        message.append(heading).append(" (").append(outcomes.size()).append(")\n");
        outcomes.stream().sorted(Comparator.comparing(DayOutcome::symbol))
                .forEach(outcome -> message.append(formatEODOutcome(outcome)));
        message.append('\n');
    }

    private String formatEODOutcome(DayOutcome outcome) {
        String direction = outcome.direction() == BacktestTrade.Direction.BUY ? "🟢 BUY" : "🔴 SELL";
        return String.format("`%-12s` %s\n  Entry: %.2f | Close: %.2f | Breakout: %.2f\n"
                        + "  High: %.2f | Low: %.2f | SL: %.2f | Target: %.2f\n",
                outcome.symbol(), direction, outcome.entryPrice(), outcome.closeOfDay(), outcome.breakoutLevel(),
                outcome.highOfDay(), outcome.lowOfDay(), outcome.stopLoss(), outcome.target());
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

    private void sendTriggeredBreakoutAlert(List<TriggeredBreakout> triggered, LocalDate today, LocalTime scanTime) {
        List<TriggeredBreakout> buys = triggered.stream()
                .filter(t -> t.direction() == BacktestTrade.Direction.BUY)
                .toList();
        List<TriggeredBreakout> sells = triggered.stream()
                .filter(t -> t.direction() == BacktestTrade.Direction.SELL)
                .toList();

        StringBuilder message = new StringBuilder();
        message.append("⚡ *Earnings Window Fibonacci Breakout Triggered*\n")
                .append("📅 ").append(today).append(" | Checked: ")
                .append(scanTime.withSecond(0).withNano(0)).append("\n")
                .append("━━━━━━━━━━━━━━━━━━━━\n\n");
        appendTriggeredCategory(message, "🟢 BUY Setups", buys);
        appendTriggeredCategory(message, "🔴 SELL Setups", sells);
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

    /**
     * Manual trigger for testing
     */
    public int manualTrigger() {
        return processEarningsWindowFibo();
    }

    private record PendingBreakout(String instrumentKey, BacktestTrade setup) { }

    private record DayOutcome(String symbol, BacktestTrade.Direction direction, double entryPrice,
                              double stopLoss, double target, double breakoutLevel,
                              boolean breakoutTriggered, double highOfDay, double lowOfDay,
                              double closeOfDay) { }

    private record TriggeredBreakout(String symbol, BacktestTrade.Direction direction, double entryPrice,
                                    double breakoutLevel, double stopLoss, double target,
                                    double riskPoints, double wickRatio) {
        private static TriggeredBreakout from(BacktestTrade setup, Candle triggerCandle,
                                             double breakoutLevel, BacktestConfig config) {
            double entry = triggerCandle.getClose();
            double marginFactor = config.getSlMarginPercent() / 100.0;
            double stopLoss = setup.getDirection() == BacktestTrade.Direction.BUY
                    ? setup.getC2Low() * (1 - marginFactor)
                    : setup.getC2High() * (1 + marginFactor);
            double risk = Math.abs(entry - stopLoss);
            double target = setup.getDirection() == BacktestTrade.Direction.BUY
                    ? entry + risk * config.getTargetRR()
                    : entry - risk * config.getTargetRR();
            return new TriggeredBreakout(setup.getSymbol(), setup.getDirection(), entry, breakoutLevel,
                    stopLoss, target, risk, setup.getC1WickRatio());
        }
    }
}
