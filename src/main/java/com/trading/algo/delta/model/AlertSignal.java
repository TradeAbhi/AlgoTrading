package com.trading.algo.delta.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Represents a triggered alert signal.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertSignal {

    public enum Direction {
        BEARISH_BREAKDOWN,   // 15m candle closed BELOW previous day low  → Sell signal
        BULLISH_BREAKOUT     // 15m candle closed ABOVE previous day high → Buy signal
    }

    private String symbol;
    private Direction direction;
    private BigDecimal candleClose;
    private BigDecimal level;         // The PDL or PDH that was breached
    private Instant candleCloseTime;
}
