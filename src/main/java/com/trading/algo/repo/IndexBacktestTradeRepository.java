package com.trading.algo.repo;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.trading.algo.entity.BacktestTrade.Outcome;
import com.trading.algo.entity.IndexBacktestTrade;
import com.trading.algo.entity.IndexBacktestTrade.IndexName;

@Repository
public interface IndexBacktestTradeRepository extends JpaRepository<IndexBacktestTrade, Long> {

    List<IndexBacktestTrade> findByIndexNameOrderByTradeDateAsc(IndexName indexName);

    List<IndexBacktestTrade> findByIndexNameAndTradeDateBetweenOrderByTradeDateAsc(
            IndexName indexName, LocalDate from, LocalDate to);

    List<IndexBacktestTrade> findByTradeDateBetweenOrderByTradeDateAsc(LocalDate from, LocalDate to);

    boolean existsByIndexNameAndTradeDate(IndexName indexName, LocalDate date);

    @Query("SELECT t FROM IndexBacktestTrade t WHERE t.indexName = :name AND t.outcome = :outcome")
    List<IndexBacktestTrade> findByIndexNameAndOutcome(
            @Param("name") IndexName name, @Param("outcome") Outcome outcome);

    // ── Summary aggregates ────────────────────────────────────────────────

    @Query("SELECT COUNT(t) FROM IndexBacktestTrade t " +
           "WHERE t.indexName = :name AND t.tradeDate BETWEEN :from AND :to AND t.outcome = 'TARGET_HIT'")
    long countWins(@Param("name") IndexName name, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT COUNT(t) FROM IndexBacktestTrade t " +
           "WHERE t.indexName = :name AND t.tradeDate BETWEEN :from AND :to AND t.outcome = 'SL_HIT'")
    long countLosses(@Param("name") IndexName name, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT COUNT(t) FROM IndexBacktestTrade t " +
           "WHERE t.indexName = :name AND t.tradeDate BETWEEN :from AND :to AND t.outcome = 'EOD_EXIT'")
    long countEodExits(@Param("name") IndexName name, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT SUM(t.pnlPoints) FROM IndexBacktestTrade t " +
           "WHERE t.indexName = :name AND t.tradeDate BETWEEN :from AND :to")
    Double totalPnl(@Param("name") IndexName name, @Param("from") LocalDate from, @Param("to") LocalDate to);
}