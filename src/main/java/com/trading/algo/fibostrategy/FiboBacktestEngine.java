package com.trading.algo.fibostrategy;

import com.trading.algo.config.BacktestConfig;
import com.trading.algo.dtos.Candle;
import com.trading.algo.entity.BacktestTrade;
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

/**
 * Simple backtest engine for Fibonacci Opening Candle Strategy.
 *
 * Uses the existing OpeningCandleStrategyService to backtest individual symbols
 * over a date range with the configured parameters.
 *
 * NEW: overloads that accept a FiboParameterSweepEngine.ParameterCombination
 * directly, so a winning combo from the sweep can be verified here without
 * touching application config / redeploying. Config is swapped in exactly
 * like FiboParameterSweepEngine does, and always restored in a finally
 * block even if evaluation throws.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FiboBacktestEngine {

    private final UpstoxHistoricalCandleService candleService;
    private final UpstoxInstrumentMasterService instrumentMaster;
    private final OpeningCandleStrategyService strategy;
    private final BacktestConfig config;

    /**
     * Run backtest for a single symbol using whatever parameters are
     * currently set in BacktestConfig (unchanged behavior).
     */
    public FiboBacktestResult runBacktest(String symbol, LocalDate fromDate, LocalDate toDate) {
        return runBacktest(symbol, fromDate, toDate, null);
    }

    /**
     * Run backtest for a single symbol using a specific parameter
     * combination - e.g. the top result from FiboParameterSweepEngine.
     * Pass null for combo to use whatever is currently in BacktestConfig.
     */
    public FiboBacktestResult runBacktest(
            String symbol,
            LocalDate fromDate,
            LocalDate toDate,
            FiboParameterSweepEngine.ParameterCombination combo) {

        log.info("Fibonacci Backtest START | symbol={} from={} to={} combo={}",
                symbol, fromDate, toDate, combo);

        String instrumentKey = instrumentMaster.getInstrumentKey(symbol).orElse(null);
        if (instrumentKey == null) {
            log.error("Could not resolve instrument key for symbol: {}", symbol);
            return new FiboBacktestResult(symbol, fromDate, toDate, new ArrayList<>());
        }

        List<BacktestTrade> trades = new ArrayList<>();
        LocalDate day = fromDate;

        // ---- swap in combo params if provided, always restore after ----
        double originalMinWickRatio = config.getMinWickRatio();
        double originalTargetRR = config.getTargetRR();
        double originalPartialExitRR = config.getPartialExitRR();
        double originalMinC1AtrRatio = config.getMinC1AtrRatio();
        double originalMinC1VolumeMultiplier = config.getMinC1VolumeMultiplier();
        double originalSlMarginPercent = config.getSlMarginPercent();

        if (combo != null) {
            config.setMinWickRatio(combo.minWickRatio);
            config.setTargetRR(combo.targetRR);
            config.setPartialExitRR(combo.partialExitRR);
            config.setMinC1AtrRatio(combo.minC1AtrRatio);
            config.setMinC1VolumeMultiplier(combo.minC1VolumeMultiplier);
            config.setSlMarginPercent(combo.slMarginPercent);
        }

        try {
            while (!day.isAfter(toDate)) {
                LocalDate currentDay = day; // effectively final copy for lambda
                if (day.getDayOfWeek() != DayOfWeek.SATURDAY &&
                        day.getDayOfWeek() != DayOfWeek.SUNDAY) {
                    try {
                        List<Candle> candles = candleService.fetchDayCandles(instrumentKey, day);

                        if (candles != null && candles.size() >= 3) {
                            Optional<BacktestTrade> trade = strategy.evaluate(symbol, day, candles);
                            trade.ifPresent(t -> {
                                trades.add(t);
                                log.info("Trade found: {} {} {} Entry={} SL={} Target={}",
                                        symbol, currentDay, t.getDirection(),
                                        String.format("%.2f", t.getEntryPrice()),
                                        String.format("%.2f", t.getStopLoss()),
                                        String.format("%.2f", t.getTarget()));
                            });
                        }
                    } catch (Exception e) {
                        log.error("Error backtesting {} on {}: {}", symbol, day, e.getMessage());
                    }
                }
                day = day.plusDays(1);
            }
        } finally {
            if (combo != null) {
                config.setMinWickRatio(originalMinWickRatio);
                config.setTargetRR(originalTargetRR);
                config.setPartialExitRR(originalPartialExitRR);
                config.setMinC1AtrRatio(originalMinC1AtrRatio);
                config.setMinC1VolumeMultiplier(originalMinC1VolumeMultiplier);
                config.setSlMarginPercent(originalSlMarginPercent);
            }
        }

        log.info("Fibonacci Backtest COMPLETE | symbol={} totalTrades={}", symbol, trades.size());
        return new FiboBacktestResult(symbol, fromDate, toDate, trades);
    }

    /**
     * Run backtest for multiple symbols using current BacktestConfig.
     */
    public Map<String, FiboBacktestResult> runBacktest(List<String> symbols, LocalDate fromDate, LocalDate toDate) {
        return runBacktest(symbols, fromDate, toDate, null);
    }

    /**
     * Run backtest for multiple symbols using a specific parameter
     * combination from the sweep engine.
     */
    public Map<String, FiboBacktestResult> runBacktest(
            List<String> symbols,
            LocalDate fromDate,
            LocalDate toDate,
            FiboParameterSweepEngine.ParameterCombination combo) {

        log.info("Fibonacci Backtest START | symbols={} from={} to={} combo={}",
                symbols.size(), fromDate, toDate, combo);

        Map<String, String> symbolKeyMap = instrumentMaster.resolveToInstrumentKeyMap(symbols);
        Map<String, FiboBacktestResult> results = new java.util.LinkedHashMap<>();

        for (Map.Entry<String, String> entry : symbolKeyMap.entrySet()) {
            String symbol = entry.getKey();

            FiboBacktestResult result = runBacktest(symbol, fromDate, toDate, combo);
            results.put(symbol, result);
        }

        return results;
    }

    /**
     * Backtest result holder.
     */
    public static class FiboBacktestResult {
        private final String symbol;
        private final LocalDate fromDate;
        private final LocalDate toDate;
        private final List<BacktestTrade> trades;

        public FiboBacktestResult(String symbol, LocalDate fromDate, LocalDate toDate, List<BacktestTrade> trades) {
            this.symbol = symbol;
            this.fromDate = fromDate;
            this.toDate = toDate;
            this.trades = trades;
        }

        public String getSymbol() { return symbol; }
        public LocalDate getFromDate() { return fromDate; }
        public LocalDate getToDate() { return toDate; }
        public List<BacktestTrade> getTrades() { return trades; }
        public int getTotalTrades() { return trades.size(); }

        public int getWins() {
            return (int) trades.stream().filter(t -> t.getActualRR() > 0).count();
        }

        public int getLosses() {
            return (int) trades.stream().filter(t -> t.getActualRR() < 0).count();
        }

        public double getWinRate() {
            int closed = getWins() + getLosses();
            return closed > 0 ? (double) getWins() / closed * 100.0 : 0;
        }

        public double getProfitFactor() {
            double grossProfit = trades.stream()
                    .filter(t -> t.getActualRR() > 0)
                    .mapToDouble(BacktestTrade::getActualRR)
                    .sum();
            double grossLoss = trades.stream()
                    .filter(t -> t.getActualRR() < 0)
                    .mapToDouble(t -> Math.abs(t.getActualRR()))
                    .sum();
            return grossLoss > 0 ? grossProfit / grossLoss : (grossProfit > 0 ? Double.MAX_VALUE : 0);
        }

        public double getAvgR() {
            return trades.stream()
                    .mapToDouble(BacktestTrade::getActualRR)
                    .average()
                    .orElse(0);
        }

        public double getTotalPnlRupees() {
            return trades.stream()
                    .mapToDouble(BacktestTrade::getPnlRupees)
                    .sum();
        }
    }
}