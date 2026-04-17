package com.trading.algo.config;


import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Data;

@Data
@Configuration
@ConfigurationProperties(prefix = "watchlist")
public class WatchlistConfig {

    // How many items to return per category
    private int topGainersLimit       = 20;
    private int topLosersLimit        = 20;
    private int activeByValueLimit    = 20;
    private int highOiLimit           = 20;
    private int volumeShockerLimit    = 20;
    private int onlyBuyersLimit       = 20;
    private int onlySellersLimit      = 20;

    // --- Volume shocker: stock is a shocker if volumeRatio >= this threshold ---
    private double volumeShockerThreshold = 3.0;   // 3x of avg volume

    // --- Only Buyers: totalBuyQty / totalSellQty >= this ratio ---
    private double onlyBuyersRatioThreshold  = 5.0;

    // --- Only Sellers: totalSellQty / totalBuyQty >= this ratio ---
    private double onlySellersRatioThreshold = 5.0;

    // --- Minimum traded value (crores) to filter illiquid stocks ---
    private double minTradedValueCrores = 5.0;

    // --- Minimum OI to appear in HIGH_OI list ---
    private long minOpenInterest = 100_000L;

    // Cache TTL in seconds (refresh watchlist every N seconds during market hours)
    private int cacheTtlSeconds = 60;

    // Segment to scan: NSE_EQ / NSE_FO
    private String segment = "NSE_EQ";

    // Instrument keys to scan (loaded from a universe file or hardcoded)
    // If empty, the service will fetch from Upstox instruments master
    private List<String> universe;
}