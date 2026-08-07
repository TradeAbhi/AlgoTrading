package com.trading.algo.delta.service;

import com.trading.algo.delta.model.Candle;
import com.trading.algo.delta.model.CryptoStrongCandleBacktestReport;
import com.trading.algo.delta.model.CryptoStrongCandleTradeRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CryptoStrongCandleBacktestEngine {

    private static final BigDecimal BODY_RATIO =
            BigDecimal.valueOf(0.65);

    private static final BigDecimal THREE =
            BigDecimal.valueOf(2);

    private static final BigDecimal FOUR =
            BigDecimal.valueOf(2.5);

    // --- ATR-based stop loss config (replaces the old flat 0.5% SL_BUFFER) ---
    private static final int ATR_PERIOD = 14;
    private static final BigDecimal ATR_SL_MULTIPLIER = BigDecimal.valueOf(0.75);

    // --- Volume confirmation filter config ---
    private static final int VOLUME_LOOKBACK = 20;
    private static final BigDecimal VOLUME_MULTIPLIER = BigDecimal.valueOf(1.2);

    private final DeltaApiService deltaApiService;

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

        // Enforce chronological order - the API may return newest-first.
        dailyCandles.sort(Comparator.comparing(Candle::getOpenTime));

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

            // Fetch an extra day of lookback purely so ATR(14) and the
            // volume average have enough history at the start of tradeDate.
            // Breakouts are still only evaluated on candles within tradeDate itself.
            ZonedDateTime fetchStart =
                    tradeDate.minusDays(1).atStartOfDay(ZoneOffset.UTC);

            ZonedDateTime tradeDayStart =
                    tradeDate.atStartOfDay(ZoneOffset.UTC);

            ZonedDateTime end =
                    tradeDate.plusDays(1)
                            .atStartOfDay(ZoneOffset.UTC);

            List<Candle> candles =
                    deltaApiService.get15mCandles(
                            symbol,
                            fetchStart.toEpochSecond(),
                            end.toEpochSecond());

            if (candles == null || candles.isEmpty()) {

                continue;
            }

            // Enforce chronological order here too - do not assume the API's ordering.
            candles.sort(Comparator.comparing(Candle::getOpenTime));

            CryptoStrongCandleTradeRecord trade =
                    findTrade(
                            symbol,
                            tradeDate,
                            direction,
                            previousDay,
                            bodyRatio,
                            candles,
                            tradeDayStart);

            if (trade != null) {

                trades.add(trade);
            }

        }

        return CryptoStrongCandleBacktestReport
                .fromTrades(trades);

    }

    /**
     * True Range / ATR over the `period` candles ending just before index
     * `uptoIndexExclusive`. Returns null if there isn't enough history yet.
     */
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

    /**
     * Average volume over the `lookback` candles ending just before index
     * `uptoIndexExclusive`. Returns null if there isn't enough history yet.
     */
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

    private CryptoStrongCandleTradeRecord findTrade(

            String symbol,

            LocalDate tradeDate,

            CryptoStrongCandleTradeRecord.Direction direction,

            Candle previousDay,

            BigDecimal bodyRatio,

            List<Candle> candles,

            ZonedDateTime tradeDayStart) {

        for (int i = 0; i < candles.size(); i++) {

            Candle candle = candles.get(i);

            // Skip lookback-only candles fetched from the prior day - only
            // trade signals occurring within tradeDate itself are actionable.
            if (candle.getOpenTime().isBefore(tradeDayStart.toInstant())) {

                continue;
            }

            boolean breakout;

            if (direction ==
                    CryptoStrongCandleTradeRecord.Direction.BULLISH) {

                breakout =
                        candle.getClose()
                                .compareTo(previousDay.getHigh()) > 0;

            } else {

                breakout =
                        candle.getClose()
                                .compareTo(previousDay.getLow()) < 0;

            }

            if (!breakout) {

                continue;
            }

            // --- Volume confirmation filter ---
            BigDecimal avgVolume = averageVolume(candles, i, VOLUME_LOOKBACK);

            if (avgVolume == null
                    || avgVolume.compareTo(BigDecimal.ZERO) == 0) {

                // Not enough history to judge volume - skip rather than
                // trade blind.
                continue;
            }

            boolean volumeConfirmed =
                    candle.getVolume()
                            .compareTo(avgVolume.multiply(VOLUME_MULTIPLIER)) > 0;

            if (!volumeConfirmed) {

                continue;
            }

            // --- ATR for the stop-loss buffer ---
            BigDecimal atr = calculateATR(candles, i, ATR_PERIOD);

            if (atr == null || atr.compareTo(BigDecimal.ZERO) == 0) {

                // Not enough history for a real ATR yet - skip this signal.
                continue;
            }

            return simulateTrade(
                    symbol,
                    tradeDate,
                    direction,
                    previousDay,
                    bodyRatio,
                    candle,
                    i,
                    atr,
                    candles);

        }

        return null;

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

            List<Candle> candles) {

        BigDecimal entry = breakoutCandle.getClose();

        BigDecimal atrBuffer = atr.multiply(ATR_SL_MULTIPLIER);

        BigDecimal stopLoss;

        if (direction == CryptoStrongCandleTradeRecord.Direction.BULLISH) {

            stopLoss = breakoutCandle.getLow().subtract(atrBuffer);

        } else {

            stopLoss = breakoutCandle.getHigh().add(atrBuffer);

        }

        BigDecimal risk =
                entry.subtract(stopLoss).abs();

        if (risk.compareTo(BigDecimal.ZERO) == 0) {

            return null;
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

        BigDecimal finalExitPrice = entry;

        BigDecimal partialExitPrice = null;

        CryptoStrongCandleTradeRecord.ExitReason
                exitReason =
                CryptoStrongCandleTradeRecord.ExitReason.END_OF_DATA;

        int candlesHeld = 0;

        for (int i = startIndex + 1; i < candles.size(); i++) {

            Candle candle = candles.get(i);

            candlesHeld++;

            if (direction ==
                    CryptoStrongCandleTradeRecord.Direction.BULLISH) {

                /*
                 * Initial Stop Loss
                 */

                if (!breakEvenActivated &&
                        candle.getLow().compareTo(stopLoss) <= 0) {

                    finalExitPrice = stopLoss;

                    exitReason =
                            CryptoStrongCandleTradeRecord
                                    .ExitReason
                                    .STOP_LOSS;

                    break;
                }

                /*
                 * 3R reached
                 */

                if (!partialBooked &&
                        candle.getHigh().compareTo(target3R) >= 0) {

                    partialBooked = true;

                    breakEvenActivated = true;

                    partialExitPrice = target3R;

                    stopLoss = entry;
                }

                /*
                 * Break-even after 3R
                 */

                if (breakEvenActivated &&
                        candle.getLow().compareTo(stopLoss) <= 0) {

                    finalExitPrice = stopLoss;

                    exitReason =
                            CryptoStrongCandleTradeRecord
                                    .ExitReason
                                    .BREAK_EVEN;

                    break;
                }

                /*
                 * 4R Hit
                 */

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

                /*
                 * Bearish Trade
                 */

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

        BigDecimal mfe = BigDecimal.ZERO;

        BigDecimal mae = BigDecimal.ZERO;

        for (int i = startIndex; i < candles.size(); i++) {

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

                .build();

    }

}

//package com.trading.algo.delta.service;
//
//import com.trading.algo.delta.model.Candle;
//import com.trading.algo.delta.model.CryptoStrongCandleBacktestReport;
//import com.trading.algo.delta.model.CryptoStrongCandleTradeRecord;
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.stereotype.Service;
//
//import java.math.BigDecimal;
//import java.math.RoundingMode;
//import java.time.LocalDate;
//import java.time.ZoneOffset;
//import java.time.ZonedDateTime;
//import java.util.ArrayList;
//import java.util.List;
//
//@Slf4j
//@Service
//@RequiredArgsConstructor
//public class CryptoStrongCandleBacktestEngine {
//
//    private static final BigDecimal BODY_RATIO =
//            BigDecimal.valueOf(0.65);
//
//    private static final BigDecimal SL_BUFFER =
//            BigDecimal.valueOf(0.005);
//
//    private static final BigDecimal THREE =
//            BigDecimal.valueOf(2);
//
//    private static final BigDecimal FOUR =
//            BigDecimal.valueOf(2.5);
//
//    private final DeltaApiService deltaApiService;
//
//    public CryptoStrongCandleBacktestReport runBacktest(
//            String symbol,
//            LocalDate fromDate,
//            LocalDate toDate) {
//
//        log.info("Starting Strong Candle Backtest {} from {} to {}",
//                symbol,
//                fromDate,
//                toDate);
//
//        List<CryptoStrongCandleTradeRecord> trades =
//                new ArrayList<>();
//
//        List<Candle> dailyCandles =
//                deltaApiService.getDailyCandles(
//                        symbol,
//                        fromDate.minusDays(2)
//                                .atStartOfDay(ZoneOffset.UTC)
//                                .toEpochSecond(),
//                        toDate.plusDays(1)
//                                .atStartOfDay(ZoneOffset.UTC)
//                                .toEpochSecond());
//
//        if (dailyCandles == null || dailyCandles.size() < 2) {
//
//            return CryptoStrongCandleBacktestReport
//                    .fromTrades(trades);
//        }
//
//        for (int i = 1; i < dailyCandles.size(); i++) {
//
//            Candle previousDay = dailyCandles.get(i - 1);
//
//            Candle currentDay = dailyCandles.get(i);
//
//            LocalDate tradeDate =
//                    currentDay.getOpenTime()
//                            .atZone(ZoneOffset.UTC)
//                            .toLocalDate();
//
//            if (tradeDate.isBefore(fromDate)
//                    || tradeDate.isAfter(toDate)) {
//
//                continue;
//            }
//
//            BigDecimal range =
//                    previousDay.getHigh()
//                            .subtract(previousDay.getLow());
//
//            if (range.compareTo(BigDecimal.ZERO) == 0) {
//
//                continue;
//            }
//
//            BigDecimal body =
//                    previousDay.getClose()
//                            .subtract(previousDay.getOpen())
//                            .abs();
//
//            BigDecimal bodyRatio =
//                    body.divide(range,
//                            4,
//                            RoundingMode.HALF_UP);
//
//            if (bodyRatio.compareTo(BODY_RATIO) < 0) {
//
//                continue;
//            }
//
//            CryptoStrongCandleTradeRecord.Direction direction;
//
//            if (previousDay.getClose()
//                    .compareTo(previousDay.getOpen()) > 0) {
//
//                direction =
//                        CryptoStrongCandleTradeRecord.Direction.BULLISH;
//
//            } else if (previousDay.getClose()
//                    .compareTo(previousDay.getOpen()) < 0) {
//
//                direction =
//                        CryptoStrongCandleTradeRecord.Direction.BEARISH;
//
//            } else {
//
//                continue;
//            }
//
//            ZonedDateTime start =
//                    tradeDate.atStartOfDay(ZoneOffset.UTC);
//
//            ZonedDateTime end =
//                    tradeDate.plusDays(1)
//                            .atStartOfDay(ZoneOffset.UTC);
//
//            List<Candle> candles =
//                    deltaApiService.get15mCandles(
//                            symbol,
//                            start.toEpochSecond(),
//                            end.toEpochSecond());
//
//            if (candles == null || candles.isEmpty()) {
//
//                continue;
//            }
//
//            CryptoStrongCandleTradeRecord trade =
//                    findTrade(
//                            symbol,
//                            tradeDate,
//                            direction,
//                            previousDay,
//                            bodyRatio,
//                            candles);
//
//            if (trade != null) {
//
//                trades.add(trade);
//            }
//
//        }
//
//        return CryptoStrongCandleBacktestReport
//                .fromTrades(trades);
//
//    }
//
//    private CryptoStrongCandleTradeRecord findTrade(
//
//            String symbol,
//
//            LocalDate tradeDate,
//
//            CryptoStrongCandleTradeRecord.Direction direction,
//
//            Candle previousDay,
//
//            BigDecimal bodyRatio,
//
//            List<Candle> candles) {
//
//        for (Candle candle : candles) {
//
//            boolean breakout;
//
//            if (direction ==
//                    CryptoStrongCandleTradeRecord.Direction.BULLISH) {
//
//                breakout =
//                        candle.getClose()
//                                .compareTo(previousDay.getHigh()) > 0;
//
//            } else {
//
//                breakout =
//                        candle.getClose()
//                                .compareTo(previousDay.getLow()) < 0;
//
//            }
//
//            if (!breakout) {
//
//                continue;
//            }
//
//            return simulateTrade(
//                    symbol,
//                    tradeDate,
//                    direction,
//                    previousDay,
//                    bodyRatio,
//                    candle,
//                    candles);
//
//        }
//
//        return null;
//
//    }
//
//    private CryptoStrongCandleTradeRecord simulateTrade(
//
//            String symbol,
//
//            LocalDate tradeDate,
//
//            CryptoStrongCandleTradeRecord.Direction direction,
//
//            Candle previousDay,
//
//            BigDecimal bodyRatio,
//
//            Candle breakoutCandle,
//
//            List<Candle> candles) {
//
//        BigDecimal entry = breakoutCandle.getClose();
//
//        BigDecimal stopLoss;
//
//        if (direction == CryptoStrongCandleTradeRecord.Direction.BULLISH) {
//
//            stopLoss = breakoutCandle.getLow()
//                    .multiply(BigDecimal.ONE.subtract(SL_BUFFER));
//
//        } else {
//
//            stopLoss = breakoutCandle.getHigh()
//                    .multiply(BigDecimal.ONE.add(SL_BUFFER));
//
//        }
//
//        BigDecimal risk =
//                entry.subtract(stopLoss).abs();
//
//        if (risk.compareTo(BigDecimal.ZERO) == 0) {
//
//            return null;
//        }
//
//        BigDecimal target3R;
//
//        BigDecimal target4R;
//
//        if (direction == CryptoStrongCandleTradeRecord.Direction.BULLISH) {
//
//            target3R =
//                    entry.add(risk.multiply(THREE));
//
//            target4R =
//                    entry.add(risk.multiply(FOUR));
//
//        } else {
//
//            target3R =
//                    entry.subtract(risk.multiply(THREE));
//
//            target4R =
//                    entry.subtract(risk.multiply(FOUR));
//
//        }
//
//        boolean partialBooked = false;
//
//        boolean breakEvenActivated = false;
//
//        BigDecimal finalExitPrice = entry;
//
//        BigDecimal partialExitPrice = null;
//
//        CryptoStrongCandleTradeRecord.ExitReason
//                exitReason =
//                CryptoStrongCandleTradeRecord.ExitReason.END_OF_DATA;
//
//        int startIndex = candles.indexOf(breakoutCandle);
//
//        int candlesHeld = 0;
//
//        for (int i = startIndex + 1; i < candles.size(); i++) {
//
//            Candle candle = candles.get(i);
//
//            candlesHeld++;
//
//            if (direction ==
//                    CryptoStrongCandleTradeRecord.Direction.BULLISH) {
//
//                /*
//                 * Initial Stop Loss
//                 */
//
//                if (!breakEvenActivated &&
//                        candle.getLow().compareTo(stopLoss) <= 0) {
//
//                    finalExitPrice = stopLoss;
//
//                    exitReason =
//                            CryptoStrongCandleTradeRecord
//                                    .ExitReason
//                                    .STOP_LOSS;
//
//                    break;
//                }
//
//                /*
//                 * 3R reached
//                 */
//
//                if (!partialBooked &&
//                        candle.getHigh().compareTo(target3R) >= 0) {
//
//                    partialBooked = true;
//
//                    breakEvenActivated = true;
//
//                    partialExitPrice = target3R;
//
//                    stopLoss = entry;
//                }
//
//                /*
//                 * Break-even after 3R
//                 */
//
//                if (breakEvenActivated &&
//                        candle.getLow().compareTo(stopLoss) <= 0) {
//
//                    finalExitPrice = stopLoss;
//
//                    exitReason =
//                            CryptoStrongCandleTradeRecord
//                                    .ExitReason
//                                    .BREAK_EVEN;
//
//                    break;
//                }
//
//                /*
//                 * 4R Hit
//                 */
//
//                if (partialBooked &&
//                        candle.getHigh().compareTo(target4R) >= 0) {
//
//                    finalExitPrice = target4R;
//
//                    exitReason =
//                            CryptoStrongCandleTradeRecord
//                                    .ExitReason
//                                    .TARGET_4R;
//
//                    break;
//                }
//
//            } else {
//
//                /*
//                 * Bearish Trade
//                 */
//
//                if (!breakEvenActivated &&
//                        candle.getHigh().compareTo(stopLoss) >= 0) {
//
//                    finalExitPrice = stopLoss;
//
//                    exitReason =
//                            CryptoStrongCandleTradeRecord
//                                    .ExitReason
//                                    .STOP_LOSS;
//
//                    break;
//                }
//
//                if (!partialBooked &&
//                        candle.getLow().compareTo(target3R) <= 0) {
//
//                    partialBooked = true;
//
//                    breakEvenActivated = true;
//
//                    partialExitPrice = target3R;
//
//                    stopLoss = entry;
//                }
//
//                if (breakEvenActivated &&
//                        candle.getHigh().compareTo(stopLoss) >= 0) {
//
//                    finalExitPrice = stopLoss;
//
//                    exitReason =
//                            CryptoStrongCandleTradeRecord
//                                    .ExitReason
//                                    .BREAK_EVEN;
//
//                    break;
//                }
//
//                if (partialBooked &&
//                        candle.getLow().compareTo(target4R) <= 0) {
//
//                    finalExitPrice = target4R;
//
//                    exitReason =
//                            CryptoStrongCandleTradeRecord
//                                    .ExitReason
//                                    .TARGET_4R;
//
//                    break;
//                }
//
//            }
//
//        }
//
//        if (exitReason ==
//                CryptoStrongCandleTradeRecord.ExitReason.END_OF_DATA) {
//
//            finalExitPrice =
//                    candles.get(candles.size() - 1).getClose();
//        }
//
//
//        BigDecimal pnlPoints;
//
//        BigDecimal pnlR;
//
//        if (!partialBooked) {
//
//            pnlPoints = direction ==
//                    CryptoStrongCandleTradeRecord.Direction.BULLISH
//                    ? finalExitPrice.subtract(entry)
//                    : entry.subtract(finalExitPrice);
//
//        } else {
//
//            BigDecimal firstLeg = direction ==
//                    CryptoStrongCandleTradeRecord.Direction.BULLISH
//                    ? partialExitPrice.subtract(entry)
//                    : entry.subtract(partialExitPrice);
//
//            BigDecimal secondLeg = direction ==
//                    CryptoStrongCandleTradeRecord.Direction.BULLISH
//                    ? finalExitPrice.subtract(entry)
//                    : entry.subtract(finalExitPrice);
//
//            pnlPoints =
//                    firstLeg.multiply(BigDecimal.valueOf(0.60))
//                            .add(
//                                    secondLeg.multiply(
//                                            BigDecimal.valueOf(0.40)
//                                    )
//                            );
//
//        }
//
//        pnlR = pnlPoints.divide(
//                risk,
//                4,
//                RoundingMode.HALF_UP);
//
//        BigDecimal mfe = BigDecimal.ZERO;
//
//        BigDecimal mae = BigDecimal.ZERO;
//
//        for (int i = startIndex; i < candles.size(); i++) {
//
//            Candle c = candles.get(i);
//
//            if (direction ==
//                    CryptoStrongCandleTradeRecord.Direction.BULLISH) {
//
//                BigDecimal favourable =
//                        c.getHigh().subtract(entry);
//
//                BigDecimal adverse =
//                        entry.subtract(c.getLow());
//
//                if (favourable.compareTo(mfe) > 0) {
//
//                    mfe = favourable;
//
//                }
//
//                if (adverse.compareTo(mae) > 0) {
//
//                    mae = adverse;
//
//                }
//
//            } else {
//
//                BigDecimal favourable =
//                        entry.subtract(c.getLow());
//
//                BigDecimal adverse =
//                        c.getHigh().subtract(entry);
//
//                if (favourable.compareTo(mfe) > 0) {
//
//                    mfe = favourable;
//
//                }
//
//                if (adverse.compareTo(mae) > 0) {
//
//                    mae = adverse;
//
//                }
//
//            }
//
//        }
//
//        return CryptoStrongCandleTradeRecord.builder()
//
//                .symbol(symbol)
//
//                .tradeDate(tradeDate)
//
//                .direction(direction)
//
//                .entryTime(
//                        breakoutCandle
//                                .getOpenTime()
//                                .atZone(ZoneOffset.UTC)
//                                .toLocalDateTime())
//
//                .exitTime(
//                        candles.get(
//                                        Math.min(
//                                                candles.size() - 1,
//                                                startIndex + candlesHeld))
//                                .getCloseTime()
//                                .atZone(ZoneOffset.UTC)
//                                .toLocalDateTime())
//
//                .previousDayOpen(previousDay.getOpen())
//
//                .previousDayHigh(previousDay.getHigh())
//
//                .previousDayLow(previousDay.getLow())
//
//                .previousDayClose(previousDay.getClose())
//
//                .bodyRatio(bodyRatio)
//
//                .breakoutHigh(breakoutCandle.getHigh())
//
//                .breakoutLow(breakoutCandle.getLow())
//
//                .entryPrice(entry)
//
//                .stopLoss(stopLoss)
//
//                .risk(risk)
//
//                .target3R(target3R)
//
//                .target4R(target4R)
//
//                .partialBooked(partialBooked)
//
//                .partialExitPrice(partialExitPrice)
//
//                .finalExitPrice(finalExitPrice)
//
//                .exitReason(exitReason)
//
//                .pnlPoints(pnlPoints)
//
//                .pnlR(pnlR)
//
//                .mfe(mfe)
//
//                .mae(mae)
//
//                .candlesHeld(candlesHeld)
//
//                .breakEvenActivated(breakEvenActivated)
//
//                .build();
//
//    }
//
//}