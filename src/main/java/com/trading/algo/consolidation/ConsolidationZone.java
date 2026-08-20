package com.trading.algo.consolidation;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A validated consolidation zone for one window length.
 *
 * zoneHigh = the window's max high (resistance), which must be touched
 *            (within tolerance) by at least MIN_TOUCHES highs.
 * zoneLow  = the window's min low (support), which must be touched
 *            (within tolerance) by at least MIN_TOUCHES lows.
 */
public record ConsolidationZone(
        int windowDays,
        LocalDate windowStart,
        LocalDate windowEnd,
        BigDecimal zoneHigh,
        BigDecimal zoneLow,
        int resistanceTouches,
        int supportTouches,
        BigDecimal widthPct
) {
}
