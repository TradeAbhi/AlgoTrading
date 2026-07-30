package com.trading.algo.repo;

import com.trading.algo.entity.LiveStrategySignal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface LiveStrategySignalRepository extends JpaRepository<LiveStrategySignal, Long> {

    /**
     * Find all signals for a specific date
     */
    List<LiveStrategySignal> findBySignalDateOrderBySignalTimeAsc(LocalDate signalDate);

    /**
     * Find signals for a specific date and strategy type
     */
    List<LiveStrategySignal> findBySignalDateAndStrategyTypeOrderBySignalTimeAsc(
            LocalDate signalDate, 
            LiveStrategySignal.StrategyType strategyType
    );

    /**
     * Find signals for a specific date, strategy type, and direction
     */
    List<LiveStrategySignal> findBySignalDateAndStrategyTypeAndDirectionOrderBySignalTimeAsc(
            LocalDate signalDate,
            LiveStrategySignal.StrategyType strategyType,
            LiveStrategySignal.Direction direction
    );

    /**
     * Find distinct symbols that had signals on a given date
     */
    @Query("SELECT DISTINCT s.symbol FROM LiveStrategySignal s WHERE s.signalDate = :date")
    List<String> findDistinctSymbolsByDate(@Param("date") LocalDate date);

    /**
     * Find signals for a specific date and symbol
     */
    List<LiveStrategySignal> findBySignalDateAndSymbolOrderBySignalTimeAsc(
            LocalDate signalDate,
            String symbol
    );

    /**
     * Count signals by strategy type for a specific date
     */
    @Query("SELECT s.strategyType, COUNT(s) FROM LiveStrategySignal s WHERE s.signalDate = :date GROUP BY s.strategyType")
    List<Object[]> countByStrategyType(@Param("date") LocalDate date);
}
