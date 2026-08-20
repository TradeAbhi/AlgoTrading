package com.trading.algo.fibostrategy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/fibo/backtest/nifty")
public class FiboNiftyBacktestController {

    private final FiboBacktestEngine backtestEngine;
    private final FiboParameterSweepEngine sweepEngine;

    // Top 10 Nifty stocks by market cap
    private static final List<String> TOP_10_NIFTY = List.of(
            "RELIANCE",
            "TCS",
            "HDFCBANK",
            "INFY",
            "ICICIBANK",
            "HINDUNILVR",
            "SBIN",
            "BHARTIARTL",
            "ITC",
            "KOTAKBANK"
    );

    @GetMapping
    public ResponseEntity<?> backtest(
            @RequestParam String symbol,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        log.info("Fibonacci Nifty Backtest requested | symbol={} from={} to={}", symbol, from, to);

        FiboBacktestEngine.FiboBacktestResult result = backtestEngine.runBacktest(symbol, from, to);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/nifty-top10")
    public ResponseEntity<?> niftyTop10(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        log.info("Fibonacci Backtest for Top 10 Nifty stocks | from={} to={}", from, to);

        Map<String, FiboBacktestEngine.FiboBacktestResult> results = 
                backtestEngine.runBacktest(TOP_10_NIFTY, from, to);

        return ResponseEntity.ok(results);
    }

    /** Combined top-10 Nifty profitability split by previous-day-level category. */
    @GetMapping("/category-summary")
    public ResponseEntity<?> categorySummary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Map<String, FiboBacktestEngine.FiboBacktestResult> results =
                backtestEngine.runBacktest(TOP_10_NIFTY, from, to);
        List<com.trading.algo.entity.BacktestTrade> trades = results.values().stream()
                .flatMap(result -> result.getTrades().stream())
                .toList();
        return ResponseEntity.ok(FiboCategoryPerformance.summarize(trades));
    }

    @GetMapping("/nifty-dynamic-check")
    public ResponseEntity<?> niftyOptimize(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        log.info("Fibonacci Parameter Sweep for Top 10 Nifty stocks | from={} to={}", from, to);

        FiboParameterSweepEngine.SweepResult result = sweepEngine.runSweep(TOP_10_NIFTY, from, to);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/dynamic-check")
    public ResponseEntity<?> optimize(
            @RequestParam List<String> symbols,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        log.info("Fibonacci Parameter Sweep for custom Nifty symbols | symbols={} from={} to={}",
                symbols, from, to);

        FiboParameterSweepEngine.SweepResult result = sweepEngine.runSweep(symbols, from, to);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/dynamic-check-summary")
    public ResponseEntity<?> optimizeSummary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "50") int topN) {

        log.info("Fibonacci Parameter Sweep Summary for Top 10 Nifty stocks | from={} to={} topN={}",
                from, to, topN);

        FiboParameterSweepEngine.SweepResult results = sweepEngine.runSweep(TOP_10_NIFTY, from, to);

        List<FiboParameterSweepEngine.ParameterSweepSummaryRow> summary =
                sweepEngine.toSummary(results.getResults(), topN);

        return ResponseEntity.ok(summary);
    }

    /** Parameter-sweep rankings for one selected category in the top-10 Nifty universe. */
    @GetMapping("/dynamic-check-category-summary")
    public ResponseEntity<?> optimizeCategorySummary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam FiboPreviousDayCategory category,
            @RequestParam(defaultValue = "50") int topN) {
        FiboParameterSweepEngine.SweepResult results = sweepEngine.runSweep(TOP_10_NIFTY, from, to);
        return ResponseEntity.ok(sweepEngine.toCategorySummary(results.getResults(), category, topN));
    }
}
