package com.trading.algo.repo;

import com.trading.algo.entity.EarningsWatchlist;
import com.trading.algo.entity.EarningsWatchlist.WatchPhase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface EarningsWatchlistRepository extends JpaRepository<EarningsWatchlist, Long> {

    // -----------------------------------------------------------------------
    // Lookups
    // -----------------------------------------------------------------------

    /** All active (non-expired) entries — used for the live watchlist view. */
    List<EarningsWatchlist> findByPhaseNot(WatchPhase phase);

    /** All entries for a specific phase. */
    List<EarningsWatchlist> findByPhase(WatchPhase phase);

    /** Guard against duplicate inserts. */
    boolean existsBySymbolAndResultDate(String symbol, LocalDate resultDate);

    /** Find a specific entry to update its phase / daysToResult. */
    Optional<EarningsWatchlist> findBySymbolAndResultDate(String symbol, LocalDate resultDate);

    // -----------------------------------------------------------------------
    // Window query — stocks whose watch window overlaps today
    // -----------------------------------------------------------------------

    /**
     * Returns all entries where today falls within [watchStart, watchEnd].
     * Used by the nightly sync to decide what should be on the watchlist.
     */
    @Query("SELECT e FROM EarningsWatchlist e WHERE :today BETWEEN e.watchStart AND e.watchEnd")
    List<EarningsWatchlist> findActiveOnDate(@Param("today") LocalDate today);

    // -----------------------------------------------------------------------
    // Bulk phase update — called by the scheduler every morning
    // -----------------------------------------------------------------------

    /**
     * Marks rows as EXPIRED when today has passed their watchEnd.
     */
    @Modifying
    @Transactional
    @Query("UPDATE EarningsWatchlist e SET e.phase = 'EXPIRED' WHERE e.watchEnd < :today AND e.phase <> 'EXPIRED'")
    int expireStaleRows(@Param("today") LocalDate today);

    /**
     * Transitions PRE_EARNINGS rows to POST_EARNINGS once the result date arrives.
     */
    @Modifying
    @Transactional
    @Query("UPDATE EarningsWatchlist e SET e.phase = 'POST_EARNINGS' " +
           "WHERE e.resultDate <= :today AND e.watchEnd >= :today AND e.phase = 'PRE_EARNINGS'")
    int transitionToPost(@Param("today") LocalDate today);
}