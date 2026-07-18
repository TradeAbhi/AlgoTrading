package com.trading.algo.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Stores a daily snapshot of the watchlist (top gainers, losers, volume shockers, active by value).
 * Used for backfilling mover analysis when the application was not running.
 *
 * One row per trading day containing the serialized watchlist data.
 */
@Entity
@Table(name = "watchlist_snapshot")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WatchlistSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Trading date for this snapshot
     */
    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    /**
     * JSON string containing the full watchlist data for this day.
     * Includes top gainers, top losers, volume shockers, active by value.
     */
    @Column(name = "watchlist_data", columnDefinition = "TEXT")
    private String watchlistData;

    /**
     * When this snapshot was saved
     */
    @Column(name = "saved_at")
    private LocalDateTime savedAt;
}
