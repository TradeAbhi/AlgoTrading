package com.trading.algo.controller;

import com.trading.algo.broker.DhanBrokerService;
import com.trading.algo.broker.DhanTokenService;
import com.trading.algo.broker.UnifiedPortfolioService;
import com.trading.algo.upstox.PortfolioHolding;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/dhan")
@RequiredArgsConstructor
public class DhanController {

    private final DhanBrokerService dhanBrokerService;
    private final DhanTokenService dhanTokenService;
    private final UnifiedPortfolioService unifiedPortfolioService;

    /**
     * Step 1: Visit this URL in browser to start OAuth flow.
     * Redirects to Dhan login page.
     */
    @GetMapping("/login")
    public RedirectView login() {
        String loginUrl = dhanTokenService.buildLoginUrl();
        log.info("Redirecting to Dhan login: {}", loginUrl);
        return new RedirectView(loginUrl);
    }

    @GetMapping("/holdings")
    public ResponseEntity<List<PortfolioHolding>> getHoldings() {
        log.info("GET /api/dhan/holdings");
        List<PortfolioHolding> holdings = dhanBrokerService.fetchHoldings();
        return ResponseEntity.ok(holdings);
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        log.info("GET /api/dhan/status");
        return ResponseEntity.ok(Map.of(
            "brokerName", dhanBrokerService.getBrokerName(),
            "enabled", dhanBrokerService.isEnabled()
        ));
    }

    @GetMapping("/unified-holdings")
    public ResponseEntity<List<PortfolioHolding>> getUnifiedHoldings() {
        log.info("GET /api/dhan/unified-holdings");
        List<PortfolioHolding> holdings = unifiedPortfolioService.fetchHoldingsByBroker("DHAN");
        return ResponseEntity.ok(holdings);
    }

    /**
     * Step 2: Dhan redirects here after successful login.
     * Automatically exchanges the auth code for an access token.
     */
    @GetMapping("/callback")
    public String callback(@RequestParam String code) {
        log.info("GET /api/dhan/callback - Received auth code from Dhan");
        String result = dhanTokenService.setCodeAndFetchToken(code);
        return result;
    }
}
