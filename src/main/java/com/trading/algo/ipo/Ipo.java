package com.trading.algo.ipo;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
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
    private String symbol;         // NSE symbol — populated after listing

    private LocalDate openDate;
    private LocalDate closeDate;
    private LocalDate listingDate;

    private String status;         // UPCOMING / OPEN / CLOSED / LISTED

    // ── IPO price details ────────────────────────────────────────────────────
    @Column(name = "issue_price")
    private Double issuePrice;     // IPO offer price

    @Column(name = "lot_size")
    private Integer lotSize;

    @Column(name = "issue_size")
    private String issueSize;      // e.g. "₹1,200 Cr"

    // ── Listing performance (populated on listing day via live quote) ────────
    @Column(name = "listing_price")
    private Double listingPrice;   // actual listing open price

    @Column(name = "listing_gain_pct")
    private Double listingGainPct; // ((listingPrice - issuePrice) / issuePrice) * 100

    @Column(name = "listing_high")
    private Double listingHigh;    // day high on listing day

    @Column(name = "listing_low")
    private Double listingLow;     // day low on listing day

    @Column(name = "listing_close")
    private Double listingClose;   // close price on listing day

    @Column(name = "listing_monitored_at")
    private LocalDateTime listingMonitoredAt;

    // ── Alert flags ──────────────────────────────────────────────────────────
    private boolean alert10DaySent;
    private boolean alertOpenSent;
    private boolean alertListingSent;

    @Column(name = "alert_listing_perf_sent")
    private boolean alertListingPerfSent;  // EOD performance alert on listing day
}