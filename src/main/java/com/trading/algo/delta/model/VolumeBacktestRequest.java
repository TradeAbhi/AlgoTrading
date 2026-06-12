package com.trading.algo.delta.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Parameters for a volume-based backtest run.
 *
 * Example JSON:
 * {
 *   "symbol"            : "BTCUSD",
 *   "fromDate"          : "2024-01-01",
 *   "toDate"            : "2024-06-30",
 *   "spikeMultiplier"   : 2.0,
 *   "climaxMultiplier"  : 3.0,
 *   "riskPercent"       : 1.0,
 *   "breakoutRR"        : 3.0,
 *   "absorptionRR"      : 2.0,
 *   "climaxRR"          : 2.0,
 *   "slMarginPct"       : 0.15
 * }
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VolumeBacktestRequest {

    private String    symbol;
    private LocalDate fromDate;
    private LocalDate toDate;

    /** Volume >= spikeMultiplier × avg(20) to qualify as a spike */
    @Builder.Default
    private double spikeMultiplier = 2.0;

    /** Volume >= climaxMultiplier × avg(20) + trending → CLIMAX */
    @Builder.Default
    private double climaxMultiplier = 3.0;

    /**
     * Risk per trade as % of entry price.
     * SL distance = entry × (riskPercent / 100)
     */
    @Builder.Default
    private double riskPercent = 1.0;

    /** Target R:R for BREAKOUT trades (high volume + big body level break) */
    @Builder.Default
    private double breakoutRR = 3.0;

    /**
     * Target R:R for ABSORPTION trades (high volume + small body → reversal).
     * Slightly conservative since it's a counter-move waiting for confirmation.
     */
    @Builder.Default
    private double absorptionRR = 2.0;

    /**
     * Target R:R for CLIMAX trades (fade the exhaustion move).
     * Conservative because we're trading against the trend.
     */
    @Builder.Default
    private double climaxRR = 2.0;

    /**
     * Extra SL buffer as % of the SL price.
     * Prevents being stopped out by noise at the candle extreme.
     */
    @Builder.Default
    private double slMarginPct = 0.15;

    /**
     * How many candles to look back when computing swing highs/lows for S/R levels.
     * Default: 50 candles (~12.5 hours of 15m data).
     */
    @Builder.Default
    private int srLookback = 50;

    /**
     * Number of candles on EACH SIDE of a pivot that must be lower/higher
     * for a candle to qualify as a swing high/low.
     * Default: 3 (standard TradingView pivot setting).
     */
    @Builder.Default
    private int srPivotStrength = 3;

    /**
     * Signal candle close must be within this % of a swing high/low
     * to be considered "near a level".
     * Default: 0.5% — tight enough to be meaningful, loose enough to fire trades.
     */
    @Builder.Default
    private double srProximityPct = 0.5;

    /**
     * When true, only trades whose signal candle is near a S/R level are taken.
     * When false (default), S/R proximity is recorded but does NOT filter trades
     * — useful for comparing with-filter vs without-filter in the same run.
     */
    @Builder.Default
    private boolean srFilterEnabled = true;
}
