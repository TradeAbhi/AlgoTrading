package com.trading.algo.fibostrategy;

import com.trading.algo.service.UniverseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Comparator;
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

    /** Combined profitability split for all selected symbols by prior-day-level category. */
    @GetMapping("/category-summary")
    public ResponseEntity<?> categorySummary(
            @RequestParam(required = false) List<String> symbols,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        List<String> selectedSymbols = symbols == null || symbols.isEmpty() ? FNO_STOCKS : symbols;
        Map<String, FiboBacktestEngine.FiboBacktestResult> results =
                backtestEngine.runBacktest(selectedSymbols, from, to);
        List<com.trading.algo.entity.BacktestTrade> trades = results.values().stream()
                .flatMap(result -> result.getTrades().stream())
                .toList();
        return ResponseEntity.ok(FiboCategoryPerformance.summarize(trades));
    }

    /**
     * Returns only losing Fibonacci trades (actual R below zero), retaining all
     * trade fields such as C1/C2, prior-day levels, entry, exit, stop and P&L.
     */
    @GetMapping("/losers")
    public ResponseEntity<FiboLosersResponse> losers(
            @RequestParam(required = false) List<String> symbols,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        List<String> selectedSymbols = symbols == null || symbols.isEmpty() ? FNO_STOCKS : symbols;
        return ResponseEntity.ok(findLosers(selectedSymbols, from, to));
    }

    /** Returns only negative-R Fibonacci trades for one stock, with every trade field. */
    @GetMapping("/stock/{symbol}/losers")
    public ResponseEntity<FiboLosersResponse> stockLosers(
            @PathVariable String symbol,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        String normalizedSymbol = symbol.trim().toUpperCase();
        log.info("Fibonacci losing trades requested | symbol={} from={} to={}", normalizedSymbol, from, to);
        return ResponseEntity.ok(findLosers(List.of(normalizedSymbol), from, to));
    }

    /** Returns only positive-R Fibonacci trades for one stock, with every trade field. */
    @GetMapping("/stock/{symbol}/winners")
    public ResponseEntity<FiboWinnersResponse> stockWinners(
            @PathVariable String symbol,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        String normalizedSymbol = symbol.trim().toUpperCase();
        log.info("Fibonacci winning trades requested | symbol={} from={} to={}", normalizedSymbol, from, to);
        return ResponseEntity.ok(findWinners(List.of(normalizedSymbol), from, to));
    }

    private FiboLosersResponse findLosers(List<String> symbols, LocalDate from, LocalDate to) {
        Map<String, FiboBacktestEngine.FiboBacktestResult> results = backtestEngine.runBacktest(symbols, from, to);
        List<com.trading.algo.entity.BacktestTrade> losingTrades = results.values().stream()
                .flatMap(result -> result.getTrades().stream())
                .filter(trade -> trade.getActualRR() < 0)
                .sorted(Comparator.comparing(com.trading.algo.entity.BacktestTrade::getTradeDate)
                        .thenComparing(com.trading.algo.entity.BacktestTrade::getSymbol))
                .toList();
        double totalLossR = losingTrades.stream().mapToDouble(com.trading.algo.entity.BacktestTrade::getActualRR).sum();
        double totalLossRupees = losingTrades.stream()
                .mapToDouble(com.trading.algo.entity.BacktestTrade::getPnlRupees).sum();
        return new FiboLosersResponse(from, to, symbols, losingTrades.size(),
                totalLossR, totalLossRupees, losingTrades);
    }

    private FiboWinnersResponse findWinners(List<String> symbols, LocalDate from, LocalDate to) {
        Map<String, FiboBacktestEngine.FiboBacktestResult> results = backtestEngine.runBacktest(symbols, from, to);
        List<com.trading.algo.entity.BacktestTrade> winningTrades = results.values().stream()
                .flatMap(result -> result.getTrades().stream())
                .filter(trade -> trade.getActualRR() > 0)
                .sorted(Comparator.comparing(com.trading.algo.entity.BacktestTrade::getTradeDate)
                        .thenComparing(com.trading.algo.entity.BacktestTrade::getSymbol))
                .toList();
        double totalProfitR = winningTrades.stream().mapToDouble(com.trading.algo.entity.BacktestTrade::getActualRR).sum();
        double totalProfitRupees = winningTrades.stream()
                .mapToDouble(com.trading.algo.entity.BacktestTrade::getPnlRupees).sum();
        return new FiboWinnersResponse(from, to, symbols, winningTrades.size(),
                totalProfitR, totalProfitRupees, winningTrades);
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

    /**
     * Runs the complete Fibonacci parameter sweep for one stock. Results include
     * the time-based trailing-SL candle count and category-wise profitability.
     */
    @GetMapping("/stock/{symbol}/dynamic-check-summary")
    public ResponseEntity<?> stockOptimizeSummary(
            @PathVariable String symbol,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "50") int topN) {
        String normalizedSymbol = symbol.trim().toUpperCase();
        log.info("Fibonacci Parameter Sweep for stock={} from={} to={} topN={}",
                normalizedSymbol, from, to, topN);
        FiboParameterSweepEngine.SweepResult results =
                sweepEngine.runSweep(List.of(normalizedSymbol), from, to);
        return ResponseEntity.ok(sweepEngine.toSummary(results.getResults(), topN));
    }

    /** Ranks one stock's parameter combinations by profitability within one category. */
    @GetMapping("/stock/{symbol}/dynamic-check-category-summary")
    public ResponseEntity<?> stockOptimizeCategorySummary(
            @PathVariable String symbol,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam FiboPreviousDayCategory category,
            @RequestParam(defaultValue = "50") int topN) {
        String normalizedSymbol = symbol.trim().toUpperCase();
        FiboParameterSweepEngine.SweepResult results =
                sweepEngine.runSweep(List.of(normalizedSymbol), from, to);
        return ResponseEntity.ok(sweepEngine.toCategorySummary(results.getResults(), category, topN));
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

    /**
     * Parameter-sweep rankings for one selected previous-day category.
     * Example category: BUY_ABOVE_PREV_DAY_HIGH.
     */
    @GetMapping("/dynamic-check-category-summary")
    public ResponseEntity<?> optimizeCategorySummary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam FiboPreviousDayCategory category,
            @RequestParam(defaultValue = "50") int topN) {
        FiboParameterSweepEngine.SweepResult results = sweepEngine.runSweep(FNO_STOCKS, from, to);
        return ResponseEntity.ok(sweepEngine.toCategorySummary(results.getResults(), category, topN));
    }

    public record FiboLosersResponse(LocalDate from, LocalDate to, List<String> symbols,
                                     int totalLosers, double totalLossR, double totalLossRupees,
                                     List<com.trading.algo.entity.BacktestTrade> trades) { }

    public record FiboWinnersResponse(LocalDate from, LocalDate to, List<String> symbols,
                                      int totalWinners, double totalProfitR, double totalProfitRupees,
                                      List<com.trading.algo.entity.BacktestTrade> trades) { }
}
