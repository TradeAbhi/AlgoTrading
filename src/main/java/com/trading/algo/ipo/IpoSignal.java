package com.trading.algo.ipo;

/**
 * Enhanced IPO Signal with momentum, body strength, and gain categorization.
 * Combines signal type with multi-dimensional analysis for conviction rating.
 */
public record IpoSignal(
    // Base signal fields
    String name,              // Company name
    String symbol,            // Trading symbol
    String type,              // Signal type (REVERSAL_BREAKOUT, WEEKLY_BREAKOUT, WEEKLY_BREAKDOWN)
    int priority,             // 1=Breakdown, 2=Breakout, 3=Reversal
    double close,             // Current week close
    double rangeHigh,         // 4-week range high
    double rangeLow,          // 4-week range low
    double changePct,         // % change from previous week
    String reason,            // Signal explanation

    // New: Momentum Pass/Fail analysis
    boolean momentumPass,     // Weekly close > 10-day high
    double previous10DaysHigh,// Highest close in last 10 days
    double momentumStrength,  // % above/below 10-day high (positive = pass, negative = fail)

    // New: Body Strength analysis
    double open,              // Weekly open
    double high,              // Weekly high
    double low,               // Weekly low
    double bodyPercentage,    // Body as % of total range (0-100)
    boolean strongBody,       // Body >= 60%

    // New: Weekly Gain Intensity
    String gainCategory,      // "SUPER_MOVER", "BIG_MOVER", "STRONG_MOVER", "NORMAL", "WEAK", "LOSER"

    // New: Conviction Tier (1=highest, 4=lowest)
    int convictionTier,       // Tier based on multi-dimensional analysis
    int convictionScore       // Raw score for tier calculation
) {

    /**
     * Emoji representation of the signal type
     */
    public String getTypeEmoji() {
        return switch (type) {
            case "REVERSAL BREAKOUT" -> "⭐";
            case "WEEKLY BREAKOUT" -> "💪";
            case "WEEKLY BREAKDOWN" -> "📉";
            default -> "📊";
        };
    }

    /**
     * Emoji representation of the gain category
     */
    public String getGainEmoji() {
        return switch (gainCategory) {
            case "SUPER_MOVER" -> "🚀";
            case "BIG_MOVER" -> "🚀";
            case "STRONG_MOVER" -> "📈";
            case "NORMAL" -> "➡️";
            case "WEAK" -> "📉";
            case "LOSER" -> "💔";
            default -> "❓";
        };
    }

    /**
     * Emoji representation of body strength
     */
    public String getBodyEmoji() {
        return strongBody ? "💪" : "📉";
    }

    /**
     * Emoji representation of momentum status
     */
    public String getMomentumEmoji() {
        return momentumPass ? "💪" : "⚠️";
    }

    /**
     * Get tier stars for visualization
     */
    public String getTierStars() {
        return "⭐".repeat(convictionTier);
    }

    /**
     * Get momentum strength indicator with arrow
     */
    public String getMomentumIndicator() {
        if (momentumPass) {
            return String.format("Above by: +%.2f%%", momentumStrength);
        } else {
            return String.format("Below by: %.2f%%", momentumStrength);
        }
    }
}

