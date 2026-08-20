package com.trading.algo.consolidation;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Single daily OHLCV candle. Reuse your existing daily-candle model if you
 * already have one (e.g. from the Upstox integration) — this is here so the
 * consolidation module is drop-in self-contained.
 */
public record DailyCandle(
        LocalDate date,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        long volume
) {
}
