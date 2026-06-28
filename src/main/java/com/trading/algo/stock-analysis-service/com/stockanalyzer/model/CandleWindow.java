package com.stockanalyzer.model;

import java.util.List;

/**
 * Holds the sliced data the analyzers operate on: the consolidation range
 * itself, the 10 candles leading into the breakout, the 10 candles after it,
 * and (if one exists) the candle within the "after" window where price
 * actually closed beyond the range - the real structural break candle,
 * which may not be the very first post-range candle if a liquidity sweep
 * happens first.
 */
public class CandleWindow {

    private List<Candle> rangeCandles;
    private List<Candle> beforeWindow;
    private List<Candle> afterWindow;
    private double rangeHigh;
    private double rangeLow;
    private Candle firstReactionCandle;
    private Candle structuralBreakCandle; // nullable - no confirmed break yet if null

    public List<Candle> getRangeCandles() { return rangeCandles; }
    public void setRangeCandles(List<Candle> rangeCandles) { this.rangeCandles = rangeCandles; }
    public List<Candle> getBeforeWindow() { return beforeWindow; }
    public void setBeforeWindow(List<Candle> beforeWindow) { this.beforeWindow = beforeWindow; }
    public List<Candle> getAfterWindow() { return afterWindow; }
    public void setAfterWindow(List<Candle> afterWindow) { this.afterWindow = afterWindow; }
    public double getRangeHigh() { return rangeHigh; }
    public void setRangeHigh(double rangeHigh) { this.rangeHigh = rangeHigh; }
    public double getRangeLow() { return rangeLow; }
    public void setRangeLow(double rangeLow) { this.rangeLow = rangeLow; }
    public Candle getFirstReactionCandle() { return firstReactionCandle; }
    public void setFirstReactionCandle(Candle c) { this.firstReactionCandle = c; }
    public Candle getStructuralBreakCandle() { return structuralBreakCandle; }
    public void setStructuralBreakCandle(Candle c) { this.structuralBreakCandle = c; }
}
