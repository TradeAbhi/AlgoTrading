package com.trading.algo.dtos;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store for US weekly breakout state.
 * Cleared and re-seeded every Monday at 6:00 AM IST.
 */
@Component
public class UsWeeklyBreakoutStateStore {

    private static final Logger log = LoggerFactory.getLogger(UsWeeklyBreakoutStateStore.class);

    private final Map<String, UsWeeklyBreakoutState> stateMap = new ConcurrentHashMap<>();

    public void put(String ticker, UsWeeklyBreakoutState state) {
        stateMap.put(ticker, state);
    }

    public UsWeeklyBreakoutState get(String ticker) {
        return stateMap.get(ticker);
    }

    public Collection<UsWeeklyBreakoutState> all() {
        return stateMap.values();
    }

    public void clear() {
        stateMap.clear();
        log.info("[US-WEEKLY] State store cleared for new week");
    }

    public int size() {
        return stateMap.size();
    }
}