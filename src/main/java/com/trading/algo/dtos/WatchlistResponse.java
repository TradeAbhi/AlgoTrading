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
public class WatchlistResponse {

    private List<WatchlistItem> highOiStocks;
    private List<WatchlistItem> topGainers;
    private List<WatchlistItem> topLosers;
    private List<WatchlistItem> activeByValue;
    private List<WatchlistItem> volumeShockers;
    private List<WatchlistItem> onlyBuyers;
    private List<WatchlistItem> onlySellers;

    private LocalDateTime generatedAt;
    private String marketStatus;      // OPEN / PRE_OPEN / CLOSED
    private int totalSymbolsScanned;
}