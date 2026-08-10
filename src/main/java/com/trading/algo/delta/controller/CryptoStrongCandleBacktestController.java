package com.trading.algo.delta.controller;

import com.trading.algo.delta.model.CryptoStrongCandleBacktestReport;
import com.trading.algo.delta.service.CryptoStrongCandleBacktestEngine;
import com.trading.algo.delta.service.CryptoStrongCandleParameterSweepEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/crypto/backtest/strong-candle")
public class CryptoStrongCandleBacktestController {

    private final CryptoStrongCandleBacktestEngine backtestEngine;

    @GetMapping
    public ResponseEntity<?> backtest(

            @RequestParam
            String symbol,

            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,

            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to) {

        log.info("Strong Candle Backtest requested | symbol={} | from={} | to={}",
                symbol,
                from,
                to);

        CryptoStrongCandleBacktestReport report =
                backtestEngine.runBacktest(
                        symbol,
                        from,
                        to);

        return ResponseEntity.ok(report);

    }

    @GetMapping("/btc")
    public ResponseEntity<?> btc(

            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,

            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to) {

        return ResponseEntity.ok(
                backtestEngine.runBacktest(
                        "BTCUSD",
                        from,
                        to));

    }

    @GetMapping("/eth")
    public ResponseEntity<?> eth(

            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,

            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to) {

        return ResponseEntity.ok(
                backtestEngine.runBacktest(
                        "ETHUSD",
                        from,
                        to));

    }

    @GetMapping("/sol")
    public ResponseEntity<?> sol(

            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,

            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to) {

        return ResponseEntity.ok(
                backtestEngine.runBacktest(
                        "SOLUSD",
                        from,
                        to));

    }

    @GetMapping("/all")
    public ResponseEntity<?> all(

            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,

            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to) {

        Map<String, CryptoStrongCandleBacktestReport> reports =
                new LinkedHashMap<>();

        reports.put(
                "BTC",
                backtestEngine.runBacktest(
                        "BTCUSD",
                        from,
                        to));

        reports.put(
                "ETH",
                backtestEngine.runBacktest(
                        "ETHUSD",
                        from,
                        to));




        reports.put(
                "BNB",
                backtestEngine.runBacktest(
                        "BNBUSD",
                        from,
                        to));


        return ResponseEntity.ok(reports);

    }

    private final CryptoStrongCandleParameterSweepEngine optimizer;

    @GetMapping("/dynamic-check")
    public ResponseEntity<?> optimize(
            @RequestParam LocalDate from,
            @RequestParam LocalDate to) {

        List<CryptoStrongCandleParameterSweepEngine.ParameterCombinationResult> results =
                optimizer.runSweep(
                        List.of("BTCUSD","ETHUSD","BNBUSD"),
                        from,
                        to);

        optimizer.printSummary(results);

        return ResponseEntity.ok(results);
    }

    @GetMapping("/dynamic-check-summary")
    public ResponseEntity<?> optimizeSummary(
            @RequestParam LocalDate from,
            @RequestParam LocalDate to,
            @RequestParam(defaultValue = "50") int topN) {

        List<CryptoStrongCandleParameterSweepEngine.ParameterCombinationResult> results =
                optimizer.runSweep(
                        List.of("BTCUSD","ETHUSD","BNBUSD"),
                        from,
                        to);

        List<CryptoStrongCandleParameterSweepEngine.ParameterSweepSummaryRow> summary =
                optimizer.toSummary(results, topN);

        return ResponseEntity.ok(summary);
    }

}