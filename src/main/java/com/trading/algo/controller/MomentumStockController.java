package com.trading.algo.controller;

import com.trading.algo.entity.MomentumStockSnapshot;
import com.trading.algo.service.MomentumStockSnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/momentum")
@RequiredArgsConstructor
public class MomentumStockController {

    private final MomentumStockSnapshotService momentumStockSnapshotService;

    /**
     * Manually trigger momentum stock capture
     * POST /api/momentum/capture
     */
    @PostMapping("/capture")
    public ResponseEntity<Map<String, Object>> captureMomentumStocks() {
        try {
            momentumStockSnapshotService.captureMomentumStocks();
            
            return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Momentum stocks captured successfully",
                "timestamp", LocalDateTime.now().toString()
            ));
        } catch (Exception e) {
            log.error("Failed to capture momentum stocks", e);
            return ResponseEntity.internalServerError().body(Map.of(
                "status", "error",
                "message", "Failed to capture momentum stocks: " + e.getMessage(),
                "timestamp", LocalDateTime.now().toString()
            ));
        }
    }

    /**
     * Manually capture the current momentum universe and send it to Telegram.
     * POST /api/momentum/capture-and-alert
     */
    @PostMapping("/capture-and-alert")
    public ResponseEntity<Map<String, Object>> captureAndAlert() {
        int captured = momentumStockSnapshotService.captureMomentumStocks();
        int sent = captured > 0 ? momentumStockSnapshotService.sendLatestSnapshotAlert() : 0;
        return ResponseEntity.ok(Map.of(
                "status", captured > 0 ? "success" : "no_stocks",
                "capturedStocks", captured,
                "telegramSent", sent > 0,
                "timestamp", LocalDateTime.now().toString()
        ));
    }

    /**
     * Re-send the latest saved momentum universe without creating a new snapshot.
     * POST /api/momentum/alert
     */
    @PostMapping("/alert")
    public ResponseEntity<Map<String, Object>> sendLatestSnapshotAlert() {
        int sent = momentumStockSnapshotService.sendLatestSnapshotAlert();
        return ResponseEntity.ok(Map.of(
                "status", sent > 0 ? "success" : "no_snapshot",
                "stocksSent", sent,
                "telegramSent", sent > 0,
                "timestamp", LocalDateTime.now().toString()
        ));
    }

    /**
     * Get latest momentum stock symbols
     * GET /api/momentum/symbols
     */
    @GetMapping("/symbols")
    public ResponseEntity<Map<String, Object>> getLatestSymbols() {
        try {
            List<String> symbols = momentumStockSnapshotService.getLatestMomentumSymbols();
            
            return ResponseEntity.ok(Map.of(
                "status", "success",
                "symbols", symbols,
                "count", symbols.size(),
                "timestamp", LocalDateTime.now().toString()
            ));
        } catch (Exception e) {
            log.error("Failed to get momentum symbols", e);
            return ResponseEntity.internalServerError().body(Map.of(
                "status", "error",
                "message", "Failed to get momentum symbols: " + e.getMessage(),
                "timestamp", LocalDateTime.now().toString()
            ));
        }
    }

    /**
     * Get latest momentum stock snapshot with full details
     * GET /api/momentum/snapshot
     */
    @GetMapping("/snapshot")
    public ResponseEntity<Map<String, Object>> getLatestSnapshot() {
        try {
            var snapshot = momentumStockSnapshotService.getLatestSnapshot();
            
            if (snapshot.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", "No momentum stock snapshot available",
                    "snapshot", null,
                    "timestamp", LocalDateTime.now().toString()
                ));
            }
            
            MomentumStockSnapshot snap = snapshot.get();
            return ResponseEntity.ok(Map.of(
                "status", "success",
                "snapshot", Map.of(
                    "id", snap.getId(),
                    "snapshotTime", snap.getSnapshotTime().toString(),
                    "symbols", snap.getSymbols(),
                    "totalStocks", snap.getTotalStocks(),
                    "symbolCategories", snap.getSymbolCategories()
                ),
                "timestamp", LocalDateTime.now().toString()
            ));
        } catch (Exception e) {
            log.error("Failed to get momentum snapshot", e);
            return ResponseEntity.internalServerError().body(Map.of(
                "status", "error",
                "message", "Failed to get momentum snapshot: " + e.getMessage(),
                "timestamp", LocalDateTime.now().toString()
            ));
        }
    }

    /**
     * Check if trade direction is allowed based on A/D ratio
     * GET /api/momentum/check-direction?adRatio=1.5&direction=BUY
     */
    @GetMapping("/check-direction")
    public ResponseEntity<Map<String, Object>> checkTradeDirection(
            @RequestParam double adRatio,
            @RequestParam String direction) {
        try {
            boolean allowed = momentumStockSnapshotService.isTradeDirectionAllowed(adRatio, direction);
            String category = momentumStockSnapshotService.categorizeTradeByAdRatio(adRatio);
            
            return ResponseEntity.ok(Map.of(
                "status", "success",
                "allowed", allowed,
                "adRatio", adRatio,
                "direction", direction,
                "category", category,
                "timestamp", LocalDateTime.now().toString()
            ));
        } catch (Exception e) {
            log.error("Failed to check trade direction", e);
            return ResponseEntity.internalServerError().body(Map.of(
                "status", "error",
                "message", "Failed to check trade direction: " + e.getMessage(),
                "timestamp", LocalDateTime.now().toString()
            ));
        }
    }

    /**
     * Get trade category based on A/D ratio
     * GET /api/momentum/category?adRatio=1.5
     */
    @GetMapping("/category")
    public ResponseEntity<Map<String, Object>> getCategory(@RequestParam double adRatio) {
        try {
            String category = momentumStockSnapshotService.categorizeTradeByAdRatio(adRatio);
            
            return ResponseEntity.ok(Map.of(
                "status", "success",
                "adRatio", adRatio,
                "category", category,
                "timestamp", LocalDateTime.now().toString()
            ));
        } catch (Exception e) {
            log.error("Failed to get category", e);
            return ResponseEntity.internalServerError().body(Map.of(
                "status", "error",
                "message", "Failed to get category: " + e.getMessage(),
                "timestamp", LocalDateTime.now().toString()
            ));
        }
    }
}
