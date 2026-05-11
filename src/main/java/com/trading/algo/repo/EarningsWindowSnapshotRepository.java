package com.trading.algo.repo;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.trading.algo.entity.EarningsWindowSnapshot;

@Repository
public interface EarningsWindowSnapshotRepository extends JpaRepository<EarningsWindowSnapshot, Long> {

    /** Always returns the single latest snapshot row. */
    Optional<EarningsWindowSnapshot> findTopByOrderBySentAtDesc();
}