package com.trading.algo.dtos;


/**
 * Per-ticker weekly breakout state for US stocks.
 *
 * Same logic as Indian WeeklyBreakoutState:
 *   weeklyHigh only moves UP   (pierce-no-close on daily candle raises the bar)
 *   weeklyLow  only moves DOWN (pierce-no-close on daily candle drops the bar)
 *   Opposite side FROZEN until signal, then shifts to prev daily candle H/L.
 *
 * Additional US-specific fields:
 *   is52WeekHigh  — true if seeded from the Finviz 52-week high list
 *   prevWeekClose — previous week's closing price
 *   weekStartOpen — Monday's open price (% move from week open in alert)
 */
public class UsWeeklyBreakoutState {

    private String  ticker;
    private double  weeklyHigh;
    private double  weeklyLow;
    private double  weeklyOpen;
    private long    weeklyVolume;
    private boolean buyAlerted;
    private boolean sellAlerted;
    private double  prevDailyHigh;
    private double  prevDailyLow;
    private double  prevWeekClose;
    private double  weekStartOpen;
    private boolean is52WeekHigh;   // seeded from Finviz 52-week high list

    private UsWeeklyBreakoutState() {}

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final UsWeeklyBreakoutState s = new UsWeeklyBreakoutState();

        public Builder ticker(String v)          { s.ticker = v; return this; }
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
        public Builder is52WeekHigh(boolean v)   { s.is52WeekHigh = v; return this; }
        public UsWeeklyBreakoutState build()     { return s; }
    }

    public String  getTicker()                       { return ticker; }
    public void    setTicker(String v)               { this.ticker = v; }

    public double  getWeeklyHigh()                   { return weeklyHigh; }
    public void    setWeeklyHigh(double v)           { this.weeklyHigh = v; }

    public double  getWeeklyLow()                    { return weeklyLow; }
    public void    setWeeklyLow(double v)            { this.weeklyLow = v; }

    public double  getWeeklyOpen()                   { return weeklyOpen; }
    public void    setWeeklyOpen(double v)           { this.weeklyOpen = v; }

    public long    getWeeklyVolume()                 { return weeklyVolume; }
    public void    setWeeklyVolume(long v)           { this.weeklyVolume = v; }

    public boolean isBuyAlerted()                    { return buyAlerted; }
    public void    setBuyAlerted(boolean v)          { this.buyAlerted = v; }

    public boolean isSellAlerted()                   { return sellAlerted; }
    public void    setSellAlerted(boolean v)         { this.sellAlerted = v; }

    public double  getPrevDailyHigh()                { return prevDailyHigh; }
    public void    setPrevDailyHigh(double v)        { this.prevDailyHigh = v; }

    public double  getPrevDailyLow()                 { return prevDailyLow; }
    public void    setPrevDailyLow(double v)         { this.prevDailyLow = v; }

    public double  getPrevWeekClose()                { return prevWeekClose; }
    public void    setPrevWeekClose(double v)        { this.prevWeekClose = v; }

    public double  getWeekStartOpen()                { return weekStartOpen; }
    public void    setWeekStartOpen(double v)        { this.weekStartOpen = v; }

    public boolean is52WeekHigh()                    { return is52WeekHigh; }
    public void    set52WeekHigh(boolean v)          { this.is52WeekHigh = v; }
}