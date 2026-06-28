package com.trading.algo.delta.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Holds the static reference high/low for the daily breakout strategy.
 * 
 * The reference high/low only updates when a daily candle closes above the current参考 high.
 * Otherwise, the same levels persist across multiple days.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyBreakoutLevel {

    private String symbol;
    private LocalDate referenceDate;      // The date when these reference levels were set
    private BigDecimal referenceHigh;     // Static high level - only updates on daily close above this
    private BigDecimal referenceLow;      // Static low level from the same candle
    private LocalDate lastCheckDate;      // Last date when we checked for daily close above reference
}
