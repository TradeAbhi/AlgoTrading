package com.trading.algo.repo;

import com.trading.algo.entity.DailyTradingSummary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface DailyTradingSummaryRepository 
extends JpaRepository<DailyTradingSummary, Long> {

List<DailyTradingSummary> findByTradingDateBetween(
LocalDate start, LocalDate end
);
}