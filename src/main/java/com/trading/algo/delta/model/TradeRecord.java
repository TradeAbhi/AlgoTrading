	package com.trading.algo.delta.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Represents the full lifecycle of a single backtest trade.
 */
@Data
@Builder
public class TradeRecord {

    public enum Direction   { SHORT, LONG }
    public enum ExitReason  { FULL_TARGET, PARTIAL_THEN_TARGET, PARTIAL_THEN_SL, STOP_LOSS, OPEN }

    // ---- Signal ----
    private String    symbol;
    private Direction direction;

    /** The 15-min breakdown/breakout candle that triggered entry */
    private Instant   signalCandleTime;

    /** 2nd candle prior to the signal candle */
    private Instant   refCandleTime;

    // ---- Levels ----
    /** Entry = close of signal candle */
    private BigDecimal entry;

    /**
     * Raw SL = high of 2nd-prior candle (SHORT) or low of 2nd-prior candle (LONG).
     * Adjusted SL adds the margin buffer.
     */
    private BigDecimal rawSl;
    private BigDecimal adjustedSl;   // rawSl × (1 + margin%) for SHORT, × (1 - margin%) for LONG

    private BigDecimal risk;          // |entry - adjustedSl|

    /** Partial exit level at 1:partialExitRR */
    private BigDecimal partialTarget;

    /** Full target at 1:riskRewardRatio */
    private BigDecimal fullTarget;

    // ---- Execution ----
    private Instant    entryTime;
    private Instant    exitTime;
    private ExitReason exitReason;

    /**
     * Weighted average exit price across partial + final exit.
     * For a losing trade this is the SL price.
     */
    private BigDecimal avgExitPrice;

    /**
     * P&L expressed in R-multiples (1R = 1 × risk).
     * Win at full target = +3R; loss at SL = -1R; partial win+SL = some fraction.
     */
    private BigDecimal pnlR;

    /** Whether a partial exit was actually triggered */
    private boolean partialExitTriggered;

    /** Price at which partial was exited */
    private BigDecimal partialExitPrice;

    /** SL after trailing to entry (breakeven) at 1:2 */
    private BigDecimal trailedSl;
}