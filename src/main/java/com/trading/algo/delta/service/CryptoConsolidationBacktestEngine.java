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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Backtest engine for Crypto Consolidation Breakout strategy — v2.
 *
 * Changes from v1 (root causes of 15.38% win rate at 1:3 R:R, well below the
 * 25% breakeven threshold, are addressed below):
 *
 *  1. CONFIRMATION ENTRY — no longer enters on the breakout candle itself.
 *     Waits for the NEXT candle to also close beyond the zone before entry.
 *     This filters the "close-back-inside" fakeouts that were causing
 *     entryTime == exitTime immediate stop-outs in the old log.
 *
 *  2. STRUCTURAL + ATR-BUFFERED STOP — SL is no longer just candle[i-2]'s
 *     low/high. It's the structural swing point MINUS/PLUS a volatility
 *     buffer (0.5x ATR of the consolidation window), so normal noise
 *     doesn't clip the trade before the move develops.
 *
 *  3. MIN ZONE WIDTH FILTER — zones tighter than MIN_RANGE_PCT are skipped.
 *     A near-flat 8-candle range isn't consolidation, it's noise, and a
 *     "breakout" from it is meaningless.
 *
 *  4. BREAKOUT STRENGTH FILTER — the confirming candle must close in the
 *     top/bottom quartile of its own range (a decisive close), not just
 *     barely tick past the zone boundary.
 *
 *  5. ADAPTIVE TARGET — target is min(fixed R:R target, measured-move
 *     target = zone height projected from breakout point) so you're not
 *     demanding a move 3x larger than the base that produced it.
 *
 * These are structural fixes, not a guarantee of profitability — re-run
 * the backtest and validate out-of-sample / walk-forward before sizing
 * real capital against this.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CryptoConsolidationBacktestEngine {

    private static final int CONSOLIDATION_DAYS = 8;
    private static final double MAX_RANGE_PCT = 5.0;
    private static final double MIN_RANGE_PCT = 0.6; // NEW: reject near-flat "consolidations"
    private static final int TARGET_RR = 3;
    private static final int EOD_HOUR = 23; // 23:00 UTC for crypto markets
    private static final double MIN_VOLUME_RATIO = 1.8; // Breakout volume >= 1.8x average volume
    private static final double ATR_STOP_MULTIPLIER = 0.5; // NEW: volatility buffer beyond structural stop
    private static final double STRONG_CLOSE_QUARTILE = 0.25; // NEW: close must be in top/bottom 25% of its own range

    private final DeltaApiService deltaApiService;

    public List<CryptoTradeRecord> runBacktest(
            String symbol,
            LocalDate fromDate,
            LocalDate toDate,
            CryptoTradeRecord.Timeframe timeframe,
            double maxRangePct,
            int targetRR,
            double minVolumeRatio) {

        log.info("Crypto Consolidation Backtest v2 START | {} | {} | from={} to={} maxRange={} minRange={} targetRR={} minVolumeRatio={}",
                symbol, timeframe, fromDate, toDate, maxRangePct, MIN_RANGE_PCT, targetRR, minVolumeRatio);

        List<LocalDate> tradingDays = new ArrayList<>();
        LocalDate day = fromDate;
        while (!day.isAfter(toDate)) {
            if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) {
                tradingDays.add(day);
            }
            day = day.plusDays(1);
        }

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

        log.info("Crypto Consolidation Backtest v2 COMPLETE | {} - {} total trades (processed {} days in parallel)",
                symbol, allTrades.size(), tradingDays.size());
        return allTrades;
    }

    private List<CryptoTradeRecord> backtestDay(
            String symbol,
            LocalDate date,
            CryptoTradeRecord.Timeframe timeframe,
            double maxRangePct,
            int targetRR,
            double minVolumeRatio) {

        List<CryptoTradeRecord> trades = new ArrayList<>();

        List<Candle> candles;
        if (timeframe == CryptoTradeRecord.Timeframe.MINUTES_15) {
            ZonedDateTime startOfDay = date.atStartOfDay(ZoneOffset.UTC);
            ZonedDateTime endOfDay = date.plusDays(1).atStartOfDay(ZoneOffset.UTC);
            candles = deltaApiService.get15mCandles(
                    symbol,
                    startOfDay.toEpochSecond(),
                    endOfDay.toEpochSecond());
        } else {
            ZonedDateTime startDate = date.minusDays(CONSOLIDATION_DAYS + 2).atStartOfDay(ZoneOffset.UTC);
            ZonedDateTime endDate = date.plusDays(1).atStartOfDay(ZoneOffset.UTC);
            candles = deltaApiService.getDailyCandles(
                    symbol,
                    startDate.toEpochSecond(),
                    endDate.toEpochSecond());
        }

        // Need one extra candle at the tail for confirmation lookahead
        if (candles == null || candles.size() < CONSOLIDATION_DAYS + 3) {
            return trades;
        }

        Set<String> tradedZones = new HashSet<>();

        // Stop one earlier than before — index i is now the BREAKOUT candle,
        // and we need candle i+1 available to confirm it.
        for (int i = CONSOLIDATION_DAYS + 1; i < candles.size() - 1; i++) {
            Candle breakoutCandle = candles.get(i);

            if (isAfterEOD(breakoutCandle)) {
                break;
            }

            List<Candle> window = candles.subList(i - CONSOLIDATION_DAYS, i);

            BigDecimal zoneHigh = window.stream().map(Candle::getHigh).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            BigDecimal zoneLow = window.stream().map(Candle::getLow).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);

            BigDecimal midpoint = zoneHigh.add(zoneLow).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
            if (midpoint.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }

            BigDecimal zoneWidth = zoneHigh.subtract(zoneLow);
            BigDecimal zoneWidthPct = zoneWidth.divide(midpoint, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));

            // NEW: reject both too-wide AND too-tight (noise) zones
            if (zoneWidthPct.compareTo(BigDecimal.valueOf(maxRangePct)) > 0
                    || zoneWidthPct.compareTo(BigDecimal.valueOf(MIN_RANGE_PCT)) < 0) {
                continue;
            }

            boolean bullishBreak = breakoutCandle.getClose().compareTo(zoneHigh) > 0;
            boolean bearishBreak = breakoutCandle.getClose().compareTo(zoneLow) < 0;
            if (!bullishBreak && !bearishBreak) {
                continue;
            }

            // Volume filter on the breakout candle
            double avgVolume = window.stream().mapToDouble(c -> c.getVolume().doubleValue()).average().orElse(0.0);
            double breakoutVolume = breakoutCandle.getVolume().doubleValue();
            double volumeRatio = avgVolume > 0 ? breakoutVolume / avgVolume : 0.0;
            if (volumeRatio < minVolumeRatio) {
                continue;
            }

            // NEW: breakout candle must show a strong (decisive) close, not a weak tick-through
            if (!hasStrongClose(breakoutCandle, bullishBreak)) {
                continue;
            }

            // NEW: confirmation — next candle must ALSO close beyond the zone.
            // This is the single biggest lever against the fakeout whipsaws
            // that were producing entryTime == exitTime SL hits in v1.
            Candle confirmCandle = candles.get(i + 1);
            boolean confirmed = bullishBreak
                    ? confirmCandle.getClose().compareTo(zoneHigh) > 0
                    : confirmCandle.getClose().compareTo(zoneLow) < 0;
            if (!confirmed) {
                continue;
            }

            String zoneKey = String.format("%s:%.2f:%.2f", date, zoneHigh, zoneLow);
            if (tradedZones.contains(zoneKey)) {
                continue;
            }
            tradedZones.add(zoneKey);

            // NEW: entry is on the CONFIRMATION candle's close, not the breakout candle's
            BigDecimal entry = confirmCandle.getClose();

            // NEW: structural stop = swing extreme across breakout+confirm candles,
            // buffered by ATR of the consolidation window so normal noise doesn't clip it
            BigDecimal atr = averageTrueRange(window);
            BigDecimal atrBuffer = atr.multiply(BigDecimal.valueOf(ATR_STOP_MULTIPLIER));

            BigDecimal stopLoss;
            if (bullishBreak) {
                BigDecimal swingLow = breakoutCandle.getLow().min(confirmCandle.getLow());
                stopLoss = swingLow.subtract(atrBuffer);
            } else {
                BigDecimal swingHigh = breakoutCandle.getHigh().max(confirmCandle.getHigh());
                stopLoss = swingHigh.add(atrBuffer);
            }

            BigDecimal risk = entry.subtract(stopLoss).abs();
            if (risk.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }

            // NEW: adaptive target — cap the fixed R:R target at the measured-move
            // target (zone height projected from the breakout point), whichever is nearer
            BigDecimal fixedTarget = bullishBreak
                    ? entry.add(risk.multiply(BigDecimal.valueOf(targetRR)))
                    : entry.subtract(risk.multiply(BigDecimal.valueOf(targetRR)));

            BigDecimal measuredMoveTarget = bullishBreak
                    ? zoneHigh.add(zoneWidth)
                    : zoneLow.subtract(zoneWidth);

            BigDecimal target = bullishBreak
                    ? fixedTarget.min(measuredMoveTarget)
                    : fixedTarget.max(measuredMoveTarget);

            CryptoTradeRecord.Direction direction = bullishBreak
                    ? CryptoTradeRecord.Direction.BULLISH
                    : CryptoTradeRecord.Direction.BEARISH;

            CryptoTradeRecord trade = simulateTrade(
                    symbol, timeframe, date, direction,
                    confirmCandle, window, zoneHigh, zoneLow, zoneWidthPct,
                    entry, stopLoss, target, risk, candles, i + 1);

            trades.add(trade);
        }

        return trades;
    }

    /** Close must sit in the outer quartile of the candle's own high-low range. */
    private boolean hasStrongClose(Candle candle, boolean bullish) {
        BigDecimal range = candle.getHigh().subtract(candle.getLow());
        if (range.compareTo(BigDecimal.ZERO) == 0) {
            return false;
        }
        BigDecimal closePosition = candle.getClose().subtract(candle.getLow())
                .divide(range, 4, RoundingMode.HALF_UP);
        return bullish
                ? closePosition.compareTo(BigDecimal.valueOf(1 - STRONG_CLOSE_QUARTILE)) >= 0
                : closePosition.compareTo(BigDecimal.valueOf(STRONG_CLOSE_QUARTILE)) <= 0;
    }

    /** Simple ATR (mean true range, no smoothing) over the consolidation window. */
    private BigDecimal averageTrueRange(List<Candle> window) {
        BigDecimal sumTR = BigDecimal.ZERO;
        for (int k = 1; k < window.size(); k++) {
            Candle curr = window.get(k);
            Candle prev = window.get(k - 1);
            BigDecimal highLow = curr.getHigh().subtract(curr.getLow());
            BigDecimal highPrevClose = curr.getHigh().subtract(prev.getClose()).abs();
            BigDecimal lowPrevClose = curr.getLow().subtract(prev.getClose()).abs();
            BigDecimal trueRange = highLow.max(highPrevClose).max(lowPrevClose);
            sumTR = sumTR.add(trueRange);
        }
        int divisor = Math.max(1, window.size() - 1);
        return sumTR.divide(BigDecimal.valueOf(divisor), 4, RoundingMode.HALF_UP);
    }

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

        for (int j = entryIdx + 1; j < candles.size(); j++) {
            Candle c = candles.get(j);

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

        BigDecimal pnlPoints = direction == CryptoTradeRecord.Direction.BULLISH
                ? exitPrice.subtract(entry)
                : entry.subtract(exitPrice);

        BigDecimal pnlR = risk.compareTo(BigDecimal.ZERO) > 0
                ? pnlPoints.divide(risk, 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        LocalDate zoneStart = consolidationWindow.get(0).getOpenTime().atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate zoneEnd = consolidationWindow.get(consolidationWindow.size() - 1).getOpenTime().atZone(ZoneOffset.UTC).toLocalDate();

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

    private boolean isAfterEOD(Candle candle) {
        ZonedDateTime zdt = candle.getOpenTime().atZone(ZoneOffset.UTC);
        return zdt.getHour() >= EOD_HOUR;
    }
}