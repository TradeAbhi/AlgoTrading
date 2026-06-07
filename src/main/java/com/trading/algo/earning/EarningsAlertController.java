package com.trading.algo.earning;

import com.trading.algo.service.SchedulerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Manual triggers for earnings Telegram alerts.
 *
 * All endpoints mirror what the scheduler runs automatically —
 * useful for testing or re-sending a missed alert.
 *
 * Base path: /api/earnings-alerts
 *
 * curl -X POST http://localhost:8080/api/earnings-alerts/weekly-summary
 * curl -X POST http://localhost:8080/api/earnings-alerts/7-day
 * curl -X POST http://localhost:8080/api/earnings-alerts/tomorrow
 * curl -X POST http://localhost:8080/api/earnings-alerts/today
 */
@Slf4j
@RestController
@RequestMapping("/api/earnings-alerts")
@RequiredArgsConstructor
public class EarningsAlertController {

    private final SchedulerService schedulerService;

    /**
     * POST /api/earnings-alerts/weekly-summary
     * F&O stocks with results in the next 7 days.
     * (Same as the every-Monday 8AM alert)
     */
    @PostMapping("/weekly-summary")
    public ResponseEntity<Map<String, String>> triggerWeeklySummary() {
        log.info("POST /api/earnings-alerts/weekly-summary — manual trigger");
        schedulerService.weeklySummaryAlert();
        return ResponseEntity.ok(Map.of("status", "Weekly summary alert sent"));
    }
    
    /**
     * POST /api/earnings-alerts/window
     * Sends the full pre-10 / post-3 window stock list to Telegram.
     * PRE_EARNINGS  — F&O stocks with result in the next 10 days.
     * POST_EARNINGS — F&O stocks whose result happened in the last 3 days.
     *
     * curl -X POST http://localhost:8080/api/earnings-alerts/window
     */
    @PostMapping("/window")
    public ResponseEntity<Map<String, String>> triggerWindowAlert() {
        log.info("POST /api/earnings-alerts/window — manual trigger");
        schedulerService.sendFullEarningsWindowAlert();
        return ResponseEntity.ok(Map.of("status", "Earnings window alert sent (pre-10 + post-3)"));

    /**
     * POST /api/earnings-alerts/7-day
     * F&O stocks whose result is exactly 7 days away.
     * (Same as daily 9:00AM alert)
     */
//    @PostMapping("/7-day")
//    public ResponseEntity<Map<String, String>> trigger7Day() {
//        log.info("POST /api/earnings-alerts/7-day — manual trigger");
//        schedulerService.weekAheadAlerts();
//        return ResponseEntity.ok(Map.of("status", "7-day ahead alert sent"));
//    }

    /**
     * POST /api/earnings-alerts/tomorrow
     * F&O stocks whose result is tomorrow.
     * (Same as daily 9:05AM alert)
     */
//    @PostMapping("/tomorrow")
//    public ResponseEntity<Map<String, String>> triggerTomorrow() {
//        log.info("POST /api/earnings-alerts/tomorrow — manual trigger");
//        schedulerService.dayAheadAlerts();
//        return ResponseEntity.ok(Map.of("status", "Tomorrow alert sent"));
//    }

    /**
     * POST /api/earnings-alerts/today
     * F&O stocks whose result is today.
     * (Same as daily 9:10AM alert)
     */
//    @PostMapping("/today")
//    public ResponseEntity<Map<String, String>> triggerToday() {
//        log.info("POST /api/earnings-alerts/today — manual trigger");
//        schedulerService.todayAlerts();
//        return ResponseEntity.ok(Map.of("status", "Today alert sent"));
//    }


    }
}