package com.trading.algo.delta.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Holds the previous trading day's high and low for a symbol.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DayLevel {

    private String symbol;
    private LocalDate date;          // The day these levels belong to
    private BigDecimal previousDayHigh;
    private BigDecimal previousDayLow;
}
