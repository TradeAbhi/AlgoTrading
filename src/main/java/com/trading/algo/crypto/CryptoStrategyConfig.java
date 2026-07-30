package com.trading.algo.crypto;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for EMA+RSI+ATR Crypto Strategy.
 * Override any value in application.yml under crypto-strategy.*
 */
@Data
@Component
@ConfigurationProperties(prefix = "crypto-strategy")
public class CryptoStrategyConfig {

    /** EMA short period for trend filter */
    private int emaShortPeriod = 9;

    /** EMA long period for trend filter */
    private int emaLongPeriod = 21;

    /** RSI period for momentum filter */
    private int rsiPeriod = 14;

    /** ATR period for volatility-based stops/targets */
    private int atrPeriod = 14;

    /** Volume average period for confirmation */
    private int volumeAvgPeriod = 20;

    /** RSI lower bound for long entries (must be >= this) - tightened to 45 */
    private double rsiLongMin = 45.0;

    /** RSI upper bound for long entries (must be <= this) - tightened to 60 */
    private double rsiLongMax = 60.0;

    /** RSI lower bound for short entries (must be >= this) - tightened to 40 */
    private double rsiShortMin = 40.0;

    /** RSI upper bound for short entries (must be <= this) - tightened to 55 */
    private double rsiShortMax = 55.0;

    /** ADX threshold to avoid ranging markets - only trade if ADX > this value */
    private double adxThreshold = 25.0;

    /** ADX period for calculation */
    private int adxPeriod = 14;

    /** Require RSI to cross above 50 for long entries */
    private boolean requireRsiCrossAbove50 = true;

    /** Higher timeframe agreement - check 1-hour trend alignment */
    private boolean checkHigherTimeframeTrend = true;

    /** Minimum body size as % of range for strong breakout candle */
    private double minBodyRatio = 0.6;

    /** Close position as % of range for strong candle (close near high for longs) */
    private double minCloseRatio = 0.7;

    /** Maximum consecutive candles in same direction before avoiding entries */
    private int maxConsecutiveCandles = 4;

    /** Use pullback entry to EMA instead of immediate crossover */
    private boolean usePullbackEntry = true;

    /** Pullback tolerance as % of EMA distance */
    private double pullbackTolerance = 0.5;

    /** Volume confirmation multiplier (current volume >= this × avg volume) */
    private double volumeMultiplier = 1.2;

    /** Stop-loss as multiple of ATR */
    private double slAtrMultiplier = 1.5;

    /** Take-profit as multiple of ATR */
    private double targetAtrMultiplier = 2.5;

    /** Maximum candles to hold position before time-stop */
    private int maxHoldCandles = 8;

    /** Risk per trade as % of equity */
    private double riskPercent = 1.0;

    /** Daily drawdown circuit breaker (%) */
    private double dailyDrawdownLimit = 3.0;

    /** Minimum candles required for indicator calculation */
    private int minCandlesRequired = 50;

    /** Thread pool size for parallel backtesting */
    private int threadPoolSize = 4;

    /** Trading window start hour (24-hour format, UTC) - default 8:00 UTC = 1:30 PM IST */
    private int tradingStartHour = 8;

    /** Trading window start minute - default 0 minutes */
    private int tradingStartMinute = 0;

    /** Trading window end hour (24-hour format, UTC) - default 17:30 UTC = 11:00 PM IST */
    private int tradingEndHour = 17;

    /** Trading window end minute - default 30 minutes */
    private int tradingEndMinute = 30;

    /** Enable time window filter - if false, strategy runs 24/7 */
    private boolean enableTimeWindow = true;
}
