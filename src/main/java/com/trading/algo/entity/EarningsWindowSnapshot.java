package com.trading.algo.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Stores a snapshot of the symbols sent in the last
 * /api/earnings-alerts/window Telegram message.
 *
 * Only ONE row is ever kept (id=1, upserted on every send).
 * symbols column is a comma-separated list e.g. "HDFCBANK,RELIANCE,TCS"
 */
@Entity
@Table(name = "earnings_window_snapshot")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EarningsWindowSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Comma-separated symbols that were included in the last window alert.
     * e.g. "HDFCBANK,RELIANCE,TCS,INFY"
     */
    @Column(name = "symbols", columnDefinition = "TEXT")
    private String symbols;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;
}