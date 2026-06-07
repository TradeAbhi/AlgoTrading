package com.trading.algo.weekly;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Manual trigger endpoints for the Weekly Breakout strategy.
 *
 * GET /weekly/capture  — seed previous week's high/low for all symbols
 * GET /weekly/scan     — run one daily close scan cycle
 * GET /weekly/state    — view all symbol states
 * GET /weekly/watching — view symbols still being watched (not yet alerted on both sides)
 * GET /weekly/alerted  — view symbols that have already triggered
 */
@RestController
@RequestMapping("/weekly")
public class WeeklyBreakoutController {

    private static final Logger log = LoggerFactory.getLogger(WeeklyBreakoutController.class);

    private final WeeklyBreakoutScannerService scannerService;
    private final WeeklyBreakoutStateStore stateStore;

    public WeeklyBreakoutController(WeeklyBreakoutScannerService scannerService,
                                    WeeklyBreakoutStateStore stateStore) {
        this.scannerService = scannerService;
        this.stateStore     = stateStore;
    }

    /**
     * Manually seed previous week's range.
     * Equivalent to what the Monday 9:31 AM scheduler does.
     * Safe to call any day — useful for testing mid-week.
     */
    @GetMapping("/capture")
    public ResponseEntity<Map<String, Object>> capture() {
        log.info("[WEEKLY] Manual seed triggered via /weekly/capture");
        scannerService.triggerManualSeed();
        return ResponseEntity.ok(Map.of(
            "status",        "done",
            "symbolsLoaded", stateStore.size()
        ));
    }

    /**
     * Manually trigger one daily scan cycle.
     * Equivalent to what the 3:31 PM scheduler does.
     * Useful for testing after market close.
     */
    @GetMapping("/scan")
    public ResponseEntity<Map<String, Object>> scan() {
        log.info("[WEEKLY] Manual scan triggered via /weekly/scan");
        scannerService.triggerManualScan();
        return ResponseEntity.ok(Map.of("status", "done"));
    }

    /**
     * View current weekly breakout state for all seeded symbols.
     * Shows weeklyHigh, weeklyLow, prevDailyLow/High, and alert flags.
     */
    @GetMapping("/state")
    public ResponseEntity<List<Map<String, Object>>> state() {
        List<Map<String, Object>> result = stateStore.all().stream()
            .map(s -> Map.of(
                "symbol",        (Object) s.getSymbol(),
                "weeklyHigh",    s.getWeeklyHigh(),
                "weeklyLow",     s.getWeeklyLow(),
                "prevDailyHigh", s.getPrevDailyHigh(),
                "prevDailyLow",  s.getPrevDailyLow(),
                "buyAlerted",    s.isBuyAlerted(),
                "sellAlerted",   s.isSellAlerted()
            ))
            .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    /**
     * View only symbols still being watched — at least one side not yet alerted.
     * Cleaner view during the week to see live candidates.
     */
    @GetMapping("/watching")
    public ResponseEntity<List<Map<String, Object>>> watching() {
        List<Map<String, Object>> result = stateStore.all().stream()
            .filter(s -> !s.isBuyAlerted() || !s.isSellAlerted())
            .map(s -> Map.of(
                "symbol",        (Object) s.getSymbol(),
                "weeklyHigh",    s.getWeeklyHigh(),
                "weeklyLow",     s.getWeeklyLow(),
                "prevDailyHigh", s.getPrevDailyHigh(),
                "prevDailyLow",  s.getPrevDailyLow(),
                "buyAlerted",    s.isBuyAlerted(),
                "sellAlerted",   s.isSellAlerted()
            ))
            .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    /**
     * View symbols that have already triggered at least one side this week.
     */
    @GetMapping("/alerted")
    public ResponseEntity<List<Map<String, Object>>> alerted() {
        List<Map<String, Object>> result = stateStore.all().stream()
            .filter(s -> s.isBuyAlerted() || s.isSellAlerted())
            .map(s -> Map.of(
                "symbol",      (Object) s.getSymbol(),
                "buyAlerted",  s.isBuyAlerted(),
                "sellAlerted", s.isSellAlerted(),
                "weeklyHigh",  s.getWeeklyHigh(),
                "weeklyLow",   s.getWeeklyLow()
            ))
            .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }
}