package com.trading.algo.gapbreakout;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import com.trading.algo.dtos.Candle;
import com.trading.algo.service.UniverseService;
import com.trading.algo.telegram.TelegramService;
import com.trading.algo.upstox.UpstoxInstrumentMasterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * F&O intraday gap-and-first-range breakout strategy.
 * A trade enters only after a later 15-minute candle closes beyond the 09:15 candle range.
 *
 * NOTE: core rule logic (identifySignal/simulate) is now parameterized via
 * ParameterCombination instead of reading static constants directly. All
 * existing public methods keep their old signatures and behavior (they now
 * delegate using DEFAULT_COMBO, built from the same constants as before).
 * This is what lets GapBreakoutParameterSweepEngine reuse the exact same
 * rule logic across a swept parameter grid without duplicating it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GapBreakoutEngineService {

    public static final double GAP_PERCENT = 0.45;
    public static final double STOP_BUFFER_PERCENT = 0.35;
    public static final double PARTIAL_RR = 1;
    public static final double FINAL_RR = 2;
    public static final double PARTIAL_QUANTITY_PERCENT = 50.0;
    public static final double MIN_BREAKOUT_BODY_PERCENT = 0.65;

    public static final double MAX_UPPER_WICK_PERCENT = 30.0;
    public static final double MAX_LOWER_WICK_PERCENT = 30.0;
    private static final LocalTime LAST_ENTRY_TIME = LocalTime.of(14, 30);

    private static final LocalTime FIRST_CANDLE_TIME = LocalTime.of(9, 15);
    private static final LocalTime EOD_TIME = LocalTime.of(15, 15);

    /**
     * Default combo built from the original static constants - used by all
     * pre-existing public methods so their behavior is unchanged.
     *
     * NOTE: MAX_UPPER_WICK_PERCENT and MAX_LOWER_WICK_PERCENT happen to be
     * equal (30.0) today. ParameterCombination collapses them into a single
     * swept maxWickPercent field. If you ever want them independently
     * tunable, this needs splitting into two fields - flagging since I
     * merged them as an assumption, not a confirmed requirement.
     */
    public static final ParameterCombination DEFAULT_COMBO = new ParameterCombination(
            GAP_PERCENT, STOP_BUFFER_PERCENT, PARTIAL_RR, FINAL_RR,
            PARTIAL_QUANTITY_PERCENT, MIN_BREAKOUT_BODY_PERCENT, MAX_UPPER_WICK_PERCENT);

    public enum Universe { NIFTY_FNO, NIFTY_50_TOP_10, NIFTY_50_GAP_BREAKOUT }

    private final GapBreakoutCandleCache candleCache;
    private final UpstoxInstrumentMasterService instrumentMaster;
    private final TelegramService telegramService;
    private final Set<String> alertedSignalKeys = ConcurrentHashMap.newKeySet();

    /** Scans today's F&O universe and alerts only signals that have not already been sent. */
    public int scanAndAlert() {
        return scanAndAlert(Universe.NIFTY_FNO);
    }

    /** Scans today's selected universe and alerts only signals that have not already been sent. */
    public int scanAndAlert(Universe universe) {
        LocalDate today = LocalDate.now();
        List<GapBreakoutSignal> freshSignals = new ArrayList<>();
        for (GapBreakoutSignal signal : scan(today, universe)) {
            String key = today + ":" + signal.symbol() + ":" + signal.direction();
            if (alertedSignalKeys.add(key)) {
                freshSignals.add(signal);
            }
        }

        if (!freshSignals.isEmpty()) {
            telegramService.sendMessage(formatAlert(freshSignals, today));
        }
        return freshSignals.size();
    }

    /** Runs the strategy without sending alerts. Useful for diagnostics and backtests. */
    public List<GapBreakoutSignal> scan(LocalDate date) {
        return scan(date, Universe.NIFTY_FNO);
    }

    /** Runs the strategy for a specific universe without sending alerts. */
    public List<GapBreakoutSignal> scan(LocalDate date, Universe universe) {
        List<String> symbols = getSymbolsForUniverse(universe);
        Map<String, String> instrumentKeys = instrumentMaster.resolveToInstrumentKeyMap(symbols);
        List<GapBreakoutSignal> signals = new ArrayList<>();

        for (Map.Entry<String, String> entry : instrumentKeys.entrySet()) {
            try {
                evaluateSymbol(entry.getKey(), entry.getValue(), date).ifPresent(signals::add);
            } catch (Exception e) {
                log.warn("Gap breakout scan failed for {}: {}", entry.getKey(), e.getMessage());
            }
        }
        return signals;
    }

    public BacktestReport backtest(LocalDate from, LocalDate to) {
        return backtest(from, to, Universe.NIFTY_FNO, DEFAULT_COMBO);
    }

    public BacktestReport backtest(LocalDate from, LocalDate to, Universe universe) {
        return backtest(from, to, universe, DEFAULT_COMBO);
    }

    /** Verify a specific ParameterCombination (e.g. the sweep's top result) across a universe/date range. */
    public BacktestReport backtest(LocalDate from, LocalDate to, Universe universe, ParameterCombination combo) {
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from date must be on or before to date");
        }

        List<String> symbols = getSymbolsForUniverse(universe);
        Map<String, String> instrumentKeys = instrumentMaster.resolveToInstrumentKeyMap(symbols);

        List<LocalDate> dateRange = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            dateRange.add(date);
        }

        ExecutorService executor = Executors.newFixedThreadPool(20); // tune based on API limits
        try {
            List<CompletableFuture<List<BacktestTrade>>> futures = instrumentKeys.entrySet().stream()
                    .map(entry -> CompletableFuture.supplyAsync(() -> {
                        List<BacktestTrade> symbolTrades = new ArrayList<>();
                        for (LocalDate date : dateRange) {
                            try {
                                Optional<SymbolData> data = loadSymbolData(entry.getKey(), entry.getValue(), date);
                                if (data.isEmpty()) continue;
                                evaluate(entry.getKey(), date, data.get().candles(), data.get().previousClose(), combo)
                                        .ifPresent(symbolTrades::add);
                            } catch (Exception e) {
                                log.debug("Gap breakout backtest skipped {} {}: {}", entry.getKey(), date, e.getMessage());
                            }
                        }
                        return symbolTrades;
                    }, executor))
                    .toList();

            List<BacktestTrade> trades = futures.stream()
                    .flatMap(f -> f.join().stream())
                    .toList();

            long wins = trades.stream().filter(t -> t.outcome() == Outcome.FINAL_TARGET || t.outcome() == Outcome.PARTIAL_AND_EOD).count();
            long losses = trades.stream().filter(t -> t.outcome() == Outcome.STOP_LOSS || t.outcome() == Outcome.BREAKEVEN).count();
            double totalR = trades.stream().mapToDouble(BacktestTrade::pnlR).sum();
            return new BacktestReport(from, to, instrumentKeys.size(), trades.size(), wins, losses, totalR, trades);
        } finally {
            executor.shutdown();
        }
    }

    /** Fetches a symbol's data once for the whole range, then walks dates in-memory. */
    private List<BacktestTrade> backtestSymbolRange(String symbol,
                                                    String instrumentKey,
                                                    LocalDate from,
                                                    LocalDate to,
                                                    ParameterCombination combo) {

        List<BacktestTrade> trades = new ArrayList<>();

        try {
            List<Candle> dailyCandles = candleCache.getDailyCandles(
                    instrumentKey,
                    from.minusDays(7),
                    to.minusDays(1));

            Map<LocalDate, Double> closeByDate = dailyCandles.stream()
                    .collect(Collectors.toMap(
                            c -> c.getTimestamp().toLocalDate(),
                            Candle::getClose,
                            (a, b) -> b));

            for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {

                Double previousClose = findPreviousClose(closeByDate, date);
                if (previousClose == null || previousClose <= 0) {
                    continue;
                }

                List<Candle> dayCandles = candleCache.getDayCandles(instrumentKey, date);
                if (dayCandles.isEmpty()) {
                    continue;
                }

                evaluate(symbol, date, dayCandles, previousClose, combo).ifPresent(trades::add);
            }

        } catch (Exception e) {
            log.debug("Gap breakout backtest skipped {}: {}", symbol, e.getMessage());
        }

        return trades;
    }

    private Double findPreviousClose(Map<LocalDate, Double> closeByDate, LocalDate date) {
        for (int i = 1; i <= 7; i++) {
            Double close = closeByDate.get(date.minusDays(i));
            if (close != null) {
                return close;
            }
        }
        return null;
    }

    /** Backtest a specific F&O stock between date range. */
    public BacktestReport backtestSymbol(String symbol, LocalDate from, LocalDate to) {
        return backtestSymbol(symbol, from, to, Universe.NIFTY_FNO, DEFAULT_COMBO);
    }

    /** Backtest a specific symbol from the chosen universe between date range. */
    public BacktestReport backtestSymbol(String symbol, LocalDate from, LocalDate to, Universe universe) {
        return backtestSymbol(symbol, from, to, universe, DEFAULT_COMBO);
    }

    /** Verify a specific combo for a single symbol - e.g. the sweep's top result. */
    public BacktestReport backtestSymbol(String symbol, LocalDate from, LocalDate to, Universe universe, ParameterCombination combo) {
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from date must be on or before to date");
        }

        List<String> symbols = getSymbolsForUniverse(universe);
        Map<String, String> instrumentKeys = instrumentMaster.resolveToInstrumentKeyMap(symbols);
        String instrumentKey = instrumentKeys.get(symbol.toUpperCase());

        if (instrumentKey == null) {
            throw new IllegalArgumentException("Symbol " + symbol + " not found in " + universe + " universe");
        }

        List<BacktestTrade> trades = new ArrayList<>();

        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            try {
                Optional<SymbolData> data = loadSymbolData(symbol, instrumentKey, date);
                if (data.isEmpty()) continue;
                evaluate(symbol, date, data.get().candles(), data.get().previousClose(), combo)
                        .ifPresent(trades::add);
            } catch (Exception e) {
                log.debug("Gap breakout backtest skipped {} {}: {}", symbol, date, e.getMessage());
            }
        }

        long wins = trades.stream().filter(t -> t.outcome() == Outcome.FINAL_TARGET || t.outcome() == Outcome.PARTIAL_AND_EOD).count();
        long losses = trades.stream().filter(t -> t.outcome() == Outcome.STOP_LOSS || t.outcome() == Outcome.BREAKEVEN).count();
        double totalR = trades.stream().mapToDouble(BacktestTrade::pnlR).sum();
        return new BacktestReport(from, to, 1, trades.size(), wins, losses, totalR, trades);
    }

    private Optional<GapBreakoutSignal> evaluateSymbol(String symbol, String instrumentKey, LocalDate date) {
        Optional<SymbolData> data = loadSymbolData(symbol, instrumentKey, date);
        return data.flatMap(d -> identifySignal(symbol, date, d.candles(), d.previousClose(), DEFAULT_COMBO));
    }

    private List<String> getSymbolsForUniverse(Universe universe) {
        return switch (universe) {
            case NIFTY_50_TOP_10 -> UniverseService.NIFTY_50_TOP_10;
            case NIFTY_50_GAP_BREAKOUT -> UniverseService.NIFTY_50_GAP_BREAKOUT;
            case NIFTY_FNO -> UniverseService.NIFTY_FNO_SYMBOLS;
        };
    }

    private Optional<SymbolData> loadSymbolData(String symbol, String instrumentKey, LocalDate date) {
        List<Candle> candles = candleCache.getDayCandles(instrumentKey, date);
        if (candles.isEmpty()) return Optional.empty();

        List<Candle> dailyCandles = candleCache.getDailyCandles(instrumentKey, date.minusDays(5), date.minusDays(1));
        Optional<Candle> previousDay = dailyCandles.stream().max(Comparator.comparing(Candle::getTimestamp));
        if (previousDay.isEmpty() || previousDay.get().getClose() <= 0) return Optional.empty();

        return Optional.of(new SymbolData(candles, previousDay.get().getClose()));
    }

    private double getLowerWickPercent(Candle candle) {
        double range = candle.getHigh() - candle.getLow();
        if (range <= 0) return 0;
        double lowerWick = Math.min(candle.getOpen(), candle.getClose()) - candle.getLow();
        return lowerWick / range * 100.0;
    }

    /**
     * SINGLE public entry point for the strategy's rule logic - identify a
     * signal, then simulate it. GapBreakoutParameterSweepEngine calls this
     * exact method for every (symbol, date, combo), so the sweep can never
     * silently drift out of sync with the live/backtest logic.
     */
    public Optional<BacktestTrade> evaluate(
            String symbol, LocalDate date, List<Candle> candles, double previousClose, ParameterCombination combo) {

        return identifySignal(symbol, date, candles, previousClose, combo)
                .map(signal -> simulate(signal, candles, combo));
    }

    private Optional<GapBreakoutSignal> identifySignal(
            String symbol, LocalDate date, List<Candle> candles, double previousClose, ParameterCombination combo) {

        Candle first = candles.stream()
                .filter(c -> c.getTimestamp().toLocalTime().equals(FIRST_CANDLE_TIME))
                .findFirst()
                .orElse(null);
        if (first == null) return Optional.empty();

        Direction direction = first.getOpen() >= previousClose * (1 + combo.gapPercent / 100.0)
                ? Direction.LONG
                : first.getOpen() <= previousClose * (1 - combo.gapPercent / 100.0) ? Direction.SHORT : null;
        if (direction == null) return Optional.empty();

        return candles.stream()
                .filter(c -> c.getTimestamp().toLocalTime().isAfter(FIRST_CANDLE_TIME))
                .filter(c -> !c.getTimestamp().toLocalTime().isAfter(LAST_ENTRY_TIME))
                .sorted(Comparator.comparing(Candle::getTimestamp))
                .filter(c -> direction == Direction.LONG ? c.getClose() > first.getHigh() : c.getClose() < first.getLow())
                .filter(c -> getBodyPercent(c) >= combo.minBreakoutBodyPercent)
                .filter(c -> direction == Direction.LONG
                        ? getUpperWickPercent(c) <= combo.maxWickPercent
                        : getLowerWickPercent(c) <= combo.maxWickPercent)
                .findFirst()
                .map(c -> buildSignal(symbol, date, direction, previousClose, first, c, combo));
    }

    private double getUpperWickPercent(Candle candle) {
        double range = candle.getHigh() - candle.getLow();
        if (range <= 0) return 0;
        double upperWick = candle.getHigh() - Math.max(candle.getOpen(), candle.getClose());
        return upperWick / range * 100.0;
    }

    private GapBreakoutSignal buildSignal(
            String symbol, LocalDate date, Direction direction, double previousClose,
            Candle first, Candle breakout, ParameterCombination combo) {

        double entry = breakout.getClose();
        double stop = direction == Direction.LONG
                ? breakout.getLow() * (1 - combo.stopBufferPercent / 100.0)
                : breakout.getHigh() * (1 + combo.stopBufferPercent / 100.0);
        double risk = direction == Direction.LONG ? entry - stop : stop - entry;
        double partialTarget = direction == Direction.LONG ? entry + risk * combo.partialRR : entry - risk * combo.partialRR;
        double finalTarget = direction == Direction.LONG ? entry + risk * combo.finalRR : entry - risk * combo.finalRR;
        return new GapBreakoutSignal(symbol, date, direction, previousClose, first.getOpen(), first.getHigh(), first.getLow(),
                breakout.getTimestamp().toLocalTime(), entry, stop, partialTarget, finalTarget, risk);
    }

    private double getBodyPercent(Candle candle) {
        if (candle.getOpen() <= 0) return 0;
        return Math.abs(candle.getClose() - candle.getOpen()) / candle.getOpen() * 100.0;
    }

    private BacktestTrade simulate(GapBreakoutSignal signal, List<Candle> candles, ParameterCombination combo) {
        boolean partialHit = false;
        double pnlR = 0.0;
        Outcome outcome = Outcome.EOD_EXIT;
        double exitPrice = signal.entry();

        double partialPct = combo.partialQuantityPercent / 100.0;
        double remainPct = 1.0 - partialPct;

        for (Candle candle : candles.stream().sorted(Comparator.comparing(Candle::getTimestamp)).toList()) {
            if (!candle.getTimestamp().toLocalTime().isAfter(signal.entryTime())) continue;
            boolean stopHit = signal.direction() == Direction.LONG ? candle.getLow() <= (partialHit ? signal.entry() : signal.stopLoss())
                    : candle.getHigh() >= (partialHit ? signal.entry() : signal.stopLoss());
            if (stopHit) {
                outcome = partialHit ? Outcome.BREAKEVEN : Outcome.STOP_LOSS;
                exitPrice = partialHit ? signal.entry() : signal.stopLoss();
                pnlR = partialHit ? combo.partialRR * partialPct : -1.0;
                break;
            }
            if (!partialHit && (signal.direction() == Direction.LONG ? candle.getHigh() >= signal.partialTarget() : candle.getLow() <= signal.partialTarget())) {
                partialHit = true;
                pnlR = combo.partialRR * partialPct;
            }
            if (signal.direction() == Direction.LONG ? candle.getHigh() >= signal.finalTarget() : candle.getLow() <= signal.finalTarget()) {
                outcome = Outcome.FINAL_TARGET;
                exitPrice = signal.finalTarget();
                pnlR = combo.partialRR * partialPct + combo.finalRR * remainPct;
                break;
            }
            if (!candle.getTimestamp().toLocalTime().isBefore(EOD_TIME)) {
                exitPrice = candle.getClose();
                double remainingR = signal.direction() == Direction.LONG
                        ? (exitPrice - signal.entry()) / signal.riskPoints()
                        : (signal.entry() - exitPrice) / signal.riskPoints();
                pnlR = partialHit ? combo.partialRR * partialPct + remainingR * remainPct : remainingR;
                outcome = partialHit ? Outcome.PARTIAL_AND_EOD : Outcome.EOD_EXIT;
                break;
            }
        }
        return new BacktestTrade(signal, outcome, exitPrice, pnlR);
    }

    private String formatAlert(List<GapBreakoutSignal> signals, LocalDate date) {
        StringBuilder message = new StringBuilder("Gap 0.45% First-15m Breakout\nDate: ").append(date).append("\n\n");
        for (GapBreakoutSignal s : signals) {
            message.append(s.direction()).append(" ").append(s.symbol())
                    .append(" | Entry ").append(String.format("%.2f", s.entry()))
                    .append(" | SL ").append(String.format("%.2f", s.stopLoss()))
                    .append(" | 50% @ ").append(String.format("%.2f", s.partialTarget()))
                    .append(" | 50% @ ").append(String.format("%.2f", s.finalTarget())).append("\n");
        }
        return message.toString();
    }

    private record SymbolData(List<Candle> candles, double previousClose) { }
    public enum Direction { LONG, SHORT }
    public enum Outcome { STOP_LOSS, BREAKEVEN, FINAL_TARGET, PARTIAL_AND_EOD, EOD_EXIT }
    public record GapBreakoutSignal(String symbol, LocalDate date, Direction direction, double previousClose,
                                    double firstOpen, double firstHigh, double firstLow, LocalTime entryTime,
                                    double entry, double stopLoss, double partialTarget, double finalTarget, double riskPoints) { }
    public record BacktestTrade(GapBreakoutSignal signal, Outcome outcome, double exitPrice, double pnlR) { }
    public record BacktestReport(LocalDate from, LocalDate to, int fnoSymbols, int trades, long wins, long losses,
                                 double totalR, List<BacktestTrade> tradeDetails) { }

    /** Swept/verifiable parameter set for the gap-breakout rules. */
    public static class ParameterCombination {
        public final double gapPercent;
        public final double stopBufferPercent;
        public final double partialRR;
        public final double finalRR;
        public final double partialQuantityPercent;
        public final double minBreakoutBodyPercent;
        public final double maxWickPercent;

        public ParameterCombination(double gapPercent, double stopBufferPercent, double partialRR, double finalRR,
                                    double partialQuantityPercent, double minBreakoutBodyPercent, double maxWickPercent) {
            this.gapPercent = gapPercent;
            this.stopBufferPercent = stopBufferPercent;
            this.partialRR = partialRR;
            this.finalRR = finalRR;
            this.partialQuantityPercent = partialQuantityPercent;
            this.minBreakoutBodyPercent = minBreakoutBodyPercent;
            this.maxWickPercent = maxWickPercent;
        }

        @Override
        public String toString() {
            return String.format(
                    "gap=%.2f%% stopBuf=%.2f%% partialRR=%.2f finalRR=%.2f partialQty=%.0f%% minBody=%.2f%% maxWick=%.0f%%",
                    gapPercent, stopBufferPercent, partialRR, finalRR, partialQuantityPercent, minBreakoutBodyPercent, maxWickPercent);
        }
    }
}
