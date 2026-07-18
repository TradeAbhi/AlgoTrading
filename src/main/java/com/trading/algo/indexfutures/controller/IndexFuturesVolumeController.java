package com.trading.algo.indexfutures.controller;

import com.trading.algo.indexfutures.model.IndexFuturesVolumeBacktestResult;
import com.trading.algo.indexfutures.model.IndexFuturesVolumeScanRequest;
import com.trading.algo.indexfutures.service.IndexFuturesVolumeBacktestEngine;
import com.trading.algo.indexfutures.service.IndexFuturesVolumeScannerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * REST controller for the Index Futures Volume Spike strategy.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * ENDPOINTS
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * GET /index-futures/volume-backtest
 *   Run backtest for a single instrument.
 *   ?instrumentKey=NSE_INDEX|Nifty 50
 *   &label=NIFTY FUT
 *   &from=2024-01-01
 *   &to=2024-06-30
 *   &spikeMultiplier=2.0
 *   &climaxMultiplier=3.0
 *   &riskPercent=0.5
 *   &breakoutRR=3.0
 *   &absorptionRR=2.0
 *   &climaxRR=2.0
 *   &slMarginPct=0.1
 *   &signalAfterHour=9
 *   &signalBeforeHour=15
 *
 * GET /index-futures/volume-backtest/all
 *   Run backtest for both Nifty and Bank Nifty with same parameters.
 *
 * POST /index-futures/volume-scan
 *   Manually trigger the live volume scan (sends Telegram alerts).
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * SIGNAL TYPES
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * BREAKOUT   : volume >= 2x avg + body >= 50% of range -> trade in candle direction
 * ABSORPTION : volume >= 2x avg + small body -> wait for next candle confirm -> reversal
 * CLIMAX     : volume >= 3x avg + 5 same-direction candles -> fade the move
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * EXAMPLES
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * # Nifty backtest with defaults
 * curl "http://localhost:8080/index-futures/volume-backtest?instrumentKey=NSE_INDEX|Nifty 50&label=NIFTY FUT&from=2024-01-01&to=2024-06-30"
 *
 * # Bank Nifty backtest, skip first 45 min
 * curl "http://localhost:8080/index-futures/volume-backtest?instrumentKey=NSE_INDEX|Nifty Bank&label=BANKNIFTY FUT&from=2024-01-01&to=2024-06-30&signalAfterHour=10"
 *
 * # Both indices
 * curl "http://localhost:8080/index-futures/volume-backtest/all?from=2024-01-01&to=2024-06-30"
 *
 * # Manual scan trigger
 * curl -X POST "http://localhost:8080/index-futures/volume-scan"
 */
@Slf4j
@RestController
@RequestMapping("/index-futures")
@RequiredArgsConstructor
public class IndexFuturesVolumeController {

    private final IndexFuturesVolumeBacktestEngine  backtestEngine;
    private final IndexFuturesVolumeScannerService  scannerService;

    // -------------------------------------------------------------------------
    // Single instrument backtest
    // -------------------------------------------------------------------------

    @GetMapping("/volume-backtest")
    public ResponseEntity<Map<String, Object>> runBacktest(
            @RequestParam String instrumentKey,
            @RequestParam(defaultValue = "INDEX FUT") String label,

            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,

            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,

            @RequestParam(defaultValue = "2.0")  double spikeMultiplier,
            @RequestParam(defaultValue = "3.0")  double climaxMultiplier,
            @RequestParam(defaultValue = "0.5")  double riskPercent,
            @RequestParam(defaultValue = "3.0")  double breakoutRR,
            @RequestParam(defaultValue = "2.0")  double absorptionRR,
            @RequestParam(defaultValue = "2.0")  double climaxRR,
            @RequestParam(defaultValue = "0.1")  double slMarginPct,
            @RequestParam(defaultValue = "9")    int    signalAfterHour,
            @RequestParam(defaultValue = "15")   int    signalBeforeHour
    ) {
        LocalDate effectiveTo   = to   != null ? to   : LocalDate.now().minusDays(1);
        LocalDate effectiveFrom = from != null ? from : effectiveTo.minusDays(30);

        if (effectiveFrom.isAfter(effectiveTo))
            return ResponseEntity.badRequest().body(Map.of("error", "'from' must be before 'to'"));

        try {
            IndexFuturesVolumeScanRequest req = IndexFuturesVolumeScanRequest.builder()
                    .instrumentKey(instrumentKey)
                    .label(label)
                    .fromDate(effectiveFrom)
                    .toDate(effectiveTo)
                    .spikeMultiplier(spikeMultiplier)
                    .climaxMultiplier(climaxMultiplier)
                    .riskPercent(riskPercent)
                    .breakoutRR(breakoutRR)
                    .absorptionRR(absorptionRR)
                    .climaxRR(climaxRR)
                    .slMarginPct(slMarginPct)
                    .signalAfterHour(signalAfterHour)
                    .signalBeforeHour(signalBeforeHour)
                    .build();

            IndexFuturesVolumeBacktestResult result = backtestEngine.run(req);
            return ResponseEntity.ok(Map.of("status", "completed", "result", result));

        } catch (Exception e) {
            log.error("Index futures backtest failed for {}: {}", label, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("status", "failed", "error", e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // Both Nifty + Bank Nifty backtest in one call
    // -------------------------------------------------------------------------

    @GetMapping("/volume-backtest/all")
    public ResponseEntity<Map<String, Object>> runBacktestAll(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,

            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,

            @RequestParam(defaultValue = "2.0")  double spikeMultiplier,
            @RequestParam(defaultValue = "3.0")  double climaxMultiplier,
            @RequestParam(defaultValue = "0.5")  double riskPercent,
            @RequestParam(defaultValue = "3.0")  double breakoutRR,
            @RequestParam(defaultValue = "2.0")  double absorptionRR,
            @RequestParam(defaultValue = "2.0")  double climaxRR,
            @RequestParam(defaultValue = "0.1")  double slMarginPct,
            @RequestParam(defaultValue = "9")    int    signalAfterHour,
            @RequestParam(defaultValue = "15")   int    signalBeforeHour
    ) {
        LocalDate effectiveTo   = to   != null ? to   : LocalDate.now().minusDays(1);
        LocalDate effectiveFrom = from != null ? from : effectiveTo.minusDays(30);

        record InstrumentDef(String key, String label) {}
        List<InstrumentDef> instruments = List.of(
                new InstrumentDef("NSE_INDEX|Nifty 50",   "NIFTY FUT"),
                new InstrumentDef("NSE_INDEX|Nifty Bank", "BANKNIFTY FUT")
        );

        try {
            List<IndexFuturesVolumeBacktestResult> results = instruments.stream().map(inst -> {
                IndexFuturesVolumeScanRequest req = IndexFuturesVolumeScanRequest.builder()
                        .instrumentKey(inst.key())
                        .label(inst.label())
                        .fromDate(effectiveFrom)
                        .toDate(effectiveTo)
                        .spikeMultiplier(spikeMultiplier)
                        .climaxMultiplier(climaxMultiplier)
                        .riskPercent(riskPercent)
                        .breakoutRR(breakoutRR)
                        .absorptionRR(absorptionRR)
                        .climaxRR(climaxRR)
                        .slMarginPct(slMarginPct)
                        .signalAfterHour(signalAfterHour)
                        .signalBeforeHour(signalBeforeHour)
                        .build();
                return backtestEngine.run(req);
            }).toList();

            return ResponseEntity.ok(Map.of("status", "completed", "results", results));

        } catch (Exception e) {
            log.error("Index futures backtest/all failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("status", "failed", "error", e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // Manual live scan trigger
    // -------------------------------------------------------------------------

    @PostMapping("/volume-scan")
    public ResponseEntity<Map<String, Object>> triggerScan() {
        log.info("Manual index futures volume scan triggered");
        try {
            scannerService.runManualScan();
            return ResponseEntity.ok(Map.of("status", "scan triggered"));
        } catch (Exception e) {
            log.error("Manual scan failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("status", "failed", "error", e.getMessage()));
        }
    }
}
