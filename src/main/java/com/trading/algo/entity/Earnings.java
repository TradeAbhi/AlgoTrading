package com.trading.algo.entity;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(
    name = "earnings",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_earnings_symbol_date_event",
        columnNames = {"symbol", "result_date", "event_type"}
    )
)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Earnings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String symbol;
    private String companyName;

    @Column(name = "result_date")
    private LocalDate resultDate;

    @Column(name = "event_type")
    private String eventType; // "Quarterly Results", "Dividend", etc.

    private boolean alertSentWeek;  // 7 days before
    private boolean alertSentDay;   // 1 day before
    private boolean alertSentToday; // morning of event
}