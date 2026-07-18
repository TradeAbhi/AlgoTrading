package com.trading.algo.ipo;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "ipos")
@Data
public class Ipo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String symbol;         // NSE symbol — from CSV or resolved from instrument master
    private String securityType;   // EQ / SME / BE / IV — from NSE CSV
    private String priceRange;     // e.g. "Rs.162 to Rs.171" — from NSE CSV

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
    private Boolean alert10DaySent = false;
    private Boolean alertOpenSent = false;
    private Boolean alertListingSent = false;

    @Column(name = "alert_listing_perf_sent")
    private Boolean alertListingPerfSent = false;  // EOD performance alert on listing day

    // ── Grey Market Premium (GMP) ────────────────────────────────────────────
    @Column(name = "gmp")
    private Double gmp;  // Grey Market Premium in percentage

    @Column(name = "gmp_updated_at")
    private LocalDateTime gmpUpdatedAt;  // When GMP was last fetched

    @Column(name = "alert_gmp_sent")
    private Boolean alertGmpSent = false;  // Whether GMP alert has been sent
}