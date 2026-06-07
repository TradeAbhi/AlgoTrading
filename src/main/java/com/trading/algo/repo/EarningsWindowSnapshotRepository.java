package com.trading.algo.repo;

import com.trading.algo.entity.EarningsWindowSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface EarningsWindowSnapshotRepository extends JpaRepository<EarningsWindowSnapshot, Long> {

    /** Always returns the single latest snapshot row. */
    Optional<EarningsWindowSnapshot> findTopByOrderBySentAtDesc();
}