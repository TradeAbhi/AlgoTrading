package com.trading.algo.delta.model;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Represents a triggered alert for the daily breakout strategy.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyBreakoutAlert {

    public enum Direction {
        BULLISH_BREAKOUT,     // 15m candle closed ABOVE reference high → Buy signal
        BEARISH_BREAKDOWN    // 15m candle closed BELOW reference low → Sell signal
    }

    private String symbol;
    private Direction direction;
    private BigDecimal candleClose;
    private BigDecimal referenceLevel;   // The static reference high that was breached
    private Instant candleCloseTime;
    private LocalDate referenceDate;    // Date when the reference levels were set
}
