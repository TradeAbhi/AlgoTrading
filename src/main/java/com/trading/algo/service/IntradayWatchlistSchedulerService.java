package com.trading.algo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.algo.entity.IntradayWatchlistSnapshot;
import com.trading.algo.momentum.WatchlistService;
import com.trading.algo.repo.IntradayWatchlistSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class IntradayWatchlistSchedulerService {

    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);

    private final WatchlistService watchlistService;
    private final IntradayWatchlistSnapshotRepository snapshotRepository;
    private final ObjectMapper objectMapper;
    private final MomentumStockSnapshotService momentumStockSnapshotService;

    /**
     * Runs every 15 minutes during market hours to save intraday snapshots.
     * Cron: 0 31/15 9-15 * * MON-FRI (9:31, 9:46, 10:01, ..., 15:01, 15:16)
     * Starts at 9:31 to allow first 15-min candle (9:15-9:30) to complete.
     * Ends at 15:16 to capture data before market close at 15:30.
     */
    @Scheduled(cron = "0 31/15 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    @Transactional
    public void saveIntradaySnapshot() {
        LocalTime now = LocalTime.now();
        
        // Only run during market hours
        if (now.isBefore(MARKET_OPEN) || now.isAfter(MARKET_CLOSE)) {
            log.debug("Skipping intraday snapshot - outside market hours");
            return;
        }

        try {
            log.info("Saving intraday watchlist snapshot at {}", now);
            
            // Get current watchlist
            var watchlist = watchlistService.getLiveWatchlist();
            
            // Serialize to JSON
            String jsonData = objectMapper.writeValueAsString(watchlist);
            
            // Save snapshot
            IntradayWatchlistSnapshot snapshot = IntradayWatchlistSnapshot.builder()
                .snapshotTime(LocalDateTime.now())
                .watchlistData(jsonData)
                .build();
            
            snapshotRepository.save(snapshot);
            // Persist the exact momentum universe used by intraday strategies.
            // This is the union of the configured momentum watchlist categories.
            int capturedStocks = momentumStockSnapshotService.captureMomentumStocks();
            if (capturedStocks > 0) {
                momentumStockSnapshotService.sendLatestSnapshotAlert();
            }
            log.info("Intraday snapshot saved successfully");
            
        } catch (Exception e) {
            log.error("Failed to save intraday snapshot", e);
        }
    }

    /**
     * Cleanup old snapshots (older than 1 day) to prevent database bloat.
     * Runs daily at 6:00 PM after market close.
     */
    @Scheduled(cron = "0 0 18 * * MON-FRI", zone = "Asia/Kolkata")
    @Transactional
    public void cleanupOldSnapshots() {
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusDays(1);
            snapshotRepository.deleteBySnapshotTimeBefore(cutoff);
            log.info("Cleaned up intraday snapshots older than 1 day");
        } catch (Exception e) {
            log.error("Failed to cleanup old snapshots", e);
        }
    }
}
