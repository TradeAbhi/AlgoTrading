package com.trading.algo.repo;

import com.trading.algo.entity.Earnings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface EarningsRepository extends JpaRepository<Earnings, Long> {
    List<Earnings> findByResultDateBetween(LocalDate start, LocalDate end);
    List<Earnings> findByResultDate(LocalDate date);
    boolean existsBySymbolAndResultDate(String symbol, LocalDate date);
    
    // Find stocks in earnings window (pre-10 to post-3 days around earnings date)
    @Query("SELECT DISTINCT e.symbol FROM Earnings e WHERE e.resultDate BETWEEN :startDate AND :endDate")
    List<String> findSymbolsInEarningsWindow(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);
}