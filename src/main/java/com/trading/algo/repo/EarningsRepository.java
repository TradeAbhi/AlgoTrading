package com.trading.algo.repo;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.trading.algo.entity.Earnings;

@Repository
public interface EarningsRepository extends JpaRepository<Earnings, Long> {
    List<Earnings> findByResultDateBetween(LocalDate start, LocalDate end);
    List<Earnings> findByResultDate(LocalDate date);
    boolean existsBySymbolAndResultDate(String symbol, LocalDate date);
}