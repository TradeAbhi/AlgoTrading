package com.trading.algo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.algo.dtos.*;
import com.trading.algo.entity.IntradayWatchlistSnapshot;
import com.trading.algo.repo.IntradayWatchlistSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class IntradayWatchlistFilterService {

    private final IntradayWatchlistSnapshotRepository snapshotRepository;
    private final ObjectMapper objectMapper;

    private static final int SNAPSHOT_LOOKBACK_COUNT = 6; // Analyze last 6 snapshots (~90 minutes)
    private static final double MIN_TRADED_VALUE_CRORES = 5.0;
    private static final double VOLUME_RATIO_THRESHOLD = 2.5;

    /**
     * Filters 10 stocks from recent intraday snapshots using multiple criteria:
     * 1. Frequency Score (3 stocks) - Most frequent appearances
     * 2. Category Diversity (3 stocks) - 1 from each key category
     * 3. Momentum Consistency (2 stocks) - Consecutive appearances with improving metrics
     * 4. Liquidity + Volume Spike (2 stocks) - High liquidity with unusual volume
     */
    public FilteredWatchlistResponse filterWatchlist() {
        LocalDateTime now = LocalDateTime.now();
        
        // Get recent snapshots
        List<IntradayWatchlistSnapshot> recentSnapshots = 
            snapshotRepository.findTopNByOrderBySnapshotTimeDesc(SNAPSHOT_LOOKBACK_COUNT);
        
        if (recentSnapshots.isEmpty()) {
            log.warn("No intraday snapshots found for filtering");
            return FilteredWatchlistResponse.builder()
                .filteredStocks(List.of())
                .generatedAt(now)
                .snapshotsAnalyzed(0)
                .build();
        }

        log.info("Filtering watchlist from {} snapshots", recentSnapshots.size());

        // Parse all snapshots
        List<WatchlistResponse> watchlistResponses = recentSnapshots.stream()
            .map(this::parseSnapshot)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());

        if (watchlistResponses.isEmpty()) {
            log.warn("No valid watchlist data found in snapshots");
            return FilteredWatchlistResponse.builder()
                .filteredStocks(List.of())
                .generatedAt(now)
                .snapshotsAnalyzed(0)
                .build();
        }

        // Build frequency map
        Map<String, StockFrequency> frequencyMap = buildFrequencyMap(watchlistResponses);

        // Apply filtering criteria
        List<FilteredWatchlistItem> filteredStocks = new ArrayList<>();
        Set<String> selectedSymbols = new HashSet<>();

        // 1. Frequency Score (3 stocks)
        addFrequencyScoreStocks(frequencyMap, filteredStocks, selectedSymbols, 3);

        // 2. Category Diversity (3 stocks)
        addCategoryDiversityStocks(watchlistResponses, filteredStocks, selectedSymbols);

        // 3. Momentum Consistency (2 stocks)
        addMomentumConsistencyStocks(watchlistResponses, filteredStocks, selectedSymbols, 2);

        // 4. Liquidity + Volume Spike (2 stocks)
        addLiquidityVolumeSpikeStocks(watchlistResponses, filteredStocks, selectedSymbols, 2);

        // If we don't have 10 stocks, fill remaining with top frequency stocks
        fillRemainingWithFrequency(frequencyMap, filteredStocks, selectedSymbols, 10);

        LocalDateTime startTime = recentSnapshots.get(recentSnapshots.size() - 1).getSnapshotTime();
        LocalDateTime endTime = recentSnapshots.get(0).getSnapshotTime();

        return FilteredWatchlistResponse.builder()
            .filteredStocks(filteredStocks)
            .generatedAt(now)
            .snapshotsAnalyzed(watchlistResponses.size())
            .snapshotStartTime(startTime)
            .snapshotEndTime(endTime)
            .build();
    }

    private WatchlistResponse parseSnapshot(IntradayWatchlistSnapshot snapshot) {
        try {
            return objectMapper.readValue(snapshot.getWatchlistData(), WatchlistResponse.class);
        } catch (Exception e) {
            log.error("Failed to parse snapshot at {}", snapshot.getSnapshotTime(), e);
            return null;
        }
    }

    private Map<String, StockFrequency> buildFrequencyMap(List<WatchlistResponse> responses) {
        Map<String, StockFrequency> frequencyMap = new HashMap<>();

        for (WatchlistResponse response : responses) {
            // Count appearances across all categories
            countAppearances(response.getTopGainers(), frequencyMap);
            countAppearances(response.getTopLosers(), frequencyMap);
            countAppearances(response.getVolumeShockers(), frequencyMap);
            countAppearances(response.getActiveByValue(), frequencyMap);
            countAppearances(response.getOnlyBuyers(), frequencyMap);
            countAppearances(response.getOnlySellers(), frequencyMap);
            countAppearances(response.getHighOiStocks(), frequencyMap);
        }

        return frequencyMap;
    }

    private void countAppearances(List<WatchlistItem> items, Map<String, StockFrequency> frequencyMap) {
        for (WatchlistItem item : items) {
            String symbol = item.getSymbol();
            StockFrequency freq = frequencyMap.computeIfAbsent(symbol, s -> new StockFrequency());
            freq.count++;
            freq.categories.add(item.getCategory());
            if (item.getChangePercent() > 0) {
                freq.positiveCount++;
            }
        }
    }

    private void addFrequencyScoreStocks(Map<String, StockFrequency> frequencyMap,
                                         List<FilteredWatchlistItem> filteredStocks,
                                         Set<String> selectedSymbols,
                                         int count) {
        List<Map.Entry<String, StockFrequency>> sortedByFrequency = frequencyMap.entrySet().stream()
            .sorted((e1, e2) -> Integer.compare(e2.getValue().count, e1.getValue().count))
            .collect(Collectors.toList());

        int added = 0;
        for (Map.Entry<String, StockFrequency> entry : sortedByFrequency) {
            if (added >= count) break;
            if (selectedSymbols.contains(entry.getKey())) continue;

            // Find the stock data from the most recent snapshot
            WatchlistItem stock = findStockInLatestSnapshot(entry.getKey());
            if (stock != null) {
                filteredStocks.add(FilteredWatchlistItem.builder()
                    .stock(stock)
                    .selectionCriteria(FilteredWatchlistItem.SelectionCriteria.FREQUENCY_SCORE)
                    .selectionReason("Appeared in " + entry.getValue().count + " recent snapshots")
                    .frequencyScore(entry.getValue().count)
                    .sourceCategory(stock.getCategory())
                    .build());
                selectedSymbols.add(entry.getKey());
                added++;
            }
        }

        log.info("Added {} frequency-based stocks", added);
    }

    private void addCategoryDiversityStocks(List<WatchlistResponse> responses,
                                           List<FilteredWatchlistItem> filteredStocks,
                                           Set<String> selectedSymbols) {
        // Get most recent response
        WatchlistResponse latest = responses.get(0);

        // 1 from top gainers
        addFromCategory(latest.getTopGainers(), filteredStocks, selectedSymbols,
            WatchlistCategory.TOP_GAINER, "Strong positive momentum");

        // 1 from volume shockers
        addFromCategory(latest.getVolumeShockers(), filteredStocks, selectedSymbols,
            WatchlistCategory.VOLUME_SHOCKER, "Unusual volume activity");

        // 1 from only buyers
        addFromCategory(latest.getOnlyBuyers(), filteredStocks, selectedSymbols,
            WatchlistCategory.ONLY_BUYERS, "Strong demand pressure");

        log.info("Added category diversity stocks");
    }

    private void addFromCategory(List<WatchlistItem> items,
                                 List<FilteredWatchlistItem> filteredStocks,
                                 Set<String> selectedSymbols,
                                 WatchlistCategory category,
                                 String reason) {
        for (WatchlistItem item : items) {
            if (!selectedSymbols.contains(item.getSymbol())) {
                filteredStocks.add(FilteredWatchlistItem.builder()
                    .stock(item)
                    .selectionCriteria(FilteredWatchlistItem.SelectionCriteria.CATEGORY_DIVERSITY)
                    .selectionReason(reason)
                    .frequencyScore(0)
                    .sourceCategory(category)
                    .build());
                selectedSymbols.add(item.getSymbol());
                return;
            }
        }
    }

    private void addMomentumConsistencyStocks(List<WatchlistResponse> responses,
                                              List<FilteredWatchlistItem> filteredStocks,
                                              Set<String> selectedSymbols,
                                              int count) {
        // Find stocks appearing in consecutive snapshots with improving metrics
        Map<String, MomentumTracker> momentumTrackers = new HashMap<>();

        for (int i = 0; i < responses.size(); i++) {
            WatchlistResponse response = responses.get(i);
            List<WatchlistItem> allItems = getAllItems(response);

            for (WatchlistItem item : allItems) {
                String symbol = item.getSymbol();
                MomentumTracker tracker = momentumTrackers.computeIfAbsent(symbol, s -> new MomentumTracker());
                
                if (i == 0) {
                    tracker.latestChangePercent = item.getChangePercent();
                    tracker.latestVolumeRatio = item.getVolumeRatio();
                } else if (i == 1) {
                    tracker.previousChangePercent = item.getChangePercent();
                    tracker.previousVolumeRatio = item.getVolumeRatio();
                }
            }
        }

        // Find stocks with improving metrics
        List<Map.Entry<String, MomentumTracker>> improvingStocks = momentumTrackers.entrySet().stream()
            .filter(e -> e.getValue().isImproving())
            .sorted((e1, e2) -> Double.compare(
                e2.getValue().getImprovementScore(),
                e1.getValue().getImprovementScore()))
            .collect(Collectors.toList());

        int added = 0;
        for (Map.Entry<String, MomentumTracker> entry : improvingStocks) {
            if (added >= count) break;
            if (selectedSymbols.contains(entry.getKey())) continue;

            WatchlistItem stock = findStockInLatestSnapshot(entry.getKey());
            if (stock != null) {
                filteredStocks.add(FilteredWatchlistItem.builder()
                    .stock(stock)
                    .selectionCriteria(FilteredWatchlistItem.SelectionCriteria.MOMENTUM_CONSISTENCY)
                    .selectionReason("Consecutive appearances with improving metrics")
                    .frequencyScore(0)
                    .sourceCategory(stock.getCategory())
                    .build());
                selectedSymbols.add(entry.getKey());
                added++;
            }
        }

        log.info("Added {} momentum consistency stocks", added);
    }

    private void addLiquidityVolumeSpikeStocks(List<WatchlistResponse> responses,
                                                List<FilteredWatchlistItem> filteredStocks,
                                                Set<String> selectedSymbols,
                                                int count) {
        WatchlistResponse latest = responses.get(0);
        List<WatchlistItem> allItems = getAllItems(latest);

        // Filter by liquidity + volume spike
        List<WatchlistItem> qualified = allItems.stream()
            .filter(i -> i.getTradedValue() >= MIN_TRADED_VALUE_CRORES)
            .filter(i -> i.getVolumeRatio() >= VOLUME_RATIO_THRESHOLD)
            .sorted((i1, i2) -> Double.compare(i2.getVolumeRatio(), i1.getVolumeRatio()))
            .collect(Collectors.toList());

        int added = 0;
        for (WatchlistItem item : qualified) {
            if (added >= count) break;
            if (selectedSymbols.contains(item.getSymbol())) continue;

            filteredStocks.add(FilteredWatchlistItem.builder()
                .stock(item)
                .selectionCriteria(FilteredWatchlistItem.SelectionCriteria.LIQUIDITY_VOLUME_SPIKE)
                .selectionReason(String.format("High liquidity (%.2f Cr) + volume spike (%.2fx)", 
                    item.getTradedValue(), item.getVolumeRatio()))
                .frequencyScore(0)
                .sourceCategory(item.getCategory())
                .build());
            selectedSymbols.add(item.getSymbol());
            added++;
        }

        log.info("Added {} liquidity + volume spike stocks", added);
    }

    private void fillRemainingWithFrequency(Map<String, StockFrequency> frequencyMap,
                                            List<FilteredWatchlistItem> filteredStocks,
                                            Set<String> selectedSymbols,
                                            int targetCount) {
        int remaining = targetCount - filteredStocks.size();
        if (remaining <= 0) return;

        List<Map.Entry<String, StockFrequency>> sortedByFrequency = frequencyMap.entrySet().stream()
            .sorted((e1, e2) -> Integer.compare(e2.getValue().count, e1.getValue().count))
            .collect(Collectors.toList());

        for (Map.Entry<String, StockFrequency> entry : sortedByFrequency) {
            if (remaining <= 0) break;
            if (selectedSymbols.contains(entry.getKey())) continue;

            WatchlistItem stock = findStockInLatestSnapshot(entry.getKey());
            if (stock != null) {
                filteredStocks.add(FilteredWatchlistItem.builder()
                    .stock(stock)
                    .selectionCriteria(FilteredWatchlistItem.SelectionCriteria.FREQUENCY_SCORE)
                    .selectionReason("Filler - high frequency appearance")
                    .frequencyScore(entry.getValue().count)
                    .sourceCategory(stock.getCategory())
                    .build());
                selectedSymbols.add(entry.getKey());
                remaining--;
            }
        }

        log.info("Filled {} remaining slots with frequency-based stocks", targetCount - remaining);
    }

    private List<WatchlistItem> getAllItems(WatchlistResponse response) {
        List<WatchlistItem> allItems = new ArrayList<>();
        allItems.addAll(response.getTopGainers());
        allItems.addAll(response.getTopLosers());
        allItems.addAll(response.getVolumeShockers());
        allItems.addAll(response.getActiveByValue());
        allItems.addAll(response.getOnlyBuyers());
        allItems.addAll(response.getOnlySellers());
        allItems.addAll(response.getHighOiStocks());
        return allItems;
    }

    private WatchlistItem findStockInLatestSnapshot(String symbol) {
        List<IntradayWatchlistSnapshot> snapshots = 
            snapshotRepository.findTopNByOrderBySnapshotTimeDesc(1);
        
        if (snapshots.isEmpty()) return null;
        
        try {
            WatchlistResponse response = objectMapper.readValue(snapshots.get(0).getWatchlistData(), WatchlistResponse.class);
            return getAllItems(response).stream()
                .filter(i -> i.getSymbol().equals(symbol))
                .findFirst()
                .orElse(null);
        } catch (Exception e) {
            log.error("Failed to find stock {} in latest snapshot", symbol, e);
            return null;
        }
    }

    private static class StockFrequency {
        int count = 0;
        int positiveCount = 0;
        Set<WatchlistCategory> categories = new HashSet<>();
    }

    private static class MomentumTracker {
        double latestChangePercent = 0;
        double previousChangePercent = 0;
        double latestVolumeRatio = 0;
        double previousVolumeRatio = 0;

        boolean isImproving() {
            return latestChangePercent > previousChangePercent && 
                   latestVolumeRatio > previousVolumeRatio;
        }

        double getImprovementScore() {
            return (latestChangePercent - previousChangePercent) + 
                   (latestVolumeRatio - previousVolumeRatio);
        }
    }
}
