package com.trading.algo.broker;

import com.trading.algo.upstox.PortfolioHolding;
import com.trading.algo.upstox.UpstoxPortfolioService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Upstox implementation of BrokerPortfolioService.
 * Delegates to the existing UpstoxPortfolioService.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UpstoxBrokerService implements BrokerPortfolioService {
    
    private final UpstoxPortfolioService upstoxPortfolioService;
    
    @Value("${upstox.enabled:true}")
    private boolean enabled;
    
    @Override
    public List<PortfolioHolding> fetchHoldings() {
        if (!isEnabled()) {
            log.warn("Upstox broker service is disabled");
            return List.of();
        }
        return upstoxPortfolioService.fetchHoldings();
    }
    
    @Override
    public String getBrokerName() {
        return "UPSTOX";
    }
    
    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
