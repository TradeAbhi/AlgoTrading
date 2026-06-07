package com.trading.algo.delta.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Represents an OHLCV candle from Delta Exchange.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Candle {

    private String symbol;
    private Instant openTime;
    private Instant closeTime;
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;
    private BigDecimal volume;

    /** Whether this candle is fully closed (not the current live candle). */
    private boolean closed;
}
