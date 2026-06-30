package com.trading.algo.repo;

import com.trading.algo.entity.IpoBacktestTrade;
import com.trading.algo.entity.IpoBacktestTrade.Outcome;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface IpoBacktestTradeRepository extends JpaRepository<IpoBacktestTrade, Long> {

    List<IpoBacktestTrade> findBySymbolOrderByListingDateAsc(String symbol);

    List<IpoBacktestTrade> findBySymbolIn(List<String> symbols);

    List<IpoBacktestTrade> findByListingDateBetweenOrderByListingDateAsc(LocalDate from, LocalDate to);

    List<IpoBacktestTrade> findByOutcome(Outcome outcome);

    boolean existsBySymbolAndListingDate(String symbol, LocalDate listingDate);

    // ── Aggregates for summary ─────────────────────────────────────────────

    @Query("SELECT COUNT(t) FROM IpoBacktestTrade t WHERE t.outcome = 'TARGET1_HIT' OR t.outcome = 'TARGET2_HIT'")
    long countWins();

    @Query("SELECT COUNT(t) FROM IpoBacktestTrade t WHERE t.outcome = 'SL_HIT' OR t.outcome = 'SL_HIT_TRAILED'")
    long countLosses();

    @Query("SELECT COUNT(t) FROM IpoBacktestTrade t WHERE t.outcome = 'EOD_EXIT'")
    long countEodExits();

    @Query("SELECT COUNT(t) FROM IpoBacktestTrade t WHERE t.outcome = 'NO_BREAKOUT'")
    long countNoBreakouts();

    @Query("SELECT SUM(t.pnlPoints) FROM IpoBacktestTrade t")
    Double totalPnl();

    @Query("SELECT t.symbol, COUNT(t), SUM(CASE WHEN t.outcome='TARGET1_HIT' OR t.outcome='TARGET2_HIT' THEN 1 ELSE 0 END), SUM(t.pnlPoints) " +
           "FROM IpoBacktestTrade t GROUP BY t.symbol ORDER BY SUM(t.pnlPoints) DESC")
    List<Object[]> symbolSummary();
}
