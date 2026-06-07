package com.trading.algo.dtos;

import com.trading.algo.entity.EarningsWatchlist.WatchPhase;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Lightweight response object for the earnings watchlist API.
 * Combines the stored EarningsWatchlist record with a human-readable label.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EarningsWatchlistItemDTO {

    private Long id;

    private String symbol;
    private String companyName;
    private String eventType;

    private LocalDate resultDate;
    private LocalDate watchStart;   // resultDate - 10 days
    private LocalDate watchEnd;     // resultDate + 3 days

    private WatchPhase phase;       // PRE_EARNINGS | POST_EARNINGS | EXPIRED

    /**
     * Positive = days until result (pre-earnings).
     * Zero     = result is today.
     * Negative = result happened N days ago (post-earnings).
     */
    private int daysToResult;

    /** Human-friendly label, e.g. "3 days before result", "Result day", "1 day after result". */
    private String phaseLabel;

    private LocalDateTime addedAt;
    private LocalDateTime lastUpdatedAt;
}