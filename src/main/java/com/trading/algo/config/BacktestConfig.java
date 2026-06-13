package com.trading.algo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * All tunable parameters for the Opening Candle Strategy backtest.
 * Override any value in application.yml under backtest.*
 *
 * Example application.yml:
 *   backtest:
 *     min-wick-ratio: 0.70
 *     sl-margin-percent: 0.015
 *     target-rr: 2.5
 */
@Data
@Component
@ConfigurationProperties(prefix = "backtest")
public class BacktestConfig {

    /**
     * Minimum wick ratio (body / range) for C1 to qualify as a strong candle.
     * 0.75 = body must be at least 75% of total range (small wicks).
     */
    private double minWickRatio = 0.65;

    /**
     * C2 must trade past this fraction of C1's body to confirm the setup.
     * 0.5 = C2 must reach at least 50% of C1.
     */
    private double confirmationLevel = 0.50;

    /**
     * SL margin added beyond C1 high/low to avoid false stops.
     * 0.015 = 0.015%
     */
    private double slMarginPercent = 0.45;

    /**
     * Minimum risk:reward ratio required.
     * Target = entry + (riskPoints * targetRR)
     */
    private double targetRR = 2.5;

    /**
     * Maximum allowed range of C2 candle as % of C1 reference price.
     * 1.41 = C2 must be a tight candle within 1.41% range.
     * Filters out wide/volatile C2 candles — only tight consolidations qualify.
     */
    private double c2MaxRangePct = 1.41;

    /**
     * Minimum body size in points for C1 to be considered significant.
     * Filters out tiny candles on low-priced stocks.
     */
    private double minCandleBodyPoints = 5.0;

    /**
     * Problem 4 — C1 body must be >= this % of C1 open price.
     * Eliminates tight-range stocks that look strong in points but are actually
     * in consolidation. e.g. stock at ₹1000 → body must be >= ₹3 (0.3%)
     */
    private double minC1BodyPct = 0.3;

    /**
     * Problem 1 — C1 range must be >= this multiple of the 20-day ATR.
     * Ensures the opening move is significant relative to average daily range.
     * 0.5 = C1 range must be at least 50% of avg daily range.
     */
    private double minC1AtrRatio = 0.5;

    /**
     * Problem 2 — C1 volume must be >= this multiple of the 5-day avg 9:15 candle volume.
     * Confirms institutional participation in the opening move.
     * 1.5 = C1 volume must be at least 1.5× the 5-day avg opening candle volume.
     */
    private double minC1VolumeMultiplier = 1.5;

    /** Partial exit RR — book partialExitQtyPct of position at this R level, trail SL to breakeven */
    private double partialExitRR = 1.5;

    /** % of position to close at partialExitRR. Default 50 = close half */
    private double partialExitQtyPct = 50.0;

    /**
     * Maximum losses allowed per day across all symbols.
     * Once this many SL_HIT outcomes occur on a given date, skip remaining symbols.
     */
    private int maxDailyLosses = 3;

    /**
     * After maxDailyLosses consecutive losses, multiply fixedRiskRupees by this factor.
     * 0.5 = halve the risk size until a win restores it.
     */
    private double lossSizeReductionFactor = 0.5;

    /**
     * Fibonacci extension factor for C2 boundary check.
     * 0.414 = the 1.414 extension level (1.414 - 1.0 = 0.414 beyond C1 high/low).
     * C2 must not extend beyond C1.high + (C1.range x 0.414) for SELL
     * or below C1.low - (C1.range x 0.414) for BUY.
     * This is hardcoded in the strategy — change only if you want a different Fib level.
     */
    // Fib extension = 0.414 (hardcoded in OpeningCandleStrategyService — not configurable)

    /**
     * EOD exit time — any open trade at or after this candle is exited at close.
     * 15:15 gives one candle buffer before 15:30 close.
     */
    /**
     * EOD exit time is fixed at 15:15 (3:15 PM) for both stocks and indexes.
     * Hardcoded in OpeningCandleStrategyService — not overridable via config
     * to prevent accidental misconfiguration.
     */
    // EOD exit = 15:15 hardcoded in strategy

    /**
     * Fixed risk per trade in rupees — applies to stocks only.
     * Quantity = floor(fixedRiskRupees / riskPoints)
     * e.g. risk=10pts → qty = floor(10000/10) = 1000 shares
     * Default: ₹10,000
     */
    private double fixedRiskRupees = 10000.0;
    private long apiDelayMs = 200;
    /**
     * Rate limit delay between Upstox API calls per symbol (milliseconds).
     * Upstox free tier: ~10 req/sec. 150ms is safe.
     */
    /**
     * Maximum API requests per second across ALL threads combined.
     * Upstox free tier limit is ~10 req/sec.
     * Set to 8 to stay safely under the limit with headroom.
     */
    private int requestsPerSecond = 5;  // conservative — 200ms gap between any two API calls

    /**
     * Number of parallel threads for symbol processing per day.
     * Can be higher than requestsPerSecond — threads will block on the
     * semaphore if the rate limit is reached, not skip the request.
     */
    private int threadPoolSize = 10;

    /**
     * Maximum retry attempts when rate limited by Upstox API.
     */
    private int maxRetries = 3;

    /**
     * Initial backoff delay in milliseconds when rate limited.
     * Will be doubled on each retry (exponential backoff).
     */
    private long initialBackoffMs = 1000;
}