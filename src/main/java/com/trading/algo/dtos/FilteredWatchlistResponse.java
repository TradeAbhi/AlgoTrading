package com.trading.algo.dtos;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FilteredWatchlistResponse {

    /**
     * The 10 filtered stocks with their selection criteria
     */
    private List<FilteredWatchlistItem> filteredStocks;

    /**
     * When this filtered watchlist was generated
     */
    private LocalDateTime generatedAt;

    /**
     * Number of intraday snapshots analyzed
     */
    private int snapshotsAnalyzed;

    /**
     * Time range of snapshots analyzed
     */
    private LocalDateTime snapshotStartTime;
    private LocalDateTime snapshotEndTime;
}
