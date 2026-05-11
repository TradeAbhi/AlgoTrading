package com.trading.algo.repo;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.trading.algo.entity.BacktestTrade;
import com.trading.algo.entity.BacktestTrade.Direction;
import com.trading.algo.entity.BacktestTrade.Outcome;

@Repository
public interface BacktestTradeRepository extends JpaRepository<BacktestTrade, Long> {

    List<BacktestTrade> findByTradeDateBetweenOrderByTradeDateAsc(LocalDate from, LocalDate to);

    List<BacktestTrade> findBySymbolOrderByTradeDateAsc(String symbol);

    List<BacktestTrade> findBySymbolAndTradeDateBetween(String symbol, LocalDate from, LocalDate to);

    List<BacktestTrade> findByOutcome(Outcome outcome);

    boolean existsBySymbolAndTradeDate(String symbol, LocalDate date);

    // ── Aggregates for summary ─────────────────────────────────────────────

    @Query("SELECT COUNT(t) FROM BacktestTrade t WHERE t.tradeDate BETWEEN :from AND :to AND t.outcome = 'TARGET_HIT'")
    long countWins(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT COUNT(t) FROM BacktestTrade t WHERE t.tradeDate BETWEEN :from AND :to AND t.outcome = 'SL_HIT'")
    long countLosses(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT COUNT(t) FROM BacktestTrade t WHERE t.tradeDate BETWEEN :from AND :to AND t.outcome = 'EOD_EXIT'")
    long countEodExits(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT SUM(t.pnlPoints) FROM BacktestTrade t WHERE t.tradeDate BETWEEN :from AND :to")
    Double totalPnl(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT t.symbol, COUNT(t), SUM(CASE WHEN t.outcome='TARGET_HIT' THEN 1 ELSE 0 END), SUM(t.pnlPoints) " +
           "FROM BacktestTrade t WHERE t.tradeDate BETWEEN :from AND :to " +
           "GROUP BY t.symbol ORDER BY SUM(t.pnlPoints) DESC")
    List<Object[]> symbolSummary(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
