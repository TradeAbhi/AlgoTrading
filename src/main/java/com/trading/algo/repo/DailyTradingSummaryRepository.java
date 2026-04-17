package com.trading.algo.repo;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.trading.algo.entity.DailyTradingSummary;

public interface DailyTradingSummaryRepository 
extends JpaRepository<DailyTradingSummary, Long> {

List<DailyTradingSummary> findByTradingDateBetween(
LocalDate start, LocalDate end
);
}