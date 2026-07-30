package com.trading.algo.controller;

import com.trading.algo.entity.WatchlistAlert;
import com.trading.algo.service.WatchlistAlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Controller to receive 15-minute watchlist alerts from external sources.
 * 
 * POST /api/watchlist/alert
 *   - Receives the raw alert text and parses it to extract stock symbols
 *   - Stores the alert in the database for processing by ORB and Fibonacci strategies
 * 
 * GET /api/watchlist/latest
 *   - Returns the latest alert for manual inspection
 */
@Slf4j
@RestController
@RequestMapping("/api/watchlist")
@RequiredArgsConstructor
public class WatchlistAlertController {

    private final WatchlistAlertService watchlistAlertService;

    /**
     * POST /api/watchlist/alert
     * 
     * Receives a watchlist alert and stores it for processing.
     * The alert text should be in the format shown in the example.
     * 
     * Request body:
     * {
     *   "alertText": "📊 Live Market Watchlist | 12:30...",
     *   "alertTime": "2026-07-27T12:30:00" (optional, defaults to now)
     * }
     * 
     * curl -X POST http://localhost:8080/api/watchlist/alert \
     *   -H "Content-Type: application/json" \
     *   -d '{"alertText": "📊 Live Market Watchlist..."}'
     */
    @PostMapping("/alert")
    public ResponseEntity<Map<String, Object>> receiveAlert(@RequestBody Map<String, String> request) {
        String alertText = request.get("alertText");
        String alertTimeStr = request.get("alertTime");
        
        if (alertText == null || alertText.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "alertText is required"));
        }
        
        LocalDateTime alertTime = null;
        if (alertTimeStr != null && !alertTimeStr.trim().isEmpty()) {
            try {
                alertTime = LocalDateTime.parse(alertTimeStr);
            } catch (Exception e) {
                log.warn("Invalid alertTime format, using current time: {}", e.getMessage());
            }
        }
        
        WatchlistAlert alert = watchlistAlertService.processAlert(alertText, alertTime);
        
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "alertId", alert.getId(),
                "symbolsExtracted", alert.getTotalSymbols(),
                "alertTime", alert.getAlertTime()
        ));
    }

    /**
     * GET /api/watchlist/latest
     * 
     * Returns the latest watchlist alert for manual inspection.
     * 
     * curl -X GET http://localhost:8080/api/watchlist/latest
     */
    @GetMapping("/latest")
    public ResponseEntity<WatchlistAlert> getLatestAlert() {
        WatchlistAlert alert = watchlistAlertService.getLatestAlert();
        if (alert == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(alert);
    }

    /**
     * POST /api/watchlist/test-alert
     * 
     * Endpoint to test with the sample alert provided by the user.
     * This is for testing purposes only.
     */
    @PostMapping("/test-alert")
    public ResponseEntity<Map<String, Object>> testAlert() {
        String sampleAlert = """
            📊 Live Market Watchlist | 12:30
            ━━━━━━━━━━━━━━━━━━━━━
            
            ⚡️ Multi-Category Stocks
            HFCL ▼ 1.26% | Loser, Active, Vol Shocker
            HDFCBANK ▼ 0.51% | High OI, Active, Vol Shocker
            KALYANKJIL ▼ 1.70% | Loser, Active, Vol Shocker
            CONCOR ▲ 5.61% | Gainer, Active, Vol Shocker
            CANBK ▲ 2.37% | High OI, Active, Vol Shocker
            INFY ▲ 3.46% | Gainer, Active, Vol Shocker
            IDFCFIRSTB ▲ 5.33% | Gainer, Active, Vol Shocker
            UCOBANK ▲ 4.15% | Gainer, Vol Shocker
            KFINTECH ▲ 7.81% | Gainer, Active
            LAURUSLABS ▲ 5.51% | Gainer, Active
            CHOLAFIN ▲ 3.71% | High OI, Gainer
            SUZLON ▲ 0.98% | High OI, Vol Shocker
            KOTAKBANK ▲ 0.08% | High OI, Vol Shocker
            RPOWER ▼ 0.88% | Loser, Vol Shocker
            BANKBARODA ▼ 0.88% | Loser, Vol Shocker
            INDIGO ▲ 4.32% | Gainer, Active
            OFSS ▲ 4.05% | Gainer, Active
            BSE ▼ 1.31% | Loser, Active
            BANDHANBNK ▲ 2.30% | High OI, Vol Shocker
            BAJFINANCE ▲ 3.11% | Gainer, Active
            ITC ▲ 1.06% | Active, Vol Shocker
            YESBANK ▼ 0.09% | High OI, Vol Shocker
            ONGC ▼ 3.04% | Loser, Vol Shocker
            INDUSTOWER ▼ 0.79% | High OI, Loser
            
            📈 Top Gainers
            LATENTVIEW ▲ 6.42%
            LALPATHLAB ▲ 5.77%
            PVRINOX ▲ 5.11%
            AUBANK ▲ 5.05% NEW
            NCC ▲ 3.51%
            HAPPSTMNDS ▲ 3.33%
            DEVYANI ▲ 3.32%
            BSOFT ▲ 3.29%
            SPANDANA ▲ 3.17%
            WHIRLPOOL ▲ 3.15%
            
            📉 Top Losers
            ROUTE ▼ 5.23%
            NIACL ▼ 4.20%
            TATACOMM ▼ 2.65%
            CYIENT ▼ 1.71%
            ABB ▼ 1.60%
            SUPREMEIND ▼ 1.44% NEW
            TANLA ▼ 1.44%
            OIL ▼ 1.35%
            TEAMLEASE ▼ 1.34% NEW
            EXIDEIND ▼ 0.99%
            BHEL ▼ 0.98%
            SBILIFE ▼ 0.81%
            ADANIPORTS ▼ 0.76%
            
            🔥 Volume Shockers
            SAIL Vol: 17.4x | ▲ 0.94%
            IEX Vol: 16.6x | ▲ 2.13%
            ADANIPOWER Vol: 16.1x | ▲ 0.09%
            POWERGRID Vol: 15.4x | ▲ 0.29%
            
            💰 Active by Value
            SUNPHARMA Val: 656.1Cr | ▲ 1.00%
            ICICIBANK Val: 646.6Cr | ▲ 0.20%
            TCS Val: 628.8Cr | ▲ 2.13%
            BHARTIARTL Val: 571.0Cr | ▲ 0.37%
            SHRIRAMFIN Val: 527.4Cr | ▲ 2.34%
            RELIANCE Val: 410.1Cr | ▲ 0.11%
            
            📌 High OI
            HDFCLIFE OI: 57.9L | ▼ 0.43%
            COALINDIA OI: 53.6L | ▲ 0.60%
            WIPRO OI: 35.3L | ▼ 0.37%
            UNIONBANK OI: 26.7L | ▲ 0.26%
            HINDPETRO OI: 20.0L | ▲ 2.42%
            CIPLA OI: 19.5L | ▼ 0.18%
            TATASTEEL OI: 12.8L | ▲ 0.74%
            CGPOWER OI: 11.8L | ▲ 0.76%
            RECLTD OI: 9.1L | ▲ 0.81%
            VEDL OI: 8.4L | ▲ 0.32%
            LICI OI: 8.1L | ▲ 0.33%
            VBL OI: 8.1L | ▲ 1.08%
            
            🟢 Only Buyers
            None
            
            🔴 Only Sellers
            ZEEL S/B: 5.6x | ▼ 1.66%
            ESCORTS S/B: 5.1x | ▼ 0.58%
            
            ━━━━━━━━━━━━━━━━━━━━━
            Scanned 225 F&O stocks
            """;
        
        WatchlistAlert alert = watchlistAlertService.processAlert(sampleAlert, LocalDateTime.now());
        
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "alertId", alert.getId(),
                "symbolsExtracted", alert.getTotalSymbols(),
                "alertTime", alert.getAlertTime(),
                "message", "Test alert processed successfully"
        ));
    }
}
