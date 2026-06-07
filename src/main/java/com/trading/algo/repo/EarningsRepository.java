package com.trading.algo.repo;

import com.trading.algo.entity.Earnings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface EarningsRepository extends JpaRepository<Earnings, Long> {
    List<Earnings> findByResultDateBetween(LocalDate start, LocalDate end);
    List<Earnings> findByResultDate(LocalDate date);
    boolean existsBySymbolAndResultDate(String symbol, LocalDate date);
}