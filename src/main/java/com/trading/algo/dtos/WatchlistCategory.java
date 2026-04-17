package com.trading.algo.dtos;


public enum WatchlistCategory {
    HIGH_OI,           // High Open Interest stocks
    TOP_GAINER,        // Top % gainers
    TOP_LOSER,         // Top % losers
    ACTIVE_BY_VALUE,   // Most traded by value (crores)
    VOLUME_SHOCKER,    // Unusual volume surge (volumeRatio > threshold)
    ONLY_BUYERS,       // Only / dominant buy orders (buy-sell ratio high)
    ONLY_SELLERS       // Only / dominant sell orders (buy-sell ratio low)
}