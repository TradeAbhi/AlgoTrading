package com.trading.algo.dtos;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a stock in the filtered watchlist with its selection criteria
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FilteredWatchlistItem {

    /**
     * The stock data
     */
    private WatchlistItem stock;

    /**
     * Selection criteria used to pick this stock
     */
    private SelectionCriteria selectionCriteria;

    /**
     * Additional metadata about why this stock was selected
     */
    private String selectionReason;

    /**
     * Frequency score (how many times appeared in recent snapshots)
     */
    private int frequencyScore;

    /**
     * Which category this stock was picked from
     */
    private WatchlistCategory sourceCategory;

    public enum SelectionCriteria {
        FREQUENCY_SCORE,           // High appearance frequency across snapshots
        CATEGORY_DIVERSITY,        // Representative from specific category
        MOMENTUM_CONSISTENCY,      // Consecutive appearances with improving metrics
        LIQUIDITY_VOLUME_SPIKE     // High liquidity + unusual volume activity
    }
}
