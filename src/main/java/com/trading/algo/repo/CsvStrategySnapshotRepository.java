package com.trading.algo.repo;

import com.trading.algo.entity.CsvStrategySnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface CsvStrategySnapshotRepository extends JpaRepository<CsvStrategySnapshot, Long> {

    /** Find snapshot by scan date */
    Optional<CsvStrategySnapshot> findByScanDate(LocalDate scanDate);

    /** Find the most recent snapshot on or before a given date, ordered by date and save time */
    Optional<CsvStrategySnapshot> findFirstByScanDateLessThanEqualOrderByScanDateDescSavedAtDesc(LocalDate scanDate);

    /** Check if snapshot exists for a given date */
    boolean existsByScanDate(LocalDate scanDate);

    /** Delete snapshot by scan date (for upsert) */
    void deleteByScanDate(LocalDate scanDate);
}
