package com.trading.algo.ipo;

import com.trading.algo.repo.IpoBacktestTradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * IPO Backtest Controller
 *
 * POST /api/ipo/backtest/run              → run backtest for all IPOs in database
 * POST /api/ipo/backtest/upload-csv       → upload IPO CSV and run backtest
 * GET  /api/ipo/backtest/results         → get all backtest results
 * GET  /api/ipo/backtest/summary         → get backtest summary statistics
 */
@Slf4j
@RestController
@RequestMapping("/api/ipo/backtest")
@RequiredArgsConstructor
public class IpoBacktestController {

    private final IpoBacktestService backtestService;
    private final IpoCsvImportService csvImportService;
    private final IpoBacktestTradeRepository backtestRepository;

    /**
     * POST /api/ipo/backtest/run
     * Run backtest for all IPOs already in the database
     *
     * curl -X POST http://localhost:8080/api/ipo/backtest/run
     */
    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> runBacktest() {
        log.info("POST /api/ipo/backtest/run - Starting IPO backtest");
        try {
            IpoBacktestService.BacktestSummary summary = backtestService.runBacktestForAllIpos();
            return ResponseEntity.ok(java.util.Map.ofEntries(
                    java.util.Map.entry("status", "completed"),
                    java.util.Map.entry("processed", summary.processed),
                    java.util.Map.entry("skipped", summary.skipped),
                    java.util.Map.entry("wins", summary.wins),
                    java.util.Map.entry("losses", summary.losses),
                    java.util.Map.entry("noBreakouts", summary.noBreakouts),
                    java.util.Map.entry("eodExits", summary.eodExits),
                    java.util.Map.entry("winRate", String.format("%.2f%%", summary.winRate)),
                    java.util.Map.entry("avgPnl", String.format("%.2f", summary.avgPnl)),
                    java.util.Map.entry("totalPnl", String.format("%.2f", summary.totalPnl))
            ));
        } catch (Exception e) {
            log.error("Backtest failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * POST /api/ipo/backtest/upload-csv
     * Upload IPO CSV file, import to database, and run backtest
     *
     * How to get the CSV:
     *   NSE website → Market Data → IPO → Download (.csv)
     *   URL: https://www.nseindia.com/market-data/all-upcoming-issues-ipo
     *
     * curl -X POST http://localhost:8080/api/ipo/backtest/upload-csv \
     *      -F "file=@/path/to/ipo_list.csv"
     */
    @PostMapping("/upload-csv")
    public ResponseEntity<Map<String, Object>> uploadCsvAndBacktest(
            @org.springframework.web.bind.annotation.RequestParam("file") MultipartFile file) {
        log.info("POST /api/ipo/backtest/upload-csv — file={} size={}",
                file.getOriginalFilename(), file.getSize());
        try {
            // Import CSV
            IpoCsvImportService.ImportResult importResult =
                    csvImportService.importCsv(file.getInputStream());

            // Run backtest only on newly imported IPOs
            IpoBacktestService.BacktestSummary backtestSummary =
                    backtestService.runBacktestForIpos(importResult.importedIpos());

            return ResponseEntity.ok(java.util.Map.ofEntries(
                    java.util.Map.entry("importStatus", "completed"),
                    java.util.Map.entry("imported", importResult.totalImported()),
                    java.util.Map.entry("importErrors", importResult.totalErrors()),
                    java.util.Map.entry("backtestStatus", "completed"),
                    java.util.Map.entry("processed", backtestSummary.processed),
                    java.util.Map.entry("skipped", backtestSummary.skipped),
                    java.util.Map.entry("wins", backtestSummary.wins),
                    java.util.Map.entry("losses", backtestSummary.losses),
                    java.util.Map.entry("noBreakouts", backtestSummary.noBreakouts),
                    java.util.Map.entry("eodExits", backtestSummary.eodExits),
                    java.util.Map.entry("winRate", String.format("%.2f%%", backtestSummary.winRate)),
                    java.util.Map.entry("avgPnl", String.format("%.2f", backtestSummary.avgPnl)),
                    java.util.Map.entry("totalPnl", String.format("%.2f", backtestSummary.totalPnl))
            ));
        } catch (Exception e) {
            log.error("CSV upload and backtest failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * GET /api/ipo/backtest/results
     * Get all backtest trade results
     *
     * curl http://localhost:8080/api/ipo/backtest/results
     */
    @GetMapping("/results")
    public ResponseEntity<List<com.trading.algo.entity.IpoBacktestTrade>> getResults() {
        log.info("GET /api/ipo/backtest/results");
        return ResponseEntity.ok(backtestRepository.findAll());
    }

    /**
     * GET /api/ipo/backtest/results-by-symbols
     * Get backtest results for specific symbols only
     *
     * curl "http://localhost:8080/api/ipo/backtest/results-by-symbols?symbols=ADANIENI,TRAVELFO,CRIZAC"
     */
    @GetMapping("/results-by-symbols")
    public ResponseEntity<List<com.trading.algo.entity.IpoBacktestTrade>> getResultsBySymbols(
            @RequestParam("symbols") String symbols) {
        log.info("GET /api/ipo/backtest/results-by-symbols - symbols={}", symbols);

        String[] symbolArray = symbols.split(",");
        List<String> symbolList = new ArrayList<>();
        for (String symbol : symbolArray) {
            symbolList.add(symbol.trim().toUpperCase());
        }

        List<com.trading.algo.entity.IpoBacktestTrade> results = backtestRepository.findBySymbolIn(symbolList);
        return ResponseEntity.ok(results);
    }

    /**
     * GET /api/ipo/backtest/summary
     * Get summary statistics of backtest results
     *
     * curl http://localhost:8080/api/ipo/backtest/summary
     */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getSummary() {
        log.info("GET /api/ipo/backtest/summary");
        long wins = backtestRepository.countWins();
        long losses = backtestRepository.countLosses();
        long eodExits = backtestRepository.countEodExits();
        long noBreakouts = backtestRepository.countNoBreakouts();
        Double totalPnl = backtestRepository.totalPnl();

        long totalTrades = wins + losses + eodExits + noBreakouts;
        double winRate = totalTrades > 0 ? (wins * 100.0 / totalTrades) : 0;
        double avgPnl = totalTrades > 0 && totalPnl != null ? totalPnl / totalTrades : 0;

        return ResponseEntity.ok(java.util.Map.ofEntries(
                java.util.Map.entry("totalTrades", totalTrades),
                java.util.Map.entry("wins", wins),
                java.util.Map.entry("losses", losses),
                java.util.Map.entry("eodExits", eodExits),
                java.util.Map.entry("noBreakouts", noBreakouts),
                java.util.Map.entry("winRate", String.format("%.2f%%", winRate)),
                java.util.Map.entry("avgPnl", String.format("%.2f", avgPnl)),
                java.util.Map.entry("totalPnl", totalPnl != null ? String.format("%.2f", totalPnl) : "0.00")
        ));
    }
}
