package com.trading.algo.delta.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Single trade lifecycle for the volume-spike backtest.
 */
@Data
@Builder
public class VolumeTradeRecord {

    public enum Direction  { LONG, SHORT }
    public enum ExitReason { FULL_TARGET, STOP_LOSS, OPEN }

    // ---- Signal ----
    private String        symbol;
    private Direction     direction;
    private VolumeSignal.Type signalType;     // BREAKOUT | ABSORPTION | CLIMAX

    private Instant       signalCandleTime;
    private BigDecimal    volumeRatio;        // currentVolume / avgVolume

    // ---- Levels ----
    private BigDecimal    entry;
    private BigDecimal    stopLoss;           // spike candle low (LONG) or high (SHORT) ± margin
    private BigDecimal    target;

    private BigDecimal    risk;               // |entry - stopLoss|
    private BigDecimal    rewardPoints;       // |target - entry|

    // ---- Execution ----
    private Instant       exitTime;
    private ExitReason    exitReason;
    private BigDecimal    exitPrice;

    /** P&L in R-multiples. Win = +RR, Loss = -1 */
    private BigDecimal    pnlR;

    /** Whether the signal candle was near a swing S/R level */
    private boolean       nearSrLevel;

    /** The actual S/R price the signal was near (null if not near any level) */
    private BigDecimal    srLevel;

    /** SUPPORT or RESISTANCE — which side the level was on */
    private String        srLevelType;
}
