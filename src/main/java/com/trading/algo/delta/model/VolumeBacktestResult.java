package com.trading.algo.delta.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Aggregated results for the volume-spike backtest.
 */
@Data
@Builder
public class VolumeBacktestResult {

    // ---- Run parameters ----
    private String    symbol;
    private LocalDate fromDate;
    private LocalDate toDate;
    private double    spikeMultiplier;
    private double    climaxMultiplier;
    private double    riskPercent;
    private double    breakoutRR;
    private double    absorptionRR;
    private double    climaxRR;

    // ---- Overall stats ----
    private int totalTrades;
    private int wins;
    private int losses;
    private int openTrades;

    private BigDecimal winRate;
    private BigDecimal totalPnlR;
    private BigDecimal avgPnlR;
    private BigDecimal profitFactor;
    private BigDecimal maxDrawdownR;
    private BigDecimal avgWinR;
    private BigDecimal avgLossR;
    private BigDecimal largestWinR;
    private BigDecimal largestLossR;
    private int        maxConsecWins;
    private int        maxConsecLosses;

    // ---- Breakdown by signal type ----
    private SignalTypeStat breakoutStats;
    private SignalTypeStat absorptionStats;
    private SignalTypeStat climaxStats;

    // ---- Per-trade detail ----
    private List<VolumeTradeRecord> trades;

    @Data
    @Builder
    public static class SignalTypeStat {
        private VolumeSignal.Type type;
        private int    total;
        private int    wins;
        private int    losses;
        private BigDecimal winRate;
        private BigDecimal totalPnlR;
        private BigDecimal avgPnlR;
    }
}
