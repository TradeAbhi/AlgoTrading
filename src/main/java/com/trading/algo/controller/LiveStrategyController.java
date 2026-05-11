package com.trading.algo.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.trading.algo.service.LiveStrategyAlertService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Manual trigger for the Opening Candle Strategy live alert.
 *
 * POST /api/live-strategy/scan
 *   → Fetches today's 9:15 and 9:30 candles for all F&O stocks
 *   → Runs strategy rules
 *   → Sends Telegram alert with BUY/SELL setups
 *
 * The scheduler runs this automatically at 9:46 AM every weekday.
 * Use this endpoint to re-trigger manually if needed.
 *
 * curl -X POST http://localhost:8080/api/live-strategy/scan
 */
@Slf4j
@RestController
@RequestMapping("/api/live-strategy")
@RequiredArgsConstructor
public class LiveStrategyController {

    private final LiveStrategyAlertService liveStrategyAlertService;

    /**
     * POST /api/live-strategy/scan
     *
     * Manually triggers the Opening Candle Strategy scan and sends
     * Telegram alert. Same as what runs automatically at 9:46 AM.
     *
     * Safe to call multiple times — no state is saved, it just
     * re-scans and re-sends the alert.
     *
     * curl -X POST http://localhost:8080/api/live-strategy/scan
     */
    @PostMapping("/scan")
    public ResponseEntity<Map<String, Object>> triggerScan() {
        log.info("POST /api/live-strategy/scan — manual trigger");
        int signals = liveStrategyAlertService.scanAndAlert();
        return ResponseEntity.ok(Map.of(
                "status", "Scan complete",
                "setupsFound", signals,
                "telegramSent", true
        ));
    }
}