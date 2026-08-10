package com.trading.algo.gapbreakout;

import com.trading.algo.dtos.Candle;
import com.trading.algo.service.UniverseService;
import com.trading.algo.upstox.UpstoxInstrumentMasterService;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Parameter sweep for GapBreakoutService's rules. Fetches candle + daily
 * data ONCE per symbol across the whole date range (cached via
 * GapBreakoutCandleCache), then runs every parameter combination in memory
 * by calling GapBreakoutEngineService.evaluate(...) directly.
 *
 * PERFORMANCE: two separate thread pools -
 *   - symbolFetchExecutor: I/O-bound (cache reads + occasional Upstox
 *     calls on cache miss), sized larger than core count since threads
 *     spend most of their time waiting on I/O, not CPU.
 *   - comboEvalExecutor: CPU-bound (pure in-memory rule evaluation across
 *     729 combos), sized to available cores - more threads than cores here
 *     just adds context-switch overhead with no throughput gain.
 *
 * Both loops are safe to parallelize because ParameterCombination is
 * passed as an explicit argument (no shared mutable config bean like the
 * Fibo sweep engine had), and symbolDataMap is fully built and read-only
 * by the time combo evaluation starts.
 */
@Service
public class GapBreakoutParameterSweepEngine {

    private static final Logger log = LoggerFactory.getLogger(GapBreakoutParameterSweepEngine.class);

    private final GapBreakoutCandleCache candleCache;
    private final UpstoxInstrumentMasterService instrumentMaster;
    private final GapBreakoutEngineService gapBreakoutService;

    // I/O-bound: candle cache reads + occasional cache-miss API calls.
    // Sized generously since these threads spend most time waiting, not
    // computing. Tune based on Upstox's actual rate limit.
    private static final int SYMBOL_FETCH_THREADS = 20;

    // CPU-bound: pure in-memory combo evaluation. Matches core count.
    private static final int COMBO_EVAL_THREADS =
            Math.max(2, Runtime.getRuntime().availableProcessors());

    public GapBreakoutParameterSweepEngine(GapBreakoutCandleCache candleCache,
                                           UpstoxInstrumentMasterService instrumentMaster,
                                           GapBreakoutEngineService gapBreakoutService) {
        this.candleCache = candleCache;
        this.instrumentMaster = instrumentMaster;
        this.gapBreakoutService = gapBreakoutService;
    }

    // ---- Swept parameters (3^6 = 729 combinations) ----
    private static final List<Double> GAP_PERCENTS = List.of(0.35, 0.45, 0.55);
    private static final List<Double> STOP_BUFFER_PERCENTS = List.of(0.25, 0.35, 0.45);
    private static final List<Double> PARTIAL_RRS = List.of(0.75, 1.0, 1.5);
    private static final List<Double> FINAL_RRS = List.of(1.5, 2.0, 2.5);
    private static final List<Double> MIN_BREAKOUT_BODY_PERCENTS = List.of(0.5, 0.65, 0.8);
    private static final List<Double> MAX_WICK_PERCENTS = List.of(20.0, 30.0, 40.0);

    private static final double PARTIAL_QUANTITY_PERCENT = GapBreakoutEngineService.PARTIAL_QUANTITY_PERCENT;

    public SweepResult runSweep(LocalDate from, LocalDate to, GapBreakoutEngineService.Universe universe) {

        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from date must be on or before to date");
        }

        List<String> symbols = switch (universe) {
            case NIFTY_50_TOP_10 -> UniverseService.NIFTY_50_TOP_10;
            case NIFTY_50_GAP_BREAKOUT -> UniverseService.NIFTY_50_GAP_BREAKOUT;
            case NIFTY_FNO -> UniverseService.NIFTY_FNO_SYMBOLS;
        };

        Map<String, String> instrumentKeys = instrumentMaster.resolveToInstrumentKeyMap(symbols);

        long t0 = System.currentTimeMillis();
        log.info("Fetching candle data (parallel, {} threads) for {} symbols, {} to {}",
                SYMBOL_FETCH_THREADS, instrumentKeys.size(), from, to);

        Map<String, SymbolData> symbolDataMap = buildSymbolDataCacheParallel(instrumentKeys, from, to);

        long t1 = System.currentTimeMillis();
        log.info("Candle data ready in {} ms", t1 - t0);

        List<GapBreakoutEngineService.ParameterCombination> combos = buildGrid();

        log.info("Running {} parameter combinations across {} symbols (parallel, {} threads, no further API calls)",
                combos.size(), symbolDataMap.size(), COMBO_EVAL_THREADS);

        List<CombinationResult> results = evaluateCombosParallel(combos, symbolDataMap);

        long t2 = System.currentTimeMillis();
        log.info("Combo evaluation done in {} ms (total sweep: {} ms)", t2 - t1, t2 - t0);

        results.sort((a, b) -> Double.compare(b.getProfitFactor(), a.getProfitFactor()));

        printTopResults(results, 10);

        return new SweepResult(results);
    }

    /**
     * Evaluates every combo in parallel. Each task owns its own ArrayList
     * of trades and its own CombinationResult - no shared mutable state
     * across threads, so no locking is needed here.
     */
    private List<CombinationResult> evaluateCombosParallel(
            List<GapBreakoutEngineService.ParameterCombination> combos,
            Map<String, SymbolData> symbolDataMap) {

        ExecutorService executor = Executors.newFixedThreadPool(COMBO_EVAL_THREADS);

        try {
            List<CompletableFuture<CombinationResult>> futures = combos.stream()
                    .map(combo -> CompletableFuture.supplyAsync(
                            () -> evaluateSingleCombo(combo, symbolDataMap), executor))
                    .toList();

            return futures.stream()
                    .map(CompletableFuture::join)
                    .collect(Collectors.toCollection(ArrayList::new));

        } finally {
            executor.shutdown();
        }
    }

    private CombinationResult evaluateSingleCombo(
            GapBreakoutEngineService.ParameterCombination combo,
            Map<String, SymbolData> symbolDataMap) {

        List<GapBreakoutEngineService.BacktestTrade> allTrades = new ArrayList<>();

        for (SymbolData symbolData : symbolDataMap.values()) {
            for (Map.Entry<LocalDate, DayData> dayEntry : symbolData.dayDataByDate().entrySet()) {

                DayData dayData = dayEntry.getValue();

                try {
                    gapBreakoutService.evaluate(
                                    symbolData.symbol(), dayEntry.getKey(),
                                    dayData.candles(), dayData.previousClose(), combo)
                            .ifPresent(allTrades::add);
                } catch (Exception e) {
                    log.debug("Combo eval skipped {} {}: {}", symbolData.symbol(), dayEntry.getKey(), e.getMessage());
                }
            }
        }

        return new CombinationResult(combo, allTrades);
    }

    public List<SweepSummaryRow> toSummary(SweepResult sweepResult, int topN) {

        List<CombinationResult> results = sweepResult.getResults();
        List<SweepSummaryRow> summary = new ArrayList<>();
        int limit = Math.min(topN, results.size());

        for (int i = 0; i < limit; i++) {
            CombinationResult r = results.get(i);
            GapBreakoutEngineService.ParameterCombination c = r.getCombo();

            summary.add(new SweepSummaryRow(
                    i + 1,
                    c.gapPercent, c.stopBufferPercent, c.partialRR, c.finalRR,
                    c.minBreakoutBodyPercent, c.maxWickPercent,
                    r.getTotalTrades(), r.getWins(), r.getLosses(),
                    r.getWinRate(), r.getProfitFactor(), r.getAvgR()));
        }

        return summary;
    }

    private void printTopResults(List<CombinationResult> results, int topN) {
        log.info("=== TOP {} GAP-BREAKOUT COMBINATIONS (sorted by profit factor) ===", topN);

        int limit = Math.min(topN, results.size());
        for (int i = 0; i < limit; i++) {
            CombinationResult r = results.get(i);
            log.info("#{} {} -> trades={} wins={} losses={} winRate={}% PF={} avgR={}",
                    i + 1, r.getCombo(), r.getTotalTrades(), r.getWins(), r.getLosses(),
                    String.format("%.1f", r.getWinRate()), String.format("%.2f", r.getProfitFactor()),
                    String.format("%.2f", r.getAvgR()));
        }
    }

    private List<GapBreakoutEngineService.ParameterCombination> buildGrid() {

        List<GapBreakoutEngineService.ParameterCombination> combos = new ArrayList<>();

        for (double gapPct : GAP_PERCENTS) {
            for (double stopBufPct : STOP_BUFFER_PERCENTS) {
                for (double partialRR : PARTIAL_RRS) {
                    for (double finalRR : FINAL_RRS) {

                        if (finalRR <= partialRR) {
                            continue;
                        }

                        for (double minBodyPct : MIN_BREAKOUT_BODY_PERCENTS) {
                            for (double maxWickPct : MAX_WICK_PERCENTS) {
                                combos.add(new GapBreakoutEngineService.ParameterCombination(
                                        gapPct, stopBufPct, partialRR, finalRR,
                                        PARTIAL_QUANTITY_PERCENT, minBodyPct, maxWickPct));
                            }
                        }
                    }
                }
            }
        }

        return combos;
    }

    /**
     * Fetches daily + 15m candle data once per symbol, in parallel across
     * symbols. Each symbol's fetch is fully independent - different
     * instrument keys, different cache rows/API calls - so there's no
     * shared state to worry about here either.
     */
    private Map<String, SymbolData> buildSymbolDataCacheParallel(
            Map<String, String> instrumentKeys, LocalDate from, LocalDate to) {

        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(SYMBOL_FETCH_THREADS, Math.max(1, instrumentKeys.size())));

        try {
            List<CompletableFuture<Map.Entry<String, SymbolData>>> futures = instrumentKeys.entrySet().stream()
                    .map(entry -> CompletableFuture.supplyAsync(
                            () -> Map.entry(entry.getKey(), fetchSymbolData(entry.getKey(), entry.getValue(), from, to)),
                            executor))
                    .toList();

            Map<String, SymbolData> cache = new LinkedHashMap<>();

            for (CompletableFuture<Map.Entry<String, SymbolData>> future : futures) {
                Map.Entry<String, SymbolData> entry = future.join();
                if (entry.getValue() != null) {
                    cache.put(entry.getKey(), entry.getValue());
                }
            }

            return cache;

        } finally {
            executor.shutdown();
        }
    }

    private SymbolData fetchSymbolData(String symbol, String instrumentKey, LocalDate from, LocalDate to) {

        try {
            List<Candle> dailyCandles = candleCache.getDailyCandles(
                    instrumentKey, from.minusDays(7), to.minusDays(1));

            Map<LocalDate, Double> closeByDate = dailyCandles.stream()
                    .collect(Collectors.toMap(
                            c -> c.getTimestamp().toLocalDate(),
                            Candle::getClose,
                            (a, b) -> b));

            Map<LocalDate, DayData> dayDataByDate = new LinkedHashMap<>();

            for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {

                Double previousClose = findPreviousClose(closeByDate, date);
                if (previousClose == null || previousClose <= 0) {
                    continue;
                }

                List<Candle> dayCandles = candleCache.getDayCandles(instrumentKey, date);
                if (dayCandles == null || dayCandles.isEmpty()) {
                    continue;
                }

                dayDataByDate.put(date, new DayData(dayCandles, previousClose));
            }

            log.info("{}: cached {} trading days", symbol, dayDataByDate.size());
            return new SymbolData(symbol, dayDataByDate);

        } catch (Exception e) {
            log.warn("Failed to cache data for {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    private Double findPreviousClose(Map<LocalDate, Double> closeByDate, LocalDate date) {
        for (int i = 1; i <= 7; i++) {
            Double close = closeByDate.get(date.minusDays(i));
            if (close != null) return close;
        }
        return null;
    }

    private record DayData(List<Candle> candles, double previousClose) { }
    private record SymbolData(String symbol, Map<LocalDate, DayData> dayDataByDate) { }

    public static class CombinationResult {
        private final GapBreakoutEngineService.ParameterCombination combo;
        private final List<GapBreakoutEngineService.BacktestTrade> trades;
        private final int totalTrades;
        private final int wins;
        private final int losses;
        private final double winRate;
        private final double profitFactor;
        private final double avgR;

        public CombinationResult(GapBreakoutEngineService.ParameterCombination combo,
                                 List<GapBreakoutEngineService.BacktestTrade> trades) {
            this.combo = combo;
            this.trades = trades;
            this.totalTrades = trades.size();

            int w = 0, l = 0;
            double totalR = 0, grossProfit = 0, grossLoss = 0;

            for (GapBreakoutEngineService.BacktestTrade t : trades) {
                double r = t.pnlR();
                totalR += r;
                if (r > 0) { w++; grossProfit += r; }
                else if (r < 0) { l++; grossLoss += Math.abs(r); }
            }

            this.wins = w;
            this.losses = l;
            this.winRate = totalTrades > 0 ? (double) w / totalTrades * 100.0 : 0;
            this.profitFactor = grossLoss > 0 ? grossProfit / grossLoss : (grossProfit > 0 ? Double.MAX_VALUE : 0);
            this.avgR = totalTrades > 0 ? totalR / totalTrades : 0;
        }

        public GapBreakoutEngineService.ParameterCombination getCombo() { return combo; }
        public List<GapBreakoutEngineService.BacktestTrade> getTrades() { return trades; }
        public int getTotalTrades() { return totalTrades; }
        public int getWins() { return wins; }
        public int getLosses() { return losses; }
        public double getWinRate() { return winRate; }
        public double getProfitFactor() { return profitFactor; }
        public double getAvgR() { return avgR; }
    }

    public static class SweepResult {
        private final List<CombinationResult> results;

        public SweepResult(List<CombinationResult> results) {
            this.results = results;
        }

        public List<CombinationResult> getResults() { return results; }
    }

    public static class SweepSummaryRow {
        private final int rank;
        private final double gapPercent;
        private final double stopBufferPercent;
        private final double partialRR;
        private final double finalRR;
        private final double minBreakoutBodyPercent;
        private final double maxWickPercent;
        private final int trades;
        private final int wins;
        private final int losses;
        private final double winRate;
        private final double profitFactor;
        private final double avgR;

        public SweepSummaryRow(int rank, double gapPercent, double stopBufferPercent, double partialRR,
                               double finalRR, double minBreakoutBodyPercent, double maxWickPercent,
                               int trades, int wins, int losses, double winRate, double profitFactor, double avgR) {
            this.rank = rank;
            this.gapPercent = gapPercent;
            this.stopBufferPercent = stopBufferPercent;
            this.partialRR = partialRR;
            this.finalRR = finalRR;
            this.minBreakoutBodyPercent = minBreakoutBodyPercent;
            this.maxWickPercent = maxWickPercent;
            this.trades = trades;
            this.wins = wins;
            this.losses = losses;
            this.winRate = winRate;
            this.profitFactor = profitFactor;
            this.avgR = avgR;
        }

        public int getRank() { return rank; }
        public double getGapPercent() { return gapPercent; }
        public double getStopBufferPercent() { return stopBufferPercent; }
        public double getPartialRR() { return partialRR; }
        public double getFinalRR() { return finalRR; }
        public double getMinBreakoutBodyPercent() { return minBreakoutBodyPercent; }
        public double getMaxWickPercent() { return maxWickPercent; }
        public int getTrades() { return trades; }
        public int getWins() { return wins; }
        public int getLosses() { return losses; }
        public double getWinRate() { return winRate; }
        public double getProfitFactor() { return profitFactor; }
        public double getAvgR() { return avgR; }
    }
}