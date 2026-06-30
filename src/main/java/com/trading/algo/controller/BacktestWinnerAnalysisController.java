package com.trading.algo.controller;

import com.trading.algo.service.BacktestWinnerAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/backtest-winner-analysis")
@RequiredArgsConstructor
public class BacktestWinnerAnalysisController {

    private final BacktestWinnerAnalysisService analysisService;

    /**
     * Analyze winners for a specific strategy and date range
     * @param strategyName Strategy identifier (e.g., "ORB", "DELTA", "FIBO", "IPO")
     * @param from Start date (defaults to today)
     * @param to End date (defaults to today)
     */
    @PostMapping("/analyze")
    public ResponseEntity<Map<String, Object>> analyze(
            @RequestParam String strategyName,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to) {

        LocalDate targetFrom = from != null ? from : LocalDate.now();
        LocalDate targetTo = to != null ? to : LocalDate.now();

        log.info("POST /api/backtest-winner-analysis/analyze - strategy={}, from={}, to={}",
                strategyName, targetFrom, targetTo);

        analysisService.analyzeWinners(strategyName, targetFrom, targetTo);

        return ResponseEntity.ok(Map.of(
                "status", "Winner analysis complete",
                "strategy", strategyName,
                "from", targetFrom.toString(),
                "to", targetTo.toString()
        ));
    }

    /**
     * Analyze today's winners for a specific strategy
     * @param strategyName Strategy identifier (e.g., "ORB", "DELTA", "FIBO", "IPO")
     */
    @PostMapping("/analyze-today")
    public ResponseEntity<Map<String, String>> analyzeToday(
            @RequestParam String strategyName) {

        log.info("POST /api/backtest-winner-analysis/analyze-today - strategy={}", strategyName);
        analysisService.analyzeTodayWinners(strategyName);

        return ResponseEntity.ok(Map.of(
                "status", "Today's winner analysis complete",
                "strategy", strategyName,
                "date", LocalDate.now().toString()
        ));
    }
}
