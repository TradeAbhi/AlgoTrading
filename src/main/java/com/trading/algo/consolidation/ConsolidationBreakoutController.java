package com.trading.algo.consolidation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * REST controller for the Consolidation Breakout strategy.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * BACKTEST ENDPOINTS
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * GET /consolidation/backtest
 *   Backtest a single instrument on a single timeframe.
 *   ?instrumentKey=NSE_INDEX|Nifty 50
 *   &label=NIFTY 50
 *   &from=2025-01-01          (or omit for today only)
 *   &to=2025-06-30            (or omit for today only)
 *   &timeframe=15m            (15m or 5m, default 15m)
 *   &maxRangePct=0.40         (consolidation tightness, default 0.40 for 15m / 0.30 for 5m)
 *   &targetRR=2.0             (R multiple for target, default 2.0)
 *
 * GET /consolidation/backtest/today
 *   Run backtest for TODAY only — both Nifty 50 and Bank Nifty, both timeframes.
 *   Useful for end-of-day review of what signals fired today.
 *   ?targetRR=2.0
 *
 * GET /consolidation/backtest/all
 *   Run backtest for both Nifty 50 and Bank Nifty, both timeframes, over a date range.
 *   ?from=2025-01-01&to=2025-06-30&targetRR=2.0
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * LIVE SCAN ENDPOINT
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * POST /consolidation/scan
 *   Manually trigger live scan (sends Telegram alerts).
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * EXAMPLES
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * # Today's backtest — quick end-of-day review
 * curl "http://localhost:8080/consolidation/backtest/today"
 *
 * # Nifty 15-min backtest for last 30 days
 * curl "http://localhost:8080/consolidation/backtest?instrumentKey=NSE_INDEX|Nifty 50&label=NIFTY 50&timeframe=15m&from=2025-05-01&to=2025-05-31"
 *
 * # Both indices, both timeframes, full date range
 * curl "http://localhost:8080/consolidation/backtest/all?from=2025-01-01&to=2025-05-31&targetRR=2.0"
 */
@Slf4j
@RestController
@RequestMapping("/consolidation")
@RequiredArgsConstructor
public class ConsolidationBreakoutController {

    private final ConsolidationBreakoutService  scannerService;
    private final ConsolidationBacktestEngine   backtestEngine;

    // ── Instrument definitions ────────────────────────────────────────────────

    private record InstrumentDef(String label, String instrumentKey) {}

    private static final List<InstrumentDef> ALL_INSTRUMENTS = List.of(
            new InstrumentDef("NIFTY 50",   "NSE_INDEX|Nifty 50"),
            new InstrumentDef("BANK NIFTY", "NSE_INDEX|Nifty Bank")
    );

    // ── Single instrument backtest ────────────────────────────────────────────

    @GetMapping("/backtest")
    public ResponseEntity<Map<String, Object>> runBacktest(
            @RequestParam String instrumentKey,
            @RequestParam(defaultValue = "INDEX") String label,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "15m")  String timeframe,
            @RequestParam(defaultValue = "-1")   double maxRangePct,
            @RequestParam(defaultValue = "2.0")  double targetRR,
            // confirmPct: % of zone width the close must exceed beyond the boundary.
            // 15m default = 0.05 (5%)  — zones are wider, smaller buffer needed.
            // 5m  default = 0.15 (15%) — zones are tighter, proportionate buffer.
            // Set to 0.0 to disable the filter entirely.
            @RequestParam(defaultValue = "-1")   double confirmPct,   // -1 = use timeframe default
            // closeDistanceThreshold: minimum distance from PDC required to take a trade.
            // Nifty default = 40.0, Bank Nifty default = 70.0
            // Set to 0.0 to disable the filter entirely.
            @RequestParam(defaultValue = "-1")   double closeDistanceThreshold
    ) {
        LocalDate effectiveTo   = to   != null ? to   : LocalDate.now();
        LocalDate effectiveFrom = from != null ? from : effectiveTo;

        if (effectiveFrom.isAfter(effectiveTo))
            return ResponseEntity.badRequest().body(Map.of("error", "'from' must be before or equal to 'to'"));

        boolean is15m           = !"5m".equalsIgnoreCase(timeframe);
        double  effectiveRange  = maxRangePct > 0 ? maxRangePct : (is15m ? 0.40 : 0.30);
        double  effectiveConfirm = confirmPct >= 0 ? confirmPct : (is15m ? 0.05 : 0.15);
        // Use provided threshold or default based on index
        double  effectiveCloseDist = closeDistanceThreshold >= 0 ? closeDistanceThreshold :
                (label.equals("NIFTY 50") ? 40.0 : 70.0);

        try {
            ConsolidationBacktestResult result = backtestEngine.run(
                    label, instrumentKey, effectiveFrom, effectiveTo,
                    is15m, effectiveRange, targetRR, effectiveConfirm, effectiveCloseDist);

            return ResponseEntity.ok(Map.of("status", "completed", "result", result));
        } catch (Exception e) {
            log.error("Consolidation backtest failed for {}: {}", label, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "failed", "error", e.getMessage()));
        }
    }

    // ── Today's backtest — both instruments, both timeframes ──────────────────

    @GetMapping("/backtest/today")
    public ResponseEntity<Map<String, Object>> runTodayBacktest(
            @RequestParam(defaultValue = "2.0")  double targetRR,
            // Per-timeframe confirm defaults: 15m=0.05, 5m=0.15
            // Override with confirm15m=0.0 or confirm5m=0.0 to disable per timeframe
            @RequestParam(defaultValue = "0.05") double confirm15m,
            @RequestParam(defaultValue = "0.15") double confirm5m,
            // closeDistanceThreshold: minimum distance from PDC required to take a trade
            // Set to 0.0 to disable, or provide custom value
            @RequestParam(defaultValue = "-1") double closeDistanceThreshold
    ) {
        LocalDate today = LocalDate.now();
        log.info("Today's consolidation backtest | confirm15m={} confirm5m={} closeDistThreshold={}",
                confirm15m, confirm5m, closeDistanceThreshold);

        try {
            List<ConsolidationBacktestResult> results = new ArrayList<>();
            for (InstrumentDef inst : ALL_INSTRUMENTS) {
                // Use provided threshold or default based on index
                double niftyThreshold = closeDistanceThreshold >= 0 ? closeDistanceThreshold : 40.0;
                double bankNiftyThreshold = closeDistanceThreshold >= 0 ? closeDistanceThreshold : 70.0;

                results.add(backtestEngine.run(
                        inst.label(), inst.instrumentKey(), today, today,
                        true, 0.40, targetRR, confirm15m,
                        inst.label().equals("NIFTY 50") ? niftyThreshold : bankNiftyThreshold));
                results.add(backtestEngine.run(
                        inst.label(), inst.instrumentKey(), today, today,
                        false, 0.30, targetRR, confirm5m,
                        inst.label().equals("NIFTY 50") ? niftyThreshold : bankNiftyThreshold));
            }

            return ResponseEntity.ok(Map.of(
                    "status",  "completed",
                    "date",    today.toString(),
                    "results", results
            ));
        } catch (Exception e) {
            log.error("Today's consolidation backtest failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "failed", "error", e.getMessage()));
        }
    }

    // ── Both instruments, both timeframes, date range ─────────────────────────

    @GetMapping("/backtest/all")
    public ResponseEntity<Map<String, Object>> runAllBacktest(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "2.0")  double targetRR,
            @RequestParam(defaultValue = "0.05") double confirm15m,
            @RequestParam(defaultValue = "0.15") double confirm5m,
            // closeDistanceThreshold: minimum distance from PDC required to take a trade
            // Set to 0.0 to disable, or provide custom value
            @RequestParam(defaultValue = "-1") double closeDistanceThreshold
    ) {
        LocalDate effectiveTo   = to   != null ? to   : LocalDate.now();
        LocalDate effectiveFrom = from != null ? from : effectiveTo.minusDays(30);

        log.info("Consolidation backtest/all from={} to={} targetRR={} confirm15m={} confirm5m={} closeDistThreshold={}",
                effectiveFrom, effectiveTo, targetRR, confirm15m, confirm5m, closeDistanceThreshold);

        try {
            List<ConsolidationBacktestResult> results = new ArrayList<>();
            for (InstrumentDef inst : ALL_INSTRUMENTS) {
                // Use provided threshold or default based on index
                double niftyThreshold = closeDistanceThreshold >= 0 ? closeDistanceThreshold : 40.0;
                double bankNiftyThreshold = closeDistanceThreshold >= 0 ? closeDistanceThreshold : 70.0;

                results.add(backtestEngine.run(
                        inst.label(), inst.instrumentKey(), effectiveFrom, effectiveTo,
                        true, 0.40, targetRR, confirm15m,
                        inst.label().equals("NIFTY 50") ? niftyThreshold : bankNiftyThreshold));
                results.add(backtestEngine.run(
                        inst.label(), inst.instrumentKey(), effectiveFrom, effectiveTo,
                        false, 0.30, targetRR, confirm5m,
                        inst.label().equals("NIFTY 50") ? niftyThreshold : bankNiftyThreshold));
            }

            return ResponseEntity.ok(Map.of("status", "completed", "results", results));
        } catch (Exception e) {
            log.error("Consolidation backtest/all failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "failed", "error", e.getMessage()));
        }
    }

    // ── Manual live scan trigger ──────────────────────────────────────────────

    @PostMapping("/scan")
    public ResponseEntity<Map<String, Object>> triggerScan() {
        log.info("Manual consolidation breakout scan triggered");
        try {
            scannerService.runManualScan();
            return ResponseEntity.ok(Map.of(
                    "status",  "triggered",
                    "message", "Consolidation scan running for Nifty 50 and Bank Nifty on 5m and 15m"
            ));
        } catch (Exception e) {
            log.error("Manual consolidation scan failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "failed", "error", e.getMessage()));
        }
    }
}
