package com.trading.algo.usmarket;

import com.trading.algo.dtos.UsWeeklyBreakoutState;
import com.trading.algo.dtos.UsWeeklyBreakoutStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Manual trigger endpoints for the US Weekly Breakout strategy.
 *
 * GET /us-weekly/capture  — seed Finviz 52-week high list + Yahoo Finance weekly data
 * GET /us-weekly/scan     — run one daily close scan (fetches yesterday's US daily candle)
 * GET /us-weekly/state    — view all ticker states
 * GET /us-weekly/watching — view unalerted tickers only
 * GET /us-weekly/alerted  — view tickers that already triggered this week
 * GET /us-weekly/52wk     — view only the 52-week high tickers being tracked
 * POST /us-weekly/upload-tickers — upload a list of tickers (one per line)
 */
@RestController
@RequestMapping("/us-weekly")
public class UsWeeklyBreakoutController {

    private static final Logger log = LoggerFactory.getLogger(UsWeeklyBreakoutController.class);

    private final UsWeeklyBreakoutScannerService scannerService;
    private final UsWeeklyBreakoutStateStore     stateStore;

    public UsWeeklyBreakoutController(UsWeeklyBreakoutScannerService scannerService,
                                      UsWeeklyBreakoutStateStore stateStore) {
        this.scannerService = scannerService;
        this.stateStore     = stateStore;
    }

    /**
     * Seed previous week's range from Finviz + Yahoo Finance.
     * Equivalent to what the Monday 6:00 AM IST scheduler does.
     */
    @GetMapping("/capture")
    public ResponseEntity<Map<String, Object>> capture() {
        log.info("[US-WEEKLY] Manual seed triggered via /us-weekly/capture");
        scannerService.triggerManualSeed();
        return ResponseEntity.ok(Map.of(
                "status",       "done",
                "tickersLoaded", stateStore.size()
        ));
    }

    /**
     * Replay the full current week (Mon–Fri) day by day.
     * Use this when the app was down during the week and daily scans were missed.
     */
    @GetMapping("/scan-week")
    public ResponseEntity<Map<String, Object>> scanWeek() {
        log.info("[US-WEEKLY] Full week replay triggered via /us-weekly/scan-week");
        int[] result = scannerService.scanWeek();
        return ResponseEntity.ok(Map.of(
                "status",    "done",
                "buyFired",  result[0],
                "sellFired", result[1]
        ));
    }

    /**
     * Manually trigger one daily scan cycle.
     * Fetches latest daily candle from Yahoo Finance for all tracked tickers.
     */
    @GetMapping("/scan")
    public ResponseEntity<Map<String, Object>> scan() {
        log.info("[US-WEEKLY] Manual scan triggered via /us-weekly/scan");
        scannerService.triggerManualScan();
        return ResponseEntity.ok(Map.of("status", "done"));
    }

    /**
     * Upload a list of tickers to be scanned. This will replace any existing in-memory list
     * and immediately trigger a seed operation.
     *
     * @param tickers A list of ticker symbols, one per line.
     * @return A response indicating the status of the upload and seed operation.
     */
    @PostMapping("/upload-tickers")
    public ResponseEntity<Map<String, Object>> uploadTickers(@RequestBody List<String> tickers) {
        log.info("[US-WEEKLY] Ticker upload triggered via /us-weekly/upload-tickers. Received {} tickers.", tickers.size());
        scannerService.uploadAndSeed(tickers);
        return ResponseEntity.ok(Map.of(
                "status", "Tickers uploaded and seeded successfully",
                "tickersLoaded", stateStore.size()
        ));
    }

    /**
     * View current state for all seeded tickers.
     */
    @GetMapping("/state")
    public ResponseEntity<List<Map<String, Object>>> state() {
        List<Map<String, Object>> result = stateStore.all().stream()
                .map(s -> Map.of(
                        "ticker",        (Object) s.getTicker(),
                        "weeklyHigh",    s.getWeeklyHigh(),
                        "weeklyLow",     s.getWeeklyLow(),
                        "prevDailyHigh", s.getPrevDailyHigh(),
                        "prevDailyLow",  s.getPrevDailyLow(),
                        "buyAlerted",    s.isBuyAlerted(),
                        "sellAlerted",   s.isSellAlerted(),
                        "is52WeekHigh",  s.is52WeekHigh()
                ))
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    /**
     * View only tickers still being watched (not yet alerted on both sides).
     */
    @GetMapping("/watching")
    public ResponseEntity<List<Map<String, Object>>> watching() {
        List<Map<String, Object>> result = stateStore.all().stream()
                .filter(s -> !s.isBuyAlerted() || !s.isSellAlerted())
                .map(s -> Map.of(
                        "ticker",        (Object) s.getTicker(),
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
     * View tickers that already triggered at least one side this week.
     */
    @GetMapping("/alerted")
    public ResponseEntity<List<Map<String, Object>>> alerted() {
        List<Map<String, Object>> result = stateStore.all().stream()
                .filter(s -> s.isBuyAlerted() || s.isSellAlerted())
                .map(s -> Map.of(
                        "ticker",      (Object) s.getTicker(),
                        "buyAlerted",  s.isBuyAlerted(),
                        "sellAlerted", s.isSellAlerted(),
                        "weeklyHigh",  s.getWeeklyHigh(),
                        "weeklyLow",   s.getWeeklyLow()
                ))
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    /**
     * View only tickers sourced from the Finviz 52-week high list.
     * These are the highest conviction BUY setups.
     */
    @GetMapping("/52wk")
    public ResponseEntity<List<Map<String, Object>>> fiftyTwoWeekHighs() {
        List<Map<String, Object>> result = stateStore.all().stream()
                .filter(UsWeeklyBreakoutState::is52WeekHigh)
                .map(s -> Map.of(
                        "ticker",      (Object) s.getTicker(),
                        "weeklyHigh",  s.getWeeklyHigh(),
                        "weeklyLow",   s.getWeeklyLow(),
                        "buyAlerted",  s.isBuyAlerted()
                ))
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }
}
