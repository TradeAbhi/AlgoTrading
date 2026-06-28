package com.stockanalyzer.service;


import com.stockanalyzer.model.AnalysisRequest;
import com.stockanalyzer.exception.InsufficientDataException;
import com.stockanalyzer.model.AnalysisResponse;
import com.stockanalyzer.service.AnalysisOrchestratorService;
import com.trading.algo.dtos.Candle;
import com.trading.algo.upstox.UpstoxHistoricalCandleService;
import com.trading.algo.upstox.UpstoxInstrumentMasterService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Manual trigger for the consolidation-breakout analysis.
 *
 * Test with:
 *   curl -X POST http://localhost:8080/api/analysis/consolidation-breakout \
 *        -H "Content-Type: application/json" \
 *        -d @sample-request.json
 */
@RestController
@RequestMapping("/api/analysis")
public class AnalysisController {

    private final AnalysisOrchestratorService orchestrator;
    private final UpstoxHistoricalCandleService upstoxHistoricalCandleService;
    private final UpstoxInstrumentMasterService upstoxInstrumentMasterService;

    public AnalysisController(AnalysisOrchestratorService orchestrator,
                              UpstoxHistoricalCandleService upstoxHistoricalCandleService,
                              UpstoxInstrumentMasterService upstoxInstrumentMasterService) {
        this.orchestrator = orchestrator;
        this.upstoxHistoricalCandleService = upstoxHistoricalCandleService;
        this.upstoxInstrumentMasterService = upstoxInstrumentMasterService;
    }

    @PostMapping("/consolidation-breakout")
    public AnalysisResponse analyze(@RequestBody AnalysisRequest request) {
        return orchestrator.analyze(
            request.getSymbol(),
            request.getTimeframe(),
            request.getCandles(),
            request.getConsolidationStart(),
            request.getConsolidationEnd()
        );
    }

    @GetMapping("/fetch-candles")
    public ResponseEntity<?> fetchCandles(
            @RequestParam String symbol,
            @RequestParam String timeframe,
            @RequestParam String fromDate,
            @RequestParam String toDate) {
        try {
            // Get instrument key from trading symbol
            var instrumentKey = upstoxInstrumentMasterService.getInstrumentKey(symbol);
            if (instrumentKey.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Symbol not found in Upstox instrument master"));
            }

            LocalDate from = LocalDate.parse(fromDate);
            LocalDate to = LocalDate.parse(toDate);

            List<Candle> candles;
            if ("WEEKLY".equalsIgnoreCase(timeframe)) {
                candles = upstoxHistoricalCandleService.fetchWeeklyCandles(instrumentKey.get(), from, to);
            } else if ("DAILY".equalsIgnoreCase(timeframe)) {
                candles = upstoxHistoricalCandleService.fetchDailyCandles(instrumentKey.get(), from, to);
            } else {
                // For intraday timeframes, fetch daily candles for each day in range
                candles = new java.util.ArrayList<>();
                for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
                    if (day.getDayOfWeek() != java.time.DayOfWeek.SATURDAY &&
                        day.getDayOfWeek() != java.time.DayOfWeek.SUNDAY) {
                        List<Candle> dayCandles = upstoxHistoricalCandleService.fetchDayCandles(instrumentKey.get(), day);
                        candles.addAll(dayCandles);
                    }
                }
            }

            if (candles.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "No candles found for the given parameters"));
            }

            // Convert to frontend format
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
            List<Map<String, Object>> candleData = new java.util.ArrayList<>();
            for (Candle c : candles) {
                Map<String, Object> candleMap = new java.util.HashMap<>();
                candleMap.put("timestamp", c.getTimestamp().format(formatter));
                candleMap.put("open", c.getOpen());
                candleMap.put("high", c.getHigh());
                candleMap.put("low", c.getLow());
                candleMap.put("close", c.getClose());
                candleMap.put("volume", c.getVolume());
                candleData.add(candleMap);
            }

            return ResponseEntity.ok(Map.of("candles", candleData));

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to fetch candles: " + e.getMessage()));
        }
    }

    @ExceptionHandler(InsufficientDataException.class)
            public ResponseEntity<Map<String, String>> handleInsufficientData(InsufficientDataException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(Map.of("error", "INSUFFICIENT_DATA", "message", ex.getMessage()));
    }
}
