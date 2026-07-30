package com.trading.algo.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Stores 15-minute watchlist alerts received from external source.
 * Contains stocks from various categories: Multi-Category, Top Gainers, 
 * Top Losers, Volume Shockers, Active by Value, High OI, Only Buyers, Only Sellers.
 */
@Entity
@Table(name = "watchlist_alerts")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WatchlistAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Timestamp when this alert was received
     */
    @Column(name = "alert_time", nullable = false)
    private LocalDateTime alertTime;

    /**
     * Raw alert text/data for reference
     */
    @Column(name = "raw_alert", columnDefinition = "TEXT")
    private String rawAlert;

    /**
     * List of all symbols from this alert (deduplicated)
     */
    @ElementCollection
    @CollectionTable(name = "watchlist_alert_symbols", joinColumns = @JoinColumn(name = "alert_id"))
    @Column(name = "symbol")
    private List<String> symbols;

    /**
     * Total number of unique symbols in this alert
     */
    @Column(name = "total_symbols")
    private Integer totalSymbols;

    /**
     * Whether this alert has been processed by ORB strategy
     */
    @Column(name = "orb_processed")
    @Builder.Default
    private Boolean orbProcessed = false;

    /**
     * Whether this alert has been processed by Fibonacci strategy
     */
    @Column(name = "fibo_processed")
    @Builder.Default
    private Boolean fiboProcessed = false;

    /**
     * When this alert was created
     */
    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
}
