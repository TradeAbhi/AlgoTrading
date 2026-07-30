package com.trading.algo.delta.service;

import com.trading.algo.delta.config.DeltaAppConfig;
import com.trading.algo.delta.model.Candle;
import com.trading.algo.delta.model.CryptoBreakoutAlert;
import com.trading.algo.delta.model.CryptoConsolidationZone;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Crypto Consolidation Breakout Service.
 *
 * Strategy Logic:
 * 1. Identify 8-day consolidation zones (price coiling within a range)
 * 2. Trigger alert when crypto closes above/below consolidation zone on:
 *    - 15-minute timeframe (intraday breakouts)
 *    - Daily timeframe (major breakouts)
 * 3. SL is placed below the second candle prior to the breakout (candle[i-2])
 * 4. Target is 1:3 risk-reward ratio
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CryptoConsolidationService {

    private static final int CONSOLIDATION_DAYS = 8;
    private static final double MAX_RANGE_PCT = 5.0; // Max 5% range for valid consolidation
    private static final int TARGET_RR = 3; // 1:3 risk-reward ratio
    
    private final DeltaApiService deltaApiService;
    private final DeltaAppConfig appConfig;
    private final TelegramServices telegramService;
    private final List<String> monitoredSymbols;
    
    // symbol -> current consolidation zone
    private final Map<String, CryptoConsolidationZone> consolidationZones = new ConcurrentHashMap<>();
    
    // symbol+direction+timeframe -> last alert time (for cooldown)
    private final Map<String, Instant> lastAlertTime = new ConcurrentHashMap<>();
    
    @PostConstruct
    public void init() {
        log.info("Initializing Crypto Consolidation Service for {} symbols", monitoredSymbols.size());
        for (String symbol : monitoredSymbols) {
            updateConsolidationZone(symbol);
        }
    }
    
    /**
     * Updates the consolidation zone for a symbol based on the last 8 daily candles.
     * Called daily to refresh consolidation zones.
     */
    public void updateConsolidationZone(String symbol) {
        try {
            // Fetch last 10 daily candles to have enough data for 8-day consolidation
            ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
            ZonedDateTime startTime = now.minusDays(10).truncatedTo(ChronoUnit.DAYS);
            
            long startEpoch = startTime.toEpochSecond();
            long endEpoch = now.toEpochSecond();
            
            List<Candle> dailyCandles = deltaApiService.getDailyCandles(symbol, startEpoch, endEpoch);
            
            if (dailyCandles == null || dailyCandles.size() < CONSOLIDATION_DAYS) {
                log.warn("Not enough daily candles for {}: {}/{}", symbol, 
                    dailyCandles != null ? dailyCandles.size() : 0, CONSOLIDATION_DAYS);
                return;
            }
            
            // Take the last 8 candles for consolidation analysis
            List<Candle> consolidationCandles = dailyCandles.subList(
                dailyCandles.size() - CONSOLIDATION_DAYS, dailyCandles.size());
            
            // Calculate zone high and low
            BigDecimal zoneHigh = consolidationCandles.stream()
                .map(Candle::getHigh)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
            
            BigDecimal zoneLow = consolidationCandles.stream()
                .map(Candle::getLow)
                .min(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
            
            BigDecimal midpoint = zoneHigh.add(zoneLow).divide(BigDecimal.valueOf(2), 2, 
                java.math.RoundingMode.HALF_UP);
            
            BigDecimal zoneWidth = zoneHigh.subtract(zoneLow);
            BigDecimal zoneWidthPct = midpoint.compareTo(BigDecimal.ZERO) > 0 
                ? zoneWidth.divide(midpoint, 4, java.math.RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                : BigDecimal.ZERO;
            
            // Check if range is within consolidation threshold
            if (zoneWidthPct.compareTo(BigDecimal.valueOf(MAX_RANGE_PCT)) > 0) {
                log.debug("{} range {}% exceeds max {}% - not in consolidation", 
                    symbol, zoneWidthPct, MAX_RANGE_PCT);
                consolidationZones.remove(symbol);
                return;
            }
            
            LocalDate zoneStart = consolidationCandles.get(0).getOpenTime()
                .atZone(ZoneOffset.UTC).toLocalDate();
            LocalDate zoneEnd = consolidationCandles.get(consolidationCandles.size() - 1)
                .getOpenTime().atZone(ZoneOffset.UTC).toLocalDate();
            
            CryptoConsolidationZone zone = CryptoConsolidationZone.builder()
                .symbol(symbol)
                .zoneStartDate(zoneStart)
                .zoneEndDate(zoneEnd)
                .zoneHigh(zoneHigh)
                .zoneLow(zoneLow)
                .zoneWidth(zoneWidth)
                .zoneWidthPct(zoneWidthPct)
                .consolidationDays(CONSOLIDATION_DAYS)
                .build();
            
            consolidationZones.put(symbol, zone);
            log.info("Updated consolidation zone for {} | high={} low={} range={}%", 
                symbol, zoneHigh, zoneLow, zoneWidthPct);
            
        } catch (Exception e) {
            log.error("Failed to update consolidation zone for {}: {}", symbol, e.getMessage(), e);
        }
    }
    
    /**
     * Checks for 15-minute timeframe breakouts.
     * Called every 15 minutes between 1:30 PM IST (8:00 AM UTC) and 11:00 PM IST (5:30 PM UTC).
     */
    @Scheduled(cron = "0 0/15 8-17 * * *", zone = "UTC")
    public void check15mBreakouts() {
        log.debug("Checking 15m breakouts for {} symbols", monitoredSymbols.size());
        for (String symbol : monitoredSymbols) {
            checkForBreakout(symbol, CryptoBreakoutAlert.Timeframe.MINUTES_15);
        }
    }
    
    /**
     * Checks for daily timeframe breakouts.
     * Called once per day.
     */
    @Scheduled(cron = "0 0 0 * * *", zone = "UTC")
    public void checkDailyBreakouts() {
        log.info("Checking daily breakouts for {} symbols", monitoredSymbols.size());
        // First update consolidation zones
        for (String symbol : monitoredSymbols) {
            updateConsolidationZone(symbol);
        }
        // Then check for breakouts
        for (String symbol : monitoredSymbols) {
            checkForBreakout(symbol, CryptoBreakoutAlert.Timeframe.DAILY);
        }
    }
    
    /**
     * Checks for breakout on a specific timeframe.
     */
    private void checkForBreakout(String symbol, CryptoBreakoutAlert.Timeframe timeframe) {
        CryptoConsolidationZone zone = consolidationZones.get(symbol);
        if (zone == null) {
            log.debug("No consolidation zone found for {}", symbol);
            return;
        }
        
        try {
            Candle currentCandle;
            if (timeframe == CryptoBreakoutAlert.Timeframe.MINUTES_15) {
                currentCandle = deltaApiService.getLastCompleted15mCandle(symbol);
            } else {
                // For daily, get the most recent daily candle
                ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
                ZonedDateTime startTime = now.minusDays(1).truncatedTo(ChronoUnit.DAYS);
                List<Candle> dailyCandles = deltaApiService.getDailyCandles(
                    symbol, startTime.toEpochSecond(), now.toEpochSecond());
                currentCandle = dailyCandles != null && !dailyCandles.isEmpty() 
                    ? dailyCandles.get(dailyCandles.size() - 1) : null;
            }
            
            if (currentCandle == null) {
                log.warn("No candle available for {} on {}", symbol, timeframe);
                return;
            }
            
            BigDecimal closePrice = currentCandle.getClose();
            CryptoBreakoutAlert.Direction direction = null;
            
            if (zone.isBullishBreakout(closePrice)) {
                direction = CryptoBreakoutAlert.Direction.BULLISH_BREAKOUT;
            } else if (zone.isBearishBreakdown(closePrice)) {
                direction = CryptoBreakoutAlert.Direction.BEARISH_BREAKDOWN;
            }
            
            if (direction != null) {
                fireAlert(symbol, direction, timeframe, closePrice, zone, currentCandle);
            }
            
        } catch (Exception e) {
            log.error("Error checking breakout for {} on {}: {}", symbol, timeframe, e.getMessage(), e);
        }
    }
    
    /**
     * Fires an alert via Telegram if not on cooldown.
     */
    private void fireAlert(String symbol, CryptoBreakoutAlert.Direction direction, 
                          CryptoBreakoutAlert.Timeframe timeframe, BigDecimal price,
                          CryptoConsolidationZone zone, Candle candle) {
        
        String cooldownKey = symbol + ":" + direction.name() + ":" + timeframe.name();
        
        if (isOnCooldown(cooldownKey)) {
            log.debug("Alert suppressed (cooldown active) for key={}", cooldownKey);
            return;
        }
        
        CryptoBreakoutAlert alert = CryptoBreakoutAlert.builder()
            .symbol(symbol)
            .direction(direction)
            .timeframe(timeframe)
            .breakoutPrice(price)
            .zoneHigh(zone.getZoneHigh())
            .zoneLow(zone.getZoneLow())
            .zoneWidthPct(zone.getZoneWidthPct())
            .alertTime(Instant.now())
            .zoneStartDate(zone.getZoneStartDate())
            .zoneEndDate(zone.getZoneEndDate())
            .consolidationDays(zone.getConsolidationDays())
            .build();
        
        log.info("🚨 CRYPTO BREAKOUT ALERT | {} {} {} | price={} | zone=[{}-{}]", 
            symbol, direction, timeframe, price, zone.getZoneLow(), zone.getZoneHigh());
        
        telegramService.sendCryptoBreakoutAlert(alert);
        lastAlertTime.put(cooldownKey, Instant.now());
    }
    
    private boolean isOnCooldown(String key) {
        Instant last = lastAlertTime.get(key);
        if (last == null) return false;
        long cooldownSeconds = (long) appConfig.getCooldownMinutes() * 60;
        return Instant.now().isBefore(last.plusSeconds(cooldownSeconds));
    }
    
    /**
     * Manual trigger to update all consolidation zones.
     */
    public void updateAllZones() {
        log.info("Manual trigger: updating all consolidation zones");
        for (String symbol : monitoredSymbols) {
            updateConsolidationZone(symbol);
        }
    }
    
    /**
     * Manual trigger to check for breakouts on all timeframes.
     */
    public void checkAllBreakouts() {
        log.info("Manual trigger: checking all breakouts");
        for (String symbol : monitoredSymbols) {
            checkForBreakout(symbol, CryptoBreakoutAlert.Timeframe.MINUTES_15);
            checkForBreakout(symbol, CryptoBreakoutAlert.Timeframe.DAILY);
        }
    }
    
    /**
     * Returns the current consolidation zone for a symbol.
     */
    public CryptoConsolidationZone getConsolidationZone(String symbol) {
        return consolidationZones.get(symbol);
    }
    
    /**
     * Returns all consolidation zones.
     */
    public Map<String, CryptoConsolidationZone> getAllConsolidationZones() {
        return Map.copyOf(consolidationZones);
    }
}
