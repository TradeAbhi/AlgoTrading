package com.trading.algo.broker;

import com.trading.algo.upstox.PortfolioHolding;

import java.util.List;

/**
 * Interface for broker-specific portfolio services.
 * Each broker implementation (Upstox, Zerodha, Dhan) must implement this interface.
 */
public interface BrokerPortfolioService {
    
    /**
     * Fetches portfolio holdings from the broker.
     * 
     * @return list of portfolio holdings
     */
    List<PortfolioHolding> fetchHoldings();
    
    /**
     * Returns the broker name for identification.
     * 
     * @return broker name (e.g., "UPSTOX", "ZERODHA", "DHAN")
     */
    String getBrokerName();
    
    /**
     * Checks if the broker service is enabled/configured.
     * 
     * @return true if enabled, false otherwise
     */
    boolean isEnabled();
}
