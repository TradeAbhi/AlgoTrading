package com.trading.algo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.algo.dtos.WatchlistCategory;
import com.trading.algo.dtos.WatchlistItem;
import com.trading.algo.dtos.WatchlistResponse;
import com.trading.algo.entity.IntradayWatchlistSnapshot;
import com.trading.algo.entity.MomentumStockSnapshot;
import com.trading.algo.repo.IntradayWatchlistSnapshotRepository;
import com.trading.algo.repo.MomentumStockSnapshotRepository;
import com.trading.algo.telegram.TelegramService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service to capture and manage momentum stock snapshots for strategy filtering.
 * 
 * Captures stocks from momentum categories:
 * - Top Gainers
 * - Top Losers  
 * - High OI
 * - Active by Value
 * - Volume Shockers
 * - Only Buyers
 * - Only Sellers
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MomentumStockSnapshotService {

    private final MomentumStockSnapshotRepository snapshotRepository;
    private final IntradayWatchlistSnapshotRepository watchlistSnapshotRepository;
    private final ObjectMapper objectMapper;
    private final IntradayWatchlistFilterService watchlistFilterService;
    private final TelegramService telegramService;

    /**
     * Captures current momentum stocks from the latest watchlist snapshot
     * and stores them for strategy use.
     */
    public int captureMomentumStocks() {
        try {
            LocalDateTime now = LocalDateTime.now();
            
            // Get the latest watchlist data from the filter service
            var filteredResponse = watchlistFilterService.filterWatchlist();
            
            if (filteredResponse == null || filteredResponse.getFilteredStocks().isEmpty()) {
                log.warn("No momentum stocks available to capture");
                return 0;
            }

            // Extract unique symbols from filtered stocks
            Set<String> momentumSymbols = filteredResponse.getFilteredStocks().stream()
                .map(item -> item.getStock().getSymbol())
                .collect(Collectors.toSet());

            // Also get raw watchlist data to capture category information
            List<IntradayWatchlistSnapshot> latestSnapshots = 
                watchlistSnapshotRepository.findTopNByOrderBySnapshotTimeDesc(1);
            
            Map<String, Set<WatchlistCategory>> symbolCategories = new HashMap<>();
            
            if (!latestSnapshots.isEmpty()) {
                try {
                    WatchlistResponse watchlist = objectMapper.readValue(
                        latestSnapshots.get(0).getWatchlistData(), 
                        WatchlistResponse.class
                    );
                    
                    // Build category mapping
                    extractCategories(watchlist.getTopGainers(), symbolCategories, WatchlistCategory.TOP_GAINER);
                    extractCategories(watchlist.getTopLosers(), symbolCategories, WatchlistCategory.TOP_LOSER);
                    extractCategories(watchlist.getVolumeShockers(), symbolCategories, WatchlistCategory.VOLUME_SHOCKER);
                    extractCategories(watchlist.getActiveByValue(), symbolCategories, WatchlistCategory.ACTIVE_BY_VALUE);
                    extractCategories(watchlist.getOnlyBuyers(), symbolCategories, WatchlistCategory.ONLY_BUYERS);
                    extractCategories(watchlist.getOnlySellers(), symbolCategories, WatchlistCategory.ONLY_SELLERS);
                    extractCategories(watchlist.getHighOiStocks(), symbolCategories, WatchlistCategory.HIGH_OI);
                } catch (Exception e) {
                    log.error("Failed to parse watchlist for category mapping", e);
                }
            }

            // Create and save snapshot - convert Map to List<SymbolCategory>
            List<com.trading.algo.entity.SymbolCategory> categoryList = new ArrayList<>();
            symbolCategories.forEach((symbol, categories) -> {
                com.trading.algo.entity.SymbolCategory sc = com.trading.algo.entity.SymbolCategory.builder()
                    .symbol(symbol)
                    .build();
                sc.setCategories(categories);
                categoryList.add(sc);
            });

            MomentumStockSnapshot snapshot = MomentumStockSnapshot.builder()
                .snapshotTime(now)
                .symbols(new ArrayList<>(momentumSymbols))
                .symbolCategories(categoryList)
                .totalStocks(momentumSymbols.size())
                .build();

            snapshotRepository.save(snapshot);
            log.info("Captured momentum stock snapshot: {} symbols at {}", momentumSymbols.size(), now);
            return momentumSymbols.size();

        } catch (Exception e) {
            log.error("Failed to capture momentum stock snapshot", e);
            return 0;
        }
    }

    /** Sends the selected momentum universe before strategy-specific filters run. */
    public int sendLatestSnapshotAlert() {
        Optional<MomentumStockSnapshot> snapshot = getLatestSnapshot();
        if (snapshot.isEmpty() || snapshot.get().getSymbols() == null || snapshot.get().getSymbols().isEmpty()) {
            log.warn("No momentum snapshot available to send to Telegram");
            return 0;
        }

        MomentumStockSnapshot value = snapshot.get();
        StringBuilder message = new StringBuilder("*Momentum Snapshot — Strategy Universe*\n");
        message.append("Time: ").append(value.getSnapshotTime()).append("\n");
        message.append("Stocks: ").append(value.getTotalStocks()).append("\n\n");

        // Build a map of symbol -> categories for quick lookup
        Map<String, Set<WatchlistCategory>> categoriesBySymbol = new HashMap<>();
        if (value.getSymbolCategories() != null) {
            value.getSymbolCategories().forEach(sc -> {
                categoriesBySymbol.put(sc.getSymbol(), sc.getCategories());
            });
        }

        value.getSymbols().stream().sorted().forEach(symbol -> {
            Set<WatchlistCategory> categories = categoriesBySymbol.getOrDefault(symbol, Collections.emptySet());
            String categoryLabel = categories.isEmpty() ? "Filtered momentum" : categories.stream()
                    .map(Enum::name).sorted().collect(Collectors.joining(", "));
            message.append("• ").append(symbol).append(" — ").append(categoryLabel).append("\n");
        });
        message.append("\nFibonacci and ORB will evaluate only these stocks. A/D is applied afterwards.");
        telegramService.sendMessageToIntraday(message.toString());
        log.info("Momentum snapshot Telegram alert sent: {} symbols", value.getTotalStocks());
        return value.getTotalStocks();
    }

    /**
     * Gets the most recent momentum stock symbols.
     * Returns empty list if no snapshot exists.
     */
    public List<String> getLatestMomentumSymbols() {
        try {
            List<MomentumStockSnapshot> snapshots = 
                snapshotRepository.findTopNByOrderBySnapshotTimeDesc(1);
            
            if (snapshots.isEmpty()) {
                log.warn("No momentum stock snapshots found");
                return Collections.emptyList();
            }

            return snapshots.get(0).getSymbols();
        } catch (Exception e) {
            log.error("Failed to get latest momentum symbols", e);
            return Collections.emptyList();
        }
    }

    /**
     * Gets the most recent momentum stock snapshot with full details.
     */
    public Optional<MomentumStockSnapshot> getLatestSnapshot() {
        try {
            List<MomentumStockSnapshot> snapshots = 
                snapshotRepository.findTopNByOrderBySnapshotTimeDesc(1);
            
            if (snapshots.isEmpty()) {
                return Optional.empty();
            }

            return Optional.of(snapshots.get(0));
        } catch (Exception e) {
            log.error("Failed to get latest momentum snapshot", e);
            return Optional.empty();
        }
    }

    /**
     * Gets momentum stocks filtered by specific category.
     */
    public List<String> getSymbolsByCategory(WatchlistCategory category) {
        return getLatestSnapshot()
            .map(snapshot -> {
                Set<String> categorySymbols = new HashSet<>();
                if (snapshot.getSymbolCategories() != null) {
                    snapshot.getSymbolCategories().forEach(sc -> {
                        if (sc.getCategories().contains(category)) {
                            categorySymbols.add(sc.getSymbol());
                        }
                    });
                }
                return new ArrayList<>(categorySymbols);
            })
            .orElse(new ArrayList<>());
    }

    /**
     * Applies advance/decline ratio to categorize trade direction.
     * 
     * @param adRatio Advance/Decline ratio
     * @param direction Proposed trade direction
     * @return true if the trade direction is allowed based on A/D ratio
     */
    public boolean isTradeDirectionAllowed(double adRatio, String direction) {
        if (adRatio < 0) {
            // A/D ratio unavailable - allow all
            return true;
        }

        boolean isBuy = "BUY".equalsIgnoreCase(direction);
        
        if (isBuy) {
            // BUY allowed when A/D ratio shows bullish breadth (>= 1.0)
            return adRatio >= 1.0;
        } else {
            // SELL allowed when A/D ratio shows bearish breadth (< 1.0)
            return adRatio < 1.0;
        }
    }

    /**
     * Categorizes trade based on A/D ratio and returns appropriate category.
     */
    public String categorizeTradeByAdRatio(double adRatio) {
        if (adRatio < 0) {
            return "NEUTRAL";
        }
        
        if (adRatio >= 2.0) {
            return "VERY_BULLISH";
        } else if (adRatio >= 1.5) {
            return "BULLISH";
        } else if (adRatio >= 1.0) {
            return "MILDLY_BULLISH";
        } else if (adRatio >= 0.7) {
            return "NEUTRAL";
        } else if (adRatio >= 0.5) {
            return "MILDLY_BEARISH";
        } else {
            return "BEARISH";
        }
    }

    private void extractCategories(List<WatchlistItem> items, 
                                   Map<String, Set<WatchlistCategory>> symbolCategories,
                                   WatchlistCategory category) {
        if (items == null) return;
        
        for (WatchlistItem item : items) {
            symbolCategories.computeIfAbsent(item.getSymbol(), k -> new HashSet<>())
                .add(category);
        }
    }
}
