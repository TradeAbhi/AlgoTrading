package com.trading.algo.delta.service;

import com.trading.algo.delta.model.Candle;
import com.trading.algo.delta.model.CryptoStrongCandleBacktestReport;
import com.trading.algo.delta.model.CryptoStrongCandleTradeRecord;
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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class CryptoStrongCandleBacktestEngine {
    //0.6,0.75,3,4,14,1,1.5
    private static final BigDecimal BODY_RATIO =
            BigDecimal.valueOf(0.6);
    private static final BigDecimal BREAKOUT_BODY_RATIO =
            BigDecimal.valueOf(0.75);

    private static final BigDecimal THREE =
            BigDecimal.valueOf(3);

    private static final BigDecimal FOUR =
            BigDecimal.valueOf(4);

    // --- ATR-based stop loss config ---
    private static final int ATR_PERIOD = 14;
    private static final BigDecimal ATR_SL_MULTIPLIER = BigDecimal.valueOf(1);

    // --- Volume confirmation filter config ---
    private static final int VOLUME_LOOKBACK = 20;
    private static final BigDecimal VOLUME_MULTIPLIER = BigDecimal.valueOf(1);

    // --- 15m fetch chunking ---
    private static final long CHUNK_SECONDS = 18L * 24 * 60 * 60;

    // ---- Obs 1: time-based SL trail. ----
    private static final int TIME_BASED_SL_TRAIL_CANDLES = 10;

    // ---- Obs 2: reject primary entries whose ATR-based risk, as a % of
    // entry price, exceeds this - i.e. breakout candle already too
    // extended. ----
    private static final BigDecimal MAX_BREAKOUT_RISK_PERCENT = BigDecimal.valueOf(5.0);

    // ---- Obs 3: pre-breakout consecutive-move size reduction. Directional
    // consistency alone doesn't distinguish real momentum from noise - two
    // candles drifting 0.01% each would trigger this just as readily as a
    // genuine 2%+ run-up. MIN_PRE_BREAKOUT_MOVE_PERCENT requires the
    // CUMULATIVE move across the lookback window to be economically
    // significant before the size reduction applies. ----
    private static final int PRE_BREAKOUT_LOOKBACK_CANDLES = 2;
    private static final BigDecimal REDUCED_SIZE_MULTIPLIER = BigDecimal.valueOf(0.5);
    private static final BigDecimal MIN_PRE_BREAKOUT_MOVE_PERCENT = BigDecimal.valueOf(0.5);

    // ---- Obs 5: safety cap on same-day chained re-entries. ----
    private static final int MAX_SAME_DAY_REENTRIES = 2;

    private final DeltaApiService deltaApiService;

    /**
     * Reasons a candidate breakout/re-entry signal (i.e. a candle that
     * ALREADY closed beyond the relevant level) got rejected before
     * becoming a trade. Deliberately does NOT include the daily
     * previous-day body-ratio filter - that one is expected to reject the
     * vast majority of days by design and would drown out everything else.
     */
    private enum RejectionReason {
        BREAKOUT_RANGE_ZERO,
        BREAKOUT_BODY_RATIO_TOO_LOW,
        VOLUME_HISTORY_INSUFFICIENT,
        VOLUME_NOT_CONFIRMED,
        ATR_INSUFFICIENT_HISTORY,
        ZERO_RISK,
        OBS2_RISK_TOO_HIGH,
        NO_TRIGGER_CANDLE_FOUND
    }

    private static class RejectionStats {

        private final Map<RejectionReason, Integer> counts = new EnumMap<>(RejectionReason.class);
        private int candidateDays = 0;
        private int daysWithNoTrade = 0;
        private int totalTradesProduced = 0;

        private void increment(RejectionReason reason) {
            counts.merge(reason, 1, Integer::sum);
        }

        private void logSummary(String symbol) {

            log.info("{}: {} candidate days (passed daily body-ratio) | {} produced zero trades | "
                            + "{} total trades produced (incl. Obs-5 re-entries)",
                    symbol, candidateDays, daysWithNoTrade, totalTradesProduced);

            log.info("{}: rejection breakdown -> breakoutBodyRatioTooLow={}, volumeHistoryInsufficient={}, "
                            + "volumeNotConfirmed={}, atrInsufficientHistory={}, zeroRisk={}, "
                            + "obs2RiskTooHigh={}, noTriggerCandleFound={}, breakoutRangeZero={}",
                    symbol,
                    counts.getOrDefault(RejectionReason.BREAKOUT_BODY_RATIO_TOO_LOW, 0),
                    counts.getOrDefault(RejectionReason.VOLUME_HISTORY_INSUFFICIENT, 0),
                    counts.getOrDefault(RejectionReason.VOLUME_NOT_CONFIRMED, 0),
                    counts.getOrDefault(RejectionReason.ATR_INSUFFICIENT_HISTORY, 0),
                    counts.getOrDefault(RejectionReason.ZERO_RISK, 0),
                    counts.getOrDefault(RejectionReason.OBS2_RISK_TOO_HIGH, 0),
                    counts.getOrDefault(RejectionReason.NO_TRIGGER_CANDLE_FOUND, 0),
                    counts.getOrDefault(RejectionReason.BREAKOUT_RANGE_ZERO, 0));
        }
    }

    public CryptoStrongCandleBacktestReport runBacktest(
            String symbol,
            LocalDate fromDate,
            LocalDate toDate) {

        log.info("Starting Strong Candle Backtest {} from {} to {}",
                symbol,
                fromDate,
                toDate);

        List<CryptoStrongCandleTradeRecord> trades =
                new ArrayList<>();

        // Created fresh per call (this bean is a shared singleton) so
        // concurrent backtests don't clobber each other's counters.
        RejectionStats stats = new RejectionStats();

        List<Candle> dailyCandles =
                deltaApiService.getDailyCandles(
                        symbol,
                        fromDate.minusDays(2)
                                .atStartOfDay(ZoneOffset.UTC)
                                .toEpochSecond(),
                        toDate.plusDays(1)
                                .atStartOfDay(ZoneOffset.UTC)
                                .toEpochSecond());

        if (dailyCandles == null || dailyCandles.size() < 2) {

            return CryptoStrongCandleBacktestReport
                    .fromTrades(trades);
        }

        dailyCandles.sort(Comparator.comparing(Candle::getOpenTime));

        List<Candle> all15m = fetchAll15mCandlesChunked(
                symbol,
                fromDate.minusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond(),
                toDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond());

        if (all15m.isEmpty()) {

            return CryptoStrongCandleBacktestReport
                    .fromTrades(trades);
        }

        all15m.sort(Comparator.comparing(Candle::getOpenTime));

        log.info("{}: fetched {} daily candles, {} 15m candles ({} to {})",
                symbol, dailyCandles.size(), all15m.size(),
                all15m.get(0).getOpenTime(), all15m.get(all15m.size() - 1).getOpenTime());

        for (int i = 1; i < dailyCandles.size(); i++) {

            Candle previousDay = dailyCandles.get(i - 1);

            Candle currentDay = dailyCandles.get(i);

            LocalDate tradeDate =
                    currentDay.getOpenTime()
                            .atZone(ZoneOffset.UTC)
                            .toLocalDate();

            if (tradeDate.isBefore(fromDate)
                    || tradeDate.isAfter(toDate)) {

                continue;
            }

            BigDecimal range =
                    previousDay.getHigh()
                            .subtract(previousDay.getLow());

            if (range.compareTo(BigDecimal.ZERO) == 0) {

                continue;
            }

            BigDecimal body =
                    previousDay.getClose()
                            .subtract(previousDay.getOpen())
                            .abs();

            BigDecimal bodyRatio =
                    body.divide(range,
                            4,
                            RoundingMode.HALF_UP);

            if (bodyRatio.compareTo(BODY_RATIO) < 0) {

                // Deliberately NOT tracked in RejectionStats - see javadoc
                // on RejectionReason.
                continue;
            }

            CryptoStrongCandleTradeRecord.Direction direction;

            if (previousDay.getClose()
                    .compareTo(previousDay.getOpen()) > 0) {

                direction =
                        CryptoStrongCandleTradeRecord.Direction.BULLISH;

            } else if (previousDay.getClose()
                    .compareTo(previousDay.getOpen()) < 0) {

                direction =
                        CryptoStrongCandleTradeRecord.Direction.BEARISH;

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

            // This day passed the daily body-ratio filter and has 15m data
            // - it's a genuine candidate for the rejection breakdown below.
            stats.candidateDays++;

            List<CryptoStrongCandleTradeRecord> chain =
                    findTradeChainForDay(
                            symbol,
                            tradeDate,
                            direction,
                            previousDay,
                            bodyRatio,
                            all15m,
                            dayStartIndex,
                            dayEndIndex,
                            stats);

            if (chain.isEmpty()) {
                stats.daysWithNoTrade++;
            } else {
                stats.totalTradesProduced += chain.size();
            }

            trades.addAll(chain);

        }

        stats.logSummary(symbol);

        return CryptoStrongCandleBacktestReport
                .fromTrades(trades);

    }

    private List<Candle> fetchAll15mCandlesChunked(
            String symbol,
            long startEpoch,
            long endEpoch) {

        List<Candle> all = new ArrayList<>();

        long chunkStart = startEpoch;
        int chunkCount = 0;

        while (chunkStart < endEpoch) {

            long chunkEnd = Math.min(chunkStart + CHUNK_SECONDS, endEpoch);

            List<Candle> chunk = deltaApiService.get15mCandles(symbol, chunkStart, chunkEnd);

            if (chunk != null && !chunk.isEmpty()) {
                all.addAll(chunk);
            }

            chunkCount++;
            chunkStart = chunkEnd;
        }

        log.info("{}: fetched 15m candles in {} chunks ({} raw candles before dedupe/sort)",
                symbol, chunkCount, all.size());

        return all;
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

    private BigDecimal calculateATR(
            List<Candle> candles,
            int uptoIndexExclusive,
            int period) {

        if (uptoIndexExclusive <= period) {

            return null;
        }

        BigDecimal trSum = BigDecimal.ZERO;

        for (int i = uptoIndexExclusive - period; i < uptoIndexExclusive; i++) {

            Candle current = candles.get(i);
            Candle previous = candles.get(i - 1);

            BigDecimal highLow =
                    current.getHigh().subtract(current.getLow());

            BigDecimal highPrevClose =
                    current.getHigh().subtract(previous.getClose()).abs();

            BigDecimal lowPrevClose =
                    current.getLow().subtract(previous.getClose()).abs();

            BigDecimal trueRange = highLow.max(highPrevClose).max(lowPrevClose);

            trSum = trSum.add(trueRange);
        }

        return trSum.divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);
    }

    private BigDecimal averageVolume(
            List<Candle> candles,
            int uptoIndexExclusive,
            int lookback) {

        if (uptoIndexExclusive < lookback) {

            return null;
        }

        BigDecimal sum = BigDecimal.ZERO;

        for (int i = uptoIndexExclusive - lookback; i < uptoIndexExclusive; i++) {

            sum = sum.add(candles.get(i).getVolume());
        }

        return sum.divide(BigDecimal.valueOf(lookback), 8, RoundingMode.HALF_UP);
    }

    private List<CryptoStrongCandleTradeRecord> findTradeChainForDay(

            String symbol,
            LocalDate tradeDate,
            CryptoStrongCandleTradeRecord.Direction direction,
            Candle previousDay,
            BigDecimal bodyRatio,
            List<Candle> candles,
            int dayStartIndex,
            int dayEndIndex,
            RejectionStats stats) {

        List<CryptoStrongCandleTradeRecord> chain = new ArrayList<>();
        boolean isBullish = direction == CryptoStrongCandleTradeRecord.Direction.BULLISH;

        BigDecimal reEntryLevel = null;

        int scanIndex = dayStartIndex;

        while (scanIndex < dayEndIndex && chain.size() <= MAX_SAME_DAY_REENTRIES) {

            boolean isPrimaryScan = reEntryLevel == null;
            Integer triggerIndex = null;

            for (int i = scanIndex; i < dayEndIndex; i++) {

                Candle candle = candles.get(i);
                boolean triggered;

                if (isPrimaryScan) {
                    triggered = isBullish
                            ? candle.getClose().compareTo(previousDay.getHigh()) > 0
                            : candle.getClose().compareTo(previousDay.getLow()) < 0;
                } else {
                    triggered = isBullish
                            ? candle.getClose().compareTo(reEntryLevel) > 0
                            : candle.getClose().compareTo(reEntryLevel) < 0;
                }

                if (!triggered) {
                    continue;
                }

                // From here on, a real signal candle was found - any
                // `continue` below is a genuine FILTER REJECTION, tracked.

                if (isPrimaryScan) {

                    BigDecimal breakoutRange = candle.getHigh().subtract(candle.getLow());

                    if (breakoutRange.compareTo(BigDecimal.ZERO) == 0) {
                        stats.increment(RejectionReason.BREAKOUT_RANGE_ZERO);
                        continue;
                    }

                    BigDecimal breakoutBody = candle.getClose().subtract(candle.getOpen()).abs();
                    BigDecimal breakoutBodyRatio =
                            breakoutBody.divide(breakoutRange, 4, RoundingMode.HALF_UP);

                    if (breakoutBodyRatio.compareTo(BREAKOUT_BODY_RATIO) < 0) {
                        stats.increment(RejectionReason.BREAKOUT_BODY_RATIO_TOO_LOW);
                        continue;
                    }

                    // ---- Volume confirmation filter DISABLED (temporarily,
                    // for testing) - uncomment to re-enable. While disabled,
                    // VOLUME_HISTORY_INSUFFICIENT / VOLUME_NOT_CONFIRMED will
                    // always show 0 in the rejection summary. ----
                    // BigDecimal avgVolume = averageVolume(candles, i, VOLUME_LOOKBACK);
                    //
                    // if (avgVolume == null || avgVolume.compareTo(BigDecimal.ZERO) == 0) {
                    //     stats.increment(RejectionReason.VOLUME_HISTORY_INSUFFICIENT);
                    //     continue;
                    // }
                    //
                    // boolean volumeConfirmed = candle.getVolume()
                    //         .compareTo(avgVolume.multiply(VOLUME_MULTIPLIER)) > 0;
                    //
                    // if (!volumeConfirmed) {
                    //     stats.increment(RejectionReason.VOLUME_NOT_CONFIRMED);
                    //     continue;
                    // }
                }

                BigDecimal atr = calculateATR(candles, i, ATR_PERIOD);

                if (atr == null || atr.compareTo(BigDecimal.ZERO) == 0) {
                    stats.increment(RejectionReason.ATR_INSUFFICIENT_HISTORY);
                    continue;
                }

                triggerIndex = i;
                break;
            }

            if (triggerIndex == null) {
                stats.increment(RejectionReason.NO_TRIGGER_CANDLE_FOUND);
                break;
            }

            int entryIndex = triggerIndex;
            Candle entryCandle = candles.get(entryIndex);
            BigDecimal atr = calculateATR(candles, entryIndex, ATR_PERIOD);
            int reEntrySequence = chain.size();

            CryptoStrongCandleTradeRecord trade = simulateTrade(
                    symbol, tradeDate, direction, previousDay, bodyRatio,
                    entryCandle, entryIndex, atr, candles,
                    reEntrySequence, isPrimaryScan, stats);

            if (trade == null) {
                // Rejection reason (ZERO_RISK or OBS2_RISK_TOO_HIGH) was
                // already recorded inside simulateTrade.
                break;
            }

            chain.add(trade);

            if (trade.getExitReason() != CryptoStrongCandleTradeRecord.ExitReason.STOP_LOSS) {
                break;
            }

            int exitIndex = Math.min(candles.size() - 1, entryIndex + trade.getCandlesHeld());

            if (exitIndex >= dayEndIndex - 1) {
                break;
            }

            reEntryLevel = isBullish ? trade.getBreakoutHigh() : trade.getBreakoutLow();
            scanIndex = exitIndex + 1;
        }

        return chain;
    }

    private CryptoStrongCandleTradeRecord simulateTrade(

            String symbol,

            LocalDate tradeDate,

            CryptoStrongCandleTradeRecord.Direction direction,

            Candle previousDay,

            BigDecimal bodyRatio,

            Candle breakoutCandle,

            int startIndex,

            BigDecimal atr,

            List<Candle> candles,

            int reEntrySequence,

            boolean isPrimaryEntry,

            RejectionStats stats) {

        BigDecimal entry = breakoutCandle.getClose();

        BigDecimal atrBuffer = atr.multiply(ATR_SL_MULTIPLIER);

        BigDecimal stopLoss;
        BigDecimal originalStopLoss;
        if (direction == CryptoStrongCandleTradeRecord.Direction.BULLISH) {

            stopLoss = breakoutCandle.getLow().subtract(atrBuffer);

        } else {

            stopLoss = breakoutCandle.getHigh().add(atrBuffer);

        }
        originalStopLoss = stopLoss;
        BigDecimal risk =
                entry.subtract(stopLoss).abs();

        if (risk.compareTo(BigDecimal.ZERO) == 0) {

            stats.increment(RejectionReason.ZERO_RISK);
            return null;
        }

        // ---- Obs 2: skip primary entries where the breakout candle is
        // already too extended. Re-entries are NOT re-screened. ----
        if (isPrimaryEntry) {
            BigDecimal riskPercent = risk.divide(entry, 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));

            if (riskPercent.compareTo(MAX_BREAKOUT_RISK_PERCENT) > 0) {
                stats.increment(RejectionReason.OBS2_RISK_TOO_HIGH);
                return null;
            }
        }

        // ---- Obs 3: reduce size when prior candles already show
        // consecutive movement in the breakout direction before the
        // breakout candle. Only checked for the primary entry.
        //
        // Two checks must BOTH pass now:
        //  1. DIRECTIONAL CONSISTENCY - each candle in the lookback window
        //     continues the move (unchanged from before).
        //  2. MAGNITUDE - the cumulative % move across the whole window
        //     meets MIN_PRE_BREAKOUT_MOVE_PERCENT, so a couple of candles
        //     drifting by fractions of a percent don't get treated the
        //     same as a genuine run-up/down. ----
        BigDecimal sizeMultiplier = BigDecimal.ONE;
        boolean consecutiveMoveDetected = false;
        BigDecimal preBreakoutMovePercent = null;

        if (isPrimaryEntry && startIndex >= PRE_BREAKOUT_LOOKBACK_CANDLES) {

            boolean directionallyConsistent = true;
            BigDecimal prevClose = breakoutCandle.getClose();

            for (int back = 1; back <= PRE_BREAKOUT_LOOKBACK_CANDLES; back++) {

                Candle earlier = candles.get(startIndex - back);

                boolean continuesMove = direction == CryptoStrongCandleTradeRecord.Direction.BULLISH
                        ? earlier.getClose().compareTo(prevClose) < 0
                        : earlier.getClose().compareTo(prevClose) > 0;

                if (!continuesMove) {
                    directionallyConsistent = false;
                    break;
                }

                prevClose = earlier.getClose();
            }

            if (directionallyConsistent) {

                Candle earliestInWindow = candles.get(startIndex - PRE_BREAKOUT_LOOKBACK_CANDLES);
                BigDecimal earliestClose = earliestInWindow.getClose();

                if (earliestClose.compareTo(BigDecimal.ZERO) != 0) {

                    // Signed distance from the earliest candle in the
                    // window to the breakout candle's close, as a % move.
                    // abs() because we only care about magnitude here -
                    // direction was already confirmed correct above.
                    preBreakoutMovePercent = breakoutCandle.getClose()
                            .subtract(earliestClose)
                            .divide(earliestClose, 6, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100))
                            .abs();

                    if (preBreakoutMovePercent.compareTo(MIN_PRE_BREAKOUT_MOVE_PERCENT) >= 0) {
                        consecutiveMoveDetected = true;
                    }
                }
            }

            if (consecutiveMoveDetected) {
                sizeMultiplier = REDUCED_SIZE_MULTIPLIER;
            }
        }

        BigDecimal target3R;

        BigDecimal target4R;

        if (direction == CryptoStrongCandleTradeRecord.Direction.BULLISH) {

            target3R =
                    entry.add(risk.multiply(THREE));

            target4R =
                    entry.add(risk.multiply(FOUR));

        } else {

            target3R =
                    entry.subtract(risk.multiply(THREE));

            target4R =
                    entry.subtract(risk.multiply(FOUR));

        }

        boolean partialBooked = false;

        boolean breakEvenActivated = false;

        boolean slTrailApplied = false;

        BigDecimal finalExitPrice = entry;

        BigDecimal partialExitPrice = null;

        CryptoStrongCandleTradeRecord.ExitReason
                exitReason =
                CryptoStrongCandleTradeRecord.ExitReason.END_OF_DATA;

        int candlesHeld = 0;
        BigDecimal trailedStopLossLevel = null;
        for (int i = startIndex + 1; i < candles.size(); i++) {

            Candle candle = candles.get(i);

            candlesHeld++;

            if (!slTrailApplied && !partialBooked && !breakEvenActivated
                    && candlesHeld == TIME_BASED_SL_TRAIL_CANDLES) {

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
                    trailedStopLossLevel = windowExtreme;
                }

                slTrailApplied = true;
            }

            if (direction ==
                    CryptoStrongCandleTradeRecord.Direction.BULLISH) {

                if (!breakEvenActivated &&
                        candle.getLow().compareTo(stopLoss) <= 0) {

                    finalExitPrice = stopLoss;

                    exitReason =
                            CryptoStrongCandleTradeRecord
                                    .ExitReason
                                    .STOP_LOSS;

                    break;
                }

                if (!partialBooked &&
                        candle.getHigh().compareTo(target3R) >= 0) {

                    partialBooked = true;

                    breakEvenActivated = true;

                    partialExitPrice = target3R;

                    stopLoss = entry;
                }

                if (breakEvenActivated &&
                        candle.getLow().compareTo(stopLoss) <= 0) {

                    finalExitPrice = stopLoss;

                    exitReason =
                            CryptoStrongCandleTradeRecord
                                    .ExitReason
                                    .BREAK_EVEN;

                    break;
                }

                if (partialBooked &&
                        candle.getHigh().compareTo(target4R) >= 0) {

                    finalExitPrice = target4R;

                    exitReason =
                            CryptoStrongCandleTradeRecord
                                    .ExitReason
                                    .TARGET_4R;

                    break;
                }

            } else {

                if (!breakEvenActivated &&
                        candle.getHigh().compareTo(stopLoss) >= 0) {

                    finalExitPrice = stopLoss;

                    exitReason =
                            CryptoStrongCandleTradeRecord
                                    .ExitReason
                                    .STOP_LOSS;

                    break;
                }

                if (!partialBooked &&
                        candle.getLow().compareTo(target3R) <= 0) {

                    partialBooked = true;

                    breakEvenActivated = true;

                    partialExitPrice = target3R;

                    stopLoss = entry;
                }

                if (breakEvenActivated &&
                        candle.getHigh().compareTo(stopLoss) >= 0) {

                    finalExitPrice = stopLoss;

                    exitReason =
                            CryptoStrongCandleTradeRecord
                                    .ExitReason
                                    .BREAK_EVEN;

                    break;
                }

                if (partialBooked &&
                        candle.getLow().compareTo(target4R) <= 0) {

                    finalExitPrice = target4R;

                    exitReason =
                            CryptoStrongCandleTradeRecord
                                    .ExitReason
                                    .TARGET_4R;

                    break;
                }

            }

        }

        if (exitReason ==
                CryptoStrongCandleTradeRecord.ExitReason.END_OF_DATA) {

            finalExitPrice =
                    candles.get(candles.size() - 1).getClose();
        }


        BigDecimal pnlPoints;

        BigDecimal pnlR;

        if (!partialBooked) {

            pnlPoints = direction ==
                    CryptoStrongCandleTradeRecord.Direction.BULLISH
                    ? finalExitPrice.subtract(entry)
                    : entry.subtract(finalExitPrice);

        } else {

            BigDecimal firstLeg = direction ==
                    CryptoStrongCandleTradeRecord.Direction.BULLISH
                    ? partialExitPrice.subtract(entry)
                    : entry.subtract(partialExitPrice);

            BigDecimal secondLeg = direction ==
                    CryptoStrongCandleTradeRecord.Direction.BULLISH
                    ? finalExitPrice.subtract(entry)
                    : entry.subtract(finalExitPrice);

            pnlPoints =
                    firstLeg.multiply(BigDecimal.valueOf(0.60))
                            .add(
                                    secondLeg.multiply(
                                            BigDecimal.valueOf(0.40)
                                    )
                            );

        }

        pnlR = pnlPoints.divide(
                risk,
                4,
                RoundingMode.HALF_UP);

        pnlPoints = pnlPoints.multiply(sizeMultiplier);

        int mfeMaeBound = Math.min(candles.size(), startIndex + candlesHeld + 1);

        BigDecimal mfe = BigDecimal.ZERO;

        BigDecimal mae = BigDecimal.ZERO;

        for (int i = startIndex; i < mfeMaeBound; i++) {

            Candle c = candles.get(i);

            if (direction ==
                    CryptoStrongCandleTradeRecord.Direction.BULLISH) {

                BigDecimal favourable =
                        c.getHigh().subtract(entry);

                BigDecimal adverse =
                        entry.subtract(c.getLow());

                if (favourable.compareTo(mfe) > 0) {

                    mfe = favourable;

                }

                if (adverse.compareTo(mae) > 0) {

                    mae = adverse;

                }

            } else {

                BigDecimal favourable =
                        entry.subtract(c.getLow());

                BigDecimal adverse =
                        c.getHigh().subtract(entry);

                if (favourable.compareTo(mfe) > 0) {

                    mfe = favourable;

                }

                if (adverse.compareTo(mae) > 0) {

                    mae = adverse;

                }

            }

        }

        return CryptoStrongCandleTradeRecord.builder()

                .symbol(symbol)

                .tradeDate(tradeDate)

                .direction(direction)

                .entryTime(
                        breakoutCandle
                                .getOpenTime()
                                .atZone(ZoneOffset.UTC)
                                .toLocalDateTime())

                .exitTime(
                        candles.get(
                                        Math.min(
                                                candles.size() - 1,
                                                startIndex + candlesHeld))
                                .getCloseTime()
                                .atZone(ZoneOffset.UTC)
                                .toLocalDateTime())

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

                .target3R(target3R)

                .target4R(target4R)

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

                .reEntrySequence(reEntrySequence)

                .positionSizeMultiplier(sizeMultiplier)

                .consecutiveMoveDetected(consecutiveMoveDetected)

                .slTrailApplied(slTrailApplied)
                .atrValue(atr)
                .originalStopLoss(originalStopLoss)
                .trailedStopLossLevel(trailedStopLossLevel)
                .breakEvenStopLoss(breakEvenActivated ? entry : null)

                .build();

    }

}