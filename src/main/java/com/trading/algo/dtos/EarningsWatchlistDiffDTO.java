package com.trading.algo.dtos;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Response for the /api/earnings-watchlist/diff endpoint.
 *
 * Shows what changed since the last window alert was sent:
 *   - toAdd    : new stocks that appeared in the window (not in last snapshot)
 *   - toRemove : stocks that were in the last snapshot but are no longer in the window
 *   - unchanged: stocks present in both
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EarningsWatchlistDiffDTO {

    /** Stocks newly entered the pre-10/post-3 window since last alert. */
    private List<String> toAdd;

    /** Stocks that were in the last alert but have now left the window (expired). */
    private List<String> toRemove;

    /** Stocks present in both last snapshot and current window. */
    private List<String> unchanged;

    /** When the last window alert was sent. Null if never sent. */
    private LocalDateTime lastSentAt;

    /** Total stocks currently in the active window. */
    private int currentTotal;

    /** Whether a Telegram diff alert was sent. */
    private boolean telegramSent;
}