package com.trading.algo.weekly;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * In-memory store for weekly breakout state across all Nifty 500 symbols.
 * Cleared and re-seeded every Monday at 9:31 AM.
 */
@Component
public class WeeklyBreakoutStateStore {

    private static final Logger log = LoggerFactory.getLogger(WeeklyBreakoutStateStore.class);

    private final Map<String, WeeklyBreakoutState> stateMap = new ConcurrentHashMap<>();

    public void put(String symbol, WeeklyBreakoutState state) {
        stateMap.put(symbol, state);
    }

    public WeeklyBreakoutState get(String symbol) {
        return stateMap.get(symbol);
    }

    public boolean contains(String symbol) {
        return stateMap.containsKey(symbol);
    }

    public Collection<WeeklyBreakoutState> all() {
        return stateMap.values();
    }

    public void clear() {
        stateMap.clear();
        log.info("[WEEKLY] State store cleared for new week");
    }

    public int size() {
        return stateMap.size();
    }
}