package com.trading.algo.fibostrategy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

/**
 * REST controller for manually triggering the Stock Strong Candle Scanner.
 */
@Slf4j
@RestController
@RequestMapping("/api/strong-candle")
@RequiredArgsConstructor
public class StockStrongCandleController {

    private final StockStrongCandleScanner scanner;
    private final DailyStrongCandleScanner  dailyScanner;

    /**
     * Manually trigger the strong candle scan for today.
     */
    @PostMapping("/scan")
    public ResponseEntity<Map<String, Object>> triggerScan() {
        log.info("Manual trigger of strong candle scan");
        try {
            scanner.scanDailyStrongCandles();
            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", "Strong candle scan triggered successfully"
            ));
        } catch (Exception e) {
            log.error("Error triggering strong candle scan", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", "Failed to trigger scan: " + e.getMessage()
            ));
        }
    }

    /**
     * Manually trigger the strong candle scan for a specific date.
     * Note: This requires modifying the scanner to accept a date parameter.
     */
    @PostMapping("/scan/{date}")
    public ResponseEntity<Map<String, Object>> triggerScanForDate(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        log.info("Manual trigger of strong candle scan for {}", date);
        return ResponseEntity.ok(Map.of("status", "info", "message", "Use /daily-scan/{date} instead."));
    }

    @PostMapping("/daily-scan")
    public ResponseEntity<Map<String, Object>> triggerDailyScan() {
        try {
            dailyScanner.scanForDate(LocalDate.now());
            return ResponseEntity.ok(Map.of("status", "success", "message", "Daily strong candle scan triggered"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    @PostMapping("/daily-scan/{date}")
    public ResponseEntity<Map<String, Object>> triggerDailyScanForDate(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        try {
            dailyScanner.scanForDate(date);
            return ResponseEntity.ok(Map.of("status", "success", "date", date.toString()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    @GetMapping("/stock/{symbol}")
    public ResponseEntity<Map<String, Object>> getStockStrongCandles(
            @PathVariable String symbol,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        try {
            Map<String, Object> result = dailyScanner.scanStockBetweenDates(symbol.toUpperCase(), from, to);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("status", "error", "message", e.getMessage()));
        }
    }
}
