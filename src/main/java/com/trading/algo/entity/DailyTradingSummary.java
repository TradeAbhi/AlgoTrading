package com.trading.algo.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Entity
@Getter
@Setter
public class DailyTradingSummary {

    @Id
    @GeneratedValue
    private Long id;

    private LocalDate tradingDate;

    private int numberOfTrades;

    private double totalPnl;

    private String result; // WIN / LOSS
}