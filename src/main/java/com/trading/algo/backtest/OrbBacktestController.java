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

import com.trading.algo.orb.OrbBacktestService;

@RestController
@RequestMapping("/orb")
public class OrbBacktestController {

    private static final Logger log = LoggerFactory.getLogger(OrbBacktestController.class);

    private final OrbBacktestService backtestService;

    public OrbBacktestController(OrbBacktestService backtestService) {
        this.backtestService = backtestService;
    }

    @GetMapping("/backtest")
    public ResponseEntity<Map<String, Object>> runBacktest(
        @RequestParam(required = false) 
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,

        @RequestParam(required = false) 
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,

        @RequestParam(defaultValue = "1.5") double stopLossPct
    ) {

        LocalDate effectiveTo   = (to != null) ? to : LocalDate.now().minusDays(1);
        LocalDate effectiveFrom = (from != null) ? from : effectiveTo.minusDays(30);

        // ✅ Date validation
        if (effectiveFrom.isAfter(effectiveTo)) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "'from' date must be before 'to' date"
            ));
        }

        // ✅ Stop loss validation
        if (stopLossPct <= 0 || stopLossPct > 10) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "stopLossPct must be between 0 and 10"
            ));
        }

        // ✅ Request ID for tracking
        String requestId = UUID.randomUUID().toString();

        log.info("[ORB-BT][{}] Triggered: {} to {} | SL: {}%",
            requestId, effectiveFrom, effectiveTo, stopLossPct);

        // ✅ Async call (Spring-managed)
        backtestService.runBacktestAsync(effectiveFrom, effectiveTo, stopLossPct, requestId);

        return ResponseEntity.ok(Map.of(
            "status", "started",
            "requestId", requestId,
            "from", effectiveFrom.toString(),
            "to", effectiveTo.toString(),
            "stopLossPct", stopLossPct,
            "message", "Backtest running. Results will be sent to Telegram."
        ));
    }
}