package com.trading.algo.controller;

import com.trading.algo.dtos.FilteredWatchlistResponse;
import com.trading.algo.service.IntradayWatchlistFilterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/intraday-watchlist")
@RequiredArgsConstructor
public class IntradayWatchlistController {

    private final IntradayWatchlistFilterService filterService;

    /**
     * Returns the filtered watchlist of 10 stocks based on intraday snapshot analysis.
     * The filtering uses multiple criteria:
     * - Frequency Score (3 stocks): Most frequent appearances across recent snapshots
     * - Category Diversity (3 stocks): 1 from each key category (gainers, volume shockers, only buyers)
     * - Momentum Consistency (2 stocks): Consecutive appearances with improving metrics
     * - Liquidity + Volume Spike (2 stocks): High liquidity with unusual volume activity
     */
    @GetMapping("/filtered")
    public ResponseEntity<FilteredWatchlistResponse> getFilteredWatchlist() {
        log.info("GET /api/intraday-watchlist/filtered");
        FilteredWatchlistResponse response = filterService.filterWatchlist();
        return ResponseEntity.ok(response);
    }
}
