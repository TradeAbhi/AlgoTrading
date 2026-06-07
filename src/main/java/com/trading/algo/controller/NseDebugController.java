package com.trading.algo.controller;

import com.trading.algo.dtos.Candle;
import com.trading.algo.upstox.UpstoxHistoricalCandleService;
import com.trading.algo.upstox.UpstoxInstrumentMasterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/nse")
@RequiredArgsConstructor
public class NseDebugController {

    private final UpstoxInstrumentMasterService instrumentMasterService;
    private final UpstoxHistoricalCandleService candleService;

    /**
     * Debug endpoint to fetch weekly and current-week daily candles for a symbol.
     * Example: GET /api/nse/debug-candles?symbol=ACUTAAS
     */
    @GetMapping("/debug-candles")
    public ResponseEntity<Map<String, Object>> debugCandles(@RequestParam("symbol") String symbol) {
        Map<String, Object> resp = new HashMap<>();
        try {
            var inst = instrumentMasterService.getInstrumentKey(symbol);
            if (inst.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "instrument key not found for " + symbol));
            }
            String instrumentKey = inst.get();
            resp.put("instrumentKey", instrumentKey);

            List<Candle> weekly = candleService.fetchWeeklyCandles(instrumentKey, LocalDate.now().minusWeeks(8), LocalDate.now().plusDays(1));
            List<Map<String, Object>> wlist = new ArrayList<>();
            if (weekly != null) {
                for (Candle c : weekly) {
                    Map<String, Object> m = new HashMap<>();
                    m.put("ts", c.getTimestamp());
                    m.put("open", c.getOpen());
                    m.put("high", c.getHigh());
                    m.put("low", c.getLow());
                    m.put("close", c.getClose());
                    m.put("volume", c.getVolume());
                    wlist.add(m);
                }
            }
            resp.put("weeklyCandles", wlist);

            LocalDate startOfWeek = LocalDate.now().with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
            List<Candle> daily = candleService.fetchDailyCandles(instrumentKey, startOfWeek, LocalDate.now());
            List<Map<String, Object>> dlist = new ArrayList<>();
            if (daily != null) {
                for (Candle c : daily) {
                    Map<String, Object> m = new HashMap<>();
                    m.put("ts", c.getTimestamp());
                    m.put("open", c.getOpen());
                    m.put("high", c.getHigh());
                    m.put("low", c.getLow());
                    m.put("close", c.getClose());
                    m.put("volume", c.getVolume());
                    dlist.add(m);
                }
            }
            resp.put("dailyCandles", dlist);

            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            log.error("debug-candles failed for {}: {}", symbol, e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }
}


