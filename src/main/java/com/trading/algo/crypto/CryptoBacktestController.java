package com.trading.algo.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/crypto")
public class CryptoBacktestController {

    private static final Logger log = LoggerFactory.getLogger(CryptoBacktestController.class);

    private final CryptoBacktestService backtestService;

    public CryptoBacktestController(CryptoBacktestService backtestService) {
        this.backtestService = backtestService;
    }

    /**
     * Run backtest on crypto strategy with Delta Exchange API
     * 
     * @param symbols Comma-separated list of symbols (e.g., BTCUSD,ETHUSD,SOLUSD)
     * @param fromDate Start date for backtest
     * @param toDate End date for backtest
     * @param equity Starting equity (default 10000)
     * @return Response with backtest status
     */
    @PostMapping("/backtest")
    public ResponseEntity<Map<String, Object>> runBacktest(
            @RequestParam(required = false) String symbols,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue = "10000") double equity) {

        // Validate equity
        if (equity <= 0) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Equity must be greater than 0"
            ));
        }

        // Parse symbols or use defaults
        Set<String> symbolSet;
        if (symbols != null && !symbols.isBlank()) {
            symbolSet = Arrays.stream(symbols.split(","))
                    .map(String::trim)
                    .map(String::toUpperCase)
                    .collect(Collectors.toSet());
        } else {
            symbolSet = new HashSet<>(Arrays.asList("BTCUSD", "ETHUSD", "SOLUSD"));
        }

        // Set default date range if not provided (last 30 days)
        LocalDate effectiveTo = toDate != null ? toDate : LocalDate.now();
        LocalDate effectiveFrom = fromDate != null ? fromDate : effectiveTo.minusDays(30);

        // Validate date range
        if (effectiveFrom.isAfter(effectiveTo)) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "fromDate must be before or equal to toDate"
            ));
        }

        // Generate request ID
        String requestId = UUID.randomUUID().toString();

        log.info("[Crypto-BT][{}] Triggered backtest with {} symbols from {} to {}, equity=${}",
                requestId, symbolSet.size(), effectiveFrom, effectiveTo, equity);

        // Run async backtest
        backtestService.runBacktestAsync(symbolSet, effectiveFrom, effectiveTo, equity, requestId);

        return ResponseEntity.ok(Map.of(
            "status", "started",
            "requestId", requestId,
            "symbols", symbolSet,
            "fromDate", effectiveFrom.toString(),
            "toDate", effectiveTo.toString(),
            "equity", equity,
            "message", "Backtest running using Delta Exchange API. Results will be sent to Telegram."
        ));
    }

    /**
     * Quick backtest with default symbols (BTC, ETH, SOL) and date range (last 30 days)
     */
    @PostMapping("/backtest/quick")
    public ResponseEntity<Map<String, Object>> runQuickBacktest(
            @RequestParam(defaultValue = "10000") double equity) {

        // Default symbols
        Set<String> defaultSymbols = new HashSet<>(Arrays.asList("BTCUSD", "ETHUSD", "SOLUSD"));
        
        // Default date range (last 30 days)
        LocalDate toDate = LocalDate.now();
        LocalDate fromDate = toDate.minusDays(30);

        String requestId = UUID.randomUUID().toString();

        log.info("[Crypto-BT][{}] Quick backtest with default symbols, equity=${}",
                requestId, equity);

        backtestService.runBacktestAsync(defaultSymbols, fromDate, toDate, equity, requestId);

        return ResponseEntity.ok(Map.of(
            "status", "started",
            "requestId", requestId,
            "symbols", defaultSymbols,
            "fromDate", fromDate.toString(),
            "toDate", toDate.toString(),
            "equity", equity,
            "message", "Quick backtest running with BTC/ETH/SOL for last 30 days. Results will be sent to Telegram."
        ));
    }
}
