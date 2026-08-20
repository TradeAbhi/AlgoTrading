package com.trading.algo.fibostrategy;

import com.trading.algo.entity.BacktestTrade;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Profitability metrics for a previous-day-level Fibonacci category. */
public record FiboCategoryPerformance(
        String category,
        String label,
        int totalTrades,
        int wins,
        int losses,
        int breakeven,
        double winRate,
        double totalR,
        double averageR,
        double grossProfitR,
        double grossLossR,
        double profitFactor,
        double totalPnlRupees) {

    public static Map<FiboPreviousDayCategory, FiboCategoryPerformance> summarize(List<BacktestTrade> trades) {
        Map<FiboPreviousDayCategory, FiboCategoryPerformance> results =
                new EnumMap<>(FiboPreviousDayCategory.class);
        for (FiboPreviousDayCategory category : FiboPreviousDayCategory.values()) {
            List<BacktestTrade> categoryTrades = trades.stream()
                    .filter(trade -> FiboPreviousDayCategory.from(trade) == category)
                    .toList();
            results.put(category, from(category, categoryTrades));
        }
        return results;
    }

    private static FiboCategoryPerformance from(FiboPreviousDayCategory category, List<BacktestTrade> trades) {
        int wins = (int) trades.stream().filter(trade -> trade.getActualRR() > 0).count();
        int losses = (int) trades.stream().filter(trade -> trade.getActualRR() < 0).count();
        int breakeven = trades.size() - wins - losses;
        double totalR = trades.stream().mapToDouble(BacktestTrade::getActualRR).sum();
        double grossProfit = trades.stream().filter(trade -> trade.getActualRR() > 0)
                .mapToDouble(BacktestTrade::getActualRR).sum();
        double grossLoss = trades.stream().filter(trade -> trade.getActualRR() < 0)
                .mapToDouble(trade -> Math.abs(trade.getActualRR())).sum();
        double totalPnl = trades.stream().mapToDouble(BacktestTrade::getPnlRupees).sum();
        double profitFactor = grossLoss > 0 ? grossProfit / grossLoss : (grossProfit > 0 ? Double.MAX_VALUE : 0);
        return new FiboCategoryPerformance(category.name(), category.getLabel(), trades.size(), wins, losses, breakeven,
                trades.isEmpty() ? 0 : (double) wins / trades.size() * 100.0,
                totalR, trades.isEmpty() ? 0 : totalR / trades.size(), grossProfit, grossLoss, profitFactor, totalPnl);
    }
}
