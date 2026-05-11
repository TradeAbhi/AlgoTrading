package com.trading.algo.entity;


import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tracks stocks that fall within the earnings observation window:
 *   - Pre-earnings  : resultDate - 10 days  →  resultDate - 1 day
 *   - Post-earnings : resultDate             →  resultDate + 3 days
 *
 * A row is created once per (symbol, resultDate) pair and transitions
 * through PRE → POST → EXPIRED automatically.
 */
@Entity
@Table(
    name = "earnings_watchlist",
    uniqueConstraints = @UniqueConstraint(columnNames = {"symbol", "result_date"})
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EarningsWatchlist {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String symbol;
    private String companyName;

    @Column(name = "result_date", nullable = false)
    private LocalDate resultDate;

    /** Underlying event type pulled from Earnings table (e.g. "Quarterly Results"). */
    private String eventType;

    /** First day the stock enters the watchlist (resultDate - 10). */
    @Column(name = "watch_start")
    private LocalDate watchStart;

    /** Last day the stock stays on the watchlist (resultDate + 3). */
    @Column(name = "watch_end")
    private LocalDate watchEnd;

    /**
     * PRE_EARNINGS  : today is in [watchStart, resultDate - 1]
     * POST_EARNINGS : today is in [resultDate, watchEnd]
     * EXPIRED       : today > watchEnd
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WatchPhase phase;

    /** Days remaining until result (negative means result already happened). */
    @Column(name = "days_to_result")
    private int daysToResult;

    @Column(name = "added_at")
    private LocalDateTime addedAt;

    @Column(name = "last_updated_at")
    private LocalDateTime lastUpdatedAt;

    // -----------------------------------------------------------------------
    // Phase enum
    // -----------------------------------------------------------------------

    public enum WatchPhase {
        PRE_EARNINGS,
        POST_EARNINGS,
        EXPIRED
    }
}