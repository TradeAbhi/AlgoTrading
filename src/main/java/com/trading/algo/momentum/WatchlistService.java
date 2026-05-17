package com.trading.algo.momentum;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.trading.algo.config.WatchlistConfig;
import com.trading.algo.dtos.WatchlistCategory;
import com.trading.algo.dtos.WatchlistItem;
import com.trading.algo.dtos.WatchlistResponse;
import com.trading.algo.service.UniverseService;
import com.trading.algo.upstox.UpstoxMarketDataService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Core service that:
 *  1. Fetches live quotes for the entire universe.
 *  2. Enriches each item with volumeRatio from AverageVolumeService.
 *  3. Filters and sorts into 7 watchlist categories:
 *       - High OI
 *       - Top Gainers
 *       - Top Losers
 *       - Active by Value
 *       - Volume Shockers
 *       - Only Buyers
 *       - Only Sellers
 *  4. Caches the result and refreshes every `cacheTtlSeconds` during market hours.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WatchlistService {

    private static final LocalTime MARKET_OPEN  = LocalTime.of(9, 15);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);

    private final UpstoxMarketDataService marketDataService;
    private final AverageVolumeService    averageVolumeService;
    private final UniverseService         universeService;
    private final WatchlistConfig         config;

    /** Fast O(1) F&O eligibility check — secondary guard against non-F&O data */
    private static final Set<String> FNO_SYMBOL_SET =
            Set.copyOf(UniverseService.NIFTY_FNO_SYMBOLS);

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns the full watchlist response (cached).
     * Cache is refreshed every minute during market hours via the scheduler.
     */
    @Cacheable(value = "watchlist", key = "'live'")
    public WatchlistResponse getLiveWatchlist() {
        return buildWatchlist();
    }

    /**
     * Returns only one category of the watchlist.
     */
    public List<WatchlistItem> getCategory(WatchlistCategory category) {
        WatchlistResponse response = getLiveWatchlist();
        return switch (category) {
            case HIGH_OI         -> response.getHighOiStocks();
            case TOP_GAINER      -> response.getTopGainers();
            case TOP_LOSER       -> response.getTopLosers();
            case ACTIVE_BY_VALUE -> response.getActiveByValue();
            case VOLUME_SHOCKER  -> response.getVolumeShockers();
            case ONLY_BUYERS     -> response.getOnlyBuyers();
            case ONLY_SELLERS    -> response.getOnlySellers();
        };
    }

    // -------------------------------------------------------------------------
    // Scheduler: refresh every `cacheTtlSeconds` on weekdays during market hours
    // -------------------------------------------------------------------------

    @Scheduled(fixedRateString = "#{watchlistConfig.cacheTtlSeconds * 1000}")
    @CacheEvict(value = "watchlist", key = "'live'")
    public void refreshWatchlist() {
        LocalTime now = LocalTime.now();
        if (isMarketHours(now)) {
            log.debug("Cache evicted - watchlist will refresh on next request");
        }
    }

    // -------------------------------------------------------------------------
    // Core build logic
    // -------------------------------------------------------------------------

    private WatchlistResponse buildWatchlist() {
        long startMs = System.currentTimeMillis();

        List<String> universe = universeService.getUniverse();
        log.info("Building watchlist for {} instruments", universe.size());

        // 1. Fetch live quotes
        List<WatchlistItem> allItems = marketDataService.fetchLiveQuotes(universe);

        // 2. Enrich with volume ratio
        allItems = enrichWithVolumeRatio(allItems);

        // 3. Filter by minimum liquidity
        List<WatchlistItem> liquid = filterLiquid(allItems);

        // 4. Build each category
        List<WatchlistItem> highOi         = buildHighOi(liquid);
        List<WatchlistItem> topGainers     = buildTopGainers(liquid);
        List<WatchlistItem> topLosers      = buildTopLosers(liquid);
        List<WatchlistItem> activeByValue  = buildActiveByValue(liquid);
        List<WatchlistItem> volumeShockers = buildVolumeShockers(liquid);
        List<WatchlistItem> onlyBuyers     = buildOnlyBuyers(liquid);
        List<WatchlistItem> onlySellers    = buildOnlySellers(liquid);

        long elapsed = System.currentTimeMillis() - startMs;
        log.info("Watchlist built in {}ms | scanned={} liquid={}", elapsed, allItems.size(), liquid.size());

        return WatchlistResponse.builder()
                .highOiStocks(highOi)
                .topGainers(topGainers)
                .topLosers(topLosers)
                .activeByValue(activeByValue)
                .volumeShockers(volumeShockers)
                .onlyBuyers(onlyBuyers)
                .onlySellers(onlySellers)
                .generatedAt(LocalDateTime.now())
                .marketStatus(getMarketStatus())
                .totalSymbolsScanned(allItems.size())
                .build();
    }

    // -------------------------------------------------------------------------
    // Enrichment
    // -------------------------------------------------------------------------

    private List<WatchlistItem> enrichWithVolumeRatio(List<WatchlistItem> items) {
        items.forEach(item -> {
            double ratio = averageVolumeService.computeVolumeRatio(item.getSymbol(), item.getVolume());
            item.setVolumeRatio(ratio);
        });
        return items;
    }

    // -------------------------------------------------------------------------
    // Filters
    // -------------------------------------------------------------------------

    /**
     * Applies all base filters:
     *  1. F&O eligible only  — hard block; non-F&O stocks are dropped entirely
     *  2. Valid LTP          — ignore zero-price / circuit-limit anomalies
     *  3. Minimum liquidity  — traded value >= minTradedValueCrores
     */
    private List<WatchlistItem> filterLiquid(List<WatchlistItem> items) {
        long before = items.size();

        List<WatchlistItem> filtered = items.stream()
                .filter(i -> FNO_SYMBOL_SET.contains(i.getSymbol().toUpperCase()))
                .filter(i -> i.getLtp() > 0)
                .filter(i -> i.getTradedValue() >= config.getMinTradedValueCrores())
                .collect(Collectors.toList());

        long dropped = before - filtered.size();
        if (dropped > 0) {
            log.debug("Dropped {} non-F&O or illiquid symbols; {} remain", dropped, filtered.size());
        }
        return filtered;
    }

    // -------------------------------------------------------------------------
    // Category builders
    // -------------------------------------------------------------------------

    /**
     * HIGH OI: Stocks / contracts with highest open interest.
     * Useful for identifying where smart money is positioned.
     */
    private List<WatchlistItem> buildHighOi(List<WatchlistItem> items) {
        return items.stream()
                .filter(i -> i.getOpenInterest() >= config.getMinOpenInterest())
                .sorted(Comparator.comparingLong(WatchlistItem::getOpenInterest).reversed())
                .limit(config.getHighOiLimit())
                .peek(i -> i.setCategory(WatchlistCategory.HIGH_OI))
                .collect(Collectors.toList());
    }

    /**
     * TOP GAINERS: Sorted by % change descending.
     * Only positive changers.
     */
    private List<WatchlistItem> buildTopGainers(List<WatchlistItem> items) {
        return items.stream()
                .filter(i -> i.getChangePercent() > 0)
                .sorted(Comparator.comparingDouble(WatchlistItem::getChangePercent).reversed())
                .limit(config.getTopGainersLimit())
                .peek(i -> i.setCategory(WatchlistCategory.TOP_GAINER))
                .collect(Collectors.toList());
    }

    /**
     * TOP LOSERS: Sorted by % change ascending (most negative first).
     */
    private List<WatchlistItem> buildTopLosers(List<WatchlistItem> items) {
        return items.stream()
                .filter(i -> i.getChangePercent() < 0)
                .sorted(Comparator.comparingDouble(WatchlistItem::getChangePercent))
                .limit(config.getTopLosersLimit())
                .peek(i -> i.setCategory(WatchlistCategory.TOP_LOSER))
                .collect(Collectors.toList());
    }

    /**
     * ACTIVE BY VALUE: Sorted by traded value (crores) descending.
     * Identifies where the most money is flowing.
     */
    private List<WatchlistItem> buildActiveByValue(List<WatchlistItem> items) {
        return items.stream()
                .sorted(Comparator.comparingDouble(WatchlistItem::getTradedValue).reversed())
                .limit(config.getActiveByValueLimit())
                .peek(i -> i.setCategory(WatchlistCategory.ACTIVE_BY_VALUE))
                .collect(Collectors.toList());
    }

    /**
     * VOLUME SHOCKERS: Stocks where today's volume is >= N× the 20-day average.
     * Indicates unusual activity / breakout potential.
     */
    private List<WatchlistItem> buildVolumeShockers(List<WatchlistItem> items) {
        return items.stream()
                .filter(i -> i.getVolumeRatio() >= config.getVolumeShockerThreshold())
                .sorted(Comparator.comparingDouble(WatchlistItem::getVolumeRatio).reversed())
                .limit(config.getVolumeShockerLimit())
                .peek(i -> i.setCategory(WatchlistCategory.VOLUME_SHOCKER))
                .collect(Collectors.toList());
    }

    /**
     * ONLY BUYERS: Stocks where buy qty >> sell qty (ratio >= threshold).
     * Signals strong demand / aggressive buying.
     *
     * Logic: totalBuyQty / totalSellQty >= onlyBuyersRatioThreshold
     * Edge case: if totalSellQty == 0 treat as "pure buyers" (ratio = MAX)
     */
    private List<WatchlistItem> buildOnlyBuyers(List<WatchlistItem> items) {
        return items.stream()
                .filter(i -> i.getTotalSellQty() == 0 ||
                             i.getBuySelRatio() >= config.getOnlyBuyersRatioThreshold())
                .sorted(Comparator.comparingDouble(WatchlistItem::getBuySelRatio).reversed())
                .limit(config.getOnlyBuyersLimit())
                .peek(i -> i.setCategory(WatchlistCategory.ONLY_BUYERS))
                .collect(Collectors.toList());
    }

    /**
     * ONLY SELLERS: Stocks where sell qty >> buy qty (inverse ratio >= threshold).
     * Signals strong selling pressure / distribution.
     *
     * Logic: totalSellQty / totalBuyQty >= onlySellersRatioThreshold
     * Edge case: if totalBuyQty == 0 treat as "pure sellers"
     */
    private List<WatchlistItem> buildOnlySellers(List<WatchlistItem> items) {
        return items.stream()
                .filter(i -> {
                    if (i.getTotalBuyQty() == 0) return true;
                    double sellBuyRatio = (double) i.getTotalSellQty() / i.getTotalBuyQty();
                    return sellBuyRatio >= config.getOnlySellersRatioThreshold();
                })
                .sorted(Comparator.comparingLong(WatchlistItem::getTotalSellQty).reversed())
                .limit(config.getOnlySellersLimit())
                .peek(i -> i.setCategory(WatchlistCategory.ONLY_SELLERS))
                .collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    private boolean isMarketHours(LocalTime time) {
        return !time.isBefore(MARKET_OPEN) && !time.isAfter(MARKET_CLOSE);
    }

    private String getMarketStatus() {
        LocalTime now = LocalTime.now();
        if (now.isBefore(LocalTime.of(9, 0)))  return "CLOSED";
        if (now.isBefore(MARKET_OPEN))          return "PRE_OPEN";
        if (now.isAfter(MARKET_CLOSE))          return "CLOSED";
        return "OPEN";
    }
}