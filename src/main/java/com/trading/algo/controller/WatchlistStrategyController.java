package com.trading.algo.controller;

import com.trading.algo.fibostrategy.WatchlistFiboService;
import com.trading.algo.orb.WatchlistOrbService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Controller for manual trigger of watchlist strategies.
 * 
 * POST /api/watchlist-strategy/orb
 *   - Manually trigger ORB strategy on latest watchlist alert
 * 
 * POST /api/watchlist-strategy/fibo
 *   - Manually trigger Fibonacci strategy on latest watchlist alert
 * 
 * POST /api/watchlist-strategy/clear-orb-state
 *   - Clear ORB state (useful for testing or reset)
 */
@Slf4j
@RestController
@RequestMapping("/api/watchlist-strategy")
@RequiredArgsConstructor
public class WatchlistStrategyController {

    private final WatchlistOrbService watchlistOrbService;
    private final WatchlistFiboService watchlistFiboService;

    /**
     * POST /api/watchlist-strategy/orb
     * 
     * Manually trigger ORB strategy on the latest watchlist alert.
     * Same as what runs automatically every 15 minutes.
     * 
     * curl -X POST http://localhost:8080/api/watchlist-strategy/orb
     */
    @PostMapping("/orb")
    public ResponseEntity<Map<String, Object>> triggerOrb() {
        log.info("Manual trigger of watchlist ORB strategy");
        watchlistOrbService.manualTrigger();
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Watchlist ORB strategy triggered"
        ));
    }

    /**
     * POST /api/watchlist-strategy/fibo
     * 
     * Manually trigger Fibonacci strategy on the latest watchlist alert.
     * Same as what runs automatically at 9:46 AM.
     * 
     * curl -X POST http://localhost:8080/api/watchlist-strategy/fibo
     */
    @PostMapping("/fibo")
    public ResponseEntity<Map<String, Object>> triggerFibo() {
        log.info("Manual trigger of watchlist Fibonacci strategy");
        int setupsFound = watchlistFiboService.manualTrigger();
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "setupsFound", setupsFound,
                "message", "Watchlist Fibonacci strategy triggered"
        ));
    }

    /**
     * POST /api/watchlist-strategy/clear-orb-state
     * 
     * Clear the ORB state for watchlist stocks.
     * Useful for testing or resetting at end of day.
     * 
     * curl -X POST http://localhost:8080/api/watchlist-strategy/clear-orb-state
     */
    @PostMapping("/clear-orb-state")
    public ResponseEntity<Map<String, Object>> clearOrbState() {
        log.info("Manual clear of watchlist ORB state");
        watchlistOrbService.clearState();
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Watchlist ORB state cleared"
        ));
    }
}
