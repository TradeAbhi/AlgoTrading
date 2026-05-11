package com.trading.algo.dtos;

import lombok.Builder;
import lombok.Data;

/**
 * Tracks the per-symbol ORB state across scanning cycles.
 *
 * BUY side:  rollingHigh starts at the 9:15 candle high and is
 *            updated each cycle if the symbol hasn't yet triggered.
 *            Once a candle closes above rollingHigh → alert fired, buyAlerted = true.
 *
 * SELL side: rollingLow starts at the 9:15 candle low and is
 *            updated each cycle if the symbol hasn't yet triggered.
 *            Once a candle closes below rollingLow → alert fired, sellAlerted = true.
 */
@Data
@Builder
public class OrbSymbolState {

    private String symbol;
    private String instrumentKey;

    // ── BUY side ───────────────────────────────────────────────
    /** High of the first (9:15) candle, then max of all subsequent candles until buy triggers. */
    private double rollingHigh;
    /** Set true once a candle close confirms the breakout above rollingHigh. */
    private boolean buyAlerted;

    // ── SELL side ──────────────────────────────────────────────
    /** Low of the first (9:15) candle, then min of all subsequent candles until sell triggers. */
    private double rollingLow;
    /** Set true once a candle close confirms the breakdown below rollingLow. */
    private boolean sellAlerted;
}