package com.trading.algo.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Stores 15-minute intraday snapshots of the watchlist.
 * Used for filtering and analyzing momentum patterns throughout the trading day.
 */
@Entity
@Table(name = "intraday_watchlist_snapshot")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntradayWatchlistSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Timestamp when this snapshot was captured
     */
    @Column(name = "snapshot_time", nullable = false)
    private LocalDateTime snapshotTime;

    /**
     * JSON string containing the full watchlist data for this snapshot.
     * Includes top gainers, top losers, volume shockers, active by value, etc.
     */
    @Column(name = "watchlist_data", columnDefinition = "TEXT")
    private String watchlistData;
}
