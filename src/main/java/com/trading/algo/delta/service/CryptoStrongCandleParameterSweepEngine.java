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
import java.util.TreeMap;

/**
 * Runs the Strong-Candle breakout strategy across a grid of
 * (Body Ratio x Breakout Body Ratio x ATR stop multiplier x Volume
 * multiplier x Partial Target R x Final Target R x Time-based SL Trail
 * candles) combinations WITHOUT re-hitting the Delta API per combination.
 *
 * Candle data (daily + 15m) is fetched exactly once per symbol, and the
 * ATR(14) / volume-average(20) series are precomputed once over the full
 * range (these two periods are fixed, not swept). Each grid combination then
 * only does cheap in-memory comparisons against that shared data.
 *
 * Implements observation-based refinements:
 *   Obs 1 - Time-based SL trailing (dynamic/swept: TIME_BASED_SL_TRAIL_CANDLES)
 *   Obs 2 - Skip entries where the breakout candle is already too extended
 *   Obs 3 - Reduce position size when prior candles already show consecutive
 *           movement in the breakout direction before the breakout candle
 *   Obs 5 - Same-day chained re-entry after a stop-loss exit
 *   (Obs 4 and Obs 6 intentionally NOT implemented per instruction - Obs 6
 *    depends on Obs 4's partial-exit event, which doesn't exist here.)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CryptoStrongCandleParameterSweepEngine {

    // ---- Fixed parameters (not swept) ----
    private static final int ATR_PERIOD = 14;
    private static final int VOLUME_LOOKBACK = 20;

    // Delta's /v2/history/candles caps responses at 2000 candles per call.
    // At 15m resolution that's ~20.8 days. We chunk at 18 days to stay
    // safely under the cap with margin.
    private static final int CANDLE_15M_CHUNK_DAYS = 18;

    // ---- Swept parameters ----
    private static final List<BigDecimal> BODY_RATIOS = List.of(
            BigDecimal.valueOf(0.60), BigDecimal.valueOf(0.70), BigDecimal.valueOf(0.80));

    private static final List<BigDecimal> ATR_SL_MULTIPLIERS = List.of(
            BigDecimal.valueOf(0.5), BigDecimal.valueOf(0.75), BigDecimal.valueOf(1.0));

    private static final List<BigDecimal> VOLUME_MULTIPLIERS = List.of(
            BigDecimal.valueOf(1.2), BigDecimal.valueOf(1.3), BigDecimal.valueOf(1.5));

    private static final List<BigDecimal> PARTIAL_TARGET_RS = List.of(
            BigDecimal.valueOf(2), BigDecimal.valueOf(2.5), BigDecimal.valueOf(3));

    private static final List<BigDecimal> FINAL_TARGET_RS = List.of(
            BigDecimal.valueOf(3), BigDecimal.valueOf(3.5), BigDecimal.valueOf(4));

    private static final List<BigDecimal> BREAKOUT_BODY_RATIOS = List.of(
            BigDecimal.valueOf(0.55), BigDecimal.valueOf(0.65), BigDecimal.valueOf(0.75));

    // Obs 1 (dynamic, per your instruction): N candles to monitor after
    // entry (excluding the breakout candle itself) before trailing SL to
    // the extreme of that window, if price hasn't moved favorably yet.
    // Values chosen to bracket the "9-12 hours" / "10-12 candles" you
    // described (15m candles: 8 candles = 2h, up to 12 candles = 3h as a
    // near-term check; tune freely).
    private static final List<Integer> TIME_BASED_SL_TRAIL_CANDLES = List.of(8, 10, 12);

    // ---- Fixed thresholds for Obs 2 / Obs 3 / Obs 5 (not swept - no
    // explicit dynamic ranges were given for these, so kept as tunable
    // constants for now; can be promoted to swept lists the same way as
    // TIME_BASED_SL_TRAIL_CANDLES if you want them backtested too) ----

    // Obs 2: if the breakout candle's ATR-based risk, expressed as % of
    // entry price, exceeds this, the entry is skipped entirely (breakout
    // candle already too extended -> SL would be unreasonably wide).
    private static final BigDecimal MAX_BREAKOUT_RISK_PERCENT = BigDecimal.valueOf(5.0);

    // Obs 3: how many 15m candles immediately BEFORE the breakout candle
    // are checked for consecutive movement in the breakout direction.
    private static final int PRE_BREAKOUT_LOOKBACK_CANDLES = 2;

    // Obs 3: position size multiplier applied when that consecutive
    // pre-move pattern is detected (reduces size rather than skipping).
    private static final BigDecimal REDUCED_SIZE_MULTIPLIER = BigDecimal.valueOf(0.5);

    // Obs 5: safety cap on same-day re-entry chain length, to prevent a
    // pathological choppy day from looping indefinitely.
    private static final int MAX_SAME_DAY_REENTRIES = 5;

    private final DeltaApiService deltaApiService;

    private BigDecimal[] computeCandleBodyRatioSeries(List<Candle> candles) {

        BigDecimal[] ratios = new BigDecimal[candles.size()];

        for (int idx = 0; idx < candles.size(); idx++) {

            Candle c = candles.get(idx);
            BigDecimal range = c.getHigh().subtract(c.getLow());

            if (range.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }

            BigDecimal body = c.getClose().subtract(c.getOpen()).abs();
            ratios[idx] = body.divide(range, 4, RoundingMode.HALF_UP);
        }

        return ratios;
    }

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

    public List<ParameterSweepSummaryRow> toSummary(
            List<ParameterCombinationResult> results, int topN) {

        List<ParameterSweepSummaryRow> summary = new ArrayList<>();

        int limit = Math.min(topN, results.size());

        for (int i = 0; i < limit; i++) {

            ParameterCombinationResult r = results.get(i);
            ParameterCombination c = r.getCombination();
            CryptoStrongCandleBacktestReport rpt = r.getCombinedReport();

            summary.add(new ParameterSweepSummaryRow(
                    i + 1,
                    c.getBodyRatio(),
                    c.getBreakoutBodyRatio(),
                    c.getAtrMultiplier(),
                    c.getVolumeMultiplier(),
                    c.getPartialTargetR(),
                    c.getFinalTargetR(),
                    c.getTimeBasedSlTrailCandles(),
                    rpt.getTotalTrades(),
                    rpt.getWinRate(),
                    rpt.getProfitFactor(),
                    rpt.getExpectancy(),
                    rpt.getNetProfit()));
        }

        return summary;
    }

    public void printSummary(List<ParameterCombinationResult> results) {

        log.info("=== Parameter Sweep Results (sorted by combined profit factor) ===");

        for (ParameterCombinationResult r : results) {

            CryptoStrongCandleBacktestReport rpt = r.getCombinedReport();
            ParameterCombination c = r.getCombination();

            log.info("BODYx{} | BREAKOUTx{} | ATRx{} | VOLx{} | PARTIALx{}R | FINALx{}R | TRAILx{} -> trades={}, winRate={}%, PF={}, netProfit={}, expectancy={}",
                    c.getBodyRatio(),
                    c.getBreakoutBodyRatio(),
                    c.getAtrMultiplier(),
                    c.getVolumeMultiplier(),
                    c.getPartialTargetR(),
                    c.getFinalTargetR(),
                    c.getTimeBasedSlTrailCandles(),
                    rpt.getTotalTrades(),
                    rpt.getWinRate(),
                    rpt.getProfitFactor(),
                    rpt.getNetProfit(),
                    rpt.getExpectancy());
        }
    }

    private List<ParameterCombination> buildGrid() {

        List<ParameterCombination> combos = new ArrayList<>();

        for (BigDecimal bodyRatio : BODY_RATIOS) {
            for (BigDecimal breakoutBodyRatio : BREAKOUT_BODY_RATIOS) {
                for (BigDecimal atrMult : ATR_SL_MULTIPLIERS) {
                    for (BigDecimal volMult : VOLUME_MULTIPLIERS) {
                        for (BigDecimal partialR : PARTIAL_TARGET_RS) {
                            for (BigDecimal finalR : FINAL_TARGET_RS) {
                                for (Integer trailCandles : TIME_BASED_SL_TRAIL_CANDLES) {

                                    if (finalR.compareTo(partialR) <= 0) {
                                        continue;
                                    }

                                    combos.add(new ParameterCombination(
                                            bodyRatio, breakoutBodyRatio, atrMult, volMult,
                                            partialR, finalR, trailCandles));
                                }
                            }
                        }
                    }
                }
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

        List<Candle> all15m = fetchAll15mCandlesChunked(
                symbol,
                fromDate.minusDays(1),
                toDate.plusDays(1));

        BigDecimal[] atrSeries = computeATRSeries(all15m, ATR_PERIOD);
        BigDecimal[] avgVolumeSeries = computeAvgVolumeSeries(all15m, VOLUME_LOOKBACK);
        BigDecimal[] breakoutBodyRatioSeries = computeCandleBodyRatioSeries(all15m);

        List<DailyCandidate> candidates = buildCandidates(dailyCandles, all15m, fromDate, toDate);

        log.info("{}: {} daily candles, {} 15m candles, {} directional candidates",
                symbol, dailyCandles.size(), all15m.size(), candidates.size());

        return new SymbolData(symbol, all15m, atrSeries, avgVolumeSeries, breakoutBodyRatioSeries, candidates);
    }

    private List<Candle> fetchAll15mCandlesChunked(
            String symbol,
            LocalDate rangeStart,
            LocalDate rangeEnd) {

        TreeMap<Instant, Candle> byOpenTime = new TreeMap<>();

        LocalDate chunkStart = rangeStart;
        int chunkCount = 0;

        while (!chunkStart.isAfter(rangeEnd)) {

            LocalDate chunkEnd = chunkStart.plusDays(CANDLE_15M_CHUNK_DAYS);

            if (chunkEnd.isAfter(rangeEnd)) {
                chunkEnd = rangeEnd;
            }

            long chunkStartEpoch = chunkStart.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
            long chunkEndEpoch = chunkEnd.atStartOfDay(ZoneOffset.UTC).toEpochSecond();

            List<Candle> chunk = deltaApiService.get15mCandles(symbol, chunkStartEpoch, chunkEndEpoch);
            chunkCount++;

            if (chunk != null) {
                for (Candle c : chunk) {
                    byOpenTime.put(c.getOpenTime(), c);
                }
            }

            chunkStart = chunkEnd.isAfter(chunkStart) ? chunkEnd : chunkStart.plusDays(1);
        }

        log.info("{}: fetched {} 15m candles across {} chunked requests ({} to {})",
                symbol, byOpenTime.size(), chunkCount, rangeStart, rangeEnd);

        return new ArrayList<>(byOpenTime.values());
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
                continue;
            }

            candidates.add(new DailyCandidate(
                    tradeDate, previousDay, direction, bodyRatio, dayStartIndex, dayEndIndex));
        }

        return candidates;
    }

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

            if (candidate.getBodyRatio().compareTo(combo.getBodyRatio()) < 0) {
                continue;
            }

            trades.addAll(findTradesForCombo(data, candidate, combo));
        }

        return CryptoStrongCandleBacktestReport.fromTrades(trades);
    }

    /**
     * Replaces the old single-trade findTradeForCombo. Scans the candidate
     * day for the primary breakout entry, then - per Obs 5 - keeps scanning
     * the SAME day for chained re-entries whenever a leg exits via
     * STOP_LOSS and a later candle closes beyond that leg's entry-candle
     * high/low (in the breakout direction).
     */
    private List<CryptoStrongCandleTradeRecord> findTradesForCombo(
            SymbolData data,
            DailyCandidate candidate,
            ParameterCombination combo) {

        List<CryptoStrongCandleTradeRecord> chain = new ArrayList<>();
        List<Candle> candles = data.getAll15m();

        int dayEndExclusive = candidate.getDayEndIndex();
        boolean isBullish = candidate.getDirection() == CryptoStrongCandleTradeRecord.Direction.BULLISH;

        // reEntryLevel is null while scanning for the PRIMARY breakout.
        // After a stop-out, it becomes that leg's entry-candle high (buy)
        // or low (sell) - Obs 5's chaining reference.
        BigDecimal reEntryLevel = null;

        int scanIndex = candidate.getDayStartIndex();

        while (scanIndex < dayEndExclusive && chain.size() <= MAX_SAME_DAY_REENTRIES) {

            boolean isPrimaryScan = reEntryLevel == null;
            Integer triggerIndex = null;

            for (int i = scanIndex; i < dayEndExclusive; i++) {

                Candle candle = candles.get(i);
                boolean triggered;

                if (isPrimaryScan) {
                    triggered = isBullish
                            ? candle.getClose().compareTo(candidate.getPreviousDay().getHigh()) > 0
                            : candle.getClose().compareTo(candidate.getPreviousDay().getLow()) < 0;
                } else {
                    // Obs 5: re-entry triggers on a 15m CLOSE beyond the
                    // previous leg's entry-candle extreme, same direction.
                    triggered = isBullish
                            ? candle.getClose().compareTo(reEntryLevel) > 0
                            : candle.getClose().compareTo(reEntryLevel) < 0;
                }

                if (!triggered) {
                    continue;
                }

                if (isPrimaryScan) {
                    // Body/volume filters only screen the PRIMARY breakout,
                    // per the original strategy - re-entries are reactive
                    // and not re-screened against these.
                    BigDecimal breakoutBodyRatio = data.getBreakoutBodyRatioSeries()[i];
                    if (breakoutBodyRatio == null
                            || breakoutBodyRatio.compareTo(combo.getBreakoutBodyRatio()) < 0) {
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
                }

                BigDecimal atr = data.getAtrSeries()[i];
                if (atr == null || atr.compareTo(BigDecimal.ZERO) == 0) {
                    continue;
                }

                triggerIndex = i;
                break;
            }

            if (triggerIndex == null) {
                // No (re-)entry found for the rest of the day - chain ends.
                break;
            }

            int entryIndex = triggerIndex;
            BigDecimal atr = data.getAtrSeries()[entryIndex];
            Candle entryCandle = candles.get(entryIndex);
            int reEntrySequence = chain.size();

            CryptoStrongCandleTradeRecord trade = simulateTrade(
                    data.getSymbol(), candidate, entryCandle, entryIndex, atr, combo, candles,
                    reEntrySequence, isPrimaryScan);

            if (trade == null) {
                // Obs 2 rejected this entry (extended breakout candle), or
                // degenerate zero risk. For the primary scan this means no
                // trade at all for this candidate. For a re-entry scan we
                // just stop the chain here.
                break;
            }

            chain.add(trade);

            if (trade.getExitReason() != CryptoStrongCandleTradeRecord.ExitReason.STOP_LOSS) {
                // Obs 5 only chains after a stop-loss exit.
                break;
            }

            int exitIndex = Math.min(candles.size() - 1, entryIndex + trade.getCandlesHeld());

            if (exitIndex >= dayEndExclusive - 1) {
                // Stopped out at/after day end - no room left for a
                // same-day re-entry.
                break;
            }

            // Next reference level = the entry candle's own high/low, per
            // "note down the high/low of the candle" in Obs 5.
            reEntryLevel = isBullish ? trade.getBreakoutHigh() : trade.getBreakoutLow();
            scanIndex = exitIndex + 1;
        }

        return chain;
    }

    private CryptoStrongCandleTradeRecord simulateTrade(

            String symbol,
            DailyCandidate candidate,
            Candle breakoutCandle,
            int startIndex,
            BigDecimal atr,
            ParameterCombination combo,
            List<Candle> candles,
            int reEntrySequence,
            boolean isPrimaryEntry) {

        CryptoStrongCandleTradeRecord.Direction direction = candidate.getDirection();
        Candle previousDay = candidate.getPreviousDay();
        BigDecimal bodyRatio = candidate.getBodyRatio();
        LocalDate tradeDate = candidate.getTradeDate();

        BigDecimal partialTargetR = combo.getPartialTargetR();
        BigDecimal finalTargetR = combo.getFinalTargetR();

        // NOTE (left exactly as you asked - you're handling this bound
        // separately): trade duration is not capped to the candidate's
        // day window here.
        int boundIndexExclusive = candles.size();

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

        // ---- Obs 2: skip primary entries where the breakout candle is
        // already too extended (ATR-based risk too large relative to
        // entry price). Re-entries are NOT re-screened against this. ----
        if (isPrimaryEntry) {
            BigDecimal riskPercent = risk.divide(entry, 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));

            if (riskPercent.compareTo(MAX_BREAKOUT_RISK_PERCENT) > 0) {
                return null;
            }
        }

        // ---- Obs 3: reduce size when prior candles already show
        // consecutive movement in the breakout direction before the
        // breakout candle. Only checked for the primary entry. ----
        BigDecimal sizeMultiplier = BigDecimal.ONE;
        boolean consecutiveMoveDetected = false;

        if (isPrimaryEntry && startIndex >= PRE_BREAKOUT_LOOKBACK_CANDLES) {

            consecutiveMoveDetected = true;
            BigDecimal prevClose = breakoutCandle.getClose();

            for (int back = 1; back <= PRE_BREAKOUT_LOOKBACK_CANDLES; back++) {

                Candle earlier = candles.get(startIndex - back);

                boolean continuesMove = direction == CryptoStrongCandleTradeRecord.Direction.BULLISH
                        ? earlier.getClose().compareTo(prevClose) < 0   // each step back was lower -> rising into breakout
                        : earlier.getClose().compareTo(prevClose) > 0;  // each step back was higher -> falling into breakout

                if (!continuesMove) {
                    consecutiveMoveDetected = false;
                    break;
                }

                prevClose = earlier.getClose();
            }

            if (consecutiveMoveDetected) {
                sizeMultiplier = REDUCED_SIZE_MULTIPLIER;
            }
        }

        BigDecimal targetPartial;
        BigDecimal targetFinal;

        if (direction == CryptoStrongCandleTradeRecord.Direction.BULLISH) {
            targetPartial = entry.add(risk.multiply(partialTargetR));
            targetFinal = entry.add(risk.multiply(finalTargetR));
        } else {
            targetPartial = entry.subtract(risk.multiply(partialTargetR));
            targetFinal = entry.subtract(risk.multiply(finalTargetR));
        }

        boolean partialBooked = false;
        boolean breakEvenActivated = false;
        boolean slTrailApplied = false;

        BigDecimal finalExitPrice = entry;
        BigDecimal partialExitPrice = null;

        CryptoStrongCandleTradeRecord.ExitReason exitReason =
                CryptoStrongCandleTradeRecord.ExitReason.END_OF_DATA;

        int candlesHeld = 0;
        int trailWindowCandles = combo.getTimeBasedSlTrailCandles();

        for (int i = startIndex + 1; i < boundIndexExclusive; i++) {

            Candle candle = candles.get(i);

            candlesHeld++;

            // ---- Obs 1: time-based SL trailing. Once exactly N candles
            // (excluding the breakout candle) have elapsed without the
            // partial target being hit, tighten SL to the extreme of that
            // window (excluding the breakout candle). Applied at most once
            // per trade, and skipped if breakeven has already kicked in. ----
            if (!slTrailApplied && !partialBooked && !breakEvenActivated
                    && candlesHeld == trailWindowCandles) {

                BigDecimal windowExtreme = null;

                for (int w = startIndex + 1; w <= i; w++) {

                    Candle windowCandle = candles.get(w);

                    if (direction == CryptoStrongCandleTradeRecord.Direction.BULLISH) {
                        if (windowExtreme == null || windowCandle.getLow().compareTo(windowExtreme) < 0) {
                            windowExtreme = windowCandle.getLow();
                        }
                    } else {
                        if (windowExtreme == null || windowCandle.getHigh().compareTo(windowExtreme) > 0) {
                            windowExtreme = windowCandle.getHigh();
                        }
                    }
                }

                if (windowExtreme != null) {
                    stopLoss = windowExtreme;
                }

                slTrailApplied = true;
            }

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

        // Obs 3: pnlR stays a pure per-unit-risk measure (unaffected by
        // size), but pnlPoints - which feeds netProfit-style aggregates -
        // is scaled down for reduced-size entries.
        pnlPoints = pnlPoints.multiply(sizeMultiplier);

        // MFE/MAE bounded to the trade's own holding period, NOT the full
        // series - this was the runaway-cost bug from before.
        int mfeMaeBound = Math.min(boundIndexExclusive, startIndex + candlesHeld + 1);

        BigDecimal mfe = BigDecimal.ZERO;
        BigDecimal mae = BigDecimal.ZERO;

        for (int i = startIndex; i < mfeMaeBound; i++) {

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
                // ---- new fields - add these to the model/builder ----
                .reEntrySequence(reEntrySequence)
                .positionSizeMultiplier(sizeMultiplier)
                .consecutiveMoveDetected(consecutiveMoveDetected)
                .slTrailApplied(slTrailApplied)
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
        private final BigDecimal[] breakoutBodyRatioSeries;
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
        private final BigDecimal bodyRatio;
        private final BigDecimal breakoutBodyRatio;
        private final BigDecimal atrMultiplier;
        private final BigDecimal volumeMultiplier;
        private final BigDecimal partialTargetR;
        private final BigDecimal finalTargetR;
        private final Integer timeBasedSlTrailCandles;
    }

    @Getter
    @AllArgsConstructor
    public static class ParameterCombinationResult {
        private final ParameterCombination combination;
        private final Map<String, CryptoStrongCandleBacktestReport> perSymbolReports;
        private final CryptoStrongCandleBacktestReport combinedReport;
    }

    @Getter
    @AllArgsConstructor
    public static class ParameterSweepSummaryRow {
        private final int rank;
        private final BigDecimal bodyRatio;
        private final BigDecimal breakoutBodyRatio;
        private final BigDecimal atrMultiplier;
        private final BigDecimal volumeMultiplier;
        private final BigDecimal partialTargetR;
        private final BigDecimal finalTargetR;
        private final Integer timeBasedSlTrailCandles;
        private final int trades;
        private final BigDecimal winRate;
        private final BigDecimal profitFactor;
        private final BigDecimal expectancy;
        private final BigDecimal netProfit;
    }
}