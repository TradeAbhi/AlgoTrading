package com.trading.algo.backtest;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.trading.algo.weekly.WeeklyBreakoutBacktestService;

/**
 * REST trigger for weekly breakout backtest.
 *
 * GET /weekly/backtest?from=2025-01-01&to=2025-06-30&stopLossPct=2.0
 *
 * Returns immediately with "started" status.
 * Results sent to Telegram when complete.
 */
@RestController
@RequestMapping("/weekly")
public class WeeklyBreakoutBacktestController {

    private static final Logger log = LoggerFactory.getLogger(WeeklyBreakoutBacktestController.class);

    private final WeeklyBreakoutBacktestService backtestService;

    public WeeklyBreakoutBacktestController(WeeklyBreakoutBacktestService backtestService) {
        this.backtestService = backtestService;
    }

    @GetMapping("/backtest")
    public ResponseEntity<Map<String, Object>> runBacktest(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(defaultValue = "2.0") double stopLossPct
    ) {
        LocalDate effectiveTo   = (to   != null) ? to   : LocalDate.now().minusDays(1);
        LocalDate effectiveFrom = (from != null) ? from : effectiveTo.minusWeeks(12);

        if (effectiveFrom.isAfter(effectiveTo)) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "'from' date must be before 'to' date"
            ));
        }
        if (stopLossPct <= 0 || stopLossPct > 10) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "stopLossPct must be between 0 and 10"
            ));
        }

        String requestId = UUID.randomUUID().toString();
        log.info("[WEEKLY-BT][{}] Triggered: {} to {} | SL: {}%",
            requestId, effectiveFrom, effectiveTo, stopLossPct);

        final LocalDate finalFrom = effectiveFrom;
        final LocalDate finalTo   = effectiveTo;
        backtestService.runBacktestAsync(finalFrom, finalTo, stopLossPct, requestId);

        return ResponseEntity.ok(Map.of(
            "status",      "started",
            "requestId",   requestId,
            "from",        effectiveFrom.toString(),
            "to",          effectiveTo.toString(),
            "stopLossPct", stopLossPct,
            "message",     "Weekly backtest running. Results will be sent to Telegram."
        ));
    }
}