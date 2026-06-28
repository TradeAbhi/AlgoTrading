package com.trading.algo.delta.service;

import com.trading.algo.delta.config.DeltaAppConfig;
import com.trading.algo.delta.model.Candle;
import com.trading.algo.delta.model.DailyBreakoutAlert;
import com.trading.algo.delta.model.DailyBreakoutLevel;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Daily Breakout Strategy Service.
 *
 * Strategy Logic:
 * 1. Start with first daily candle's high/low as reference levels
 * 2. Reference levels stay static until a daily candle closes ABOVE the reference high
 * 3. When daily candle closes above reference high, update reference to that candle's high/low
 * 4. Alert when 15-minute candle closes ABOVE the current reference high
 *
 * This is different from the previous-day strategy where levels change every day.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DailyBreakoutService {

    private final DeltaApiService deltaApiService;
    private final DeltaAppConfig appConfig;
    private final TelegramServices telegramService;
    private final List<String> monitoredSymbols;

    // symbol → DailyBreakoutLevel
    private final Map<String, DailyBreakoutLevel> referenceCache = new ConcurrentHashMap<>();

    // symbol+direction → last alert time (for cooldown)
    private final Map<String, Instant> lastAlertTime = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        log.info("Initializing Daily Breakout reference levels on startup...");
        for (String symbol : monitoredSymbols) {
            initializeSymbol(symbol);
        }
    }

    /**
     * Initializes reference levels for a symbol using the first available daily candle.
     * Should be called on startup for all monitored symbols.
     */
    public void initializeSymbol(String symbol) {
        try {
            // Get the most recent daily candle to establish initial reference
            Candle dailyCandle = deltaApiService.getPreviousDayCandle(symbol);
            if (dailyCandle == null) {
                log.warn("No daily candle available for initialization of {}", symbol);
                return;
            }

            LocalDate candleDate = dailyCandle.getOpenTime().atZone(ZoneOffset.UTC).toLocalDate();

            DailyBreakoutLevel level = DailyBreakoutLevel.builder()
                    .symbol(symbol)
                    .referenceDate(candleDate)
                    .referenceHigh(dailyCandle.getHigh())
                    .referenceLow(dailyCandle.getLow())
                    .lastCheckDate(candleDate)
                    .build();

            referenceCache.put(symbol, level);
            log.info("Initialized reference levels for {} | date={} | high={} | low={}",
                    symbol, candleDate, level.getReferenceHigh(), level.getReferenceLow());

        } catch (Exception e) {
            log.error("Failed to initialize symbol {}: {}", symbol, e.getMessage(), e);
        }
    }

    /**
     * Checks if the daily candle has closed above the reference high.
     * If so, updates the reference levels to the new daily candle's high/low.
     * Should be called once per day after market close.
     */
    public void checkAndUpdateReferenceLevels(String symbol) {
        DailyBreakoutLevel currentLevel = referenceCache.get(symbol);
        if (currentLevel == null) {
            log.warn("No reference level found for {}. Initializing first.", symbol);
            initializeSymbol(symbol);
            return;
        }

        try {
            // Get the most recent daily candle
            Candle dailyCandle = deltaApiService.getPreviousDayCandle(symbol);
            if (dailyCandle == null) {
                log.warn("No daily candle available for reference check of {}", symbol);
                return;
            }

            LocalDate candleDate = dailyCandle.getOpenTime().atZone(ZoneOffset.UTC).toLocalDate();

            // Only check if this is a new day (not the same day as last check)
            if (!candleDate.isAfter(currentLevel.getLastCheckDate())) {
                log.debug("Daily candle for {} is from same day as last check. Skipping.", symbol);
                return;
            }

            // Check if daily candle closed above reference high
            if (dailyCandle.getClose().compareTo(currentLevel.getReferenceHigh()) > 0) {
                // Update reference levels to this candle's high/low
                DailyBreakoutLevel newLevel = DailyBreakoutLevel.builder()
                        .symbol(symbol)
                        .referenceDate(candleDate)
                        .referenceHigh(dailyCandle.getHigh())
                        .referenceLow(dailyCandle.getLow())
                        .lastCheckDate(candleDate)
                        .build();

                referenceCache.put(symbol, newLevel);
                log.info("📈 REFERENCE UPDATED for {} | old_high={} | new_high={} | new_low={} | date={}",
                        symbol, currentLevel.getReferenceHigh(), newLevel.getReferenceHigh(),
                        newLevel.getReferenceLow(), candleDate);
            } else {
                // Keep same reference levels, just update last check date
                currentLevel.setLastCheckDate(candleDate);
                log.debug("Reference levels unchanged for {} | close={} | reference_high={}",
                        symbol, dailyCandle.getClose(), currentLevel.getReferenceHigh());
            }

        } catch (Exception e) {
            log.error("Failed to check reference levels for {}: {}", symbol, e.getMessage(), e);
        }
    }

    /**
     * Checks if the last completed 15-minute candle closed above the reference high
     * or below the reference low.
     * If so, fires an alert.
     * Should be called every 15 minutes.
     */
    public void checkSymbolForAlert(String symbol) {
        DailyBreakoutLevel level = referenceCache.get(symbol);
        if (level == null) {
            log.warn("No reference level found for {}. Cannot check for alert.", symbol);
            return;
        }

        try {
            // Get the last completed 15-minute candle
            Candle candle = deltaApiService.getLastCompleted15mCandle(symbol);
            if (candle == null) {
                log.warn("No completed 15m candle available for {}", symbol);
                return;
            }

            log.debug("{} | 15m close={} | reference_high={} | reference_low={}",
                    symbol, candle.getClose(), level.getReferenceHigh(), level.getReferenceLow());

            // Check if 15m candle closed above reference high (bullish breakout)
            if (candle.getClose().compareTo(level.getReferenceHigh()) > 0) {
                fireAlert(DailyBreakoutAlert.builder()
                        .symbol(symbol)
                        .direction(DailyBreakoutAlert.Direction.BULLISH_BREAKOUT)
                        .candleClose(candle.getClose())
                        .referenceLevel(level.getReferenceHigh())
                        .candleCloseTime(candle.getCloseTime())
                        .referenceDate(level.getReferenceDate())
                        .build());
            }
            // Check if 15m candle closed below reference low (bearish breakdown)
            else if (candle.getClose().compareTo(level.getReferenceLow()) < 0) {
                fireAlert(DailyBreakoutAlert.builder()
                        .symbol(symbol)
                        .direction(DailyBreakoutAlert.Direction.BEARISH_BREAKDOWN)
                        .candleClose(candle.getClose())
                        .referenceLevel(level.getReferenceLow())
                        .candleCloseTime(candle.getCloseTime())
                        .referenceDate(level.getReferenceDate())
                        .build());
            } else {
                log.debug("{} | No breakout signal. Close between reference levels.", symbol);
            }

        } catch (Exception e) {
            log.error("Error checking symbol {} for alert: {}", symbol, e.getMessage(), e);
        }
    }

    /**
     * Fires an alert via Telegram if not on cooldown.
     */
    private void fireAlert(DailyBreakoutAlert alert) {
        String cooldownKey = alert.getSymbol() + ":" + alert.getDirection().name();

        if (isOnCooldown(cooldownKey)) {
            log.info("Alert suppressed (cooldown active) for key={}", cooldownKey);
            return;
        }

        log.info("🚨 DAILY BREAKOUT ALERT | symbol={} | close={} | reference_high={} | reference_date={}",
                alert.getSymbol(),
                alert.getCandleClose(),
                alert.getReferenceLevel(),
                alert.getReferenceDate());

        telegramService.sendDailyBreakoutAlert(alert);
        lastAlertTime.put(cooldownKey, Instant.now());
    }

    private boolean isOnCooldown(String key) {
        Instant last = lastAlertTime.get(key);
        if (last == null) return false;
        long cooldownSeconds = (long) appConfig.getCooldownMinutes() * 60;
        return Instant.now().isBefore(last.plusSeconds(cooldownSeconds));
    }

    /**
     * Returns the current reference level for a symbol (for debugging/status).
     */
    public DailyBreakoutLevel getReferenceLevel(String symbol) {
        return referenceCache.get(symbol);
    }

    /**
     * Returns all reference levels (for debugging/status).
     */
    public Map<String, DailyBreakoutLevel> getAllReferenceLevels() {
        return Map.copyOf(referenceCache);
    }
}
