package com.trading.algo.delta.service;

import com.trading.algo.delta.model.Candle;
import com.trading.algo.delta.model.DayLevel;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches and caches previous-day high/low levels for all monitored symbols.
 * Refreshed once per day via the scheduler.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PreviousDayLevelService {

    private final DeltaApiService      deltaApiService;
    private final List<String>         monitoredSymbols;

    // symbol → DayLevel
    private final Map<String, DayLevel> cache = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        log.info("Initialising previous-day levels on startup...");
        refreshAll();
    }

    /**
     * Called by the daily scheduler and also on startup.
     */
    public void refreshAll() {
        for (String symbol : monitoredSymbols) {
            refreshSymbol(symbol);
        }
    }

    public void refreshSymbol(String symbol) {
        try {
            Candle daily = deltaApiService.getPreviousDayCandle(symbol);
            if (daily == null) {
                log.warn("No previous-day candle returned for {}", symbol);
                return;
            }

            LocalDate date = daily.getOpenTime().atZone(ZoneOffset.UTC).toLocalDate();

            DayLevel level = DayLevel.builder()
                    .symbol(symbol)
                    .date(date)
                    .previousDayHigh(daily.getHigh())
                    .previousDayLow(daily.getLow())
                    .build();

            cache.put(symbol, level);
            log.info("PDL/PDH cached for {} | date={} | low={} | high={}",
                    symbol, date, level.getPreviousDayLow(), level.getPreviousDayHigh());

        } catch (Exception e) {
            log.error("Failed to refresh day level for {}: {}", symbol, e.getMessage(), e);
        }
    }

    /**
     * Returns the cached DayLevel for the given symbol, or null if not available.
     */
    public DayLevel get(String symbol) {
        return cache.get(symbol);
    }

    /** For health/status endpoint */
    public Map<String, DayLevel> getAll() {
        return Map.copyOf(cache);
    }
}
