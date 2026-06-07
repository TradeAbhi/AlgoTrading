package com.trading.algo.dtos;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WatchlistItem {

    private String symbol;
    private String exchange;          // NSE / BSE
    private String instrumentToken;

    // Price
    private double ltp;               // Last Traded Price
    private double open;
    private double high;
    private double low;
    private double close;             // Previous close
    private double change;            // Absolute change
    private double changePercent;     // % change

    // Volume & Value
    private long volume;
    private long averageVolume;       // 20-day avg volume
    private double volumeRatio;       // volume / averageVolume
    private double tradedValue;       // in crores

    // Options / OI (for derivatives)
    private long openInterest;
    private long prevOpenInterest;
    private double oiChange;
    private double oiChangePercent;

    // Order book signals
    private long totalBuyQty;
    private long totalSellQty;
    private double buySelRatio;       // totalBuyQty / totalSellQty

    // Category tags (a stock can belong to multiple lists)
    private WatchlistCategory category;

    private LocalDateTime capturedAt;
}