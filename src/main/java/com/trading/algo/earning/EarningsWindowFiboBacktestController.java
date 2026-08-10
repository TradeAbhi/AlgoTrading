package com.trading.algo.earning;

import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/earnings-window-fibo")
@RequiredArgsConstructor
public class EarningsWindowFiboBacktestController {

    private final EarningsService earningsService;
    private final EarningsWindowFiboBacktestService backtestService;

    /**
     * Imports the complete earnings window needed for a requested backtest
     * period: ten days before the start through three days after the end.
     */
    @PostMapping("/historical-earnings")
    public ResponseEntity<Map<String, Object>> importHistoricalEarnings(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate importFrom = from.minusDays(10);
        LocalDate importTo = to.plusDays(3);
        return ResponseEntity.ok(Map.of(
                "stored", earningsService.fetchAndStoreHistoricalEarnings(importFrom, importTo),
                "importFrom", importFrom,
                "importTo", importTo));
    }

    /** Runs the confirmed C1/C2 breakout strategy for one date or a date range. */
    @GetMapping("/backtest")
    public ResponseEntity<EarningsWindowFiboBacktestService.BacktestReport> backtest(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(backtestService.run(from, to));
    }
}
