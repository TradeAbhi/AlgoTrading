package com.trading.algo.service;

import com.trading.algo.dtos.Candle;
import com.trading.algo.dtos.NiftyATRAnalysisResponse;
import com.trading.algo.dtos.NiftyExtremeDay;
import com.trading.algo.upstox.UpstoxHistoricalCandleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/**
 * Service for analyzing Nifty index daily movements using ATR (Average True Range).
 * Identifies the most positive and most negative days based on ATR multiples.
 *
 * Definitions:
 * - Very positive day: Close - Open > 2 × ATR(14)
 * - Very negative day: Open - Close > 2 × ATR(14)
 *
 * This approach automatically adapts to market volatility conditions.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NiftyATRAnalysisService {

    private final UpstoxHistoricalCandleService candleService;

    private static final String NIFTY_50_INDEX_KEY = "NSE_INDEX|Nifty 50";
    private static final int ATR_PERIOD = 14;
    private static final double EXTREME_MOVE_THRESHOLD = 2.0; // 2 × ATR

    /**
     * Analyzes Nifty for extreme days between the given date range.
     *
     * @param fromDate Start date (inclusive)
     * @param toDate End date (inclusive)
     * @return NiftyATRAnalysisResponse containing most positive/negative days and statistics
     */
    public NiftyATRAnalysisResponse analyzeNiftyExtremeMovements(LocalDate fromDate, LocalDate toDate) {
        log.info("Starting Nifty ATR analysis from {} to {}", fromDate, toDate);

        try {
            // Fetch daily candles from Upstox
            List<Candle> dailyCandles = candleService.fetchDailyCandles(NIFTY_50_INDEX_KEY, fromDate, toDate);

            if (dailyCandles.isEmpty()) {
                log.warn("No candles fetched for Nifty 50 between {} and {}", fromDate, toDate);
                return buildEmptyResponse(fromDate, toDate);
            }

            log.info("Fetched {} daily candles for Nifty 50", dailyCandles.size());

            // Sort by timestamp (should already be sorted but making sure)
            dailyCandles.sort(Comparator.comparing(Candle::getTimestamp));

            // Calculate ATR for each candle
            List<Double> atrValues = calculateATR(dailyCandles, ATR_PERIOD);

            if (atrValues.size() < ATR_PERIOD) {
                log.warn("Insufficient data for ATR calculation. Need at least {} candles, got {}",
                        ATR_PERIOD, atrValues.size());
            }

            // Identify extreme days
            List<NiftyExtremeDay> positiveDays = new ArrayList<>();
            List<NiftyExtremeDay> negativeDays = new ArrayList<>();
            NiftyExtremeDay mostPositive = null;
            NiftyExtremeDay mostNegative = null;
            double maxPositiveMultiple = 0;
            double maxNegativeMultiple = 0;

            for (int i = 0; i < dailyCandles.size(); i++) {
                Candle candle = dailyCandles.get(i);
                double atr = atrValues.get(i);

                if (atr > 0) {
                    double dayMove = Math.abs(candle.getClose() - candle.getOpen());
                    double atrMultiple = dayMove / atr;

                    if (atrMultiple > EXTREME_MOVE_THRESHOLD) {
                        NiftyExtremeDay extremeDay = buildExtremeDayData(candle, atr, dayMove, atrMultiple);

                        if (candle.getClose() > candle.getOpen()) {
                            // Positive day
                            extremeDay.setMoveType("POSITIVE");
                            positiveDays.add(extremeDay);
                            if (atrMultiple > maxPositiveMultiple) {
                                maxPositiveMultiple = atrMultiple;
                                mostPositive = extremeDay;
                            }
                        } else {
                            // Negative day
                            extremeDay.setMoveType("NEGATIVE");
                            negativeDays.add(extremeDay);
                            if (atrMultiple > maxNegativeMultiple) {
                                maxNegativeMultiple = atrMultiple;
                                mostNegative = extremeDay;
                            }
                        }
                    }
                }
            }

            // Sort by ATR multiple descending
            positiveDays.sort((a, b) -> Double.compare(b.getAtrMultiple(), a.getAtrMultiple()));
            negativeDays.sort((a, b) -> Double.compare(b.getAtrMultiple(), a.getAtrMultiple()));

            // Calculate statistics
            double avgAtr = atrValues.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double avgAtrMultiplePositive = positiveDays.isEmpty() ? 0 :
                    positiveDays.stream().mapToDouble(NiftyExtremeDay::getAtrMultiple).average().orElse(0);
            double avgAtrMultipleNegative = negativeDays.isEmpty() ? 0 :
                    negativeDays.stream().mapToDouble(NiftyExtremeDay::getAtrMultiple).average().orElse(0);

            // Build response
            return NiftyATRAnalysisResponse.builder()
                    .analysisStartDate(fromDate)
                    .analysisEndDate(toDate)
                    .totalTradingDays(dailyCandles.size())
                    .extremeDaysCount(positiveDays.size() + negativeDays.size())
                    .atrThreshold(EXTREME_MOVE_THRESHOLD)
                    .atrPeriod(ATR_PERIOD)
                    .mostPositiveDay(mostPositive)
                    .mostNegativeDay(mostNegative)
                    .allPositiveDays(positiveDays)
                    .allNegativeDays(negativeDays)
                    .averageAtr14(avgAtr)
                    .averageAtrMultiplePositive(avgAtrMultiplePositive)
                    .averageAtrMultipleNegative(avgAtrMultipleNegative)
                    .maxAtrMultiplePositive(maxPositiveMultiple)
                    .maxAtrMultipleNegative(maxNegativeMultiple)
                    .build();

        } catch (Exception e) {
            log.error("Error analyzing Nifty extreme movements", e);
            return buildEmptyResponse(fromDate, toDate);
        }
    }

    /**
     * Calculates ATR (Average True Range) for each candle in the list.
     * ATR uses 14-period moving average of True Range.
     *
     * True Range = max(high - low, abs(high - prevClose), abs(low - prevClose))
     *
     * @param candles List of candles sorted by timestamp
     * @param period ATR period (typically 14)
     * @return List of ATR values corresponding to each candle
     */
    private List<Double> calculateATR(List<Candle> candles, int period) {
        List<Double> atrValues = new ArrayList<>();
        List<Double> trueRanges = new ArrayList<>();

        // Calculate true ranges
        for (int i = 0; i < candles.size(); i++) {
            Candle current = candles.get(i);
            double tr;

            if (i == 0) {
                // For first candle, TR = High - Low
                tr = current.getHigh() - current.getLow();
            } else {
                Candle previous = candles.get(i - 1);
                double hl = current.getHigh() - current.getLow();
                double hc = Math.abs(current.getHigh() - previous.getClose());
                double lc = Math.abs(current.getLow() - previous.getClose());
                tr = Math.max(hl, Math.max(hc, lc));
            }
            trueRanges.add(tr);
        }

        // Calculate ATR using SMA
        for (int i = 0; i < candles.size(); i++) {
            if (i < period - 1) {
                // Not enough data for full period
                atrValues.add(0.0);
            } else if (i == period - 1) {
                // First ATR: simple average of first 'period' TR values
                double sum = trueRanges.subList(0, period).stream()
                        .mapToDouble(Double::doubleValue)
                        .sum();
                atrValues.add(sum / period);
            } else {
                // Subsequent ATR: use Wilder's smoothing (EMA-like)
                double prevAtr = atrValues.get(i - 1);
                double currentTr = trueRanges.get(i);
                double atr = (prevAtr * (period - 1) + currentTr) / period;
                atrValues.add(atr);
            }
        }

        return atrValues;
    }

    /**
     * Builds an NiftyExtremeDay object with all required data.
     */
    private NiftyExtremeDay buildExtremeDayData(Candle candle, double atr, double dayMove, double atrMultiple) {
        LocalDate date = candle.getTimestamp().toLocalDate();
        double movePercentage = (dayMove / candle.getOpen()) * 100;
        String niftyLevel = String.format("Level: %.0f (±%.0f)", candle.getOpen(), candle.getHigh() - candle.getLow());

        return NiftyExtremeDay.builder()
                .date(date)
                .open(candle.getOpen())
                .close(candle.getClose())
                .high(candle.getHigh())
                .low(candle.getLow())
                .volume(candle.getVolume())
                .atr14(atr)
                .dayMove(dayMove)
                .atrMultiple(atrMultiple)
                .movePercentage(movePercentage)
                .niftyLevel(niftyLevel)
                .build();
    }

    /**
     * Builds an empty response when analysis fails.
     */
    private NiftyATRAnalysisResponse buildEmptyResponse(LocalDate fromDate, LocalDate toDate) {
        return NiftyATRAnalysisResponse.builder()
                .analysisStartDate(fromDate)
                .analysisEndDate(toDate)
                .totalTradingDays(0)
                .extremeDaysCount(0)
                .atrThreshold(EXTREME_MOVE_THRESHOLD)
                .atrPeriod(ATR_PERIOD)
                .mostPositiveDay(null)
                .mostNegativeDay(null)
                .allPositiveDays(Collections.emptyList())
                .allNegativeDays(Collections.emptyList())
                .averageAtr14(0)
                .averageAtrMultiplePositive(0)
                .averageAtrMultipleNegative(0)
                .maxAtrMultiplePositive(0)
                .maxAtrMultipleNegative(0)
                .build();
    }
}


