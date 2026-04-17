package com.trading.algo.entity;

import java.time.LocalDate;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Table(name = "ipos")
@Data
public class Ipo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String symbol;

    private LocalDate openDate;
    private LocalDate closeDate;
    private LocalDate listingDate;

    private String status; // UPCOMING / LISTED

    private boolean alert10DaySent;
    private boolean alertOpenSent;
    private boolean alertListingSent;
}