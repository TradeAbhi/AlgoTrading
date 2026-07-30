package com.trading.algo.controller;

import com.trading.algo.entity.MomentumStockSnapshot;
import com.trading.algo.dtos.OrbSymbolState;
import com.trading.algo.service.MomentumStockSnapshotService;
import com.trading.algo.orb.OrbStateStore;
import com.trading.algo.orb.MomentumOrbScannerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Controller to query which stocks had Fibonacci and ORB strategies applied today.
 * 
 * Both strategies run on the momentum stock snapshot universe:
 * - Fibonacci: Runs at 9:46 AM on momentum snapshot stocks
 * - ORB: Runs every 15 minutes on momentum snapshot stocks
 */
@Slf4j
@RestController
@RequestMapping("/api/strategy-status")
@RequiredArgsConstructor
public class StrategyStatusController {

    private final MomentumStockSnapshotService momentumStockSnapshotService;
    private final OrbStateStore orbStateStore;
    private final MomentumOrbScannerService momentumOrbScannerService;

    /**
     * Get today's momentum snapshot stocks (universe for both strategies)
     * If no snapshot exists, will trigger a capture automatically.
     * GET /api/strategy-status/momentum-universe?forceCapture=true
     */
    @GetMapping("/momentum-universe")
    public ResponseEntity<Map<String, Object>> getMomentumUniverse(
            @RequestParam(defaultValue = "false") boolean forceCapture) {
        try {
            Optional<MomentumStockSnapshot> snapshot = momentumStockSnapshotService.getLatestSnapshot();
            
            // Fallback: trigger capture if no snapshot or forceCapture is true
            if (snapshot.isEmpty() || forceCapture) {
                log.info("No momentum snapshot available or forceCapture=true, triggering capture...");
                int captured = momentumStockSnapshotService.captureMomentumStocks();
                if (captured > 0) {
                    snapshot = momentumStockSnapshotService.getLatestSnapshot();
                    log.info("Captured {} momentum stocks", captured);
                }
            }
            
            if (snapshot.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                    "status", "no_snapshot",
                    "message", "No momentum snapshot available even after capture attempt",
                    "timestamp", LocalDateTime.now().toString()
                ));
            }

            MomentumStockSnapshot snap = snapshot.get();
            LocalDate today = LocalDate.now();
            boolean isToday = snap.getSnapshotTime().toLocalDate().equals(today);

            return ResponseEntity.ok(Map.of(
                "status", "success",
                "isToday", isToday,
                "snapshotTime", snap.getSnapshotTime().toString(),
                "totalStocks", snap.getTotalStocks(),
                "symbols", snap.getSymbols(),
                "symbolCategories", snap.getSymbolCategories(),
                "timestamp", LocalDateTime.now().toString()
            ));
        } catch (Exception e) {
            log.error("Failed to get momentum universe", e);
            return ResponseEntity.internalServerError().body(Map.of(
                "status", "error",
                "message", "Failed to get momentum universe: " + e.getMessage(),
                "timestamp", LocalDateTime.now().toString()
            ));
        }
    }

    /**
     * Get current ORB state for today
     * If state is empty, will trigger a manual ORB scan automatically.
     * GET /api/strategy-status/orb-state?forceScan=true
     */
    @GetMapping("/orb-state")
    public ResponseEntity<Map<String, Object>> getOrbState(
            @RequestParam(defaultValue = "false") boolean forceScan) {
        try {
            Collection<OrbSymbolState> states = orbStateStore.all();
            
            // Fallback: trigger ORB scan if state is empty or forceScan is true
            if (states.isEmpty() || forceScan) {
                log.info("ORB state empty or forceScan=true, triggering manual ORB scan...");
                momentumOrbScannerService.triggerManualScan();
                states = orbStateStore.all();
                log.info("ORB scan completed, now tracking {} symbols", states.size());
            }
            
            List<Map<String, Object>> stateList = states.stream()
                .map(s -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("symbol", s.getSymbol());
                    map.put("rollingHigh", s.getRollingHigh());
                    map.put("rollingLow", s.getRollingLow());
                    map.put("buyAlerted", s.isBuyAlerted());
                    map.put("sellAlerted", s.isSellAlerted());
                    map.put("openingCandleVolume", s.getOpeningCandleVolume());
                    map.put("openPrice", s.getOpenPrice());
                    return map;
                })
                .collect(Collectors.toList());

            return ResponseEntity.ok(Map.of(
                "status", "success",
                "totalSymbols", states.size(),
                "states", stateList,
                "timestamp", LocalDateTime.now().toString()
            ));
        } catch (Exception e) {
            log.error("Failed to get ORB state", e);
            return ResponseEntity.internalServerError().body(Map.of(
                "status", "error",
                "message", "Failed to get ORB state: " + e.getMessage(),
                "timestamp", LocalDateTime.now().toString()
            ));
        }
    }

    /**
     * Get ORB symbols that are still being watched (no alert triggered yet)
     * GET /api/strategy-status/orb-watching
     */
    @GetMapping("/orb-watching")
    public ResponseEntity<Map<String, Object>> getOrbWatching() {
        try {
            Collection<OrbSymbolState> states = orbStateStore.all();
            
            List<Map<String, Object>> watchingList = states.stream()
                .filter(s -> !s.isBuyAlerted() && !s.isSellAlerted())
                .map(s -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("symbol", s.getSymbol());
                    map.put("rollingHigh", s.getRollingHigh());
                    map.put("rollingLow", s.getRollingLow());
                    map.put("openPrice", s.getOpenPrice());
                    return map;
                })
                .collect(Collectors.toList());

            return ResponseEntity.ok(Map.of(
                "status", "success",
                "watchingCount", watchingList.size(),
                "symbols", watchingList,
                "timestamp", LocalDateTime.now().toString()
            ));
        } catch (Exception e) {
            log.error("Failed to get ORB watching", e);
            return ResponseEntity.internalServerError().body(Map.of(
                "status", "error",
                "message", "Failed to get ORB watching: " + e.getMessage(),
                "timestamp", LocalDateTime.now().toString()
            ));
        }
    }

    /**
     * Get ORB symbols that have triggered alerts
     * GET /api/strategy-status/orb-alerted
     */
    @GetMapping("/orb-alerted")
    public ResponseEntity<Map<String, Object>> getOrbAlerted() {
        try {
            Collection<OrbSymbolState> states = orbStateStore.all();
            
            List<Map<String, Object>> buyAlerts = states.stream()
                .filter(OrbSymbolState::isBuyAlerted)
                .map(s -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("symbol", s.getSymbol());
                    map.put("direction", "BUY");
                    map.put("breakoutLevel", s.getRollingHigh());
                    return map;
                })
                .collect(Collectors.toList());

            List<Map<String, Object>> sellAlerts = states.stream()
                .filter(OrbSymbolState::isSellAlerted)
                .map(s -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("symbol", s.getSymbol());
                    map.put("direction", "SELL");
                    map.put("breakoutLevel", s.getRollingLow());
                    return map;
                })
                .collect(Collectors.toList());

            return ResponseEntity.ok(Map.of(
                "status", "success",
                "buyAlerts", buyAlerts,
                "sellAlerts", sellAlerts,
                "totalAlerts", buyAlerts.size() + sellAlerts.size(),
                "timestamp", LocalDateTime.now().toString()
            ));
        } catch (Exception e) {
            log.error("Failed to get ORB alerted", e);
            return ResponseEntity.internalServerError().body(Map.of(
                "status", "error",
                "message", "Failed to get ORB alerted: " + e.getMessage(),
                "timestamp", LocalDateTime.now().toString()
            ));
        }
    }

    /**
     * Get comprehensive status for both strategies
     * If data is missing, will trigger fallback scans automatically.
     * GET /api/strategy-status/today?forceCapture=true&forceScan=true
     */
    @GetMapping("/today")
    public ResponseEntity<Map<String, Object>> getTodayStatus(
            @RequestParam(defaultValue = "false") boolean forceCapture,
            @RequestParam(defaultValue = "false") boolean forceScan) {
        try {
            // Get momentum universe with fallback
            Optional<MomentumStockSnapshot> snapshot = momentumStockSnapshotService.getLatestSnapshot();
            if (snapshot.isEmpty() || forceCapture) {
                log.info("No momentum snapshot or forceCapture=true, triggering capture...");
                int captured = momentumStockSnapshotService.captureMomentumStocks();
                if (captured > 0) {
                    snapshot = momentumStockSnapshotService.getLatestSnapshot();
                    log.info("Captured {} momentum stocks", captured);
                }
            }
            List<String> momentumSymbols = snapshot.isPresent() ? snapshot.get().getSymbols() : List.of();
            
            // Get ORB state with fallback
            Collection<OrbSymbolState> orbStates = orbStateStore.all();
            if (orbStates.isEmpty() || forceScan) {
                log.info("ORB state empty or forceScan=true, triggering manual ORB scan...");
                momentumOrbScannerService.triggerManualScan();
                orbStates = orbStateStore.all();
                log.info("ORB scan completed, now tracking {} symbols", orbStates.size());
            }
            Set<String> orbSymbols = orbStates.stream()
                .map(OrbSymbolState::getSymbol)
                .collect(Collectors.toSet());

            // Calculate overlap
            Set<String> momentumSet = new HashSet<>(momentumSymbols);
            Set<String> bothStrategies = new HashSet<>(momentumSet);
            bothStrategies.retainAll(orbSymbols);

            return ResponseEntity.ok(Map.of(
                "status", "success",
                "date", LocalDate.now().toString(),
                "momentumUniverse", Map.of(
                    "count", momentumSymbols.size(),
                    "symbols", momentumSymbols,
                    "snapshotTime", snapshot.map(s -> s.getSnapshotTime().toString()).orElse("N/A")
                ),
                "orbState", Map.of(
                    "totalTracked", orbStates.size(),
                    "buyAlerts", orbStates.stream().filter(OrbSymbolState::isBuyAlerted).count(),
                    "sellAlerts", orbStates.stream().filter(OrbSymbolState::isSellAlerted).count(),
                    "stillWatching", orbStates.stream()
                        .filter(s -> !s.isBuyAlerted() && !s.isSellAlerted()).count()
                ),
                "overlap", Map.of(
                    "bothStrategies", bothStrategies.size(),
                    "symbols", bothStrategies
                ),
                "note", "Fibonacci runs at 9:46 AM on momentum universe. ORB runs every 15 min on momentum universe.",
                "timestamp", LocalDateTime.now().toString()
            ));
        } catch (Exception e) {
            log.error("Failed to get today status", e);
            return ResponseEntity.internalServerError().body(Map.of(
                "status", "error",
                "message", "Failed to get today status: " + e.getMessage(),
                "timestamp", LocalDateTime.now().toString()
            ));
        }
    }
}
