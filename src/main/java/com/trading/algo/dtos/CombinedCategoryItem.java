package com.trading.algo.dtos;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CombinedCategoryItem {

    private String symbol;
    private String exchange;
    private String instrumentToken;

    // Price
    private double ltp;
    private double changePercent;

    // Volume & Value
    private long volume;
    private double volumeRatio;
    private double tradedValue;

    // Options / OI
    private long openInterest;

    // Order book signals
    private long totalBuyQty;
    private long totalSellQty;
    private double buySelRatio;

    // List of categories this stock belongs to
    private List<WatchlistCategory> categories;
}
