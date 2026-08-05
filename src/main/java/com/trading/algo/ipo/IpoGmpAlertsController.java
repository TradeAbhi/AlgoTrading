package com.trading.algo.ipo;

import com.trading.algo.service.IpoAlertsInGmpService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST endpoint for GMP alerts from ipoalerts.in.
 *
 * Allows manual triggers and status checks for GMP monitoring.
 */
@Slf4j
@RestController
@RequestMapping("/api/ipo/gmp")
@RequiredArgsConstructor
public class IpoGmpAlertsController {

    private final IpoAlertsInGmpService gmpService;

    /**
     * GET /api/ipo/gmp/fetch
     * Manually trigger GMP data fetch and alert check.
     */
    @PostMapping("/fetch")
    public ResponseEntity<String> manualFetch() {
        log.info("Manual GMP fetch requested");
        gmpService.manualGmpFetch();
        return ResponseEntity.ok("{\"status\": \"GMP fetch initiated\"}");
    }

    /**
     * GET /api/ipo/gmp/status
     * Get GMP status summary of all tracked IPOs.
     */
    @GetMapping("/status")
    public ResponseEntity<String> getStatus() {
        String summary = gmpService.getGmpStatusSummary();
        return ResponseEntity.ok(summary);
    }

    /**
     * POST /api/ipo/gmp/update?symbol=SYMBOL&gmp=15.5
     * Update GMP for specific IPO (for testing or manual updates).
     */
    @PostMapping("/update")
    public ResponseEntity<String> updateGmp(
            @RequestParam String symbol,
            @RequestParam double gmp) {
        log.info("Updating GMP for {}: {}%", symbol, gmp);
        gmpService.updateGmpForIpo(symbol, gmp);
        return ResponseEntity.ok("{\"status\": \"GMP updated\", \"symbol\": \"" + symbol + "\", \"gmp\": " + gmp + "}");
    }
}

