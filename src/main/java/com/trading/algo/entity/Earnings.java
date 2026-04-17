package com.trading.algo.entity;

import java.time.LocalDate;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Earnings {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String symbol;
	private String companyName;
	private LocalDate resultDate;
	private String eventType; // "Quarterly Results", "Dividend", etc.

	private boolean alertSentWeek; // 7 days before
	private boolean alertSentDay; // 1 day before
	private boolean alertSentToday; // morning of event
}