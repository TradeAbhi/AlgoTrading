package com.trading.algo.dtos;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Represents a single 15-minute OHLC candle from Upstox historical API.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Candle {

    private LocalDateTime timestamp;
    private double open;
    private double high;
    private double low;
    private double close;
    private long   volume;

    // -------------------------------------------------------------------------
    // Derived helpers used by the strategy
    // -------------------------------------------------------------------------

    public double body()       { return Math.abs(close - open); }
    public double range()      { return high - low; }
    public boolean isBullish() { return close > open; }
    public boolean isBearish() { return close < open; }

    /**
     * Wick ratio = body / range.
     * 1.0 = no wicks at all (pure marubozu).
     * 0.75+ = strong candle with small wicks.
     */
    public double wickRatio() {
        return range() == 0 ? 0 : body() / range();
    }

    /** 50% level of this candle's body (midpoint between open and close). */
    public double fiftyPercent() {
        return (open + close) / 2.0;
    }
}
