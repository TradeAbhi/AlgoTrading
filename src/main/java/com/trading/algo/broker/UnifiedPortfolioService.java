package com.trading.algo.broker;

import com.trading.algo.upstox.PortfolioHolding;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Unified portfolio service that aggregates holdings from all enabled brokers.
 * Provides a single entry point for fetching portfolio data across multiple brokers.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UnifiedPortfolioService {
    
    private final List<BrokerPortfolioService> brokerServices;
    
    /**
     * Fetches holdings from all enabled brokers and aggregates them.
     * 
     * @return list of all portfolio holdings from all enabled brokers
     */
    public List<PortfolioHolding> fetchAllHoldings() {
        List<PortfolioHolding> allHoldings = new ArrayList<>();
        
        for (BrokerPortfolioService broker : brokerServices) {
            if (broker.isEnabled()) {
                try {
                    log.info("Fetching holdings from {}", broker.getBrokerName());
                    List<PortfolioHolding> holdings = broker.fetchHoldings();
                    allHoldings.addAll(holdings);
                    log.info("Fetched {} holdings from {}", holdings.size(), broker.getBrokerName());
                } catch (Exception e) {
                    log.error("Failed to fetch holdings from {}: {}", 
                        broker.getBrokerName(), e.getMessage(), e);
                }
            } else {
                log.debug("{} broker service is disabled, skipping", broker.getBrokerName());
            }
        }
        
        log.info("Total holdings fetched from all brokers: {}", allHoldings.size());
        return allHoldings;
    }
    
    /**
     * Fetches holdings from a specific broker by name.
     * 
     * @param brokerName the broker name (e.g., "UPSTOX", "ZERODHA", "DHAN")
     * @return list of holdings from the specified broker
     */
    public List<PortfolioHolding> fetchHoldingsByBroker(String brokerName) {
        return brokerServices.stream()
            .filter(broker -> broker.getBrokerName().equalsIgnoreCase(brokerName))
            .filter(BrokerPortfolioService::isEnabled)
            .findFirst()
            .map(broker -> {
                try {
                    return broker.fetchHoldings();
                } catch (Exception e) {
                    log.error("Failed to fetch holdings from {}: {}", brokerName, e.getMessage(), e);
                    return List.<PortfolioHolding>of();
                }
            })
            .orElseGet(() -> {
                log.warn("No enabled broker found with name: {}", brokerName);
                return List.<PortfolioHolding>of();
            });
    }
    
    /**
     * Returns a list of all enabled broker names.
     * 
     * @return list of enabled broker names
     */
    public List<String> getEnabledBrokers() {
        return brokerServices.stream()
            .filter(BrokerPortfolioService::isEnabled)
            .map(BrokerPortfolioService::getBrokerName)
            .collect(Collectors.toList());
    }
}
