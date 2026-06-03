package com.trading.algo.repo;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.trading.algo.entity.MoverCharacteristics;

@Repository
public interface MoverCharacteristicsRepository
        extends JpaRepository<MoverCharacteristics, Long> {

    List<MoverCharacteristics> findByTradeDateOrderByChangePercentDesc(LocalDate date);

    List<MoverCharacteristics> findByTradeDateAndCategoryOrderByChangePercentDesc(
            LocalDate date, String category);

    List<MoverCharacteristics> findBySymbolOrderByTradeDateDesc(String symbol);

    boolean existsBySymbolAndTradeDateAndCategory(
            String symbol, LocalDate date, String category);

    // ── Pattern aggregation queries ───────────────────────────────────────────

    @Query("SELECT AVG(m.volumeRatio) FROM MoverCharacteristics m " +
            "WHERE m.category = :cat AND m.tradeDate >= :since")
    Double avgVolumeRatio(@Param("cat") String category, @Param("since") LocalDate since);

    @Query("SELECT AVG(m.prevRsi) FROM MoverCharacteristics m " +
            "WHERE m.category = :cat AND m.tradeDate >= :since AND m.prevRsi IS NOT NULL")
    Double avgPrevRsi(@Param("cat") String category, @Param("since") LocalDate since);

    @Query("SELECT AVG(m.atrRatio) FROM MoverCharacteristics m " +
            "WHERE m.category = :cat AND m.tradeDate >= :since AND m.atrRatio IS NOT NULL")
    Double avgAtrRatio(@Param("cat") String category, @Param("since") LocalDate since);

    @Query("SELECT AVG(m.c1WickRatio) FROM MoverCharacteristics m " +
            "WHERE m.category = :cat AND m.tradeDate >= :since")
    Double avgC1WickRatio(@Param("cat") String category, @Param("since") LocalDate since);

    @Query("SELECT AVG(m.gapPercent) FROM MoverCharacteristics m " +
            "WHERE m.category = :cat AND m.tradeDate >= :since")
    Double avgGapPercent(@Param("cat") String category, @Param("since") LocalDate since);

    @Query("SELECT COUNT(m) FROM MoverCharacteristics m " +
            "WHERE m.category = :cat AND m.tradeDate >= :since")
    long countByCategorySince(@Param("cat") String category, @Param("since") LocalDate since);

    List<MoverCharacteristics> findByTradeDateBetweenAndCategory(
            LocalDate from, LocalDate to, String category);
}