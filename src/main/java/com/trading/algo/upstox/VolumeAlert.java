package com.trading.algo.upstox;

import lombok.Builder;
import lombok.Data;

/**
 * Represents a volume spike alert for a portfolio holding.
 * Supports multiple timeframes: daily, weekly, and monthly.
 */
@Data
@Builder
public class VolumeAlert {
    private String symbol;
    private String companyName;
    private double closePrice;
    private double openPrice;
    private long volume;
    private double avgVolume;
    private double volumeRatio;
    private double dayChange;
    private double dayChangePercentage;
    private int quantity;
    private double pnl;
    
    // Timeframe-specific fields
    private String timeframe; // DAILY, WEEKLY, MONTHLY
    private long currentVolume;
    private long avgVolumeTimeframe;
    private double volumeRatioTimeframe;
    private int lookbackPeriod; // Number of candles used for average
    private String broker; // UPSTOX, ZERODHA, DHAN
}
