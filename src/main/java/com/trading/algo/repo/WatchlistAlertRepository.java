package com.trading.algo.repo;

import com.trading.algo.entity.WatchlistAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface WatchlistAlertRepository extends JpaRepository<WatchlistAlert, Long> {

    /**
     * Find the most recent alert that hasn't been processed by ORB
     */
    Optional<WatchlistAlert> findFirstByOrbProcessedFalseOrderByAlertTimeDesc();

    /**
     * Find the most recent alert that hasn't been processed by Fibonacci
     */
    Optional<WatchlistAlert> findFirstByFiboProcessedFalseOrderByAlertTimeDesc();

    /**
     * Find all alerts after a given time
     */
    List<WatchlistAlert> findByAlertTimeAfterOrderByAlertTimeDesc(LocalDateTime alertTime);

    /**
     * Find the most recent alert overall
     */
    Optional<WatchlistAlert> findFirstByOrderByAlertTimeDesc();

    /**
     * Delete alerts older than specified days (cleanup)
     */
    void deleteByAlertTimeBefore(LocalDateTime alertTime);
}
