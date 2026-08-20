package com.trading.algo.fibostrategy;

import com.trading.algo.entity.BacktestTrade;

/**
 * Mutually exclusive Fibonacci setup categories based on its entry relative
 * to the corresponding previous-day reference level.
 */
public enum FiboPreviousDayCategory {
    BUY_ABOVE_PREV_DAY_HIGH("BUY above previous-day high"),
    BUY_BELOW_OR_AT_PREV_DAY_HIGH("BUY below / at previous-day high"),
    SELL_BELOW_PREV_DAY_LOW("SELL below previous-day low"),
    SELL_ABOVE_OR_AT_PREV_DAY_LOW("SELL above / at previous-day low"),
    PREV_DAY_LEVEL_UNAVAILABLE("Previous-day level unavailable");

    private final String label;

    FiboPreviousDayCategory(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static FiboPreviousDayCategory from(BacktestTrade trade) {
        if (trade.getDirection() == BacktestTrade.Direction.BUY) {
            if (trade.getPrevDayHigh() == null) {
                return PREV_DAY_LEVEL_UNAVAILABLE;
            }
            // Above is strict; an entry exactly at the previous high belongs in below / at.
            return trade.getEntryPrice() > trade.getPrevDayHigh()
                    ? BUY_ABOVE_PREV_DAY_HIGH : BUY_BELOW_OR_AT_PREV_DAY_HIGH;
        }
        if (trade.getPrevDayLow() == null) {
            return PREV_DAY_LEVEL_UNAVAILABLE;
        }
        // Below is strict; an entry exactly at the previous low belongs in above / at.
        return trade.getEntryPrice() < trade.getPrevDayLow()
                ? SELL_BELOW_PREV_DAY_LOW : SELL_ABOVE_OR_AT_PREV_DAY_LOW;
    }
}
