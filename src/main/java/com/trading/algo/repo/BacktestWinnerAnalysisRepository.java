package com.trading.algo.repo;

import com.trading.algo.entity.BacktestWinnerAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface BacktestWinnerAnalysisRepository extends JpaRepository<BacktestWinnerAnalysis, Long> {

    List<BacktestWinnerAnalysis> findByTradeDateBetweenOrderByTradeDateDesc(LocalDate from, LocalDate to);

    List<BacktestWinnerAnalysis> findBySymbolOrderByTradeDateDesc(String symbol);

    List<BacktestWinnerAnalysis> findByStrategyNameAndTradeDateBetweenOrderByTradeDateDesc(
            String strategyName, LocalDate from, LocalDate to);

    boolean existsBySymbolAndTradeDateAndStrategyName(String symbol, LocalDate date, String strategyName);

    // ── Aggregates for pattern analysis ─────────────────────────────────────

    @Query("SELECT AVG(b.prevRsi) FROM BacktestWinnerAnalysis b WHERE b.tradeDate BETWEEN :from AND :to AND b.prevRsi IS NOT NULL")
    Double avgPrevRsi(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT AVG(b.atrRatio) FROM BacktestWinnerAnalysis b WHERE b.tradeDate BETWEEN :from AND :to AND b.atrRatio IS NOT NULL")
    Double avgAtrRatio(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT AVG(b.volumeRatio) FROM BacktestWinnerAnalysis b WHERE b.tradeDate BETWEEN :from AND :to AND b.volumeRatio IS NOT NULL")
    Double avgVolumeRatio(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT AVG(b.gapPercent) FROM BacktestWinnerAnalysis b WHERE b.tradeDate BETWEEN :from AND :to AND b.gapPercent IS NOT NULL")
    Double avgGapPercent(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT COUNT(b) FROM BacktestWinnerAnalysis b WHERE b.tradeDate BETWEEN :from AND :to AND b.strongOpening = true")
    long countStrongOpening(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT COUNT(b) FROM BacktestWinnerAnalysis b WHERE b.tradeDate BETWEEN :from AND :to AND b.breakout = true")
    long countBreakout(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT COUNT(b) FROM BacktestWinnerAnalysis b WHERE b.tradeDate BETWEEN :from AND :to AND b.highVolBreakout = true")
    long countHighVolBreakout(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT COUNT(b) FROM BacktestWinnerAnalysis b WHERE b.tradeDate BETWEEN :from AND :to")
    long countByDateRange(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT b.symbol, COUNT(b), AVG(b.pnlPercent) FROM BacktestWinnerAnalysis b " +
           "WHERE b.tradeDate BETWEEN :from AND :to " +
           "GROUP BY b.symbol ORDER BY COUNT(b) DESC")
    List<Object[]> symbolWinCount(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
