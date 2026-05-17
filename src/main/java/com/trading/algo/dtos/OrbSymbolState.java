package com.trading.algo.dtos;

/**
 * Per-symbol ORB state tracked across scan cycles.
 *
 * BUY side:
 *   rollingHigh  — starts at 9:15 candle high.
 *                  Advances to candle high if price pierces but fails to close above.
 *                  Stays frozen otherwise.
 *   rollingLow   — starts at 9:15 candle low. FROZEN throughout BUY watch.
 *                  Shifts to prevCandleLow only at the moment BUY triggers.
 *
 * SELL side (mirror):
 *   rollingLow   — starts at 9:15 candle low.
 *                  Advances to candle low if price pierces but fails to close below.
 *                  Stays frozen otherwise.
 *   rollingHigh  — starts at 9:15 candle high. FROZEN throughout SELL watch.
 *                  Shifts to prevCandleHigh only at the moment SELL triggers.
 *
 * prevCandleHigh / prevCandleLow:
 *   Updated at the end of every scan cycle to the current candle's high/low.
 *   Used to set the invalidation level (stop reference) when a signal triggers.
 *   Initialized to the 9:15 candle high/low at seed time.
 */
public class OrbSymbolState {

    private String symbol;
    private String instrumentKey;

    // ── BUY side ─────────────────────────────────────────────────────────────
    private double rollingHigh;
    private boolean buyAlerted;

    // ── SELL side ─────────────────────────────────────────────────────────────
    private double rollingLow;
    private boolean sellAlerted;

    // ── Shared — previous candle reference ───────────────────────────────────
    /** High of the candle from the previous scan cycle. */
    private double prevCandleHigh;
    /** Low of the candle from the previous scan cycle. */
    private double prevCandleLow;
    /** Volume of the 9:15 opening candle — used as baseline for volume filter. */
    private long openingCandleVolume;
    /** Open price of the 9:15 candle — used to compute % move from open at alert time. */
    private double openPrice;
    /** Previous day's close — used to compute gap % and show context in alert. */
    private double prevDayClose;

    private OrbSymbolState() {}

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final OrbSymbolState s = new OrbSymbolState();

        public Builder symbol(String symbol)               { s.symbol = symbol; return this; }
        public Builder instrumentKey(String instrumentKey) { s.instrumentKey = instrumentKey; return this; }
        public Builder rollingHigh(double rollingHigh)     { s.rollingHigh = rollingHigh; return this; }
        public Builder rollingLow(double rollingLow)       { s.rollingLow = rollingLow; return this; }
        public Builder buyAlerted(boolean buyAlerted)      { s.buyAlerted = buyAlerted; return this; }
        public Builder sellAlerted(boolean sellAlerted)    { s.sellAlerted = sellAlerted; return this; }
        public Builder prevCandleHigh(double v)            { s.prevCandleHigh = v; return this; }
        public Builder prevCandleLow(double v)             { s.prevCandleLow = v; return this; }
        public Builder openingCandleVolume(long v)         { s.openingCandleVolume = v; return this; }
        public Builder openPrice(double v)                 { s.openPrice = v; return this; }
        public Builder prevDayClose(double v)              { s.prevDayClose = v; return this; }
        public OrbSymbolState build()                      { return s; }
    }

    public String getSymbol()                        { return symbol; }
    public void   setSymbol(String symbol)           { this.symbol = symbol; }

    public String getInstrumentKey()                          { return instrumentKey; }
    public void   setInstrumentKey(String instrumentKey)      { this.instrumentKey = instrumentKey; }

    public double getRollingHigh()                   { return rollingHigh; }
    public void   setRollingHigh(double rollingHigh) { this.rollingHigh = rollingHigh; }

    public double getRollingLow()                    { return rollingLow; }
    public void   setRollingLow(double rollingLow)   { this.rollingLow = rollingLow; }

    public boolean isBuyAlerted()                    { return buyAlerted; }
    public void    setBuyAlerted(boolean buyAlerted) { this.buyAlerted = buyAlerted; }

    public boolean isSellAlerted()                      { return sellAlerted; }
    public void    setSellAlerted(boolean sellAlerted)  { this.sellAlerted = sellAlerted; }

    public double getPrevCandleHigh()                { return prevCandleHigh; }
    public void   setPrevCandleHigh(double v)        { this.prevCandleHigh = v; }

    public double getPrevCandleLow()                 { return prevCandleLow; }
    public void   setPrevCandleLow(double v)         { this.prevCandleLow = v; }

    public long getOpeningCandleVolume()             { return openingCandleVolume; }
    public void setOpeningCandleVolume(long v)       { this.openingCandleVolume = v; }

    public double getOpenPrice()                     { return openPrice; }
    public void   setOpenPrice(double v)             { this.openPrice = v; }

    public double getPrevDayClose()                  { return prevDayClose; }
    public void   setPrevDayClose(double v)          { this.prevDayClose = v; }
}