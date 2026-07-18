package com.trading.algo.repo;

import com.trading.algo.entity.IntradayWatchlistSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface IntradayWatchlistSnapshotRepository extends JpaRepository<IntradayWatchlistSnapshot, Long> {

    /** Find snapshots within a time range (most recent first) */
    List<IntradayWatchlistSnapshot> findBySnapshotTimeBetweenOrderBySnapshotTimeDesc(
            LocalDateTime startTime, LocalDateTime endTime);

    /** Find the N most recent snapshots */
    List<IntradayWatchlistSnapshot> findTopNByOrderBySnapshotTimeDesc(int limit);

    /** Delete snapshots older than a certain time (cleanup) */
    void deleteBySnapshotTimeBefore(LocalDateTime cutoffTime);
}
