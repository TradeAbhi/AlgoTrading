package com.trading.algo.delta.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import lombok.Builder;
import lombok.Data;

/**
 * Aggregated backtest results returned to the caller.
 */
@Data
@Builder
public class BacktestResult {

    // ---- Run parameters ----
    private String    symbol;
    private LocalDate fromDate;
    private LocalDate toDate;
    private double    slMarginPct;
    private double    riskRewardRatio;
    private double    partialExitRR;
    private double    partialExitQtyPct;

    // ---- Summary stats ----
    private int totalTrades;
    private int totalLong;
    private int totalShort;
    private int wins;           // trades that hit full target
    private int partialWins;    // trades: partial hit + then SL (net positive)
    private int losses;         // pure SL hits (no partial)
    private int openTrades;     // candle history ran out before exit

    private BigDecimal winRate;           // wins / (wins+losses+partialWins) %
    private BigDecimal totalPnlR;         // sum of pnlR across all trades
    private BigDecimal avgPnlR;           // mean R per trade
    private BigDecimal maxDrawdownR;      // largest consecutive R drawdown
    private BigDecimal profitFactor;      // gross profit R / gross loss R
    private BigDecimal avgWinR;
    private BigDecimal avgLossR;
    private BigDecimal largestWinR;
    private BigDecimal largestLossR;
    private int        maxConsecWins;
    private int        maxConsecLosses;

    // ---- Per-trade detail ----
    private List<TradeRecord> trades;
}