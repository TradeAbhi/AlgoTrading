package com.trading.algo.delta.controller;

import com.trading.algo.delta.model.DayLevel;
import com.trading.algo.delta.service.AlertService;
import com.trading.algo.delta.service.PreviousDayLevelService;
import com.trading.algo.delta.service.TelegramServices;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Simple REST endpoints for monitoring and manual triggers.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class StatusController {

    private final PreviousDayLevelService pdlService;
    private final AlertService            alertService;
    private final TelegramServices         telegramService;
    private final List<String>            monitoredSymbols;

    /** Returns cached previous-day levels for all symbols. */
    @GetMapping("/levels")
    public ResponseEntity<Map<String, DayLevel>> getLevels() {
        return ResponseEntity.ok(pdlService.getAll());
    }

    /** Manually triggers the alert check for a specific symbol. */
    @PostMapping("/check/{symbol}")
    public ResponseEntity<Map<String, String>> checkSymbol(@PathVariable String symbol) {
        alertService.checkSymbol(symbol.toUpperCase());
        Map<String, String> resp = new HashMap<>();
        resp.put("symbol", symbol.toUpperCase());
        resp.put("checkedAt", Instant.now().toString());
        resp.put("message", "Check triggered — see logs and Telegram.");
        return ResponseEntity.ok(resp);
    }

    /** Manually triggers an alert check for ALL symbols. */
    @PostMapping("/check/all")
    public ResponseEntity<Map<String, Object>> checkAll() {
        monitoredSymbols.forEach(alertService::checkSymbol);
        Map<String, Object> resp = new HashMap<>();
        resp.put("symbols", monitoredSymbols);
        resp.put("checkedAt", Instant.now().toString());
        return ResponseEntity.ok(resp);
    }

    /** Forces a refresh of previous-day levels. */
    @PostMapping("/refresh-levels")
    public ResponseEntity<Map<String, String>> refreshLevels() {
        pdlService.refreshAll();
        Map<String, String> resp = new HashMap<>();
        resp.put("refreshedAt", Instant.now().toString());
        resp.put("message", "Previous-day levels refreshed.");
        return ResponseEntity.ok(resp);
    }

    /** Sends a Telegram test message. */
    @PostMapping("/test-telegram")
    public ResponseEntity<Map<String, String>> testTelegram() {
        telegramService.sendMessage("✅ Delta Alert Service is *running* and connected to Telegram!");
        Map<String, String> resp = new HashMap<>();
        resp.put("sent", "true");
        return ResponseEntity.ok(resp);
    }

    /** Basic service info. */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> resp = new HashMap<>();
        resp.put("service", "delta-alert");
        resp.put("monitoredSymbols", monitoredSymbols);
        resp.put("serverTime", Instant.now().toString());
        resp.put("levelsLoaded", pdlService.getAll().size());
        return ResponseEntity.ok(resp);
    }
}
