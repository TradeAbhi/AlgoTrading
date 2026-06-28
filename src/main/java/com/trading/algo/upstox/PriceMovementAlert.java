package com.trading.algo.upstox;

import lombok.Builder;
import lombok.Data;

/**
 * Represents a price movement alert for a portfolio holding.
 * Alerts when price changes by a significant percentage from previous period.
 */
@Data
@Builder
public class PriceMovementAlert {
    private String symbol;
    private String companyName;
    private double currentClose;
    private double previousClose;
    private double priceChange;
    private double priceChangePercentage;
    private String timeframe; // DAILY, WEEKLY, MONTHLY
    private int quantity;
    private double pnl;
    private String broker; // UPSTOX, ZERODHA, DHAN
}
