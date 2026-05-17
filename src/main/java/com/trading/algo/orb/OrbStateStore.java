package com.trading.algo.orb;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.trading.algo.dtos.OrbSymbolState;

import lombok.extern.slf4j.Slf4j;

/**
 * In-memory store for ORB state across all Nifty 500 symbols.
 * Cleared once per day at market open (9:15 AM) by OrbScannerService.
 */
@Slf4j
@Component
public class OrbStateStore {

    private final Map<String, OrbSymbolState> stateMap = new ConcurrentHashMap<>();

    public void put(String symbol, OrbSymbolState state) {
        stateMap.put(symbol, state);
    }

    public OrbSymbolState get(String symbol) {
        return stateMap.get(symbol);
    }

    public boolean contains(String symbol) {
        return stateMap.containsKey(symbol);
    }

    public Collection<OrbSymbolState> all() {
        return stateMap.values();
    }

    public void clear() {
        stateMap.clear();
        log.info("[ORB] State store cleared for new trading day");
    }

    public int size() {
        return stateMap.size();
    }
}