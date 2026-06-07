package com.trading.algo.fibostrategy;

import com.trading.algo.dtos.BacktestSummaryDTO;
import com.trading.algo.entity.BacktestTrade;
import com.trading.algo.entity.BacktestTrade.Outcome;
import com.trading.algo.repo.BacktestTradeRepository;
import com.trading.algo.service.UniverseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Backtest REST controller.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * ENDPOINTS
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * RUN
 *   POST /api/backtest/run?from=2024-01-01&to=2024-03-31
 *   POST /api/backtest/run?from=2024-01-01&to=2024-03-31&clearOld=true
 *
 * RESULTS
 *   GET  /api/backtest/summary?from=2024-01-01&to=2024-03-31
 *   GET  /api/backtest/trades?from=2024-01-01&to=2024-03-31
 *   GET  /api/backtest/trades?symbol=RELIANCE
 *   GET  /api/backtest/trades?outcome=TARGET_HIT
 *
 * MANAGE
 *   DELETE /api/backtest/clear?from=2024-01-01&to=2024-03-31
 * ─────────────────────────────────────────────────────────────────────────────
 */
@Slf4j
@RestController
@RequestMapping("/api/backtest")
@RequiredArgsConstructor
public class BacktestController {

    private final BacktestRunnerService   runner;
    private final BacktestTradeRepository tradeRepo;

    // =========================================================================
    // RUN
    // =========================================================================

    /**
     * POST /api/backtest/run?from=2024-01-01&to=2024-03-31&clearOld=false
     *
     * Runs the full Opening Candle Strategy backtest across all F&O stocks.
     * This is a LONG-running operation (~180 symbols × N days × 150ms delay).
     *
     * Estimated time:
     *   1 month  ≈  180 × 22 days = 3,960 API calls ≈ ~10 minutes
     *   3 months ≈  180 × 65 days = 11,700 API calls ≈ ~30 minutes
     *
     * Run asynchronously — results are saved to DB as they come in.
     * Poll GET /api/backtest/summary while it runs to see progress.
     *
     * curl -X POST "http://localhost:8080/api/backtest/run?from=2024-01-01&to=2024-01-31"
     */
    @PostMapping("/run")
    public ResponseEntity<BacktestSummaryDTO> runBacktest(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "false") boolean clearOld) {

        log.info("POST /api/backtest/run — from={} to={} clearOld={}", from, to, clearOld);

        if (from.isAfter(to)) {
            return ResponseEntity.badRequest().build();
        }

        // Limit to 3 months per run to be safe with rate limits
        if (from.plusMonths(3).isBefore(to)) {
            log.warn("Date range > 3 months — consider running in smaller chunks");
        }

        BacktestSummaryDTO summary = runner.run(from, to, clearOld);
        return ResponseEntity.ok(summary);
    }

    // =========================================================================
    // SUMMARY
    // =========================================================================

    /**
     * GET /api/backtest/summary?from=2024-01-01&to=2024-03-31
     *
     * Returns aggregated stats: win rate, avg P&L, top/worst symbols etc.
     * Can be polled while a backtest is running.
     *
     * curl "http://localhost:8080/api/backtest/summary?from=2024-01-01&to=2024-03-31"
     */
    @GetMapping("/summary")
    public ResponseEntity<BacktestSummaryDTO> getSummary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        log.info("GET /api/backtest/summary — from={} to={}", from, to);
        BacktestSummaryDTO summary = runner.buildSummary(from, to, UniverseService.NIFTY_FNO_SYMBOLS.size());
        return ResponseEntity.ok(summary);
    }

    // =========================================================================
    // TRADES
    // =========================================================================

    /**
     * GET /api/backtest/trades?from=2024-01-01&to=2024-03-31
     * GET /api/backtest/trades?symbol=RELIANCE
     * GET /api/backtest/trades?outcome=TARGET_HIT
     *
     * Returns raw trade list. Filter by date range, symbol, or outcome.
     *
     * curl "http://localhost:8080/api/backtest/trades?from=2024-01-01&to=2024-01-31"
     * curl "http://localhost:8080/api/backtest/trades?symbol=HDFCBANK"
     * curl "http://localhost:8080/api/backtest/trades?outcome=SL_HIT"
     */
    @GetMapping("/trades")
    public ResponseEntity<List<BacktestTrade>> getTrades(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) Outcome outcome) {

        List<BacktestTrade> result;

        if (symbol != null && from != null && to != null) {
            result = tradeRepo.findBySymbolAndTradeDateBetween(symbol.toUpperCase(), from, to);
        } else if (symbol != null) {
            result = tradeRepo.findBySymbolOrderByTradeDateAsc(symbol.toUpperCase());
        } else if (outcome != null) {
            result = tradeRepo.findByOutcome(outcome);
        } else if (from != null && to != null) {
            result = tradeRepo.findByTradeDateBetweenOrderByTradeDateAsc(from, to);
        } else {
            result = tradeRepo.findAll();
        }

        return ResponseEntity.ok(result);
    }

    // =========================================================================
    // MANAGE
    // =========================================================================

    /**
     * DELETE /api/backtest/clear?from=2024-01-01&to=2024-03-31
     *
     * Clears all backtest results in the given date range.
     * Useful before re-running with different parameters.
     *
     * curl -X DELETE "http://localhost:8080/api/backtest/clear?from=2024-01-01&to=2024-03-31"
     */
    @DeleteMapping("/clear")
    public ResponseEntity<Map<String, Object>> clearResults(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        log.info("DELETE /api/backtest/clear — from={} to={}", from, to);
        List<BacktestTrade> toDelete = tradeRepo.findByTradeDateBetweenOrderByTradeDateAsc(from, to);
        tradeRepo.deleteAll(toDelete);
        return ResponseEntity.ok(Map.of(
                "deleted", toDelete.size(),
                "from", from.toString(),
                "to", to.toString()
        ));
    }
}
