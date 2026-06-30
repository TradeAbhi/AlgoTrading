package com.trading.algo.delta.service;

import com.trading.algo.delta.config.DeltaAppConfig;
import com.trading.algo.delta.model.AlertSignal;
import com.trading.algo.delta.model.Candle;
import com.trading.algo.delta.model.DayLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core alert logic.
 *
 * Rules:
 *  - BEARISH BREAKDOWN: last completed 15-min candle CLOSES BELOW previous-day low
 *  - BULLISH BREAKOUT : last completed 15-min candle CLOSES ABOVE previous-day high
 *
 * Alerts fire only ONCE per day per symbol per direction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertService {

    private final DeltaApiService        deltaApiService;
    private final PreviousDayLevelService pdlService;
    private final TelegramServices        telegramService;
    private final DeltaAppConfig         appConfig;

    // symbol+direction+date → alert already sent today (for daily deduplication)
    private final Map<String, Boolean> alertSentToday = new ConcurrentHashMap<>();

    /**
     * Checks a single symbol and fires Telegram alert if a signal is triggered.
     */
    public void checkSymbol(String symbol) {
        log.debug("Checking symbol: {}", symbol);

        // 1. Get the last completed 15-min candle
        Candle candle = deltaApiService.getLastCompleted15mCandle(symbol);
        if (candle == null) {
            log.warn("No completed 15m candle available for {}", symbol);
            return;
        }

        // 2. Get previous-day levels
        DayLevel dayLevel = pdlService.get(symbol);
        if (dayLevel == null) {
            log.warn("No previous-day level cached for {}. Skipping.", symbol);
            return;
        }

        log.debug("{} | candle close={} | PDL={} | PDH={}",
                symbol,
                candle.getClose(),
                dayLevel.getPreviousDayLow(),
                dayLevel.getPreviousDayHigh());

        // 3. Evaluate signals
        boolean bearish = candle.getClose().compareTo(dayLevel.getPreviousDayLow()) < 0;
        boolean bullish = candle.getClose().compareTo(dayLevel.getPreviousDayHigh()) > 0;

        if (bearish) {
            fireAlert(AlertSignal.builder()
                    .symbol(symbol)
                    .direction(AlertSignal.Direction.BEARISH_BREAKDOWN)
                    .candleClose(candle.getClose())
                    .level(dayLevel.getPreviousDayLow())
                    .candleCloseTime(candle.getCloseTime())
                    .build());
        }

        if (bullish) {
            fireAlert(AlertSignal.builder()
                    .symbol(symbol)
                    .direction(AlertSignal.Direction.BULLISH_BREAKOUT)
                    .candleClose(candle.getClose())
                    .level(dayLevel.getPreviousDayHigh())
                    .candleCloseTime(candle.getCloseTime())
                    .build());
        }

        if (!bearish && !bullish) {
            log.debug("{} | No signal triggered.", symbol);
        }
    }

    // -------------------------------------------------------------------------
    // Private
    // -------------------------------------------------------------------------

    private void fireAlert(AlertSignal signal) {
        // Create key with date for daily deduplication
        LocalDate alertDate = signal.getCandleCloseTime().atZone(ZoneOffset.UTC).toLocalDate();
        String dailyKey = signal.getSymbol() + ":" + signal.getDirection().name() + ":" + alertDate.toString();

        if (alertSentToday.containsKey(dailyKey)) {
            log.info("Alert suppressed (already sent today) for key={}", dailyKey);
            return;
        }

        log.info("🚨 ALERT TRIGGERED | symbol={} | direction={} | close={} | level={}",
                signal.getSymbol(),
                signal.getDirection(),
                signal.getCandleClose(),
                signal.getLevel());

        telegramService.sendAlert(signal);
        alertSentToday.put(dailyKey, true);
    }

    /**
     * Clears all daily alert tracking. Should be called at start of each trading day.
     */
    public void clearDailyAlerts() {
        log.info("Clearing daily alert tracking");
        alertSentToday.clear();
    }
}
