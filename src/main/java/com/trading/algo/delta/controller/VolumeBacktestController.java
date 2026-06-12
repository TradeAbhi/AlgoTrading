package com.trading.algo.delta.controller;

import com.trading.algo.delta.model.VolumeBacktestRequest;
import com.trading.algo.delta.model.VolumeBacktestResult;
import com.trading.algo.delta.service.VolumeBacktestEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for the volume-spike backtest.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * ENDPOINT
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * GET /delta/volume-backtest
 *   ?symbol=BTCUSD
 *   &from=2024-01-01
 *   &to=2024-06-30
 *   &spikeMultiplier=2.0          (volume >= 2x avg → spike, default 2.0)
 *   &climaxMultiplier=3.0         (volume >= 3x avg + trending → climax, default 3.0)
 *   &riskPercent=1.0              (1% of entry price as risk per trade, default 1.0)
 *   &breakoutRR=3.0               (target R:R for BREAKOUT trades, default 3.0)
 *   &absorptionRR=2.0             (target R:R for ABSORPTION trades, default 2.0)
 *   &climaxRR=2.0                 (target R:R for CLIMAX trades, default 2.0)
 *   &slMarginPct=0.15             (SL buffer % beyond candle extreme, default 0.15)
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * SIGNAL TYPES
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * BREAKOUT   : volume >= 2x avg + body >= 50% of range → trade in breakout direction
 * ABSORPTION : volume >= 2x avg + small body (<50% range) → wait for next candle confirm → reversal
 * CLIMAX     : volume >= 3x avg + 5 same-direction candles prior → fade the move
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * EXAMPLES
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * # Default parameters (recommended starting point)
 * curl "http://localhost:8080/delta/volume-backtest?symbol=BTCUSD&from=2024-01-01&to=2024-06-30"
 *
 * # Strict spike filter + aggressive targets
 * curl "http://localhost:8080/delta/volume-backtest?symbol=ETHUSD&from=2024-01-01&to=2024-06-30&spikeMultiplier=2.5&breakoutRR=3.0&absorptionRR=2.5&climaxRR=2.5"
 *
 * # Conservative climax fade only (check climaxStats in response)
 * curl "http://localhost:8080/delta/volume-backtest?symbol=SOLUSD&from=2024-01-01&to=2024-06-30&climaxMultiplier=2.5&climaxRR=2.0&riskPercent=1.0"
 */
@Slf4j
@RestController
@RequestMapping("/delta")
@RequiredArgsConstructor
public class VolumeBacktestController {

    private final VolumeBacktestEngine engine;

    @GetMapping("/volume-backtest")
    public ResponseEntity<Map<String, Object>> runBacktest(
            @RequestParam String symbol,

            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,

            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,

            @RequestParam(defaultValue = "2.0")  double spikeMultiplier,
            @RequestParam(defaultValue = "3.0")  double climaxMultiplier,
            @RequestParam(defaultValue = "1.0")  double riskPercent,
            @RequestParam(defaultValue = "3.0")  double breakoutRR,
            @RequestParam(defaultValue = "2.0")  double absorptionRR,
            @RequestParam(defaultValue = "2.0")  double climaxRR,
            @RequestParam(defaultValue = "0.15") double slMarginPct,
            @RequestParam(defaultValue = "50")   int    srLookback,
            @RequestParam(defaultValue = "3")    int    srPivotStrength,
            @RequestParam(defaultValue = "0.5")  double srProximityPct,
            @RequestParam(defaultValue = "true") boolean srFilterEnabled
    ) {
        LocalDate effectiveTo   = (to   != null) ? to   : LocalDate.now().minusDays(1);
        LocalDate effectiveFrom = (from != null) ? from : effectiveTo.minusDays(30);

        if (effectiveFrom.isAfter(effectiveTo)) {
            return ResponseEntity.badRequest().body(Map.of("error", "'from' date must be before 'to' date"));
        }
        if (spikeMultiplier < 1.0 || spikeMultiplier > 10.0) {
            return ResponseEntity.badRequest().body(Map.of("error", "spikeMultiplier must be between 1.0 and 10.0"));
        }
        if (riskPercent <= 0 || riskPercent > 10.0) {
            return ResponseEntity.badRequest().body(Map.of("error", "riskPercent must be between 0 and 10"));
        }
        if (breakoutRR <= 0 || absorptionRR <= 0 || climaxRR <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "all RR values must be > 0"));
        }

        String requestId = UUID.randomUUID().toString();
        log.info("[VOL-BT][{}] symbol={} {} to {} | spike={}x climax={}x risk={}% RR: BO={} AB={} CL={} | SR: lookback={} strength={} proximity={}% filter={}",
                requestId, symbol, effectiveFrom, effectiveTo,
                spikeMultiplier, climaxMultiplier, riskPercent,
                breakoutRR, absorptionRR, climaxRR,
                srLookback, srPivotStrength, srProximityPct, srFilterEnabled);

        try {
            VolumeBacktestRequest request = VolumeBacktestRequest.builder()
                    .symbol(symbol.toUpperCase())
                    .fromDate(effectiveFrom)
                    .toDate(effectiveTo)
                    .spikeMultiplier(spikeMultiplier)
                    .climaxMultiplier(climaxMultiplier)
                    .riskPercent(riskPercent)
                    .breakoutRR(breakoutRR)
                    .absorptionRR(absorptionRR)
                    .climaxRR(climaxRR)
                    .slMarginPct(slMarginPct)
                    .srLookback(srLookback)
                    .srPivotStrength(srPivotStrength)
                    .srProximityPct(srProximityPct)
                    .srFilterEnabled(srFilterEnabled)
                    .build();

            VolumeBacktestResult result = engine.run(request);

            return ResponseEntity.ok(Map.of(
                    "status",    "completed",
                    "requestId", requestId,
                    "result",    result
            ));

        } catch (Exception e) {
            log.error("[VOL-BT][{}] Backtest failed: {}", requestId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status",    "failed",
                    "requestId", requestId,
                    "error",     e.getMessage()
            ));
        }
    }
}
