package com.trading.algo.delta.controller;

import com.trading.algo.delta.model.BacktestRequest;
import com.trading.algo.delta.model.BacktestResult;
import com.trading.algo.delta.service.BacktestEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * REST trigger for delta backtest.
 *
 * GET /delta/backtest?symbol=BTCUSD&from=2024-01-01&to=2024-06-30&slMarginPct=0.15&riskRewardRatio=3.0&partialExitRR=2.0&partialExitQtyPct=50.0
 *
 * Returns backtest results synchronously.
 */
@RestController
@RequestMapping("/delta")
public class DeltaBacktestController {

    private static final Logger log = LoggerFactory.getLogger(DeltaBacktestController.class);

    private final BacktestEngine backtestEngine;

    public DeltaBacktestController(BacktestEngine backtestEngine) {
        this.backtestEngine = backtestEngine;
    }

    @GetMapping("/backtest")
    public ResponseEntity<Map<String, Object>> runBacktest(
        @RequestParam String symbol,

        @RequestParam(required = false) 
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,

        @RequestParam(required = false) 
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,

        @RequestParam(defaultValue = "0.15") double slMarginPct,

        @RequestParam(defaultValue = "3.0") double riskRewardRatio,

        @RequestParam(defaultValue = "2.0") double partialExitRR,

        @RequestParam(defaultValue = "50.0") double partialExitQtyPct
    ) {
        LocalDate effectiveTo   = (to != null) ? to : LocalDate.now().minusDays(1);
        LocalDate effectiveFrom = (from != null) ? from : effectiveTo.minusDays(30);

        // Date validation
        if (effectiveFrom.isAfter(effectiveTo)) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "'from' date must be before 'to' date"
            ));
        }

        // Parameter validation
        if (slMarginPct < 0 || slMarginPct > 10) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "slMarginPct must be between 0 and 10"
            ));
        }

        if (riskRewardRatio <= 0 || riskRewardRatio > 20) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "riskRewardRatio must be between 0 and 20"
            ));
        }

        if (partialExitRR <= 0 || partialExitRR > 20) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "partialExitRR must be between 0 and 20"
            ));
        }

        if (partialExitQtyPct <= 0 || partialExitQtyPct > 100) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "partialExitQtyPct must be between 0 and 100"
            ));
        }

        String requestId = UUID.randomUUID().toString();
        log.info("[DELTA-BT][{}] Triggered: symbol={} {} to {} | SL: {}% | RR: {} | PartialRR: {} | PartialQty: {}%",
            requestId, symbol, effectiveFrom, effectiveTo, slMarginPct, riskRewardRatio, partialExitRR, partialExitQtyPct);

        try {
            BacktestRequest request = BacktestRequest.builder()
                .symbol(symbol.toUpperCase())
                .fromDate(effectiveFrom)
                .toDate(effectiveTo)
                .slMarginPct(slMarginPct)
                .riskRewardRatio(riskRewardRatio)
                .partialExitRR(partialExitRR)
                .partialExitQtyPct(partialExitQtyPct)
                .build();

            BacktestResult result = backtestEngine.run(request);

            return ResponseEntity.ok(Map.of(
                "status", "completed",
                "requestId", requestId,
                "result", result
            ));

        } catch (Exception e) {
            log.error("[DELTA-BT][{}] Backtest failed: {}", requestId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                "status", "failed",
                "requestId", requestId,
                "error", e.getMessage()
            ));
        }
    }
}
