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
}
