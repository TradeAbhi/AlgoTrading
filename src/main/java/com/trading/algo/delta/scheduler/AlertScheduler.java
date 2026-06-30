package com.trading.algo.delta.scheduler;

import com.trading.algo.delta.service.AlertService;
import com.trading.algo.delta.service.PreviousDayLevelService;
import com.trading.algo.delta.service.TelegramServices;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Two scheduled jobs:
 *
 *  1. alertJob()     — every 15 minutes, checks all symbols for candle signals
 *  2. refreshPdlJob() — daily at 00:01 UTC, refreshes previous-day levels
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlertScheduler {

    private final AlertService           alertService;
    private final PreviousDayLevelService pdlService;
    private final TelegramServices       telegramService;
    private final List<String>           monitoredSymbols;

    /**
     * Runs at :00, :15, :30, :45 of every hour.
     * Checks each symbol against previous-day levels.
     */
    @Scheduled(cron = "${alert.scheduler.cron}")
    public void alertJob() {
        log.info("=== Alert check started at {} ===", Instant.now());

        for (String symbol : monitoredSymbols) {
            try {
                alertService.checkSymbol(symbol);
            } catch (Exception e) {
                log.error("Unexpected error checking symbol {}: {}", symbol, e.getMessage(), e);
            }
        }

        log.info("=== Alert check completed ===");
    }

    /**
     * Refreshes previous-day high/low at 00:01 UTC daily.
     */
    @Scheduled(cron = "${alert.pdl.refresh.cron}")
    public void refreshPdlJob() {
        log.info("Refreshing previous-day levels at {}", Instant.now());
        
        // Clear daily alert tracking at start of new day
        alertService.clearDailyAlerts();
        
        pdlService.refreshAll();

        // Send a daily summary to Telegram
        StringBuilder sb = new StringBuilder("📊 *Daily Level Refresh*\n\n");
        pdlService.getAll().forEach((symbol, level) ->
                sb.append(String.format("*%s* | PDL: `%s` | PDH: `%s`\n",
                        symbol,
                        level.getPreviousDayLow().toPlainString(),
                        level.getPreviousDayHigh().toPlainString()))
        );
        telegramService.sendMessage(sb.toString());
    }
}
