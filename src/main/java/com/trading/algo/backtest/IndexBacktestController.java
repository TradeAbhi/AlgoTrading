package com.trading.algo.backtest;

import com.trading.algo.dtos.BacktestSummaryDTO;
import com.trading.algo.entity.IndexBacktestTrade;
import com.trading.algo.entity.IndexBacktestTrade.IndexName;
import com.trading.algo.fibostrategy.IndexBacktestService;
import com.trading.algo.repo.IndexBacktestTradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * REST controller for index backtest — separate from F&O stock backtest.
 *
 * ─────────────────────────────────────────────────────────────────────
 * INDEXES SUPPORTED:
 *   NIFTY_50    → NSE_INDEX|Nifty 50
 *   BANK_NIFTY  → NSE_INDEX|Nifty Bank
 *   FIN_NIFTY   → NSE_INDEX|Nifty Fin Service
 *
 * RUN
 *   POST /api/index-backtest/run/NIFTY_50?from=2024-01-01&to=2024-03-31
 *   POST /api/index-backtest/run/BANK_NIFTY?from=2024-01-01&to=2024-03-31
 *   POST /api/index-backtest/run/FIN_NIFTY?from=2024-01-01&to=2024-03-31
 *   POST /api/index-backtest/run/all?from=2024-01-01&to=2024-03-31
 *
 * SUMMARY
 *   GET  /api/index-backtest/summary/NIFTY_50?from=2024-01-01&to=2024-03-31
 *   GET  /api/index-backtest/summary/BANK_NIFTY?from=2024-01-01&to=2024-03-31
 *   GET  /api/index-backtest/summary/all?from=2024-01-01&to=2024-03-31
 *
 * TRADES
 *   GET  /api/index-backtest/trades/NIFTY_50
 *   GET  /api/index-backtest/trades/NIFTY_50?from=2024-01-01&to=2024-03-31
 *
 * CLEAR
 *   DELETE /api/index-backtest/clear/NIFTY_50?from=2024-01-01&to=2024-03-31
 *   DELETE /api/index-backtest/clear/all?from=2024-01-01&to=2024-03-31
 * ─────────────────────────────────────────────────────────────────────
 */
@Slf4j
@RestController
@RequestMapping("/api/index-backtest")
@RequiredArgsConstructor
public class IndexBacktestController {

    private final IndexBacktestService         indexService;
    private final IndexBacktestTradeRepository tradeRepo;

    // =========================================================================
    // RUN
    // =========================================================================

    /**
     * POST /api/index-backtest/run/NIFTY_50?from=2024-01-01&to=2024-03-31
     * Run strategy on a single index.
     *
     * curl -X POST "http://localhost:8080/api/index-backtest/run/NIFTY_50?from=2024-01-01&to=2024-01-31"
     * curl -X POST "http://localhost:8080/api/index-backtest/run/BANK_NIFTY?from=2024-01-01&to=2024-01-31"
     * curl -X POST "http://localhost:8080/api/index-backtest/run/FIN_NIFTY?from=2024-01-01&to=2024-01-31"
     */
    @PostMapping("/run/{index}")
    public ResponseEntity<BacktestSummaryDTO> runSingle(
            @PathVariable String index,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "false") boolean clearOld) {

        IndexName indexName = parseIndex(index);
        if (indexName == null) return ResponseEntity.badRequest().build();

        log.info("POST /api/index-backtest/run/{} from={} to={}", index, from, to);
        BacktestSummaryDTO summary = indexService.run(indexName, from, to, clearOld);
        return ResponseEntity.ok(summary);
    }

    /**
     * POST /api/index-backtest/run/all?from=2024-01-01&to=2024-03-31
     * Run strategy on ALL indexes (Nifty 50 + Bank Nifty + Fin Nifty) sequentially.
     *
     * curl -X POST "http://localhost:8080/api/index-backtest/run/all?from=2024-01-01&to=2024-01-31"
     */
    @PostMapping("/run/all")
    public ResponseEntity<List<BacktestSummaryDTO>> runAll(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "false") boolean clearOld) {

        log.info("POST /api/index-backtest/run/all from={} to={}", from, to);
        return ResponseEntity.ok(indexService.runAll(from, to, clearOld));
    }

    // =========================================================================
    // SUMMARY
    // =========================================================================

    /**
     * GET /api/index-backtest/summary/NIFTY_50?from=2024-01-01&to=2024-03-31
     *
     * curl "http://localhost:8080/api/index-backtest/summary/NIFTY_50?from=2024-01-01&to=2024-03-31"
     */
    @GetMapping("/summary/{index}")
    public ResponseEntity<?> getSummary(
            @PathVariable String index,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        if ("all".equalsIgnoreCase(index)) {
            List<BacktestSummaryDTO> all = List.of(IndexName.values()).stream()
                    .map(i -> indexService.buildSummary(i, from, to))
                    .collect(java.util.stream.Collectors.toList());
            return ResponseEntity.ok(all);
        }

        IndexName indexName = parseIndex(index);
        if (indexName == null) return ResponseEntity.badRequest().build();

        return ResponseEntity.ok(indexService.buildSummary(indexName, from, to));
    }

    // =========================================================================
    // TRADES
    // =========================================================================

    /**
     * GET /api/index-backtest/trades/NIFTY_50
     * GET /api/index-backtest/trades/NIFTY_50?from=2024-01-01&to=2024-03-31
     *
     * curl "http://localhost:8080/api/index-backtest/trades/BANK_NIFTY?from=2024-01-01&to=2024-01-31"
     */
    @GetMapping("/trades/{index}")
    public ResponseEntity<List<IndexBacktestTrade>> getTrades(
            @PathVariable String index,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        IndexName indexName = parseIndex(index);
        if (indexName == null) return ResponseEntity.badRequest().build();

        List<IndexBacktestTrade> trades = (from != null && to != null)
                ? tradeRepo.findByIndexNameAndTradeDateBetweenOrderByTradeDateAsc(indexName, from, to)
                : tradeRepo.findByIndexNameOrderByTradeDateAsc(indexName);

        return ResponseEntity.ok(trades);
    }

    // =========================================================================
    // CLEAR
    // =========================================================================

    /**
     * DELETE /api/index-backtest/clear/NIFTY_50?from=2024-01-01&to=2024-03-31
     * DELETE /api/index-backtest/clear/all?from=2024-01-01&to=2024-03-31
     *
     * curl -X DELETE "http://localhost:8080/api/index-backtest/clear/all?from=2024-01-01&to=2024-03-31"
     */
    @DeleteMapping("/clear/{index}")
    public ResponseEntity<Map<String, Object>> clear(
            @PathVariable String index,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        List<IndexBacktestTrade> toDelete;

        if ("all".equalsIgnoreCase(index)) {
            toDelete = tradeRepo.findByTradeDateBetweenOrderByTradeDateAsc(from, to);
        } else {
            IndexName indexName = parseIndex(index);
            if (indexName == null) return ResponseEntity.badRequest().build();
            toDelete = tradeRepo.findByIndexNameAndTradeDateBetweenOrderByTradeDateAsc(indexName, from, to);
        }

        tradeRepo.deleteAll(toDelete);
        return ResponseEntity.ok(Map.of(
                "deleted", toDelete.size(),
                "index", index,
                "from", from.toString(),
                "to", to.toString()
        ));
    }

    // =========================================================================
    // Helper
    // =========================================================================

    private IndexName parseIndex(String index) {
        try {
            return IndexName.valueOf(index.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown index: '{}'. Valid values: NIFTY_50, BANK_NIFTY, FIN_NIFTY", index);
            return null;
        }
    }
}