package com.trading.algo.fibostrategy;

import com.trading.algo.service.UniverseService;
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
@RequestMapping("/api/fibo/backtest")
public class FiboBacktestController {

    private final FiboBacktestEngine backtestEngine;
    private final FiboParameterSweepEngine sweepEngine;

    // FNO Stocks list
    private static final List<String> FNO_STOCKS = List.of(
            "RELIANCE", "HDFCBANK", "ICICIBANK", "INFY", "TCS",
            "HINDUNILVR", "ITC", "SBIN", "BHARTIARTL", "KOTAKBANK",
            "LICI", "AXISBANK", "BAJFINANCE", "MARUTI", "LT",
            "HCLTECH", "ASIANPAINT", "TATAMOTORS", "SUNPHARMA", "TITAN",
            "WIPRO", "NTPC", "ULTRACEMCO", "POWERGRID", "ONGC",
            "TATASTEEL", "JSWSTEEL", "COALINDIA", "BAJAJFINSV", "DMART"
    );

    @GetMapping
    public ResponseEntity<?> backtest(
            @RequestParam String symbol,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        log.info("Fibonacci Backtest requested | symbol={} from={} to={}", symbol, from, to);

        FiboBacktestEngine.FiboBacktestResult result = backtestEngine.runBacktest(symbol, from, to);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/fno-all")
    public ResponseEntity<?> fnoAll(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        log.info("Fibonacci Backtest for all FNO stocks | from={} to={}", from, to);

        Map<String, FiboBacktestEngine.FiboBacktestResult> results = 
                backtestEngine.runBacktest(FNO_STOCKS, from, to);

        return ResponseEntity.ok(results);
    }

    @GetMapping("/fno-dynamic-check")
    public ResponseEntity<?> fnoOptimize(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        log.info("Fibonacci Parameter Sweep for FNO stocks | from={} to={}", from, to);

        FiboParameterSweepEngine.SweepResult result = sweepEngine.runSweep(FNO_STOCKS, from, to);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/dynamic-check")
    public ResponseEntity<?> optimize(
            @RequestParam List<String> symbols,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        log.info("Fibonacci Parameter Sweep for custom symbols | symbols={} from={} to={}",
                symbols, from, to);

        FiboParameterSweepEngine.SweepResult result = sweepEngine.runSweep(symbols, from, to);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/dynamic-check-summary")
    public ResponseEntity<?> optimizeSummary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "50") int topN) {

        log.info("Fibonacci Parameter Sweep Summary for FNO stocks | from={} to={} topN={}",
                from, to, topN);

        FiboParameterSweepEngine.SweepResult results = sweepEngine.runSweep(FNO_STOCKS, from, to);

        List<FiboParameterSweepEngine.ParameterSweepSummaryRow> summary =
                sweepEngine.toSummary(results.getResults(), topN);

        return ResponseEntity.ok(summary);
    }
}
