package com.stockanalyzer.model;

import java.time.LocalDateTime;

public class Candle {

    private LocalDateTime timestamp;
    private double open;
    private double high;
    private double low;
    private double close;
    private long volume;

    public Candle() {}

    public Candle(LocalDateTime timestamp, double open, double high, double low, double close, long volume) {
        this.timestamp = timestamp;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
    }

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
    public double getOpen() { return open; }
    public void setOpen(double open) { this.open = open; }
    public double getHigh() { return high; }
    public void setHigh(double high) { this.high = high; }
    public double getLow() { return low; }
    public void setLow(double low) { this.low = low; }
    public double getClose() { return close; }
    public void setClose(double close) { this.close = close; }
    public long getVolume() { return volume; }
    public void setVolume(long volume) { this.volume = volume; }

    public boolean isBullish() { return close >= open; }
    public double range() { return high - low; }

    @Override
    public String toString() {
        return "Candle{" + timestamp + ", O=" + open + ", H=" + high + ", L=" + low + ", C=" + close + ", V=" + volume + "}";
    }
}
