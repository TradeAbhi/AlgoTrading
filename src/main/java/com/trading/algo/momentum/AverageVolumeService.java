package com.trading.algo.momentum;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * Maintains a simple in-memory store of 20-day average daily volumes.
 *
 * In a production setup this would be seeded from a DB / Redis on startup.
 * The values here are approximate NSE averages for Nifty 500 universe.
 *
 * You can refresh this data nightly via a @Scheduled job that reads
 * historical OHLCV from Upstox Historical API.
 */
@Slf4j
@Service
public class AverageVolumeService {

    /**
     * symbol -> 20-day average daily volume
     * Seed from your DB / historical data on startup.
     */
    private final Map<String, Long> avgVolumeMap = new ConcurrentHashMap<>();

    /**
     * Returns the average volume for a symbol.
     * Falls back to a conservative default if not found.
     */
    public long getAverageVolume(String symbol) {
        return avgVolumeMap.getOrDefault(symbol.toUpperCase(), 500_000L);
    }

    /**
     * Bulk update average volumes (call from nightly scheduler).
     */
    public void updateAverageVolumes(Map<String, Long> updates) {
        avgVolumeMap.putAll(updates);
        log.info("Updated average volumes for {} symbols", updates.size());
    }

    /**
     * Compute the volume ratio: today's volume vs avg volume.
     * ratio >= 3 => volume shocker
     */
    public double computeVolumeRatio(String symbol, long currentVolume) {
        long avg = getAverageVolume(symbol);
        if (avg == 0) return 0.0;
        return (double) currentVolume / avg;
    }
}