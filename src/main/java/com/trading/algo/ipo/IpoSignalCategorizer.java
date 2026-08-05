package com.trading.algo.ipo;

/**
 * Utility class for categorizing IPO signals by momentum, body strength, and gain intensity.
 * Provides multi-dimensional analysis for conviction tier assignment.
 */
public class IpoSignalCategorizer {

    // Configuration constants (can be made configurable later)
    private static final double BODY_STRONG_THRESHOLD = 0.60;
    private static final double BIG_MOVER_THRESHOLD = 10.0;
    private static final double SUPER_MOVER_THRESHOLD = 15.0;
    private static final double STRONG_MOVER_THRESHOLD = 5.0;

    // Conviction tier scoring weights
    private static final int REVERSAL_PRIORITY_WEIGHT = 30;
    private static final int BREAKOUT_PRIORITY_WEIGHT = 20;
    private static final int BREAKDOWN_PRIORITY_WEIGHT = 10;
    private static final int MOMENTUM_PASS_WEIGHT = 30;
    private static final int MOMENTUM_FAIL_PENALTY = -15;
    private static final int STRONG_BODY_WEIGHT = 20;
    private static final int WEAK_BODY_PENALTY = -10;
    private static final int SUPER_MOVER_WEIGHT = 25;
    private static final int BIG_MOVER_WEIGHT = 20;
    private static final int STRONG_MOVER_WEIGHT = 15;
    private static final int WEAK_MOVER_PENALTY = -15;
    private static final int LOSER_PENALTY = -30;

    /**
     * Check if momentum pass: weekly close > previous 10 days high
     */
    public static boolean isMomentumPass(double close, double previous10DaysHigh) {
        return close > previous10DaysHigh;
    }

    /**
     * Calculate momentum strength percentage
     * Positive = above 10-day high (momentum pass)
     * Negative = below 10-day high (momentum fail)
     */
    public static double calculateMomentumStrength(double close, double previous10DaysHigh) {
        if (previous10DaysHigh <= 0) {
            return 0.0;
        }
        return ((close - previous10DaysHigh) / previous10DaysHigh) * 100.0;
    }

    /**
     * Check if body is strong: body >= 60% of total range
     * Body = abs(close - open)
     * Range = high - low
     */
    public static boolean isStrongBody(double open, double close, double high, double low) {
        double body = Math.abs(close - open);
        double range = high - low;

        // Edge case: no range (open == close == high == low)
        if (range <= 0) {
            return false;
        }

        return (body / range) >= BODY_STRONG_THRESHOLD;
    }

    /**
     * Calculate body as percentage of total range
     */
    public static double calculateBodyPercentage(double open, double close, double high, double low) {
        double body = Math.abs(close - open);
        double range = high - low;

        if (range <= 0) {
            return 0.0;
        }

        return (body / range) * 100.0;
    }

    /**
     * Categorize weekly gain into discrete categories
     */
    public static String getGainCategory(double weeklyGainPct) {
        if (weeklyGainPct > SUPER_MOVER_THRESHOLD) {
            return "SUPER_MOVER";
        } else if (weeklyGainPct > BIG_MOVER_THRESHOLD) {
            return "BIG_MOVER";
        } else if (weeklyGainPct > STRONG_MOVER_THRESHOLD) {
            return "STRONG_MOVER";
        } else if (weeklyGainPct >= 0) {
            return "NORMAL";
        } else {
            return "LOSER";
        }
    }

    /**
     * Calculate conviction score based on multi-dimensional analysis
     * Higher score = more conviction
     */
    public static int calculateConvictionScore(
            String signalType,
            boolean momentumPass,
            boolean strongBody,
            String gainCategory) {

        int score = 0;

        // 1. Base score from signal type/priority
        score += switch (signalType) {
            case "REVERSAL BREAKOUT" -> REVERSAL_PRIORITY_WEIGHT;
            case "WEEKLY BREAKOUT" -> BREAKOUT_PRIORITY_WEIGHT;
            case "WEEKLY BREAKDOWN" -> BREAKDOWN_PRIORITY_WEIGHT;
            default -> 0;
        };

        // 2. Momentum score
        score += momentumPass ? MOMENTUM_PASS_WEIGHT : MOMENTUM_FAIL_PENALTY;

        // 3. Body strength score
        score += strongBody ? STRONG_BODY_WEIGHT : WEAK_BODY_PENALTY;

        // 4. Gain category score
        score += switch (gainCategory) {
            case "SUPER_MOVER" -> SUPER_MOVER_WEIGHT;
            case "BIG_MOVER" -> BIG_MOVER_WEIGHT;
            case "STRONG_MOVER" -> STRONG_MOVER_WEIGHT;
            case "NORMAL" -> 5;
            case "WEAK" -> WEAK_MOVER_PENALTY;
            case "LOSER" -> LOSER_PENALTY;
            default -> 0;
        };

        return score;
    }

    /**
     * Convert conviction score to tier (1=highest, 4=lowest)
     * Tier 1: Score >= 70  (Highest conviction)
     * Tier 2: Score >= 50  (High conviction)
     * Tier 3: Score >= 30  (Medium conviction)
     * Tier 4: Score < 30   (Low conviction)
     */
    public static int getConvictionTier(int score) {
        if (score >= 70) {
            return 1;  // Tier 1 - Highest conviction
        } else if (score >= 50) {
            return 2;  // Tier 2 - High conviction
        } else if (score >= 30) {
            return 3;  // Tier 3 - Medium conviction
        } else {
            return 4;  // Tier 4 - Low conviction
        }
    }

    /**
     * Get tier description
     */
    public static String getTierDescription(int tier) {
        return switch (tier) {
            case 1 -> "HIGHEST CONVICTION";
            case 2 -> "HIGH CONVICTION";
            case 3 -> "MEDIUM CONVICTION";
            case 4 -> "LOW CONVICTION";
            default -> "UNKNOWN";
        };
    }

    /**
     * Get configuration parameters for external adjustment
     */
    public static class Config {
        public static double getBodyStrongThreshold() {
            return BODY_STRONG_THRESHOLD;
        }

        public static double getBigMoverThreshold() {
            return BIG_MOVER_THRESHOLD;
        }

        public static double getSuperMoverThreshold() {
            return SUPER_MOVER_THRESHOLD;
        }
    }
}

