package com.trading.algo.delta.scheduler;

import com.trading.algo.delta.service.DailyBreakoutService;
import com.trading.algo.delta.service.TelegramServices;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Scheduled jobs for the Daily Breakout Strategy:
 *
 *  1. alertJob()     — every 15 minutes, checks all symbols for 15m candle breakouts
 *  2. refreshReferenceJob() — daily at 00:01 UTC, checks if daily candle closed above reference high
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyBreakoutScheduler {

    private final DailyBreakoutService dailyBreakoutService;
    private final TelegramServices telegramService;
    private final List<String> monitoredSymbols;

    /**
     * Runs at :00, :15, :30, :45 of every hour.
     * Checks each symbol for 15m candle closing above reference high.
     */
    @Scheduled(cron = "${daily-breakout.scheduler.alert.cron}")
    public void alertJob() {
        log.info("=== Daily Breakout alert check started at {} ===", Instant.now());

        for (String symbol : monitoredSymbols) {
            try {
                dailyBreakoutService.checkSymbolForAlert(symbol);
            } catch (Exception e) {
                log.error("Unexpected error checking symbol {} for daily breakout: {}", symbol, e.getMessage(), e);
            }
        }

        log.info("=== Daily Breakout alert check completed ===");
    }

    /**
     * Checks if daily candle closed above reference high at 00:01 UTC daily.
     * If so, updates reference levels to the new daily candle's high/low.
     */
    @Scheduled(cron = "${daily-breakout.scheduler.reference.refresh.cron}")
    public void refreshReferenceJob() {
        log.info("Checking daily candle for reference level update at {}", Instant.now());

        for (String symbol : monitoredSymbols) {
            try {
                dailyBreakoutService.checkAndUpdateReferenceLevels(symbol);
            } catch (Exception e) {
                log.error("Unexpected error updating reference levels for {}: {}", symbol, e.getMessage(), e);
            }
        }

        // Send a daily summary to Telegram
        StringBuilder sb = new StringBuilder("📊 *Daily Breakout Reference Levels*\n\n");
        dailyBreakoutService.getAllReferenceLevels().forEach((symbol, level) ->
                sb.append(String.format("*%s* | Ref High: `%s` | Ref Low: `%s` | Set On: `%s`\n",
                        symbol,
                        level.getReferenceHigh().toPlainString(),
                        level.getReferenceLow().toPlainString(),
                        level.getReferenceDate()))
        );
        telegramService.sendMessage(sb.toString());
    }
}
