package com.trading.algo.weekly;


/**
 * Per-symbol state for the Weekly Breakout strategy.
 *
 * Seeded every Monday at 9:31 AM from the previous week's OHLCV candle.
 * Scanned every day Mon–Fri at 3:31 PM against the completed daily candle.
 *
 * BUY side:
 *   weeklyHigh  — starts at prev week high. Only ever moves UP (pierce-no-close advances it).
 *   weeklyLow   — FROZEN at prev week low throughout BUY watch.
 *                 Shifts to prevDailyLow at the moment BUY triggers.
 *
 * SELL side (mirror):
 *   weeklyLow   — starts at prev week low. Only ever moves DOWN.
 *   weeklyHigh  — FROZEN at prev week high throughout SELL watch.
 *                 Shifts to prevDailyHigh at the moment SELL triggers.
 *
 * prevDailyHigh / prevDailyLow:
 *   Updated at end of every daily scan cycle.
 *   Seeded from the previous week candle's high/low on Monday.
 *   Used as the SL/invalidation reference when a signal triggers.
 */
public class WeeklyBreakoutState {

    private String symbol;
    private String instrumentKey;

    // ── Range levels ──────────────────────────────────────────────────────────
    private double weeklyHigh;
    private double weeklyLow;
    private double weeklyOpen;      // prev week open — for % move context in alert
    private long   weeklyVolume;    // prev week volume — baseline for volume filter

    // ── Alert flags (once per symbol per week) ────────────────────────────────
    private boolean buyAlerted;
    private boolean sellAlerted;

    // ── Previous daily candle reference ──────────────────────────────────────
    private double prevDailyHigh;   // shifts to this on SELL trigger
    private double prevDailyLow;    // shifts to this on BUY trigger

    // ── Alert enrichment ──────────────────────────────────────────────────────
    private double prevWeekClose;   // prev week close — gap/context in alert
    private double weekStartOpen;   // Monday open — % move from week open in alert

    private WeeklyBreakoutState() {}

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final WeeklyBreakoutState s = new WeeklyBreakoutState();

        public Builder symbol(String v)          { s.symbol = v; return this; }
        public Builder instrumentKey(String v)   { s.instrumentKey = v; return this; }
        public Builder weeklyHigh(double v)      { s.weeklyHigh = v; return this; }
        public Builder weeklyLow(double v)       { s.weeklyLow = v; return this; }
        public Builder weeklyOpen(double v)      { s.weeklyOpen = v; return this; }
        public Builder weeklyVolume(long v)      { s.weeklyVolume = v; return this; }
        public Builder buyAlerted(boolean v)     { s.buyAlerted = v; return this; }
        public Builder sellAlerted(boolean v)    { s.sellAlerted = v; return this; }
        public Builder prevDailyHigh(double v)   { s.prevDailyHigh = v; return this; }
        public Builder prevDailyLow(double v)    { s.prevDailyLow = v; return this; }
        public Builder prevWeekClose(double v)   { s.prevWeekClose = v; return this; }
        public Builder weekStartOpen(double v)   { s.weekStartOpen = v; return this; }
        public WeeklyBreakoutState build()       { return s; }
    }

    public String getSymbol()                        { return symbol; }
    public void   setSymbol(String v)                { this.symbol = v; }

    public String getInstrumentKey()                 { return instrumentKey; }
    public void   setInstrumentKey(String v)         { this.instrumentKey = v; }

    public double getWeeklyHigh()                    { return weeklyHigh; }
    public void   setWeeklyHigh(double v)            { this.weeklyHigh = v; }

    public double getWeeklyLow()                     { return weeklyLow; }
    public void   setWeeklyLow(double v)             { this.weeklyLow = v; }

    public double getWeeklyOpen()                    { return weeklyOpen; }
    public void   setWeeklyOpen(double v)            { this.weeklyOpen = v; }

    public long   getWeeklyVolume()                  { return weeklyVolume; }
    public void   setWeeklyVolume(long v)            { this.weeklyVolume = v; }

    public boolean isBuyAlerted()                    { return buyAlerted; }
    public void    setBuyAlerted(boolean v)          { this.buyAlerted = v; }

    public boolean isSellAlerted()                   { return sellAlerted; }
    public void    setSellAlerted(boolean v)         { this.sellAlerted = v; }

    public double getPrevDailyHigh()                 { return prevDailyHigh; }
    public void   setPrevDailyHigh(double v)         { this.prevDailyHigh = v; }

    public double getPrevDailyLow()                  { return prevDailyLow; }
    public void   setPrevDailyLow(double v)          { this.prevDailyLow = v; }

    public double getPrevWeekClose()                 { return prevWeekClose; }
    public void   setPrevWeekClose(double v)         { this.prevWeekClose = v; }

    public double getWeekStartOpen()                 { return weekStartOpen; }
    public void   setWeekStartOpen(double v)         { this.weekStartOpen = v; }
}