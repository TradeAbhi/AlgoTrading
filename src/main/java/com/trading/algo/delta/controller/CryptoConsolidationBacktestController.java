package com.trading.algo.delta.controller;

import com.trading.algo.delta.model.CryptoTradeRecord;
import com.trading.algo.delta.service.CryptoConsolidationBacktestEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * REST controller for Crypto Consolidation Breakout backtest.
 *
 * GET /delta/crypto-consolidation/backtest?symbol=BTCUSD&from=2024-01-01&to=2024-06-30&timeframe=15m&maxRangePct=5.0&targetRR=3
 *
 * Returns backtest results synchronously.
 */
@Slf4j
@RestController
@RequestMapping("/delta/crypto-consolidation")
public class CryptoConsolidationBacktestController {

    private final CryptoConsolidationBacktestEngine backtestEngine;

    public CryptoConsolidationBacktestController(CryptoConsolidationBacktestEngine backtestEngine) {
        this.backtestEngine = backtestEngine;
    }

    @GetMapping("/backtest")
    public ResponseEntity<Map<String, Object>> runBacktest(
            @RequestParam String symbol,

            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,

            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,

            @RequestParam(defaultValue = "15m") String timeframe,

            @RequestParam(defaultValue = "5.0") double maxRangePct,

            @RequestParam(defaultValue = "3") int targetRR,

            @RequestParam(defaultValue = "1.8") double minVolumeRatio) {

        LocalDate effectiveTo = (to != null) ? to : LocalDate.now().minusDays(1);
        LocalDate effectiveFrom = (from != null) ? from : effectiveTo.minusDays(90);

        // Date validation
        if (effectiveFrom.isAfter(effectiveTo)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "'from' date must be before 'to' date"
            ));
        }

        // Timeframe validation
        if (!timeframe.equals("15m") && !timeframe.equals("Daily")) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "timeframe must be '15m' or 'Daily'"
            ));
        }

        // Parameter validation
        if (maxRangePct <= 0 || maxRangePct > 50) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "maxRangePct must be between 0 and 50"
            ));
        }

        if (targetRR <= 0 || targetRR > 20) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "targetRR must be between 0 and 20"
            ));
        }

        if (minVolumeRatio <= 0 || minVolumeRatio > 10) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "minVolumeRatio must be between 0 and 10"
            ));
        }

        String requestId = UUID.randomUUID().toString();
        CryptoTradeRecord.Timeframe tf = timeframe.equals("15m") 
                ? CryptoTradeRecord.Timeframe.MINUTES_15 
                : CryptoTradeRecord.Timeframe.DAILY;

        log.info("[CRYPTO-CONSOLIDATION-BT][{}] Triggered: symbol={} {} to {} | timeframe={} | maxRange={}% | targetRR={} | minVolumeRatio={}",
                requestId, symbol, effectiveFrom, effectiveTo, timeframe, maxRangePct, targetRR, minVolumeRatio);

        try {
            List<CryptoTradeRecord> trades = backtestEngine.runBacktest(
                    symbol.toUpperCase(),
                    effectiveFrom,
                    effectiveTo,
                    tf,
                    maxRangePct,
                    targetRR,
                    minVolumeRatio
            );

            Map<String, Object> summary = calculateSummary(trades);

            return ResponseEntity.ok(Map.of(
                    "status", "completed",
                    "requestId", requestId,
                    "symbol", symbol.toUpperCase(),
                    "fromDate", effectiveFrom,
                    "toDate", effectiveTo,
                    "timeframe", timeframe,
                    "maxRangePct", maxRangePct,
                    "targetRR", targetRR,
                    "summary", summary,
                    "trades", trades
            ));

        } catch (Exception e) {
            log.error("[CRYPTO-CONSOLIDATION-BT][{}] Backtest failed: {}", requestId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "failed",
                    "requestId", requestId,
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Calculates summary statistics from trade records.
     */
    private Map<String, Object> calculateSummary(List<CryptoTradeRecord> trades) {
        if (trades.isEmpty()) {
            return Map.of(
                    "totalTrades", 0,
                    "wins", 0,
                    "losses", 0,
                    "eodExits", 0,
                    "winRate", 0.0,
                    "totalPnlR", 0.0,
                    "avgPnlR", 0.0,
                    "profitFactor", 0.0
            );
        }

        long wins = trades.stream().filter(t -> t.getExitReason() == CryptoTradeRecord.ExitReason.TARGET_HIT).count();
        long losses = trades.stream().filter(t -> t.getExitReason() == CryptoTradeRecord.ExitReason.SL_HIT).count();
        long eodExits = trades.stream().filter(t -> t.getExitReason() == CryptoTradeRecord.ExitReason.EOD_EXIT).count();
        long closed = wins + losses;

        double winRate = closed > 0 ? (double) wins / closed * 100.0 : 0.0;
        double totalPnlR = trades.stream()
                .filter(t -> t.getExitReason() != CryptoTradeRecord.ExitReason.EOD_EXIT)
                .mapToDouble(t -> t.getPnlR().doubleValue())
                .sum();
        double avgPnlR = closed > 0 ? totalPnlR / closed : 0.0;

        double grossProfit = trades.stream()
                .filter(t -> t.getExitReason() == CryptoTradeRecord.ExitReason.TARGET_HIT)
                .mapToDouble(t -> t.getPnlR().doubleValue())
                .sum();
        double grossLoss = Math.abs(trades.stream()
                .filter(t -> t.getExitReason() == CryptoTradeRecord.ExitReason.SL_HIT)
                .mapToDouble(t -> t.getPnlR().doubleValue())
                .sum());
        double profitFactor = grossLoss > 0 ? grossProfit / grossLoss : (grossProfit > 0 ? Double.POSITIVE_INFINITY : 0.0);

        Map<String, Object> summary = new HashMap<>();
        summary.put("totalTrades", trades.size());
        summary.put("wins", wins);
        summary.put("losses", losses);
        summary.put("eodExits", eodExits);
        summary.put("winRate", Math.round(winRate * 100.0) / 100.0);
        summary.put("totalPnlR", Math.round(totalPnlR * 100.0) / 100.0);
        summary.put("avgPnlR", Math.round(avgPnlR * 100.0) / 100.0);
        summary.put("profitFactor", Math.round(profitFactor * 100.0) / 100.0);

        return summary;
    }
}
