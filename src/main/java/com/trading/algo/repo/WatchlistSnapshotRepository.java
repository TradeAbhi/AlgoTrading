package com.trading.algo.repo;

import com.trading.algo.entity.WatchlistSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface WatchlistSnapshotRepository extends JpaRepository<WatchlistSnapshot, Long> {

    /** Find snapshot by trade date */
    Optional<WatchlistSnapshot> findByTradeDate(LocalDate tradeDate);

    /** Find all snapshots between two dates (inclusive) */
    List<WatchlistSnapshot> findByTradeDateBetweenOrderByTradeDateAsc(LocalDate fromDate, LocalDate toDate);

    /** Check if snapshot exists for a given date */
    boolean existsByTradeDate(LocalDate tradeDate);

    /** Delete snapshot by trade date (for upsert) */
    void deleteByTradeDate(LocalDate tradeDate);
}
