package com.trading.algo.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.trading.algo.service.NseWeekHighService;
import com.trading.algo.service.WeekHighWeeklyCloseBreakoutService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * REST controller for 52-week high/low CSV Telegram alerts.
 *
 * POST /api/nse/52-week-high   → fetches 52-week highs, sends CSV to Telegram
 * POST /api/nse/52-week-low    → fetches 52-week lows, sends CSV to Telegram
 * POST /api/nse/52-week-both   → sends both high and low CSVs
 *
 * The scheduler also runs these automatically at market close (3:35 PM).
 */
@Slf4j
@RestController
@RequestMapping("/api/nse")
@RequiredArgsConstructor
public class NseWeekHighController {

    private final NseWeekHighService nseWeekHighService;
    private final WeekHighWeeklyCloseBreakoutService weekHighWeeklyCloseBreakoutService;

    /**
     * POST /api/nse/52-week-high
     * Fetches today's 52-week high stocks from NSE and sends CSV to Telegram.
     *
     * curl -X POST http://localhost:8080/api/nse/52-week-high
     */
    @PostMapping("/52-week-high")
    public ResponseEntity<Map<String, Object>> sendWeekHigh() {
        log.info("POST /api/nse/52-week-high — manual trigger");
        int count = nseWeekHighService.sendWeekHighCsv();
        return ResponseEntity.ok(Map.of(
                "status", "CSV sent to Telegram",
                "stocksFound", count
        ));
    }

    /**
     * POST /api/nse/52-week-low
     * Fetches today's 52-week low stocks from NSE and sends CSV to Telegram.
     *
     * curl -X POST http://localhost:8080/api/nse/52-week-low
     */
    @PostMapping("/52-week-low")
    public ResponseEntity<Map<String, Object>> sendWeekLow() {
        log.info("POST /api/nse/52-week-low — manual trigger");
        int count = nseWeekHighService.sendWeekLowCsv();
        return ResponseEntity.ok(Map.of(
                "status", "CSV sent to Telegram",
                "stocksFound", count
        ));
    }

    /**
     * POST /api/nse/52-week-both
     * Sends both 52-week high and low CSVs to Telegram in one call.
     *
     * curl -X POST http://localhost:8080/api/nse/52-week-both
     */
    @PostMapping("/52-week-both")
    public ResponseEntity<Map<String, String>> sendBoth() {
        log.info("POST /api/nse/52-week-both — manual trigger");
        nseWeekHighService.sendBothCsv();
        return ResponseEntity.ok(Map.of("status", "Both CSVs sent to Telegram"));
    }
    @PostMapping("/52-week-high/weekly-close-breakout")
    public ResponseEntity<Map<String, String>> scanWeeklyCloseBreakout() {
        log.info("POST /api/nse/52-week-high/weekly-close-breakout - manual trigger");
        weekHighWeeklyCloseBreakoutService.scanAndAlert();
        return ResponseEntity.ok(Map.of("status", "52-week high weekly close breakout scan complete"));
    }
}
