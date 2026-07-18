package com.trading.algo.indexfutures.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * Aggregated backtest results for the Index Futures Volume Spike strategy.
 */
@Data
@Builder
public class IndexFuturesVolumeBacktestResult {

    // ---- Run parameters ----
    private String    label;
    private String    instrumentKey;
    private LocalDate fromDate;
    private LocalDate toDate;
    private double    spikeMultiplier;
    private double    climaxMultiplier;
    private double    riskPercent;
    private double    breakoutRR;
    private double    absorptionRR;
    private double    climaxRR;

    // ---- Overall stats ----
    private int    totalTrades;
    private int    wins;
    private int    losses;
    private int    openTrades;
    private double winRate;
    private double totalPnlR;
    private double avgPnlR;
    private double profitFactor;
    private double maxDrawdownR;
    private double avgWinR;
    private double avgLossR;
    private double largestWinR;
    private double largestLossR;
    private int    maxConsecWins;
    private int    maxConsecLosses;

    // ---- Breakdown by signal type ----
    private SignalTypeStat breakoutStats;
    private SignalTypeStat absorptionStats;
    private SignalTypeStat climaxStats;

    // ---- Per-trade detail ----
    private List<IndexFuturesVolumeTradeRecord> trades;

    @Data
    @Builder
    public static class SignalTypeStat {
        private IndexFuturesVolumeTradeRecord.SignalType type;
        private int    total;
        private int    wins;
        private int    losses;
        private double winRate;
        private double totalPnlR;
        private double avgPnlR;
    }
}
