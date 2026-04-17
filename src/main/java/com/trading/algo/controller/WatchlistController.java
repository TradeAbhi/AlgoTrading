package com.trading.algo.controller;


import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.trading.algo.dtos.WatchlistCategory;
import com.trading.algo.dtos.WatchlistItem;
import com.trading.algo.dtos.WatchlistResponse;
import com.trading.algo.service.WatchlistService;
import com.trading.algo.service.WatchlistTelegramAlertService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/watchlist")
@RequiredArgsConstructor
public class WatchlistController {

    private final WatchlistService watchlistService;
    private final WatchlistTelegramAlertService telegramAlertService;

    // GET /api/watchlist — all 7 categories
    @GetMapping
    public ResponseEntity<WatchlistResponse> getFullWatchlist() {
        log.info("GET /api/watchlist - full watchlist requested");
        return ResponseEntity.ok(watchlistService.getLiveWatchlist());
    }

    /**
     * POST /api/watchlist/alert
     * Manually triggers a Telegram alert with all 7 categories right now.
     * No request body needed — just hit the endpoint.
     *
     * curl -X POST http://localhost:8080/api/watchlist/alert
     */
    @PostMapping("/alert")
    public ResponseEntity<Map<String, String>> triggerAlert() {
        log.info("POST /api/watchlist/alert — manual Telegram alert triggered");
        telegramAlertService.sendWatchlistDigest();
        return ResponseEntity.ok(Map.of("status", "Alert sent to Telegram"));
    }

    // GET /api/watchlist/high-oi
    @GetMapping("/high-oi")
    public ResponseEntity<List<WatchlistItem>> getHighOi() {
        return ResponseEntity.ok(watchlistService.getCategory(WatchlistCategory.HIGH_OI));
    }

    // GET /api/watchlist/top-gainers
    @GetMapping("/top-gainers")
    public ResponseEntity<List<WatchlistItem>> getTopGainers() {
        return ResponseEntity.ok(watchlistService.getCategory(WatchlistCategory.TOP_GAINER));
    }

    // GET /api/watchlist/top-losers
    @GetMapping("/top-losers")
    public ResponseEntity<List<WatchlistItem>> getTopLosers() {
        return ResponseEntity.ok(watchlistService.getCategory(WatchlistCategory.TOP_LOSER));
    }

    // GET /api/watchlist/active-by-value
    @GetMapping("/active-by-value")
    public ResponseEntity<List<WatchlistItem>> getActiveByValue() {
        return ResponseEntity.ok(watchlistService.getCategory(WatchlistCategory.ACTIVE_BY_VALUE));
    }

    // GET /api/watchlist/volume-shockers
    @GetMapping("/volume-shockers")
    public ResponseEntity<List<WatchlistItem>> getVolumeShockers() {
        return ResponseEntity.ok(watchlistService.getCategory(WatchlistCategory.VOLUME_SHOCKER));
    }

    // GET /api/watchlist/only-buyers
    @GetMapping("/only-buyers")
    public ResponseEntity<List<WatchlistItem>> getOnlyBuyers() {
        return ResponseEntity.ok(watchlistService.getCategory(WatchlistCategory.ONLY_BUYERS));
    }

    // GET /api/watchlist/only-sellers
    @GetMapping("/only-sellers")
    public ResponseEntity<List<WatchlistItem>> getOnlySellers() {
        return ResponseEntity.ok(watchlistService.getCategory(WatchlistCategory.ONLY_SELLERS));
    }
}
/**
 * REST controller for live market watchlists.
 *
 * Base path: /api/watchlist
 */
//@Slf4j
//@RestController
//@RequestMapping("/api/watchlist")
//@RequiredArgsConstructor
//public class WatchlistController {
//
//    private final WatchlistService watchlistService;
//
//    /**
//     * GET /api/watchlist
//     * Returns all 7 watchlist categories in a single response.
//     */
//    @GetMapping
//    public ResponseEntity<WatchlistResponse> getFullWatchlist() {
//        log.info("GET /api/watchlist - full watchlist requested");
//        WatchlistResponse response = watchlistService.getLiveWatchlist();
//        return ResponseEntity.ok(response);
//    }
//
//    /**
//     * GET /api/watchlist/high-oi
//     * Top stocks / contracts by Open Interest.
//     */
//    @GetMapping("/high-oi")
//    public ResponseEntity<List<WatchlistItem>> getHighOi() {
//        return ResponseEntity.ok(watchlistService.getCategory(WatchlistCategory.HIGH_OI));
//    }
//
//    /**
//     * GET /api/watchlist/top-gainers
//     * Stocks sorted by % gain (descending).
//     */
//    @GetMapping("/top-gainers")
//    public ResponseEntity<List<WatchlistItem>> getTopGainers() {
//        return ResponseEntity.ok(watchlistService.getCategory(WatchlistCategory.TOP_GAINER));
//    }
//
//    /**
//     * GET /api/watchlist/top-losers
//     * Stocks sorted by % loss (ascending).
//     */
//    @GetMapping("/top-losers")
//    public ResponseEntity<List<WatchlistItem>> getTopLosers() {
//        return ResponseEntity.ok(watchlistService.getCategory(WatchlistCategory.TOP_LOSER));
//    }
//
//    /**
//     * GET /api/watchlist/active-by-value
//     * Stocks sorted by traded value in crores (descending).
//     */
//    @GetMapping("/active-by-value")
//    public ResponseEntity<List<WatchlistItem>> getActiveByValue() {
//        return ResponseEntity.ok(watchlistService.getCategory(WatchlistCategory.ACTIVE_BY_VALUE));
//    }
//
//    /**
//     * GET /api/watchlist/volume-shockers
//     * Stocks with unusually high volume vs their 20-day average.
//     */
//    @GetMapping("/volume-shockers")
//    public ResponseEntity<List<WatchlistItem>> getVolumeShockers() {
//        return ResponseEntity.ok(watchlistService.getCategory(WatchlistCategory.VOLUME_SHOCKER));
//    }
//
//    /**
//     * GET /api/watchlist/only-buyers
//     * Stocks with dominant buy orders (buy qty >> sell qty).
//     */
//    @GetMapping("/only-buyers")
//    public ResponseEntity<List<WatchlistItem>> getOnlyBuyers() {
//        return ResponseEntity.ok(watchlistService.getCategory(WatchlistCategory.ONLY_BUYERS));
//    }
//
//    /**
//     * GET /api/watchlist/only-sellers
//     * Stocks with dominant sell orders (sell qty >> buy qty).
//     */
//    @GetMapping("/only-sellers")
//    public ResponseEntity<List<WatchlistItem>> getOnlySellers() {
//        return ResponseEntity.ok(watchlistService.getCategory(WatchlistCategory.ONLY_SELLERS));
//    }
//}