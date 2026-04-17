package com.trading.algo.entity;

import java.time.LocalDate;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;

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