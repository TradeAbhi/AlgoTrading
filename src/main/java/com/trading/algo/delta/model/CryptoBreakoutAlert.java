package com.trading.algo.delta.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
/**
 * Represents a breakout alert for crypto consolidation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CryptoBreakoutAlert {
    
    public enum Direction {
        BULLISH_BREAKOUT,
        BEARISH_BREAKDOWN
    }
    
    public enum Timeframe {
        MINUTES_15,
        DAILY
    }
    
    private String symbol;
    private Direction direction;
    private Timeframe timeframe;
    private BigDecimal breakoutPrice;
    private BigDecimal zoneHigh;
    private BigDecimal zoneLow;
    private BigDecimal zoneWidthPct;
    private Instant alertTime;
    private LocalDate zoneStartDate;
    private LocalDate zoneEndDate;
    private int consolidationDays;
}
