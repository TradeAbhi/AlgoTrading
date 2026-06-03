package com.trading.algo.entity;


import java.time.LocalDate;

/**
 * OHLCV candle for a US stock (NYSE/NASDAQ).
 * Date is in America/New_York timezone.
 */
public class UsCandle {

    private final String    ticker;
    private final LocalDate date;
    private final double    open;
    private final double    high;
    private final double    low;
    private final double    close;
    private final long      volume;

    public UsCandle(String ticker, LocalDate date,
                    double open, double high, double low,
                    double close, long volume) {
        this.ticker = ticker;
        this.date   = date;
        this.open   = open;
        this.high   = high;
        this.low    = low;
        this.close  = close;
        this.volume = volume;
    }

    public String    getTicker() { return ticker; }
    public LocalDate getDate()   { return date;   }
    public double    getOpen()   { return open;   }
    public double    getHigh()   { return high;   }
    public double    getLow()    { return low;    }
    public double    getClose()  { return close;  }
    public long      getVolume() { return volume; }

    @Override
    public String toString() {
        return String.format("UsCandle{%s %s O=%.2f H=%.2f L=%.2f C=%.2f V=%,d}",
                ticker, date, open, high, low, close, volume);
    }
}