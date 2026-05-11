package com.trading.algo.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.trading.algo.dtos.EarningsWatchlistItemDTO;
import com.trading.algo.dtos.EarningsWatchlistDiffDTO;
import com.trading.algo.service.EarningsWatchlistDiffService;
import com.trading.algo.service.EarningsWatchlistService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * REST controller for the earnings-based watchlist.
 *
 * Base path : /api/earnings-watchlist
 *
 * Endpoints:
 *   GET  /api/earnings-watchlist            — all active (PRE + POST)
 *   GET  /api/earnings-watchlist/pre        — PRE_EARNINGS only (approaching result)
 *   GET  /api/earnings-watchlist/post       — POST_EARNINGS only (result just happened)
 *   POST /api/earnings-watchlist/sync       — manually trigger a sync with the Earnings table
 */
@Slf4j
@RestController
@RequestMapping("/api/earnings-watchlist")
@RequiredArgsConstructor
public class EarningsWatchlistController {

    private final EarningsWatchlistService     earningsWatchlistService;
    private final EarningsWatchlistDiffService  diffService;

    /**
     * GET /api/earnings-watchlist
     *
     * Returns all stocks currently in the earnings observation window
     * (both pre- and post-earnings), sorted by result date ascending.
     *
     * curl http://localhost:8080/api/earnings-watchlist
     */
    @GetMapping
    public ResponseEntity<List<EarningsWatchlistItemDTO>> getActiveWatchlist() {
        log.info("GET /api/earnings-watchlist — active watchlist requested");
        return ResponseEntity.ok(earningsWatchlistService.getActiveWatchlist());
    }

    /**
     * GET /api/earnings-watchlist/pre
     *
     * Returns only PRE_EARNINGS stocks — i.e. those where the result is
     * still upcoming (within the next 10 days).
     *
     * curl http://localhost:8080/api/earnings-watchlist/pre
     */
    @GetMapping("/pre")
    public ResponseEntity<List<EarningsWatchlistItemDTO>> getPreEarnings() {
        log.info("GET /api/earnings-watchlist/pre");
        return ResponseEntity.ok(earningsWatchlistService.getPreEarnings());
    }

    /**
     * GET /api/earnings-watchlist/post
     *
     * Returns only POST_EARNINGS stocks — i.e. those where the result
     * already happened (within the last 3 days).
     *
     * curl http://localhost:8080/api/earnings-watchlist/post
     */
    @GetMapping("/post")
    public ResponseEntity<List<EarningsWatchlistItemDTO>> getPostEarnings() {
        log.info("GET /api/earnings-watchlist/post");
        return ResponseEntity.ok(earningsWatchlistService.getPostEarnings());
    }

    /**
     * POST /api/earnings-watchlist/sync
     *
     * Manually triggers a full sync: expires stale rows, transitions
     * phases, and ingests any new Earnings records in the window.
     * Useful for testing or if the nightly scheduler missed a run.
     *
     * curl -X POST http://localhost:8080/api/earnings-watchlist/sync
     */
    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> manualSync() {
        log.info("POST /api/earnings-watchlist/sync — manual sync triggered");
        int added = earningsWatchlistService.manualSync();
        return ResponseEntity.ok(Map.of(
                "status", "Sync complete",
                "newEntriesAdded", added
        ));
    }

    /**
     * GET /api/earnings-watchlist/diff
     *
     * Compares the current active earnings window against the last snapshot
     * sent via /api/earnings-alerts/window and returns:
     *   - toAdd    : new stocks entered the window (add to your watchlist)
     *   - toRemove : stocks that exited the window (remove from your watchlist)
     *   - unchanged: no action needed
     *
     * Add ?notify=true to also send the diff as a Telegram message.
     *
     * curl http://localhost:8080/api/earnings-watchlist/diff
     * curl http://localhost:8080/api/earnings-watchlist/diff?notify=true
     */
    @GetMapping("/diff")
    public ResponseEntity<EarningsWatchlistDiffDTO> getDiff(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "false") boolean notify) {
        log.info("GET /api/earnings-watchlist/diff — notify={}", notify);
        return ResponseEntity.ok(diffService.diff(notify));
    }
}