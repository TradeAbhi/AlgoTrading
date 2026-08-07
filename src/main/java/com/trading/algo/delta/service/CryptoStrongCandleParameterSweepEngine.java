package com.trading.algo.delta.service;

import com.trading.algo.delta.model.Candle;
import com.trading.algo.delta.model.CryptoStrongCandleBacktestReport;
import com.trading.algo.delta.model.CryptoStrongCandleTradeRecord;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the Strong-Candle breakout strategy across a grid of (ATR stop
 * multiplier x volume multiplier) combinations WITHOUT re-hitting the Delta
 * API per combination.
 *
 * Candle data (daily + 15m) is fetched exactly once per symbol, and the
 * ATR(14) / volume-average(20) series are precomputed once over the full
 * range. Each grid combination then only does cheap in-memory comparisons
 * against that shared data.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CryptoStrongCandleParameterSweepEngine {

    // ---- Fixed parameters (not swept) ----
    private static final BigDecimal BODY_RATIO = BigDecimal.valueOf(0.7);
    private static final int ATR_PERIOD = 14;
    private static final int VOLUME_LOOKBACK = 20;
    private static final BigDecimal PARTIAL_TARGET_R = BigDecimal.valueOf(2);
    private static final BigDecimal FINAL_TARGET_R = BigDecimal.valueOf(3);

    // ---- Swept parameters (3 x 3 = 9 combinations) ----
    private static final List<BigDecimal> ATR_SL_MULTIPLIERS = List.of(
            BigDecimal.valueOf(0.25), BigDecimal.valueOf(0.5), BigDecimal.valueOf(0.7));

    private static final List<BigDecimal> VOLUME_MULTIPLIERS = List.of(
            BigDecimal.valueOf(1), BigDecimal.valueOf(1.5), BigDecimal.valueOf(2));

    private final DeltaApiService deltaApiService;

    /**
     * Fetches data once per symbol, then runs every (atr x volume)
     * combination in memory. Results are sorted by combined (all-symbol)
     * profit factor, descending.
     */
    public List<ParameterCombinationResult> runSweep(
            List<String> symbols,
            LocalDate fromDate,
            LocalDate toDate) {

        log.info("Fetching candle data once per symbol for {} symbols, {} to {}",
                symbols.size(), fromDate, toDate);

        Map<String, SymbolData> symbolDataMap = new LinkedHashMap<>();

        for (String symbol : symbols) {
            symbolDataMap.put(symbol, prepareSymbolData(symbol, fromDate, toDate));
        }

        List<ParameterCombination> combos = buildGrid();

        log.info("Running {} parameter combinations across {} symbols (no further API calls)",
                combos.size(), symbols.size());

        List<ParameterCombinationResult> results = new ArrayList<>();

        for (ParameterCombination combo : combos) {

            Map<String, CryptoStrongCandleBacktestReport> perSymbolReports = new LinkedHashMap<>();
            List<CryptoStrongCandleTradeRecord> allTrades = new ArrayList<>();

            for (Map.Entry<String, SymbolData> entry : symbolDataMap.entrySet()) {

                CryptoStrongCandleBacktestReport report = runForCombo(entry.getValue(), combo);
                perSymbolReports.put(entry.getKey(), report);
                allTrades.addAll(report.getTrades());
            }

            CryptoStrongCandleBacktestReport combinedReport =
                    CryptoStrongCandleBacktestReport.fromTrades(allTrades);

            results.add(new ParameterCombinationResult(combo, perSymbolReports, combinedReport));
        }

        results.sort((a, b) -> {
            BigDecimal pfA = a.getCombinedReport().getProfitFactor();
            BigDecimal pfB = b.getCombinedReport().getProfitFactor();
            return pfB.compareTo(pfA);
        });

        return results;
    }

    /** Logs a compact leaderboard - call after runSweep(). */
    public void printSummary(List<ParameterCombinationResult> results) {

        log.info("=== Parameter Sweep Results (sorted by combined profit factor) ===");

        for (ParameterCombinationResult r : results) {

            CryptoStrongCandleBacktestReport rpt = r.getCombinedReport();

            log.info("ATRx{} | VOLx{} -> trades={}, winRate={}%, PF={}, netProfit={}, expectancy={}",
                    r.getCombination().getAtrMultiplier(),
                    r.getCombination().getVolumeMultiplier(),
                    rpt.getTotalTrades(),
                    rpt.getWinRate(),
                    rpt.getProfitFactor(),
                    rpt.getNetProfit(),
                    rpt.getExpectancy());
        }
    }

    private List<ParameterCombination> buildGrid() {

        List<ParameterCombination> combos = new ArrayList<>();

        for (BigDecimal atrMult : ATR_SL_MULTIPLIERS) {
            for (BigDecimal volMult : VOLUME_MULTIPLIERS) {
                combos.add(new ParameterCombination(atrMult, volMult));
            }
        }

        return combos;
    }

    // ---------------- One-time per-symbol data preparation ----------------

    private SymbolData prepareSymbolData(String symbol, LocalDate fromDate, LocalDate toDate) {

        List<Candle> dailyCandles = deltaApiService.getDailyCandles(
                symbol,
                fromDate.minusDays(2).atStartOfDay(ZoneOffset.UTC).toEpochSecond(),
                toDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond());

        dailyCandles = dailyCandles == null ? new ArrayList<>() : new ArrayList<>(dailyCandles);
        dailyCandles.sort(Comparator.comparing(Candle::getOpenTime));

        // Single bulk fetch for the ENTIRE 15m history - this is the fix.
        // The old engine fetched 15m candles inside the per-day loop, which
        // meant one API call per matching breakout day. Now it's one call
        // per symbol, period, regardless of how many parameter combos run.
        List<Candle> all15m = deltaApiService.get15mCandles(
                symbol,
                fromDate.minusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond(),
                toDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond());

        all15m = all15m == null ? new ArrayList<>() : new ArrayList<>(all15m);
        all15m.sort(Comparator.comparing(Candle::getOpenTime));

        BigDecimal[] atrSeries = computeATRSeries(all15m, ATR_PERIOD);
        BigDecimal[] avgVolumeSeries = computeAvgVolumeSeries(all15m, VOLUME_LOOKBACK);

        List<DailyCandidate> candidates = buildCandidates(dailyCandles, all15m, fromDate, toDate);

        log.info("{}: {} daily candles, {} 15m candles, {} breakout candidates",
                symbol, dailyCandles.size(), all15m.size(), candidates.size());

        return new SymbolData(symbol, all15m, atrSeries, avgVolumeSeries, candidates);
    }

    private List<DailyCandidate> buildCandidates(
            List<Candle> dailyCandles,
            List<Candle> all15m,
            LocalDate fromDate,
            LocalDate toDate) {

        List<DailyCandidate> candidates = new ArrayList<>();

        for (int i = 1; i < dailyCandles.size(); i++) {

            Candle previousDay = dailyCandles.get(i - 1);
            Candle currentDay = dailyCandles.get(i);

            LocalDate tradeDate = currentDay.getOpenTime().atZone(ZoneOffset.UTC).toLocalDate();

            if (tradeDate.isBefore(fromDate) || tradeDate.isAfter(toDate)) {
                continue;
            }

            BigDecimal range = previousDay.getHigh().subtract(previousDay.getLow());

            if (range.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }

            BigDecimal body = previousDay.getClose().subtract(previousDay.getOpen()).abs();
            BigDecimal bodyRatio = body.divide(range, 4, RoundingMode.HALF_UP);

            if (bodyRatio.compareTo(BODY_RATIO) < 0) {
                continue;
            }

            CryptoStrongCandleTradeRecord.Direction direction;
            int cmp = previousDay.getClose().compareTo(previousDay.getOpen());

            if (cmp > 0) {
                direction = CryptoStrongCandleTradeRecord.Direction.BULLISH;
            } else if (cmp < 0) {
                direction = CryptoStrongCandleTradeRecord.Direction.BEARISH;
            } else {
                continue;
            }

            Instant dayStart = tradeDate.atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant dayEnd = tradeDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

            int dayStartIndex = firstIndexAtOrAfter(all15m, dayStart);
            int dayEndIndex = firstIndexAtOrAfter(all15m, dayEnd);

            if (dayStartIndex >= dayEndIndex) {
                // No 15m candles available for this day - skip rather than fail.
                continue;
            }

            candidates.add(new DailyCandidate(
                    tradeDate, previousDay, direction, bodyRatio, dayStartIndex, dayEndIndex));
        }

        return candidates;
    }

    /** Binary search: index of the first candle with openTime >= target. */
    private int firstIndexAtOrAfter(List<Candle> candles, Instant target) {

        int lo = 0;
        int hi = candles.size();

        while (lo < hi) {

            int mid = (lo + hi) >>> 1;

            if (candles.get(mid).getOpenTime().isBefore(target)) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }

        return lo;
    }

    /**
     * atr[idx] = ATR(period) computed from candles [idx-period, idx-1],
     * i.e. the ATR value usable when evaluating candles.get(idx) as a
     * potential breakout candle. Null for indices without enough history.
     */
    private BigDecimal[] computeATRSeries(List<Candle> candles, int period) {

        BigDecimal[] atr = new BigDecimal[candles.size()];

        for (int idx = period + 1; idx < candles.size(); idx++) {

            BigDecimal trSum = BigDecimal.ZERO;

            for (int j = idx - period; j < idx; j++) {

                Candle current = candles.get(j);
                Candle previous = candles.get(j - 1);

                BigDecimal highLow = current.getHigh().subtract(current.getLow());
                BigDecimal highPrevClose = current.getHigh().subtract(previous.getClose()).abs();
                BigDecimal lowPrevClose = current.getLow().subtract(previous.getClose()).abs();

                trSum = trSum.add(highLow.max(highPrevClose).max(lowPrevClose));
            }

            atr[idx] = trSum.divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);
        }

        return atr;
    }

    /**
     * avgVolume[idx] = average volume over the `lookback` candles ending
     * just before idx. Null for indices without enough history.
     */
    private BigDecimal[] computeAvgVolumeSeries(List<Candle> candles, int lookback) {

        BigDecimal[] avg = new BigDecimal[candles.size()];

        for (int idx = lookback; idx < candles.size(); idx++) {

            BigDecimal sum = BigDecimal.ZERO;

            for (int j = idx - lookback; j < idx; j++) {
                sum = sum.add(candles.get(j).getVolume());
            }

            avg[idx] = sum.divide(BigDecimal.valueOf(lookback), 8, RoundingMode.HALF_UP);
        }

        return avg;
    }

    // ---------------- Per-combination simulation (in-memory only) ----------------

    private CryptoStrongCandleBacktestReport runForCombo(SymbolData data, ParameterCombination combo) {

        List<CryptoStrongCandleTradeRecord> trades = new ArrayList<>();

        for (DailyCandidate candidate : data.getCandidates()) {

            CryptoStrongCandleTradeRecord trade = findTradeForCombo(data, candidate, combo);

            if (trade != null) {
                trades.add(trade);
            }
        }

        return CryptoStrongCandleBacktestReport.fromTrades(trades);
    }

    private CryptoStrongCandleTradeRecord findTradeForCombo(
            SymbolData data,
            DailyCandidate candidate,
            ParameterCombination combo) {

        List<Candle> candles = data.getAll15m();

        for (int i = candidate.getDayStartIndex(); i < candidate.getDayEndIndex(); i++) {

            Candle candle = candles.get(i);

            boolean breakout;

            if (candidate.getDirection() == CryptoStrongCandleTradeRecord.Direction.BULLISH) {
                breakout = candle.getClose().compareTo(candidate.getPreviousDay().getHigh()) > 0;
            } else {
                breakout = candle.getClose().compareTo(candidate.getPreviousDay().getLow()) < 0;
            }

            if (!breakout) {
                continue;
            }

            BigDecimal avgVolume = data.getAvgVolumeSeries()[i];

            if (avgVolume == null || avgVolume.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }

            boolean volumeConfirmed = candle.getVolume()
                    .compareTo(avgVolume.multiply(combo.getVolumeMultiplier())) > 0;

            if (!volumeConfirmed) {
                continue;
            }

            BigDecimal atr = data.getAtrSeries()[i];

            if (atr == null || atr.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }

            return simulateTrade(data.getSymbol(), candidate, candle, i, atr, combo, candles);
        }

        return null;
    }

    private CryptoStrongCandleTradeRecord simulateTrade(

            String symbol,
            DailyCandidate candidate,
            Candle breakoutCandle,
            int startIndex,
            BigDecimal atr,
            ParameterCombination combo,
            List<Candle> candles) {

        CryptoStrongCandleTradeRecord.Direction direction = candidate.getDirection();
        Candle previousDay = candidate.getPreviousDay();
        BigDecimal bodyRatio = candidate.getBodyRatio();
        LocalDate tradeDate = candidate.getTradeDate();

        // Trades are bounded to the same day-plus-one window the original
        // engine used (it fetched tradeDate -> tradeDate+1 per day).
        int boundIndexExclusive = candidate.getDayEndIndex();

        BigDecimal entry = breakoutCandle.getClose();
        BigDecimal atrBuffer = atr.multiply(combo.getAtrMultiplier());

        BigDecimal stopLoss;

        if (direction == CryptoStrongCandleTradeRecord.Direction.BULLISH) {
            stopLoss = breakoutCandle.getLow().subtract(atrBuffer);
        } else {
            stopLoss = breakoutCandle.getHigh().add(atrBuffer);
        }

        BigDecimal risk = entry.subtract(stopLoss).abs();

        if (risk.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }

        BigDecimal targetPartial;
        BigDecimal targetFinal;

        if (direction == CryptoStrongCandleTradeRecord.Direction.BULLISH) {
            targetPartial = entry.add(risk.multiply(PARTIAL_TARGET_R));
            targetFinal = entry.add(risk.multiply(FINAL_TARGET_R));
        } else {
            targetPartial = entry.subtract(risk.multiply(PARTIAL_TARGET_R));
            targetFinal = entry.subtract(risk.multiply(FINAL_TARGET_R));
        }

        boolean partialBooked = false;
        boolean breakEvenActivated = false;

        BigDecimal finalExitPrice = entry;
        BigDecimal partialExitPrice = null;

        CryptoStrongCandleTradeRecord.ExitReason exitReason =
                CryptoStrongCandleTradeRecord.ExitReason.END_OF_DATA;

        int candlesHeld = 0;

        for (int i = startIndex + 1; i < boundIndexExclusive; i++) {

            Candle candle = candles.get(i);

            candlesHeld++;

            if (direction == CryptoStrongCandleTradeRecord.Direction.BULLISH) {

                if (!breakEvenActivated && candle.getLow().compareTo(stopLoss) <= 0) {
                    finalExitPrice = stopLoss;
                    exitReason = CryptoStrongCandleTradeRecord.ExitReason.STOP_LOSS;
                    break;
                }

                if (!partialBooked && candle.getHigh().compareTo(targetPartial) >= 0) {
                    partialBooked = true;
                    breakEvenActivated = true;
                    partialExitPrice = targetPartial;
                    stopLoss = entry;
                }

                if (breakEvenActivated && candle.getLow().compareTo(stopLoss) <= 0) {
                    finalExitPrice = stopLoss;
                    exitReason = CryptoStrongCandleTradeRecord.ExitReason.BREAK_EVEN;
                    break;
                }

                if (partialBooked && candle.getHigh().compareTo(targetFinal) >= 0) {
                    finalExitPrice = targetFinal;
                    exitReason = CryptoStrongCandleTradeRecord.ExitReason.TARGET_4R;
                    break;
                }

            } else {

                if (!breakEvenActivated && candle.getHigh().compareTo(stopLoss) >= 0) {
                    finalExitPrice = stopLoss;
                    exitReason = CryptoStrongCandleTradeRecord.ExitReason.STOP_LOSS;
                    break;
                }

                if (!partialBooked && candle.getLow().compareTo(targetPartial) <= 0) {
                    partialBooked = true;
                    breakEvenActivated = true;
                    partialExitPrice = targetPartial;
                    stopLoss = entry;
                }

                if (breakEvenActivated && candle.getHigh().compareTo(stopLoss) >= 0) {
                    finalExitPrice = stopLoss;
                    exitReason = CryptoStrongCandleTradeRecord.ExitReason.BREAK_EVEN;
                    break;
                }

                if (partialBooked && candle.getLow().compareTo(targetFinal) <= 0) {
                    finalExitPrice = targetFinal;
                    exitReason = CryptoStrongCandleTradeRecord.ExitReason.TARGET_4R;
                    break;
                }
            }
        }

        if (exitReason == CryptoStrongCandleTradeRecord.ExitReason.END_OF_DATA) {
            int lastIndex = Math.min(boundIndexExclusive - 1, candles.size() - 1);
            finalExitPrice = candles.get(lastIndex).getClose();
        }

        BigDecimal pnlPoints;
        BigDecimal pnlR;

        if (!partialBooked) {

            pnlPoints = direction == CryptoStrongCandleTradeRecord.Direction.BULLISH
                    ? finalExitPrice.subtract(entry)
                    : entry.subtract(finalExitPrice);

        } else {

            BigDecimal firstLeg = direction == CryptoStrongCandleTradeRecord.Direction.BULLISH
                    ? partialExitPrice.subtract(entry)
                    : entry.subtract(partialExitPrice);

            BigDecimal secondLeg = direction == CryptoStrongCandleTradeRecord.Direction.BULLISH
                    ? finalExitPrice.subtract(entry)
                    : entry.subtract(finalExitPrice);

            pnlPoints = firstLeg.multiply(BigDecimal.valueOf(0.60))
                    .add(secondLeg.multiply(BigDecimal.valueOf(0.40)));
        }

        pnlR = pnlPoints.divide(risk, 4, RoundingMode.HALF_UP);

        BigDecimal mfe = BigDecimal.ZERO;
        BigDecimal mae = BigDecimal.ZERO;

        for (int i = startIndex; i < boundIndexExclusive; i++) {

            Candle c = candles.get(i);

            if (direction == CryptoStrongCandleTradeRecord.Direction.BULLISH) {

                BigDecimal favourable = c.getHigh().subtract(entry);
                BigDecimal adverse = entry.subtract(c.getLow());

                if (favourable.compareTo(mfe) > 0) mfe = favourable;
                if (adverse.compareTo(mae) > 0) mae = adverse;

            } else {

                BigDecimal favourable = entry.subtract(c.getLow());
                BigDecimal adverse = c.getHigh().subtract(entry);

                if (favourable.compareTo(mfe) > 0) mfe = favourable;
                if (adverse.compareTo(mae) > 0) mae = adverse;
            }
        }

        int exitIndex = Math.min(boundIndexExclusive - 1, startIndex + candlesHeld);

        return CryptoStrongCandleTradeRecord.builder()
                .symbol(symbol)
                .tradeDate(tradeDate)
                .direction(direction)
                .entryTime(breakoutCandle.getOpenTime().atZone(ZoneOffset.UTC).toLocalDateTime())
                .exitTime(candles.get(exitIndex).getCloseTime().atZone(ZoneOffset.UTC).toLocalDateTime())
                .previousDayOpen(previousDay.getOpen())
                .previousDayHigh(previousDay.getHigh())
                .previousDayLow(previousDay.getLow())
                .previousDayClose(previousDay.getClose())
                .bodyRatio(bodyRatio)
                .breakoutHigh(breakoutCandle.getHigh())
                .breakoutLow(breakoutCandle.getLow())
                .entryPrice(entry)
                .stopLoss(stopLoss)
                .risk(risk)
                .target3R(targetPartial)
                .target4R(targetFinal)
                .partialBooked(partialBooked)
                .partialExitPrice(partialExitPrice)
                .finalExitPrice(finalExitPrice)
                .exitReason(exitReason)
                .pnlPoints(pnlPoints)
                .pnlR(pnlR)
                .mfe(mfe)
                .mae(mae)
                .candlesHeld(candlesHeld)
                .breakEvenActivated(breakEvenActivated)
                .build();
    }

    // ---------------- Supporting POJOs ----------------

    @Getter
    @AllArgsConstructor
    private static class SymbolData {
        private final String symbol;
        private final List<Candle> all15m;
        private final BigDecimal[] atrSeries;
        private final BigDecimal[] avgVolumeSeries;
        private final List<DailyCandidate> candidates;
    }

    @Getter
    @AllArgsConstructor
    private static class DailyCandidate {
        private final LocalDate tradeDate;
        private final Candle previousDay;
        private final CryptoStrongCandleTradeRecord.Direction direction;
        private final BigDecimal bodyRatio;
        private final int dayStartIndex;
        private final int dayEndIndex;
    }

    @Getter
    @AllArgsConstructor
    public static class ParameterCombination {
        private final BigDecimal atrMultiplier;
        private final BigDecimal volumeMultiplier;
    }

    @Getter
    @AllArgsConstructor
    public static class ParameterCombinationResult {
        private final ParameterCombination combination;
        private final Map<String, CryptoStrongCandleBacktestReport> perSymbolReports;
        private final CryptoStrongCandleBacktestReport combinedReport;
    }
}