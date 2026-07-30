package com.trading.algo.crypto;

import com.trading.algo.crypto.CryptoStrategyConfig;
import com.trading.algo.dtos.Candle;
import com.trading.algo.entity.BacktestTrade;
import com.trading.algo.entity.BacktestTrade.Direction;
import com.trading.algo.entity.BacktestTrade.Outcome;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * EMA + RSI + ATR Intraday Strategy for Crypto (BTC/ETH/SOL on Delta Exchange)
 *
 * Strategy Logic:
 * 1. Trend Filter: EMA(9) vs EMA(21) crossover sets direction
 *    - EMA(9) > EMA(21) = Bullish trend (look for longs)
 *    - EMA(9) < EMA(21) = Bearish trend (look for shorts)
 *
 * 2. Momentum Filter: RSI(14) must be in "not exhausted" range
 *    - Long: RSI between 40-70 (not oversold, not overbought)
 *    - Short: RSI between 30-60 (not overbought, not oversold)
 *
 * 3. Volume Confirmation: Current volume > 1.2x its 20-period average
 *
 * 4. Entry: At candle close when all conditions met
 *
 * 5. Stop-loss: 1.5x ATR(14) from entry
 *
 * 6. Take-profit: 2.5x ATR(14) from entry
 *
 * 7. Time-stop: Force-exit after 8 candles if neither level hit
 *
 * 8. Position sizing: Risk 1% of equity per trade, sized by ATR distance
 *
 * 9. Daily circuit breaker: Stop trading at -3% drawdown
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmaRsiAtrStrategyService {

    private final CryptoStrategyConfig config;

    /**
     * Evaluate strategy on a single symbol's candles
     *
     * @param symbol Crypto symbol (e.g., "BTCUSD", "ETHUSD", "SOLUSD")
     * @param date Trade date
     * @param candles List of candles (OHLCV)
     * @param equity Starting equity for position sizing
     * @param dailyPnL Running daily P&L for circuit breaker
     * @return Optional trade if signal generated
     */
    public Optional<BacktestTrade> evaluate(String symbol, LocalDate date, 
                                            List<Candle> candles, 
                                            double equity,
                                            double dailyPnL) {
        
        // Check circuit breaker
        if (dailyPnL < 0 && Math.abs(dailyPnL / equity) * 100.0 >= config.getDailyDrawdownLimit()) {
            log.debug("{} {} — Circuit breaker triggered: dailyPnL={} equity={}", 
                    symbol, date, dailyPnL, equity);
            return Optional.empty();
        }

        if (candles == null || candles.size() < config.getMinCandlesRequired()) {
            log.debug("{} {} — Insufficient candles: required={} actual={}", 
                    symbol, date, config.getMinCandlesRequired(), 
                    candles == null ? 0 : candles.size());
            return Optional.empty();
        }

        // Calculate indicators
        List<Double> emaShort = calculateEMA(candles, config.getEmaShortPeriod());
        List<Double> emaLong = calculateEMA(candles, config.getEmaLongPeriod());
        List<Double> rsi = calculateRSI(candles, config.getRsiPeriod());
        List<Double> atr = calculateATR(candles, config.getAtrPeriod());
        List<Double> adx = calculateADX(candles, config.getAdxPeriod());
        List<Double> volumeAvg = calculateSMA(candles, config.getVolumeAvgPeriod(), true);

        if (emaShort.isEmpty() || emaLong.isEmpty() || rsi.isEmpty() || atr.isEmpty() || 
            adx.isEmpty() || volumeAvg.isEmpty()) {
            log.debug("{} {} — Indicator calculation failed", symbol, date);
            return Optional.empty();
        }

        // Find entry points (scan through candles)
        for (int i = config.getAtrPeriod() + 1; i < candles.size(); i++) {
            Candle current = candles.get(i);
            
            // Skip if we don't have enough data for all indicators at this point
            if (i >= emaShort.size() || i >= emaLong.size() || i >= rsi.size() || 
                i >= atr.size() || i >= adx.size() || i >= volumeAvg.size()) {
                continue;
            }

            // Time window filter: only trade during active hours
            if (config.isEnableTimeWindow() && !isWithinTradingWindow(current)) {
                continue;
            }

            double currentEmaShort = emaShort.get(i);
            double currentEmaLong = emaLong.get(i);
            double currentRsi = rsi.get(i);
            double prevRsi = rsi.get(i - 1);
            double currentAtr = atr.get(i);
            double currentAdx = adx.get(i);
            double currentVolumeAvg = volumeAvg.get(i);
            
            double prevEmaShort = emaShort.get(i - 1);
            double prevEmaLong = emaLong.get(i - 1);

            // ADX filter: Avoid ranging markets (ADX must be above threshold)
            if (currentAdx < config.getAdxThreshold()) {
                continue;
            }

            // Trend filter: EMA crossover
            boolean bullishTrend = currentEmaShort > currentEmaLong;
            boolean bearishTrend = currentEmaShort < currentEmaLong;
            
            // Crossover detection
            boolean bullCross = prevEmaShort <= prevEmaLong && currentEmaShort > currentEmaLong;
            boolean bearCross = prevEmaShort >= prevEmaLong && currentEmaShort < currentEmaLong;

            // Volume confirmation
            boolean volumeConfirmed = current.getVolume() >= currentVolumeAvg * config.getVolumeMultiplier();

            // Strong breakout candle filter
            double bodyRatio = current.range() == 0 ? 0 : current.body() / current.range();
            double closeRatio = current.range() == 0 ? 0.5 : (current.getClose() - current.getLow()) / current.range();
            boolean strongCandle = bodyRatio >= config.getMinBodyRatio() && closeRatio >= config.getMinCloseRatio();

            // Check consecutive candles in same direction
            int consecutiveBullish = 0;
            int consecutiveBearish = 0;
            for (int j = i - 1; j >= Math.max(0, i - config.getMaxConsecutiveCandles()); j--) {
                if (candles.get(j).isBullish()) consecutiveBullish++;
                else consecutiveBearish++;
            }
            boolean extendedRun = (consecutiveBullish >= config.getMaxConsecutiveCandles() || 
                                 consecutiveBearish >= config.getMaxConsecutiveCandles());

            Direction direction = null;
            boolean signalValid = false;

            // Long signal conditions
            if ((bullishTrend || bullCross) && volumeConfirmed && strongCandle && !extendedRun) {
                // Tightened RSI range
                if (currentRsi >= config.getRsiLongMin() && currentRsi <= config.getRsiLongMax()) {
                    // RSI cross above 50 filter
                    if (!config.isRequireRsiCrossAbove50() || (prevRsi <= 50 && currentRsi > 50)) {
                        direction = Direction.BUY;
                        signalValid = true;
                    }
                }
            }

            // Short signal conditions
            if ((bearishTrend || bearCross) && volumeConfirmed && !extendedRun) {
                // For shorts, check close near low
                double shortCloseRatio = current.range() == 0 ? 0.5 : (current.getHigh() - current.getClose()) / current.range();
                boolean strongShortCandle = bodyRatio >= config.getMinBodyRatio() && shortCloseRatio >= config.getMinCloseRatio();
                
                if (strongShortCandle) {
                    if (currentRsi >= config.getRsiShortMin() && currentRsi <= config.getRsiShortMax()) {
                        direction = Direction.SELL;
                        signalValid = true;
                    }
                }
            }

            // Pullback entry logic
            if (signalValid && direction != null && config.isUsePullbackEntry()) {
                double emaDistance = Math.abs(currentEmaShort - currentEmaLong);
                double pullbackZone = direction == Direction.BUY 
                        ? currentEmaShort - (emaDistance * config.getPullbackTolerance())
                        : currentEmaShort + (emaDistance * config.getPullbackTolerance());
                
                // Only enter if price is near pullback zone
                boolean nearPullback = direction == Direction.BUY
                        ? current.getClose() >= pullbackZone && current.getClose() <= currentEmaShort
                        : current.getClose() <= pullbackZone && current.getClose() >= currentEmaShort;
                
                if (!nearPullback) {
                    signalValid = false;
                }
            }

            if (signalValid && direction != null && currentAtr > 0) {
                // Calculate trade levels
                double entry = current.getClose();
                double sl = direction == Direction.BUY 
                        ? entry - (currentAtr * config.getSlAtrMultiplier())
                        : entry + (currentAtr * config.getSlAtrMultiplier());
                double target = direction == Direction.BUY
                        ? entry + (currentAtr * config.getTargetAtrMultiplier())
                        : entry - (currentAtr * config.getTargetAtrMultiplier());
                double risk = Math.abs(entry - sl);

                if (risk <= 0) {
                    continue;
                }

                // Position sizing: risk 1% of equity
                double riskAmount = equity * (config.getRiskPercent() / 100.0);
                int quantity = (int) Math.floor(riskAmount / risk);

                if (quantity <= 0) {
                    continue;
                }

                // Simulate trade from entry candle
                BacktestTrade trade = simulateTrade(
                        symbol, date, direction, entry, sl, target, risk, 
                        quantity, candles, i);

                log.info("{} {} — {} SIGNAL | Entry={} SL={} Target={} Risk={} Qty={}",
                        symbol, date, direction,
                        String.format("%.2f", entry),
                        String.format("%.2f", sl),
                        String.format("%.2f", target),
                        String.format("%.2f", risk),
                        quantity);

                return Optional.of(trade);
            }
        }

        return Optional.empty();
    }

    /**
     * Simulate trade execution from entry candle onwards
     * Fixed EOD exit logic to ensure no trade loses more than planned risk
     */
    private BacktestTrade simulateTrade(String symbol, LocalDate date, Direction direction,
                                        double entry, double sl, double target, double risk,
                                        int quantity, List<Candle> candles, int entryIndex) {
        
        double exitPrice = entry;
        Outcome outcome = Outcome.EOD_EXIT;
        int candlesHeld = 0;

        for (int i = entryIndex + 1; i < candles.size(); i++) {
            Candle c = candles.get(i);
            candlesHeld++;

            // Time-stop: exit after maxHoldCandles
            if (candlesHeld > config.getMaxHoldCandles()) {
                // EOD exit: ensure we don't lose more than planned risk
                if (direction == Direction.BUY) {
                    exitPrice = Math.max(c.getClose(), sl); // Don't go below SL
                } else {
                    exitPrice = Math.min(c.getClose(), sl); // Don't go above SL
                }
                outcome = Outcome.EOD_EXIT;
                break;
            }

            if (direction == Direction.BUY) {
                if (c.getLow() <= sl) {
                    exitPrice = sl;
                    outcome = Outcome.SL_HIT;
                    break;
                }
                if (c.getHigh() >= target) {
                    exitPrice = target;
                    outcome = Outcome.TARGET_HIT;
                    break;
                }
            } else {
                if (c.getHigh() >= sl) {
                    exitPrice = sl;
                    outcome = Outcome.SL_HIT;
                    break;
                }
                if (c.getLow() <= target) {
                    exitPrice = target;
                    outcome = Outcome.TARGET_HIT;
                    break;
                }
            }
        }

        // If we exited the loop without hitting SL/Target, use last candle close
        // but ensure it doesn't exceed the planned risk
        if (outcome == Outcome.EOD_EXIT && !candles.isEmpty()) {
            double lastClose = candles.get(candles.size() - 1).getClose();
            if (direction == Direction.BUY) {
                exitPrice = Math.max(lastClose, sl); // Cap loss at SL
            } else {
                exitPrice = Math.min(lastClose, sl); // Cap loss at SL
            }
        }

        double pnlPoints = direction == Direction.BUY ? exitPrice - entry : entry - exitPrice;
        double pnlRupees = pnlPoints * quantity;
        double pnlPercent = entry > 0 ? (pnlPoints / entry) * 100.0 : 0.0;
        double actualRR = risk > 0 ? pnlPoints / risk : 0.0;

        return BacktestTrade.builder()
                .symbol(symbol)
                .tradeDate(date)
                .direction(direction)
                .entryPrice(entry)
                .stopLoss(sl)
                .target(target)
                .riskPoints(risk)
                .rewardPoints(Math.abs(target - entry))
                .quantity(quantity)
                .riskRupees((double) quantity * risk)
                .pnlRupees(pnlRupees)
                .outcome(outcome)
                .exitPrice(exitPrice)
                .pnlPoints(pnlPoints)
                .pnlPercent(pnlPercent)
                .actualRR(actualRR)
                .exitCandleTime(candles.get(Math.min(entryIndex + candlesHeld, candles.size() - 1)).getTimestamp())
                .createdAt(java.time.LocalDateTime.now())
                .build();
    }

    // =========================================================================
    // Indicator Calculations
    // =========================================================================

    /**
     * Calculate Exponential Moving Average
     */
    private List<Double> calculateEMA(List<Candle> candles, int period) {
        List<Double> ema = new ArrayList<>();
        if (candles.size() < period) {
            return ema;
        }

        // Calculate SMA for first period
        double sum = 0;
        for (int i = 0; i < period; i++) {
            sum += candles.get(i).getClose();
        }
        double sma = sum / period;
        ema.add(sma);

        // Calculate EMA for remaining periods
        double multiplier = 2.0 / (period + 1);
        for (int i = period; i < candles.size(); i++) {
            double currentEma = (candles.get(i).getClose() - ema.get(ema.size() - 1)) * multiplier 
                              + ema.get(ema.size() - 1);
            ema.add(currentEma);
        }

        // Pad with nulls for initial candles before we have enough data
        List<Double> result = new ArrayList<>();
        for (int i = 0; i < period - 1; i++) {
            result.add(0.0);
        }
        result.addAll(ema);

        return result;
    }

    /**
     * Calculate Relative Strength Index
     */
    private List<Double> calculateRSI(List<Candle> candles, int period) {
        List<Double> rsi = new ArrayList<>();
        if (candles.size() < period + 1) {
            return rsi;
        }

        List<Double> gains = new ArrayList<>();
        List<Double> losses = new ArrayList<>();

        // Calculate initial gains/losses
        for (int i = 1; i < candles.size(); i++) {
            double change = candles.get(i).getClose() - candles.get(i - 1).getClose();
            gains.add(change > 0 ? change : 0);
            losses.add(change < 0 ? Math.abs(change) : 0);
        }

        // Calculate initial average gain/loss
        double avgGain = 0, avgLoss = 0;
        for (int i = 0; i < period; i++) {
            avgGain += gains.get(i);
            avgLoss += losses.get(i);
        }
        avgGain /= period;
        avgLoss /= period;

        // First RSI value
        double rs = avgLoss == 0 ? 100 : avgGain / avgLoss;
        rsi.add(100.0 - (100.0 / (1.0 + rs)));

        // Calculate subsequent RSI values using smoothed moving average
        for (int i = period; i < gains.size(); i++) {
            avgGain = ((avgGain * (period - 1)) + gains.get(i)) / period;
            avgLoss = ((avgLoss * (period - 1)) + losses.get(i)) / period;
            
            rs = avgLoss == 0 ? 100 : avgGain / avgLoss;
            rsi.add(100.0 - (100.0 / (1.0 + rs)));
        }

        // Pad with nulls for initial candles
        List<Double> result = new ArrayList<>();
        for (int i = 0; i < period; i++) {
            result.add(50.0); // Neutral RSI for initial period
        }
        result.addAll(rsi);

        return result;
    }

    /**
     * Calculate Average Directional Index (ADX)
     * ADX measures trend strength regardless of direction
     * ADX > 25 indicates a strong trend, < 20 indicates ranging
     */
    private List<Double> calculateADX(List<Candle> candles, int period) {
        List<Double> adx = new ArrayList<>();
        if (candles.size() < period * 2 + 1) {
            return adx;
        }

        // Calculate True Range
        List<Double> tr = new ArrayList<>();
        for (int i = 0; i < candles.size(); i++) {
            if (i == 0) {
                tr.add(candles.get(i).range());
            } else {
                double hl = candles.get(i).range();
                double hc = Math.abs(candles.get(i).getHigh() - candles.get(i - 1).getClose());
                double lc = Math.abs(candles.get(i).getLow() - candles.get(i - 1).getClose());
                tr.add(Math.max(hl, Math.max(hc, lc)));
            }
        }

        // Calculate +DM and -DM
        List<Double> plusDM = new ArrayList<>();
        List<Double> minusDM = new ArrayList<>();
        for (int i = 1; i < candles.size(); i++) {
            double upMove = candles.get(i).getHigh() - candles.get(i - 1).getHigh();
            double downMove = candles.get(i - 1).getLow() - candles.get(i).getLow();
            
            double plusDm = (upMove > downMove && upMove > 0) ? upMove : 0;
            double minusDm = (downMove > upMove && downMove > 0) ? downMove : 0;
            
            plusDM.add(plusDm);
            minusDM.add(minusDm);
        }

        // Calculate smoothed TR, +DM, -DM
        List<Double> smoothedTR = new ArrayList<>();
        List<Double> smoothedPlusDM = new ArrayList<>();
        List<Double> smoothedMinusDM = new ArrayList<>();

        // Initial smoothing (first period values)
        double sumTR = 0, sumPlusDM = 0, sumMinusDM = 0;
        for (int i = 0; i < period; i++) {
            sumTR += tr.get(i);
            if (i < plusDM.size()) sumPlusDM += plusDM.get(i);
            if (i < minusDM.size()) sumMinusDM += minusDM.get(i);
        }
        smoothedTR.add(sumTR);
        smoothedPlusDM.add(sumPlusDM);
        smoothedMinusDM.add(sumMinusDM);

        // Subsequent smoothing
        for (int i = period; i < tr.size(); i++) {
            smoothedTR.add(smoothedTR.get(smoothedTR.size() - 1) - (smoothedTR.get(smoothedTR.size() - 1) / period) + tr.get(i));
            if (i - 1 < plusDM.size()) {
                smoothedPlusDM.add(smoothedPlusDM.get(smoothedPlusDM.size() - 1) - (smoothedPlusDM.get(smoothedPlusDM.size() - 1) / period) + plusDM.get(i - 1));
            }
            if (i - 1 < minusDM.size()) {
                smoothedMinusDM.add(smoothedMinusDM.get(smoothedMinusDM.size() - 1) - (smoothedMinusDM.get(smoothedMinusDM.size() - 1) / period) + minusDM.get(i - 1));
            }
        }

        // Calculate +DI and -DI
        List<Double> plusDI = new ArrayList<>();
        List<Double> minusDI = new ArrayList<>();
        for (int i = 0; i < smoothedTR.size(); i++) {
            if (smoothedTR.get(i) > 0) {
                plusDI.add((smoothedPlusDM.get(i) / smoothedTR.get(i)) * 100);
                minusDI.add((smoothedMinusDM.get(i) / smoothedTR.get(i)) * 100);
            } else {
                plusDI.add(0.0);
                minusDI.add(0.0);
            }
        }

        // Calculate DX and ADX
        List<Double> dx = new ArrayList<>();
        for (int i = 0; i < plusDI.size(); i++) {
            double diDiff = Math.abs(plusDI.get(i) - minusDI.get(i));
            double diSum = plusDI.get(i) + minusDI.get(i);
            dx.add(diSum == 0 ? 0 : (diDiff / diSum) * 100);
        }

        // First ADX value (average of first period DX values)
        double sumDX = 0;
        for (int i = 0; i < period && i < dx.size(); i++) {
            sumDX += dx.get(i);
        }
        if (dx.size() >= period) {
            adx.add(sumDX / period);
        }

        // Subsequent ADX values
        for (int i = period; i < dx.size(); i++) {
            double prevADX = adx.get(adx.size() - 1);
            adx.add(((prevADX * (period - 1)) + dx.get(i)) / period);
        }

        // Pad with zeros for initial candles
        List<Double> result = new ArrayList<>();
        int padding = (period * 2) + 1;
        for (int i = 0; i < padding && i < candles.size(); i++) {
            result.add(0.0);
        }
        result.addAll(adx);

        return result;
    }

    /**
     * Check if candle timestamp is within the active trading window
     * Trading window: 13:30 UTC to 23:00 UTC (7:00 PM to 4:30 AM IST)
     */
    private boolean isWithinTradingWindow(Candle candle) {
        LocalTime candleTime = candle.getTimestamp().toLocalTime();
        LocalTime startTime = LocalTime.of(config.getTradingStartHour(), config.getTradingStartMinute());
        LocalTime endTime = LocalTime.of(config.getTradingEndHour(), config.getTradingEndMinute());
        
        // Handle case where window crosses midnight (not applicable here, but good practice)
        if (startTime.isBefore(endTime)) {
            return !candleTime.isBefore(startTime) && !candleTime.isAfter(endTime);
        } else {
            // Window crosses midnight
            return !candleTime.isBefore(startTime) || !candleTime.isAfter(endTime);
        }
    }

    /**
     * Calculate Average True Range
     */
    private List<Double> calculateATR(List<Candle> candles, int period) {
        List<Double> atr = new ArrayList<>();
        if (candles.size() < period + 1) {
            return atr;
        }

        List<Double> trueRanges = new ArrayList<>();

        // Calculate True Range for each candle
        for (int i = 0; i < candles.size(); i++) {
            double tr;
            if (i == 0) {
                tr = candles.get(i).getHigh() - candles.get(i).getLow();
            } else {
                double highLow = candles.get(i).getHigh() - candles.get(i).getLow();
                double highClose = Math.abs(candles.get(i).getHigh() - candles.get(i - 1).getClose());
                double lowClose = Math.abs(candles.get(i).getLow() - candles.get(i - 1).getClose());
                tr = Math.max(highLow, Math.max(highClose, lowClose));
            }
            trueRanges.add(tr);
        }

        // Calculate initial ATR using SMA
        double sum = 0;
        for (int i = 0; i < period; i++) {
            sum += trueRanges.get(i);
        }
        atr.add(sum / period);

        // Calculate subsequent ATR values
        for (int i = period; i < trueRanges.size(); i++) {
            double currentAtr = ((atr.get(atr.size() - 1) * (period - 1)) + trueRanges.get(i)) / period;
            atr.add(currentAtr);
        }

        // Pad with nulls for initial candles
        List<Double> result = new ArrayList<>();
        for (int i = 0; i < period; i++) {
            result.add(0.0);
        }
        result.addAll(atr);

        return result;
    }

    /**
     * Calculate Simple Moving Average
     * @param useVolume if true, calculate SMA of volume, else of close price
     */
    private List<Double> calculateSMA(List<Candle> candles, int period, boolean useVolume) {
        List<Double> sma = new ArrayList<>();
        if (candles.size() < period) {
            return sma;
        }

        for (int i = period - 1; i < candles.size(); i++) {
            double sum = 0;
            for (int j = i - period + 1; j <= i; j++) {
                sum += useVolume ? candles.get(j).getVolume() : candles.get(j).getClose();
            }
            sma.add(sum / period);
        }

        // Pad with nulls for initial candles
        List<Double> result = new ArrayList<>();
        for (int i = 0; i < period - 1; i++) {
            result.add(useVolume ? 0.0 : candles.get(0).getClose());
        }
        result.addAll(sma);

        return result;
    }
}
