package com.trading.algo.delta.service;

import com.trading.algo.delta.model.Candle;
import com.trading.algo.delta.model.CryptoTradeRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Backtest engine for Crypto Consolidation Breakout strategy.
 *
 * Strategy Logic:
 * 1. Identify 8-day consolidation zones (price coiling within a range)
 * 2. Entry when price closes above/below consolidation zone
 * 3. SL is placed below the preceding candle's low (for bullish) or above preceding candle's high (for bearish)
 * 4. Target is 1:3 risk-reward ratio
 * 5. Exit at target, SL, or end of day
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CryptoConsolidationBacktestEngine {

    private static final int CONSOLIDATION_DAYS = 8;
    private static final double MAX_RANGE_PCT = 5.0;
    private static final int TARGET_RR = 3;
    private static final int EOD_HOUR = 23; // 23:00 UTC for crypto markets
    private static final int THREAD_POOL_SIZE = 10; // Parallel processing threads
    private static final double MIN_VOLUME_RATIO = 1.8; // Breakout volume >= 1.8x average volume

    private final DeltaApiService deltaApiService;

    /**
     * Runs backtest for a symbol over a date range using parallel processing.
     *
     * @param symbol        Crypto symbol (e.g., "BTCUSD")
     * @param fromDate      Start date for backtest
     * @param toDate        End date for backtest
     * @param timeframe     Timeframe for breakout detection (15m or Daily)
     * @param maxRangePct   Maximum consolidation range percentage
     * @param targetRR      Target risk-reward ratio
     * @param minVolumeRatio Minimum volume ratio for breakout confirmation (default 1.8)
     */
    public List<CryptoTradeRecord> runBacktest(
            String symbol,
            LocalDate fromDate,
            LocalDate toDate,
            CryptoTradeRecord.Timeframe timeframe,
            double maxRangePct,
            int targetRR,
            double minVolumeRatio) {

        log.info("Crypto Consolidation Backtest START | {} | {} | from={} to={} maxRange={} targetRR={} minVolumeRatio={} (parallel processing)",
                symbol, timeframe, fromDate, toDate, maxRangePct, targetRR, minVolumeRatio);

        // Collect all trading days
        List<LocalDate> tradingDays = new ArrayList<>();
        LocalDate day = fromDate;
        while (!day.isAfter(toDate)) {
            if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) {
                tradingDays.add(day);
            }
            day = day.plusDays(1);
        }

        // Process days in parallel using parallel stream
        List<CryptoTradeRecord> allTrades = tradingDays.parallelStream()
                .map(date -> {
                    try {
                        List<CryptoTradeRecord> dayTrades = backtestDay(
                                symbol, date, timeframe, maxRangePct, targetRR, minVolumeRatio);
                        if (!dayTrades.isEmpty()) {
                            log.info("[{}] {} - {} trades found", symbol, date, dayTrades.size());
                        }
                        return dayTrades;
                    } catch (Exception e) {
                        log.error("[{}] Error on {}: {}", symbol, date, e.getMessage());
                        return new ArrayList<CryptoTradeRecord>();
                    }
                })
                .flatMap(List::stream)
                .collect(Collectors.toList());

        log.info("Crypto Consolidation Backtest COMPLETE | {} - {} total trades (processed {} days in parallel)",
                symbol, allTrades.size(), tradingDays.size());
        return allTrades;
    }

    /**
     * Backtests a single day.
     */
    private List<CryptoTradeRecord> backtestDay(
            String symbol,
            LocalDate date,
            CryptoTradeRecord.Timeframe timeframe,
            double maxRangePct,
            int targetRR,
            double minVolumeRatio) {

        List<CryptoTradeRecord> trades = new ArrayList<>();

        // Fetch candles based on timeframe
        List<Candle> candles;
        if (timeframe == CryptoTradeRecord.Timeframe.MINUTES_15) {
            ZonedDateTime startOfDay = date.atStartOfDay(ZoneOffset.UTC);
            ZonedDateTime endOfDay = date.plusDays(1).atStartOfDay(ZoneOffset.UTC);
            candles = deltaApiService.get15mCandles(
                    symbol,
                    startOfDay.toEpochSecond(),
                    endOfDay.toEpochSecond());
        } else {
            // Daily timeframe - fetch more days for consolidation analysis
            ZonedDateTime startDate = date.minusDays(CONSOLIDATION_DAYS + 2).atStartOfDay(ZoneOffset.UTC);
            ZonedDateTime endDate = date.plusDays(1).atStartOfDay(ZoneOffset.UTC);
            candles = deltaApiService.getDailyCandles(
                    symbol,
                    startDate.toEpochSecond(),
                    endDate.toEpochSecond());
        }

        if (candles == null || candles.size() < CONSOLIDATION_DAYS + 2) {
            return trades;
        }

        // Dedup: one trade per zone per day
        Set<String> tradedZones = new HashSet<>();

        // Walk through candles starting from index CONSOLIDATION_DAYS + 1
        // Need at least CONSOLIDATION_DAYS + 2 candles to have second prior candle for SL
        for (int i = CONSOLIDATION_DAYS + 1; i < candles.size(); i++) {
            Candle current = candles.get(i);

            // Skip candles after EOD
            if (isAfterEOD(current)) {
                break;
            }

            // Get consolidation window (previous CONSOLIDATION_DAYS candles before current)
            List<Candle> window = candles.subList(i - CONSOLIDATION_DAYS, i);

            // Calculate consolidation zone
            BigDecimal zoneHigh = window.stream()
                    .map(Candle::getHigh)
                    .max(BigDecimal::compareTo)
                    .orElse(BigDecimal.ZERO);

            BigDecimal zoneLow = window.stream()
                    .map(Candle::getLow)
                    .min(BigDecimal::compareTo)
                    .orElse(BigDecimal.ZERO);

            BigDecimal midpoint = zoneHigh.add(zoneLow).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
            if (midpoint.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }

            BigDecimal zoneWidth = zoneHigh.subtract(zoneLow);
            BigDecimal zoneWidthPct = zoneWidth.divide(midpoint, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));

            // Check if range is within consolidation threshold
            if (zoneWidthPct.compareTo(BigDecimal.valueOf(maxRangePct)) > 0) {
                continue;
            }

            // Check for breakout
            boolean bullishBreakout = current.getClose().compareTo(zoneHigh) > 0;
            boolean bearishBreakdown = current.getClose().compareTo(zoneLow) < 0;

            if (!bullishBreakout && !bearishBreakdown) {
                continue;
            }

            // Volume filter: Breakout volume >= minVolumeRatio x average volume of consolidation zone
            double avgVolume = window.stream()
                    .mapToDouble(c -> c.getVolume().doubleValue())
                    .average()
                    .orElse(0.0);
            
            double breakoutVolume = current.getVolume().doubleValue();
            double volumeRatio = avgVolume > 0 ? breakoutVolume / avgVolume : 0.0;
            
            if (volumeRatio < minVolumeRatio) {
                log.debug("[{}] Volume filter failed: breakout volume={} < {}x avg volume={}",
                        date, breakoutVolume, minVolumeRatio, avgVolume);
                continue;
            }

            // Dedup check
            String zoneKey = String.format("%s:%.2f:%.2f", date, zoneHigh, zoneLow);
            if (tradedZones.contains(zoneKey)) {
                continue;
            }
            tradedZones.add(zoneKey);

            // Calculate SL below second candle prior to breakout (candle[i-2])
            Candle secondPriorCandle = candles.get(i - 2);
            BigDecimal stopLoss;
            if (bullishBreakout) {
                stopLoss = secondPriorCandle.getLow();
            } else {
                stopLoss = secondPriorCandle.getHigh();
            }

            BigDecimal entry = current.getClose();
            BigDecimal risk = entry.subtract(stopLoss).abs();

            if (risk.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }

            // Calculate target with 1:RR ratio
            BigDecimal target = bullishBreakout
                    ? entry.add(risk.multiply(BigDecimal.valueOf(targetRR)))
                    : entry.subtract(risk.multiply(BigDecimal.valueOf(targetRR)));

            CryptoTradeRecord.Direction direction = bullishBreakout
                    ? CryptoTradeRecord.Direction.BULLISH
                    : CryptoTradeRecord.Direction.BEARISH;

            // Simulate trade
            CryptoTradeRecord trade = simulateTrade(
                    symbol, timeframe, date, direction,
                    current, window, zoneHigh, zoneLow, zoneWidthPct,
                    entry, stopLoss, target, risk, candles, i);

            trades.add(trade);
        }

        return trades;
    }

    /**
     * Simulates a trade forward from entry.
     */
    private CryptoTradeRecord simulateTrade(
            String symbol,
            CryptoTradeRecord.Timeframe timeframe,
            LocalDate date,
            CryptoTradeRecord.Direction direction,
            Candle entryCandle,
            List<Candle> consolidationWindow,
            BigDecimal zoneHigh,
            BigDecimal zoneLow,
            BigDecimal zoneWidthPct,
            BigDecimal entry,
            BigDecimal stopLoss,
            BigDecimal target,
            BigDecimal risk,
            List<Candle> candles,
            int entryIdx) {

        CryptoTradeRecord.ExitReason exitReason = CryptoTradeRecord.ExitReason.EOD_EXIT;
        BigDecimal exitPrice = entry;
        Instant exitTime = null;

        // Walk forward from entryIdx + 1
        for (int j = entryIdx + 1; j < candles.size(); j++) {
            Candle c = candles.get(j);

            // EOD exit
            if (isAfterEOD(c)) {
                exitPrice = c.getClose();
                exitTime = c.getCloseTime();
                exitReason = CryptoTradeRecord.ExitReason.EOD_EXIT;
                break;
            }

            if (direction == CryptoTradeRecord.Direction.BULLISH) {
                if (c.getLow().compareTo(stopLoss) <= 0) {
                    exitPrice = stopLoss;
                    exitTime = c.getCloseTime();
                    exitReason = CryptoTradeRecord.ExitReason.SL_HIT;
                    break;
                }
                if (c.getHigh().compareTo(target) >= 0) {
                    exitPrice = target;
                    exitTime = c.getCloseTime();
                    exitReason = CryptoTradeRecord.ExitReason.TARGET_HIT;
                    break;
                }
            } else {
                if (c.getHigh().compareTo(stopLoss) >= 0) {
                    exitPrice = stopLoss;
                    exitTime = c.getCloseTime();
                    exitReason = CryptoTradeRecord.ExitReason.SL_HIT;
                    break;
                }
                if (c.getLow().compareTo(target) <= 0) {
                    exitPrice = target;
                    exitTime = c.getCloseTime();
                    exitReason = CryptoTradeRecord.ExitReason.TARGET_HIT;
                    break;
                }
            }
        }

        // Calculate P&L
        BigDecimal pnlPoints;
        if (direction == CryptoTradeRecord.Direction.BULLISH) {
            pnlPoints = exitPrice.subtract(entry);
        } else {
            pnlPoints = entry.subtract(exitPrice);
        }

        BigDecimal pnlR = risk.compareTo(BigDecimal.ZERO) > 0
                ? pnlPoints.divide(risk, 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        // Get consolidation window dates
        LocalDate zoneStart = consolidationWindow.get(0).getOpenTime()
                .atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate zoneEnd = consolidationWindow.get(consolidationWindow.size() - 1)
                .getOpenTime().atZone(ZoneOffset.UTC).toLocalDate();

        return CryptoTradeRecord.builder()
                .symbol(symbol)
                .direction(direction)
                .timeframe(timeframe)
                .tradeDate(date)
                .entryTime(entryCandle.getOpenTime().atZone(ZoneOffset.UTC).toLocalDateTime())
                .exitTime(exitTime != null ? exitTime.atZone(ZoneOffset.UTC).toLocalDateTime() : null)
                .zoneStartDate(zoneStart)
                .zoneEndDate(zoneEnd)
                .zoneHigh(zoneHigh)
                .zoneLow(zoneLow)
                .zoneWidthPct(zoneWidthPct)
                .consolidationDays(CONSOLIDATION_DAYS)
                .entryPrice(entry)
                .stopLoss(stopLoss)
                .target(target)
                .risk(risk)
                .exitPrice(exitPrice)
                .exitReason(exitReason)
                .pnlPoints(pnlPoints)
                .pnlR(pnlR)
                .build();
    }

    /**
     * Checks if candle is after EOD time.
     */
    private boolean isAfterEOD(Candle candle) {
        ZonedDateTime zdt = candle.getOpenTime().atZone(ZoneOffset.UTC);
        return zdt.getHour() >= EOD_HOUR;
    }
}
