package com.trading.algo.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "momentum_stock_snapshots")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MomentumStockSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "snapshot_time", nullable = false)
    private LocalDateTime snapshotTime;

    @ElementCollection
    @CollectionTable(name = "momentum_snapshot_symbols", joinColumns = @JoinColumn(name = "snapshot_id"))
    @Column(name = "symbol")
    private List<String> symbols;

    @ElementCollection
    @CollectionTable(name = "momentum_snapshot_categories", joinColumns = @JoinColumn(name = "snapshot_id"))
    @Builder.Default
    private List<SymbolCategory> symbolCategories = new ArrayList<>();

    @Column(name = "total_stocks")
    private int totalStocks;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
}
