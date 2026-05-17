package com.trading.algo.earning;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.trading.algo.dtos.EarningsWatchlistItemDTO;
import com.trading.algo.entity.Earnings;
import com.trading.algo.entity.EarningsWatchlist;
import com.trading.algo.entity.EarningsWatchlist.WatchPhase;
import com.trading.algo.repo.EarningsRepository;
import com.trading.algo.repo.EarningsWatchlistRepository;
import com.trading.algo.service.UniverseService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Manages the earnings-based watchlist.
 *
 * Logic:
 *  - Watch window : [resultDate - 10 days, resultDate + 3 days]
 *  - PRE_EARNINGS : today in [watchStart, resultDate - 1]
 *  - POST_EARNINGS: today in [resultDate, watchEnd]
 *  - EXPIRED      : today > watchEnd
 *
 * The nightly scheduler (00:05 IST) syncs new Earnings records into
 * EarningsWatchlist and advances the phase of existing rows.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EarningsWatchlistService {

    /** Days before the result to start watching. */
    private static final int PRE_DAYS  = 10;

    /** Days after the result to keep watching. */
    private static final int POST_DAYS = 3;

    private final EarningsRepository           earningsRepo;
    private final EarningsWatchlistRepository  watchlistRepo;
    private final UniverseService              universeService;

    /** Fast O(1) F&O eligibility check — same pattern as WatchlistService. */
    private static final Set<String> FNO_SYMBOL_SET =
            Set.copyOf(UniverseService.NIFTY_FNO_SYMBOLS);

    // =========================================================================
    // Scheduled sync — runs every day at 00:05 IST
    // =========================================================================

    /**
     * 1. Expire stale rows (watchEnd < today).
     * 2. Transition PRE → POST for result-day / post-result rows.
     * 3. Ingest new Earnings records whose window overlaps today.
     * 4. Refresh daysToResult for all active rows.
     */
    @Scheduled(cron = "0 5 0 * * MON-FRI", zone = "Asia/Kolkata")
    @Transactional
    public void syncWatchlist() {
        LocalDate today = LocalDate.now();
        log.info("EarningsWatchlistService.syncWatchlist() — date={}", today);

        // Step 1 — expire
        int expired = watchlistRepo.expireStaleRows(today);
        log.info("Expired {} stale watchlist rows", expired);

        // Step 2 — transition PRE → POST
        int transitioned = watchlistRepo.transitionToPost(today);
        log.info("Transitioned {} rows PRE → POST", transitioned);

        // Step 3 — ingest upcoming earnings that fall inside the window
        ingestUpcoming(today);

        // Step 4 — refresh daysToResult on all active rows
        refreshDaysToResult(today);
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /** All active (non-expired) watchlist items, newest result date first. */
    public List<EarningsWatchlistItemDTO> getActiveWatchlist() {
        return watchlistRepo.findByPhaseNot(WatchPhase.EXPIRED)
                .stream()
                .sorted((a, b) -> a.getResultDate().compareTo(b.getResultDate()))
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    /** Only PRE_EARNINGS items — stocks approaching their result date. */
    public List<EarningsWatchlistItemDTO> getPreEarnings() {
        return watchlistRepo.findByPhase(WatchPhase.PRE_EARNINGS)
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    /** Only POST_EARNINGS items — stocks in the post-result observation window. */
    public List<EarningsWatchlistItemDTO> getPostEarnings() {
        return watchlistRepo.findByPhase(WatchPhase.POST_EARNINGS)
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    /**
     * Manual trigger — useful for testing or if the nightly job is missed.
     * Safe to call at any time.
     */
    @Transactional
    public int manualSync() {
        LocalDate today = LocalDate.now();
        watchlistRepo.expireStaleRows(today);
        watchlistRepo.transitionToPost(today);
        int added = ingestUpcoming(today);
        refreshDaysToResult(today);
        log.info("manualSync: {} new entries added", added);
        return added;
    }

    // =========================================================================
    // Internals
    // =========================================================================

    /**
     * Pulls Earnings records whose result date falls in
     * [today - POST_DAYS, today + PRE_DAYS] and ingests them into
     * the watchlist — but ONLY for F&O eligible symbols.
     *
     * The earnings table stores every NSE stock; this filter ensures
     * the watchlist stays focused on tradeable F&O names only.
     */
    private int ingestUpcoming(LocalDate today) {
        LocalDate windowStart = today.minusDays(POST_DAYS);
        LocalDate windowEnd   = today.plusDays(PRE_DAYS);

        List<Earnings> upcoming = earningsRepo.findByResultDateBetween(windowStart, windowEnd);
        int added = 0;

        for (Earnings e : upcoming) {
            // ── F&O filter ────────────────────────────────────────────────
            // earnings table keeps ALL NSE stocks; watchlist is F&O only
            if (!FNO_SYMBOL_SET.contains(e.getSymbol())) {
                continue;
            }
            // ─────────────────────────────────────────────────────────────

            if (watchlistRepo.existsBySymbolAndResultDate(e.getSymbol(), e.getResultDate())) {
                continue; // already tracked
            }

            LocalDate watchStart = e.getResultDate().minusDays(PRE_DAYS);
            LocalDate watchEnd   = e.getResultDate().plusDays(POST_DAYS);

            WatchPhase phase = resolvePhase(today, e.getResultDate(), watchEnd);

            EarningsWatchlist row = EarningsWatchlist.builder()
                    .symbol(e.getSymbol())
                    .companyName(e.getCompanyName())
                    .resultDate(e.getResultDate())
                    .eventType(e.getEventType())
                    .watchStart(watchStart)
                    .watchEnd(watchEnd)
                    .phase(phase)
                    .daysToResult((int) ChronoUnit.DAYS.between(today, e.getResultDate()))
                    .addedAt(LocalDateTime.now())
                    .lastUpdatedAt(LocalDateTime.now())
                    .build();

            watchlistRepo.save(row);
            added++;
        }

        log.info("ingestUpcoming: added {} new EarningsWatchlist rows", added);
        return added;
    }

    /**
     * Refreshes the daysToResult counter on all non-expired rows so the
     * front-end always shows an accurate countdown.
     */
    private void refreshDaysToResult(LocalDate today) {
        List<EarningsWatchlist> active = watchlistRepo.findByPhaseNot(WatchPhase.EXPIRED);
        for (EarningsWatchlist row : active) {
            row.setDaysToResult((int) ChronoUnit.DAYS.between(today, row.getResultDate()));
            row.setLastUpdatedAt(LocalDateTime.now());
        }
        watchlistRepo.saveAll(active);
    }

    private WatchPhase resolvePhase(LocalDate today, LocalDate resultDate, LocalDate watchEnd) {
        if (today.isAfter(watchEnd))          return WatchPhase.EXPIRED;
        if (!today.isBefore(resultDate))      return WatchPhase.POST_EARNINGS; // today >= resultDate
        return WatchPhase.PRE_EARNINGS;
    }

    // =========================================================================
    // DTO mapping
    // =========================================================================

    private EarningsWatchlistItemDTO toDTO(EarningsWatchlist e) {
        return EarningsWatchlistItemDTO.builder()
                .id(e.getId())
                .symbol(e.getSymbol())
                .companyName(e.getCompanyName())
                .eventType(e.getEventType())
                .resultDate(e.getResultDate())
                .watchStart(e.getWatchStart())
                .watchEnd(e.getWatchEnd())
                .phase(e.getPhase())
                .daysToResult(e.getDaysToResult())
                .phaseLabel(buildPhaseLabel(e.getDaysToResult(), e.getPhase()))
                .addedAt(e.getAddedAt())
                .lastUpdatedAt(e.getLastUpdatedAt())
                .build();
    }

    /**
     * Produces a human-friendly label shown on the watchlist UI / Telegram alerts.
     * Examples:
     *   daysToResult=5  → "5 days before result"
     *   daysToResult=0  → "Result today"
     *   daysToResult=-2 → "2 days after result"
     */
    private String buildPhaseLabel(int daysToResult, WatchPhase phase) {
        if (phase == WatchPhase.EXPIRED) return "Expired";
        if (daysToResult > 0)  return daysToResult + " day" + (daysToResult == 1 ? "" : "s") + " before result";
        if (daysToResult == 0) return "Result today";
        int after = Math.abs(daysToResult);
        return after + " day" + (after == 1 ? "" : "s") + " after result";
    }
}