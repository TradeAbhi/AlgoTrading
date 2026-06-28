package com.trading.algo.upstox;

import com.trading.algo.broker.UnifiedPortfolioService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Portfolio REST controller.
 *
 * GET  /api/portfolio/holdings           → get all portfolio holdings from all brokers
 * GET  /api/portfolio/holdings/{broker}  → get holdings from specific broker
 * POST /api/portfolio/volume-scan        → trigger volume spike scan manually
 */
@Slf4j
@RestController
@RequestMapping("/api/portfolio")
@RequiredArgsConstructor
public class PortfolioController {

    private final UnifiedPortfolioService unifiedPortfolioService;
    private final PortfolioVolumeScanService portfolioVolumeScanService;

    /**
     * GET /api/portfolio/holdings
     * Fetches all portfolio holdings from all enabled brokers.
     *
     * curl http://localhost:8080/api/portfolio/holdings
     */
    @GetMapping("/holdings")
    public ResponseEntity<List<PortfolioHolding>> getHoldings() {
        log.info("GET /api/portfolio/holdings");
        List<PortfolioHolding> holdings = unifiedPortfolioService.fetchAllHoldings();
        return ResponseEntity.ok(holdings);
    }

    /**
     * GET /api/portfolio/holdings/{broker}
     * Fetches portfolio holdings from a specific broker.
     *
     * curl http://localhost:8080/api/portfolio/holdings/UPSTOX
     * curl http://localhost:8080/api/portfolio/holdings/ZERODHA
     * curl http://localhost:8080/api/portfolio/holdings/DHAN
     */
    @GetMapping("/holdings/{broker}")
    public ResponseEntity<List<PortfolioHolding>> getHoldingsByBroker(@PathVariable String broker) {
        log.info("GET /api/portfolio/holdings/{}", broker);
        List<PortfolioHolding> holdings = unifiedPortfolioService.fetchHoldingsByBroker(broker);
        return ResponseEntity.ok(holdings);
    }

    /**
     * POST /api/portfolio/volume-scan
     * Manually triggers the portfolio volume spike scan.
     * Useful for testing or running outside the scheduled time.
     *
     * curl -X POST http://localhost:8080/api/portfolio/volume-scan
     */
    @PostMapping("/volume-scan")
    public ResponseEntity<Map<String, String>> triggerVolumeScan() {
        log.info("POST /api/portfolio/volume-scan");
        try {
            portfolioVolumeScanService.scanPortfolioForVolumeSpike();
            return ResponseEntity.ok(Map.of("status", "Portfolio volume scan complete"));
        } catch (Exception e) {
            log.error("Volume scan failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }
}
