package com.trading.algo.delta.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Represents a consolidation zone for crypto assets.
 * Tracks the high and low of an 8-day consolidation period.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CryptoConsolidationZone {
    
    private String symbol;
    private LocalDate zoneStartDate;
    private LocalDate zoneEndDate;
    private BigDecimal zoneHigh;
    private BigDecimal zoneLow;
    private BigDecimal zoneWidth;
    private BigDecimal zoneWidthPct;
    private int consolidationDays;
    
    /**
     * Calculates the midpoint of the consolidation zone.
     */
    public BigDecimal getMidpoint() {
        if (zoneHigh == null || zoneLow == null) {
            return BigDecimal.ZERO;
        }
        return zoneHigh.add(zoneLow).divide(BigDecimal.valueOf(2), 2, java.math.RoundingMode.HALF_UP);
    }
    
    /**
     * Checks if a given price breaks out above the zone high.
     */
    public boolean isBullishBreakout(BigDecimal price) {
        return price != null && zoneHigh != null && price.compareTo(zoneHigh) > 0;
    }
    
    /**
     * Checks if a given price breaks down below the zone low.
     */
    public boolean isBearishBreakdown(BigDecimal price) {
        return price != null && zoneLow != null && price.compareTo(zoneLow) < 0;
    }
}
