package com.trading.algo.momentum;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.algo.config.WatchlistConfig;
import com.trading.algo.dtos.CombinedCategoryItem;
import com.trading.algo.dtos.WatchlistCategory;
import com.trading.algo.dtos.WatchlistItem;
import com.trading.algo.dtos.WatchlistResponse;
import com.trading.algo.entity.WatchlistSnapshot;
import com.trading.algo.repo.WatchlistSnapshotRepository;
import com.trading.algo.service.UniverseService;
import com.trading.algo.upstox.UpstoxMarketDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

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
    private final WatchlistConfig            config;
    private final WatchlistSnapshotRepository snapshotRepo;
    private final ObjectMapper                objectMapper;
    private final com.trading.algo.upstox.UpstoxTokenService upstoxTokenService;
    private final com.trading.algo.upstox.UpstoxInstrumentMasterService instrumentMasterService;

    /** Fast O(1) F&O eligibility check — secondary guard against non-F&O data */
    private static final Set<String> FNO_SYMBOL_SET =
            Set.copyOf(UniverseService.NIFTY_FNO_SYMBOLS);

    /** Ensures the missed-snapshot check runs at most once per app lifecycle */
    private final AtomicBoolean startupCheckDone = new AtomicBoolean(false);

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

    @Scheduled(fixedRateString = "#{watchlistConfig.cacheTtlSeconds * 1000}", initialDelayString = "#{watchlistConfig.cacheTtlSeconds * 1000}")
    @CacheEvict(value = "watchlist", key = "'live'")
    public void refreshWatchlist() {
        LocalTime now = LocalTime.now();
        if (isMarketHours(now)) {
            log.debug("Cache evicted - watchlist will refresh on next request");
        }
    }

    // -------------------------------------------------------------------------
    // Daily snapshot: save watchlist at market close for backfill
    // -------------------------------------------------------------------------

    /**
     * Saves a snapshot of the watchlist at 3:35 PM (after market close).
     * This snapshot is used for backfilling mover analysis when the application was not running.
     */
    @Scheduled(cron = "0 35 15 * * MON-FRI", zone = "Asia/Kolkata")
    @Transactional
    public void saveDailySnapshot() {
        saveSnapshotForDate(LocalDate.now());
    }

    /**
     * On startup: check if today's or the last trading day's snapshot was missed
     * (e.g. app was down at 3:35 PM) and save it now if after market close.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void saveSnapshotOnStartupIfMissed() {
        if (!upstoxTokenService.isAuthenticated()) {
            log.info("Startup snapshot check skipped — Upstox not authenticated yet");
            return;
        }
        runMissedSnapshotCheck();
    }

    /**
     * Called after Upstox OAuth completes. Runs the missed-snapshot check
     * only if it hasn't already run during startup.
     */
    public void trySnapshotAfterAuth() {
        runMissedSnapshotCheck();
    }

    private void runMissedSnapshotCheck() {
        if (!startupCheckDone.compareAndSet(false, true)) {
            log.info("Missed-snapshot check already ran, skipping");
            return;
        }

        LocalDate today = LocalDate.now();
        LocalTime now = LocalTime.now();
        boolean isWeekday = today.getDayOfWeek() != DayOfWeek.SATURDAY
                         && today.getDayOfWeek() != DayOfWeek.SUNDAY;

        if (isWeekday && now.isAfter(MARKET_CLOSE) && !snapshotRepo.existsByTradeDate(today)) {
            log.info("Missed-snapshot check: today's snapshot missing, saving for {}", today);
            saveSnapshotForDate(today);
        }

        LocalDate lastTradingDay = getLastTradingDay(today);
        if (!snapshotRepo.existsByTradeDate(lastTradingDay)) {
            log.info("Missed-snapshot check: last trading day snapshot missing, saving for {}", lastTradingDay);
            saveSnapshotForDate(lastTradingDay);
        }
    }

    @Transactional
    public void saveSnapshotForDate(LocalDate date) {
        try {
            WatchlistResponse watchlist = buildWatchlist();
            String jsonData = objectMapper.writeValueAsString(watchlist);
            snapshotRepo.deleteByTradeDate(date);
            snapshotRepo.save(WatchlistSnapshot.builder()
                    .tradeDate(date)
                    .watchlistData(jsonData)
                    .savedAt(LocalDateTime.now())
                    .build());
            log.info("Watchlist snapshot saved for {}", date);
        } catch (Exception e) {
            log.error("Failed to save watchlist snapshot for {}: {}", date, e.getMessage());
        }
    }

    /** Returns the most recent weekday before or equal to the given date (skipping Sat/Sun). */
    private LocalDate getLastTradingDay(LocalDate date) {
        LocalDate d = date.minusDays(1);
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
            d = d.minusDays(1);
        }
        return d;
    }

    // -------------------------------------------------------------------------
    // Core build logic
    // -------------------------------------------------------------------------

    private WatchlistResponse buildWatchlist() {
        if (!upstoxTokenService.isAuthenticated()) {
            log.debug("buildWatchlist skipped — Upstox not authenticated yet");
            return WatchlistResponse.builder()
                    .topGainers(List.of()).topLosers(List.of())
                    .volumeShockers(List.of()).activeByValue(List.of())
                    .highOiStocks(List.of()).onlyBuyers(List.of()).onlySellers(List.of())
                    .generatedAt(LocalDateTime.now()).marketStatus("CLOSED").totalSymbolsScanned(0)
                    .build();
        }

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

        // 5. Detect stocks in multiple categories and create combined category
        List<CombinedCategoryItem> combinedCategory = buildCombinedCategory(
                highOi, topGainers, topLosers, activeByValue, volumeShockers, onlyBuyers, onlySellers);

        // 6. Remove stocks in combined category from individual categories
        Set<String> combinedSymbols = combinedCategory.stream()
                .map(CombinedCategoryItem::getSymbol)
                .collect(Collectors.toSet());

        highOi         = removeCombinedStocks(highOi, combinedSymbols);
        topGainers     = removeCombinedStocks(topGainers, combinedSymbols);
        topLosers      = removeCombinedStocks(topLosers, combinedSymbols);
        activeByValue  = removeCombinedStocks(activeByValue, combinedSymbols);
        volumeShockers = removeCombinedStocks(volumeShockers, combinedSymbols);
        onlyBuyers     = removeCombinedStocks(onlyBuyers, combinedSymbols);
        onlySellers    = removeCombinedStocks(onlySellers, combinedSymbols);

        long elapsed = System.currentTimeMillis() - startMs;
        log.info("Watchlist built in {}ms | scanned={} liquid={} combined={}", 
                elapsed, allItems.size(), liquid.size(), combinedCategory.size());

        return WatchlistResponse.builder()
                .highOiStocks(highOi)
                .topGainers(topGainers)
                .topLosers(topLosers)
                .activeByValue(activeByValue)
                .volumeShockers(volumeShockers)
                .onlyBuyers(onlyBuyers)
                .onlySellers(onlySellers)
                .combinedCategory(combinedCategory)
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
     * Fetches OI data from F&O instruments separately since equity instruments don't have OI.
     */
    private List<WatchlistItem> buildHighOi(List<WatchlistItem> items) {
        long minOiThreshold = config.getMinOpenInterest();
        log.info("[HIGH_OI] Building high OI list - threshold: {}, input items: {}", minOiThreshold, items.size());

        // Extract symbols from equity items
        List<String> symbols = items.stream()
                .map(WatchlistItem::getSymbol)
                .distinct()
                .toList();

        // Fetch OI data from F&O instruments
        Map<String, Long> oiMap = fetchOiFromFnoInstruments(symbols);
        log.info("[HIGH_OI] Fetched OI for {}/{} symbols from F&O instruments", oiMap.size(), symbols.size());

        // Enrich equity items with F&O OI data
        List<WatchlistItem> enrichedItems = items.stream()
                .peek(item -> {
                    Long fnoOi = oiMap.get(item.getSymbol());
                    if (fnoOi != null && fnoOi > 0) {
                        item.setOpenInterest(fnoOi);
                    }
                })
                .toList();

        // Log top 10 OI values after enrichment
        List<WatchlistItem> sortedByOi = enrichedItems.stream()
                .sorted(Comparator.comparingLong(WatchlistItem::getOpenInterest).reversed())
                .toList();

        if (!sortedByOi.isEmpty()) {
            log.info("[HIGH_OI] Top 10 OI values after F&O enrichment:");
            for (int i = 0; i < Math.min(10, sortedByOi.size()); i++) {
                WatchlistItem item = sortedByOi.get(i);
                log.info("[HIGH_OI]   #{}: {} - OI: {}, LTP: {}, TradedValue: {}",
                        i + 1, item.getSymbol(), item.getOpenInterest(), item.getLtp(), item.getTradedValue());
            }
        }

        // Count how many items have OI data
        long itemsWithOi = enrichedItems.stream().filter(i -> i.getOpenInterest() > 0).count();
        log.info("[HIGH_OI] Items with OI > 0 after F&O enrichment: {}/{}", itemsWithOi, enrichedItems.size());

        // Apply filter
        List<WatchlistItem> filtered = enrichedItems.stream()
                .filter(i -> i.getOpenInterest() >= minOiThreshold)
                .sorted(Comparator.comparingLong(WatchlistItem::getOpenInterest).reversed())
                .limit(config.getHighOiLimit())
                .peek(i -> i.setCategory(WatchlistCategory.HIGH_OI))
                .collect(Collectors.toList());

        log.info("[HIGH_OI] After filtering - items with OI >= {}: {}", minOiThreshold, filtered.size());

        if (filtered.isEmpty()) {
            log.warn("[HIGH_OI] NO ITEMS PASSED THE OI FILTER! This might indicate:");
            log.warn("[HIGH_OI]   1. F&O OI data not available for these symbols");
            log.warn("[HIGH_OI]   2. Threshold ({}) is too high for current market conditions", minOiThreshold);
            log.warn("[HIGH_OI]   3. All items filtered out by earlier filters (F&O eligibility, liquidity)");
        }

        return filtered;
    }

    /**
     * Fetches open interest data from F&O instruments for the given symbols.
     * Returns a map of symbol -> OI value.
     */
    private Map<String, Long> fetchOiFromFnoInstruments(List<String> symbols) {
        Map<String, Long> oiMap = new HashMap<>();
        try {
            // Resolve symbols to F&O instrument keys using the new F&O-specific method
            Map<String, String> fnoKeyMap = instrumentMasterService
                    .resolveToInstrumentKeyMapForFno(symbols);

            if (fnoKeyMap.isEmpty()) {
                log.warn("[HIGH_OI] No F&O instrument keys resolved for {} symbols", symbols.size());
                return oiMap;
            }

            log.info("[HIGH_OI] Resolved {}/{} symbols to F&O instrument keys", fnoKeyMap.size(), symbols.size());

            // Fetch quotes for F&O instruments to get OI data
            List<String> fnoKeys = new ArrayList<>(fnoKeyMap.values());
            List<WatchlistItem> fnoQuotes = marketDataService.fetchLiveQuotes(fnoKeys);

            log.info("[HIGH_OI] Fetched {} F&O quotes with OI data", fnoQuotes.size());

            // Log first 5 F&O quotes to debug OI values
            for (int i = 0; i < Math.min(5, fnoQuotes.size()); i++) {
                WatchlistItem item = fnoQuotes.get(i);
                log.info("[HIGH_OI] F&O Quote #{}: symbol={}, token={}, OI={}", i+1, item.getSymbol(), item.getInstrumentToken(), item.getOpenInterest());
            }

            // Map OI back to original symbols
            for (WatchlistItem fnoItem : fnoQuotes) {
                String instrumentToken = fnoItem.getInstrumentToken();
                long oi = fnoItem.getOpenInterest();

                // Find the symbol that maps to this instrument token
                for (Map.Entry<String, String> entry : fnoKeyMap.entrySet()) {
                    if (entry.getValue().equals(instrumentToken)) {
                        if (oi > 0) {
                            oiMap.put(entry.getKey(), oi);
                        } else {
                            log.debug("[HIGH_OI] Skipping {} - OI is 0 for token {}", entry.getKey(), instrumentToken);
                        }
                        break;
                    }
                }
            }

            log.info("[HIGH_OI] Successfully mapped OI for {} symbols", oiMap.size());

        } catch (Exception e) {
            log.error("[HIGH_OI] Error fetching OI from F&O instruments: {}", e.getMessage(), e);
        }

        return oiMap;
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

    /**
     * COMBINED CATEGORY: Stocks appearing in at least two categories.
     * These stocks show strong signals across multiple metrics and are highlighted separately.
     */
    private List<CombinedCategoryItem> buildCombinedCategory(
            List<WatchlistItem> highOi,
            List<WatchlistItem> topGainers,
            List<WatchlistItem> topLosers,
            List<WatchlistItem> activeByValue,
            List<WatchlistItem> volumeShockers,
            List<WatchlistItem> onlyBuyers,
            List<WatchlistItem> onlySellers) {

        // Map symbol -> list of categories it belongs to
        Map<String, List<WatchlistCategory>> symbolCategories = new HashMap<>();

        addCategories(symbolCategories, highOi, WatchlistCategory.HIGH_OI);
        addCategories(symbolCategories, topGainers, WatchlistCategory.TOP_GAINER);
        addCategories(symbolCategories, topLosers, WatchlistCategory.TOP_LOSER);
        addCategories(symbolCategories, activeByValue, WatchlistCategory.ACTIVE_BY_VALUE);
        addCategories(symbolCategories, volumeShockers, WatchlistCategory.VOLUME_SHOCKER);
        addCategories(symbolCategories, onlyBuyers, WatchlistCategory.ONLY_BUYERS);
        addCategories(symbolCategories, onlySellers, WatchlistCategory.ONLY_SELLERS);

        // Filter to stocks in at least 2 categories
        Map<String, List<WatchlistCategory>> multiCategorySymbols = symbolCategories.entrySet().stream()
                .filter(entry -> entry.getValue().size() >= 2)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        if (multiCategorySymbols.isEmpty()) {
            return List.of();
        }

        // Build CombinedCategoryItem for each multi-category stock
        // Use the first occurrence of the stock from any category list
        List<WatchlistItem> allItems = new ArrayList<>();
        allItems.addAll(highOi);
        allItems.addAll(topGainers);
        allItems.addAll(topLosers);
        allItems.addAll(activeByValue);
        allItems.addAll(volumeShockers);
        allItems.addAll(onlyBuyers);
        allItems.addAll(onlySellers);

        // Create a map for quick lookup by symbol
        Map<String, WatchlistItem> itemMap = allItems.stream()
                .collect(Collectors.toMap(WatchlistItem::getSymbol, item -> item, (existing, replacement) -> existing));

        return multiCategorySymbols.entrySet().stream()
                .map(entry -> {
                    WatchlistItem item = itemMap.get(entry.getKey());
                    if (item == null) return null;
                    return CombinedCategoryItem.builder()
                            .symbol(item.getSymbol())
                            .exchange(item.getExchange())
                            .instrumentToken(item.getInstrumentToken())
                            .ltp(item.getLtp())
                            .changePercent(item.getChangePercent())
                            .volume(item.getVolume())
                            .volumeRatio(item.getVolumeRatio())
                            .tradedValue(item.getTradedValue())
                            .openInterest(item.getOpenInterest())
                            .totalBuyQty(item.getTotalBuyQty())
                            .totalSellQty(item.getTotalSellQty())
                            .buySelRatio(item.getBuySelRatio())
                            .categories(entry.getValue())
                            .build();
                })
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(item -> -item.getCategories().size())) // Sort by number of categories descending
                .collect(Collectors.toList());
    }

    /**
     * Helper method to add categories to the symbol->categories map.
     */
    private void addCategories(Map<String, List<WatchlistCategory>> symbolCategories,
                               List<WatchlistItem> items, WatchlistCategory category) {
        for (WatchlistItem item : items) {
            symbolCategories.computeIfAbsent(item.getSymbol(), k -> new ArrayList<>()).add(category);
        }
    }

    /**
     * Removes stocks that are in the combined category from individual category lists.
     */
    private List<WatchlistItem> removeCombinedStocks(List<WatchlistItem> items, Set<String> combinedSymbols) {
        return items.stream()
                .filter(item -> !combinedSymbols.contains(item.getSymbol()))
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