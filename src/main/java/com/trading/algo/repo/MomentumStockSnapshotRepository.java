package com.trading.algo.repo;

import com.trading.algo.entity.MomentumStockSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MomentumStockSnapshotRepository extends JpaRepository<MomentumStockSnapshot, Long> {

    List<MomentumStockSnapshot> findTopNByOrderBySnapshotTimeDesc(int n);
}
