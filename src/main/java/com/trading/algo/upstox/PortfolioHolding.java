package com.trading.algo.upstox;

import lombok.Builder;
import lombok.Data;

/**
 * Represents a single holding from Upstox portfolio.
 */
@Data
@Builder
public class PortfolioHolding {
    private String tradingSymbol;
    private String instrumentToken;
    private String exchange;
    private int quantity;
    private double lastPrice;
    private double closePrice;
    private double pnl;
    private double dayChange;
    private double dayChangePercentage;
    private double averagePrice;
    private String companyName;
    private String isin;
}
