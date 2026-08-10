package com.trading.algo.fibostrategy;

import com.trading.algo.config.BacktestConfig;
import com.trading.algo.dtos.Candle;
import com.trading.algo.entity.BacktestTrade;
import com.trading.algo.service.UniverseService;
import com.trading.algo.upstox.UpstoxHistoricalCandleService;
import com.trading.algo.upstox.UpstoxInstrumentMasterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Parameter sweep engine for Fibonacci Opening Candle Strategy.
 *
 * FIX: candles are now fetched exactly ONCE per (symbol, day) - cached in
 * candleCache - and reused across all 1,728 parameter combinations. The old
 * version called candleService.fetchDayCandles() inside the combo loop,
 * meaning identical candle data was re-fetched from Upstox 1,728 times per
 * symbol-day (1,728 combos x 10 symbols x 2 days = 34,560 API calls for a
 * 2-day/10-symbol run). That's the entire cause of the multi-hour hang -
 * combo parameters only change how the STRATEGY interprets candles, they
 * never change which candles get fetched.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FiboParameterSweepEngine {

    private final UpstoxHistoricalCandleService candleService;
    private final UpstoxInstrumentMasterService instrumentMaster;
    private final OpeningCandleStrategyService strategy;
    private final BacktestConfig config;

    // Dynamic parameters to sweep
    private static final List<Double> MIN_WICK_RATIOS = List.of(0.60, 0.65, 0.70, 0.75);
    private static final List<Double> TARGET_RRS = List.of(2.0, 2.5, 3.0, 3.5);
    private static final List<Double> PARTIAL_EXIT_RRS = List.of(1.0, 1.5, 2.0);
    private static final List<Double> MIN_C1_ATR_RATIOS = List.of(0.4, 0.5, 0.6);
    private static final List<Double> MIN_C1_VOLUME_MULTIPLIERS = List.of(1.2, 1.3, 1.5, 1.8);
    private static final List<Double> SL_MARGIN_PERCENTS = List.of(0.3, 0.45, 0.6);

    /**
     * Run parameter sweep for given symbols and date range.
     */
    public SweepResult runSweep(List<String> symbols, LocalDate fromDate, LocalDate toDate) {
        log.info("Fibonacci Parameter Sweep START | symbols={} from={} to={}",
                symbols.size(), fromDate, toDate);

        Map<String, String> symbolKeyMap = instrumentMaster.resolveToInstrumentKeyMap(symbols);

        // ---- FIX: fetch every (symbol, day) candle set exactly once,
        // before touching the combo grid at all. ----
        Map<String, Map<LocalDate, List<Candle>>> candleCache =
                buildCandleCache(symbolKeyMap, fromDate, toDate);

        List<ParameterCombination> combinations = buildParameterCombinations();
        log.info("Testing {} parameter combinations against pre-fetched candle cache " +
                        "({} symbols, {} trading-day entries cached)",
                combinations.size(), symbolKeyMap.size(), countCachedDays(candleCache));

        List<CombinationResult> results = new ArrayList<>();

        for (ParameterCombination combo : combinations) {
            log.info("Testing combo: wickRatio={} targetRR={} partialRR={} atrRatio={} volMult={} slMargin={}%",
                    combo.minWickRatio, combo.targetRR, combo.partialExitRR,
                    combo.minC1AtrRatio, combo.minC1VolumeMultiplier, combo.slMarginPercent);

            List<BacktestTrade> allTrades = new ArrayList<>();

            for (Map.Entry<String, String> entry : symbolKeyMap.entrySet()) {
                String symbol = entry.getKey();

                List<BacktestTrade> symbolTrades = evaluateSymbolForCombo(
                        symbol, candleCache.get(symbol), combo);
                allTrades.addAll(symbolTrades);
            }

            CombinationResult result = new CombinationResult(combo, allTrades);
            results.add(result);
        }

        results.sort((a, b) -> Double.compare(b.getProfitFactor(), a.getProfitFactor()));

        log.info("Fibonacci Parameter Sweep COMPLETE");
        printTopResults(results, 10);

        return new SweepResult(results);
    }

    /**
     * Converts full results to a summary view with top N combinations.
     */
    public List<ParameterSweepSummaryRow> toSummary(List<CombinationResult> results, int topN) {
        List<ParameterSweepSummaryRow> summary = new ArrayList<>();

        int limit = Math.min(topN, results.size());

        for (int i = 0; i < limit; i++) {
            CombinationResult r = results.get(i);
            ParameterCombination c = r.combo;

            summary.add(new ParameterSweepSummaryRow(
                    i + 1,
                    c.minWickRatio,
                    c.targetRR,
                    c.partialExitRR,
                    c.minC1AtrRatio,
                    c.minC1VolumeMultiplier,
                    c.slMarginPercent,
                    r.totalTrades,
                    r.wins,
                    r.losses,
                    r.winRate,
                    r.profitFactor,
                    r.avgR));
        }

        return summary;
    }

    /**
     * Fetches candles for every (symbol, trading day) pair exactly once.
     * This is the ONLY place fetchDayCandles() is called in the sweep.
     */
    private Map<String, Map<LocalDate, List<Candle>>> buildCandleCache(
            Map<String, String> symbolKeyMap,
            LocalDate fromDate,
            LocalDate toDate) {

        Map<String, Map<LocalDate, List<Candle>>> cache = new LinkedHashMap<>();

        for (Map.Entry<String, String> entry : symbolKeyMap.entrySet()) {

            String symbol = entry.getKey();
            String instrumentKey = entry.getValue();

            Map<LocalDate, List<Candle>> perDay = new LinkedHashMap<>();
            LocalDate day = fromDate;

            while (!day.isAfter(toDate)) {

                if (day.getDayOfWeek() != DayOfWeek.SATURDAY
                        && day.getDayOfWeek() != DayOfWeek.SUNDAY) {

                    try {
                        List<Candle> candles = candleService.fetchDayCandles(instrumentKey, day);

                        if (candles != null && candles.size() >= 3) {
                            perDay.put(day, candles);
                        }

                    } catch (Exception e) {
                        log.error("Error fetching candles {} on {}: {}", symbol, day, e.getMessage());
                    }
                }

                day = day.plusDays(1);
            }

            cache.put(symbol, perDay);

            log.info("{}: cached {} trading days of candles", symbol, perDay.size());
        }

        return cache;
    }

    private int countCachedDays(Map<String, Map<LocalDate, List<Candle>>> cache) {
        int total = 0;
        for (Map<LocalDate, List<Candle>> perDay : cache.values()) {
            total += perDay.size();
        }
        return total;
    }

    /**
     * Build all parameter combinations to test.
     */
    private List<ParameterCombination> buildParameterCombinations() {
        List<ParameterCombination> combos = new ArrayList<>();

        for (double minWickRatio : MIN_WICK_RATIOS) {
            for (double targetRR : TARGET_RRS) {
                for (double partialExitRR : PARTIAL_EXIT_RRS) {
                    for (double minC1AtrRatio : MIN_C1_ATR_RATIOS) {
                        for (double minC1VolumeMultiplier : MIN_C1_VOLUME_MULTIPLIERS) {
                            for (double slMarginPercent : SL_MARGIN_PERCENTS) {
                                combos.add(new ParameterCombination(
                                        minWickRatio, targetRR, partialExitRR,
                                        minC1AtrRatio, minC1VolumeMultiplier, slMarginPercent));
                            }
                        }
                    }
                }
            }
        }

        return combos;
    }

    /**
     * Evaluates a single symbol against one combo, using ONLY the
     * pre-fetched candle cache - no API calls happen in here at all.
     *
     * NOTE ON THREAD-SAFETY: config is still a shared mutable bean, mutated
     * in place per combo exactly as before. That's fine as long as this
     * loop stays single-threaded (which it is, in runSweep above). Do NOT
     * parallelize this method's caller without first reworking evaluate()
     * to take config as an explicit parameter - concurrent combos would
     * otherwise race on config's fields and silently corrupt results.
     */
    private List<BacktestTrade> evaluateSymbolForCombo(
            String symbol,
            Map<LocalDate, List<Candle>> cachedDaysForSymbol,
            ParameterCombination combo) {

        List<BacktestTrade> trades = new ArrayList<>();

        if (cachedDaysForSymbol == null || cachedDaysForSymbol.isEmpty()) {
            return trades;
        }

        double originalMinWickRatio = config.getMinWickRatio();
        double originalTargetRR = config.getTargetRR();
        double originalPartialExitRR = config.getPartialExitRR();
        double originalMinC1AtrRatio = config.getMinC1AtrRatio();
        double originalMinC1VolumeMultiplier = config.getMinC1VolumeMultiplier();
        double originalSlMarginPercent = config.getSlMarginPercent();

        config.setMinWickRatio(combo.minWickRatio);
        config.setTargetRR(combo.targetRR);
        config.setPartialExitRR(combo.partialExitRR);
        config.setMinC1AtrRatio(combo.minC1AtrRatio);
        config.setMinC1VolumeMultiplier(combo.minC1VolumeMultiplier);
        config.setSlMarginPercent(combo.slMarginPercent);

        try {
            for (Map.Entry<LocalDate, List<Candle>> dayEntry : cachedDaysForSymbol.entrySet()) {

                LocalDate day = dayEntry.getKey();
                List<Candle> candles = dayEntry.getValue();

                try {
                    Optional<BacktestTrade> trade = strategy.evaluate(symbol, day, candles);
                    trade.ifPresent(trades::add);
                } catch (Exception e) {
                    log.error("Error evaluating {} on {}: {}", symbol, day, e.getMessage());
                }
            }
        } finally {
            config.setMinWickRatio(originalMinWickRatio);
            config.setTargetRR(originalTargetRR);
            config.setPartialExitRR(originalPartialExitRR);
            config.setMinC1AtrRatio(originalMinC1AtrRatio);
            config.setMinC1VolumeMultiplier(originalMinC1VolumeMultiplier);
            config.setSlMarginPercent(originalSlMarginPercent);
        }

        return trades;
    }

    private void printTopResults(List<CombinationResult> results, int topN) {
        log.info("=== TOP {} PARAMETER COMBINATIONS (sorted by profit factor) ===", topN);

        int limit = Math.min(topN, results.size());
        for (int i = 0; i < limit; i++) {
            CombinationResult r = results.get(i);
            ParameterCombination c = r.combo;

            log.info("#{} wick={} target={} partial={} atr={} vol={} sl={}% | trades={} wins={} winRate={}% PF={} avgR={}",
                    i + 1,
                    c.minWickRatio, c.targetRR, c.partialExitRR,
                    c.minC1AtrRatio, c.minC1VolumeMultiplier, c.slMarginPercent,
                    r.totalTrades, r.wins, r.winRate, r.profitFactor, r.avgR);
        }
    }

    // Parameter combination holder
    public static class ParameterCombination {
        final double minWickRatio;
        final double targetRR;
        final double partialExitRR;
        final double minC1AtrRatio;
        final double minC1VolumeMultiplier;
        final double slMarginPercent;

        public ParameterCombination(double minWickRatio, double targetRR, double partialExitRR,
                                    double minC1AtrRatio, double minC1VolumeMultiplier, double slMarginPercent) {
            this.minWickRatio = minWickRatio;
            this.targetRR = targetRR;
            this.partialExitRR = partialExitRR;
            this.minC1AtrRatio = minC1AtrRatio;
            this.minC1VolumeMultiplier = minC1VolumeMultiplier;
            this.slMarginPercent = slMarginPercent;
        }
    }

    // Result for a single parameter combination
    public static class CombinationResult {
        final ParameterCombination combo;
        final List<BacktestTrade> trades;
        final int totalTrades;
        final int wins;
        final int losses;
        final double winRate;
        final double profitFactor;
        final double avgR;

        public ParameterCombination getCombo() { return combo; }
        public List<BacktestTrade> getTrades() { return trades; }
        public int getTotalTrades() { return totalTrades; }
        public int getWins() { return wins; }
        public int getLosses() { return losses; }
        public double getWinRate() { return winRate; }
        public double getProfitFactor() { return profitFactor; }
        public double getAvgR() { return avgR; }

        public CombinationResult(ParameterCombination combo, List<BacktestTrade> trades) {
            this.combo = combo;
            this.trades = trades;
            this.totalTrades = trades.size();

            int w = 0, l = 0;
            double totalR = 0;
            double grossProfit = 0;
            double grossLoss = 0;

            for (BacktestTrade t : trades) {
                double r = t.getActualRR();
                totalR += r;

                if (r > 0) {
                    w++;
                    grossProfit += r;
                } else if (r < 0) {
                    l++;
                    grossLoss += Math.abs(r);
                }
            }

            this.wins = w;
            this.losses = l;
            this.winRate = totalTrades > 0 ? (double) w / totalTrades * 100.0 : 0;
            this.profitFactor = grossLoss > 0 ? grossProfit / grossLoss : (grossProfit > 0 ? Double.MAX_VALUE : 0);
            this.avgR = totalTrades > 0 ? totalR / totalTrades : 0;
        }
    }

    // Overall sweep result
    public static class SweepResult {
        final List<CombinationResult> results;

        public SweepResult(List<CombinationResult> results) {
            this.results = results;
        }

        public List<CombinationResult> getResults() {
            return results;
        }
    }

    /**
     * Summary row for top N parameter combinations.
     */
    @lombok.Getter
    @lombok.AllArgsConstructor
    public static class ParameterSweepSummaryRow {
        private final int rank;
        private final double minWickRatio;
        private final double targetRR;
        private final double partialExitRR;
        private final double minC1AtrRatio;
        private final double minC1VolumeMultiplier;
        private final double slMarginPercent;
        private final int totalTrades;
        private final int wins;
        private final int losses;
        private final double winRate;
        private final double profitFactor;
        private final double avgR;
    }
}