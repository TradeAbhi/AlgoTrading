package com.trading.algo.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.trading.algo.dtos.EarningsWatchlistDiffDTO;
import com.trading.algo.entity.EarningsWindowSnapshot;
import com.trading.algo.entity.EarningsWatchlist;
import com.trading.algo.entity.EarningsWatchlist.WatchPhase;
import com.trading.algo.repo.EarningsWatchlistRepository;
import com.trading.algo.repo.EarningsWindowSnapshotRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class EarningsWatchlistDiffService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd-MMM");

    private final EarningsWatchlistRepository      watchlistRepo;
    private final EarningsWindowSnapshotRepository snapshotRepo;
    private final TelegramService                  telegramService;

    // =========================================================================
    // Save snapshot — called every time /api/earnings-alerts/window is hit
    // =========================================================================

    @Transactional
    public void saveSnapshot(List<String> symbols) {
        snapshotRepo.deleteAll();
        snapshotRepo.save(EarningsWindowSnapshot.builder()
                .symbols(String.join(",", symbols))
                .sentAt(LocalDateTime.now())
                .build());
        log.info("Snapshot saved: {} symbols", symbols.size());
    }

    // =========================================================================
    // Diff — compare current window vs last snapshot
    // =========================================================================

    @Transactional(readOnly = true)
    public EarningsWatchlistDiffDTO diff(boolean sendTelegram) {
        LocalDate today = LocalDate.now();

        // All active rows (PRE + POST, not expired) — keyed by symbol for fast lookup
        Map<String, EarningsWatchlist> currentMap = watchlistRepo
                .findByPhaseNot(WatchPhase.EXPIRED)
                .stream()
                .collect(Collectors.toMap(
                        EarningsWatchlist::getSymbol,
                        e -> e,
                        (a, b) -> a,
                        LinkedHashMap::new
                ));

        // Last snapshot
        EarningsWindowSnapshot snapshot = snapshotRepo.findTopByOrderBySentAtDesc().orElse(null);

        if (snapshot == null || snapshot.getSymbols() == null || snapshot.getSymbols().isBlank()) {
            log.warn("No snapshot found — hit /api/earnings-alerts/window first.");
            return EarningsWatchlistDiffDTO.builder()
                    .toAdd(List.copyOf(currentMap.keySet()))
                    .toRemove(Collections.emptyList())
                    .unchanged(Collections.emptyList())
                    .lastSentAt(null)
                    .currentTotal(currentMap.size())
                    .telegramSent(false)
                    .build();
        }

        Set<String> lastSymbols = Arrays.stream(snapshot.getSymbols().split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toSet());

        // toAdd — in current window but NOT in last snapshot
        List<EarningsWatchlist> toAddRows = currentMap.values().stream()
                .filter(e -> !lastSymbols.contains(e.getSymbol()))
                .sorted((a, b) -> a.getResultDate().compareTo(b.getResultDate()))
                .collect(Collectors.toList());

        // toRemove — in last snapshot but NOT in current window (exited / expired)
        List<String> toRemove = lastSymbols.stream()
                .filter(s -> !currentMap.containsKey(s))
                .sorted()
                .collect(Collectors.toList());

        // unchanged — in both
        List<String> unchanged = currentMap.keySet().stream()
                .filter(lastSymbols::contains)
                .sorted()
                .collect(Collectors.toList());

        log.info("Diff — toAdd: {}, toRemove: {}, unchanged: {}",
                toAddRows.size(), toRemove.size(), unchanged.size());

        boolean telegramSent = false;
        if (sendTelegram && (!toAddRows.isEmpty() || !toRemove.isEmpty())) {
            sendDiffAlert(toAddRows, toRemove, unchanged, snapshot.getSentAt(), today, currentMap);
            telegramSent = true;
        }

        return EarningsWatchlistDiffDTO.builder()
                .toAdd(toAddRows.stream().map(EarningsWatchlist::getSymbol).collect(Collectors.toList()))
                .toRemove(toRemove)
                .unchanged(unchanged)
                .lastSentAt(snapshot.getSentAt())
                .currentTotal(currentMap.size())
                .telegramSent(telegramSent)
                .build();
    }

    // =========================================================================
    // Telegram diff message — with result dates + pre/post split
    // =========================================================================

    private void sendDiffAlert(
            List<EarningsWatchlist> toAddRows,
            List<String> toRemove,
            List<String> unchanged,
            LocalDateTime lastSentAt,
            LocalDate today,
            Map<String, EarningsWatchlist> currentMap) {

        StringBuilder sb = new StringBuilder();
        sb.append("🔄 *Earnings Watchlist — Changes*\n");
        sb.append("_(since: ").append(lastSentAt.format(DateTimeFormatter.ofPattern("dd-MMM HH:mm"))).append(")_\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━\n\n");

        // ── STOCKS TO ADD — split into pre and post ──────────────────────────
        if (!toAddRows.isEmpty()) {
            sb.append("✅ *Add to watchlist* (").append(toAddRows.size()).append(" new)\n\n");

            List<EarningsWatchlist> newPre = toAddRows.stream()
                    .filter(e -> e.getResultDate().isAfter(today))
                    .collect(Collectors.toList());

            List<EarningsWatchlist> newPost = toAddRows.stream()
                    .filter(e -> !e.getResultDate().isAfter(today))
                    .collect(Collectors.toList());

            if (!newPre.isEmpty()) {
                sb.append("🕐 *Pre-Earnings*\n");
                newPre.forEach(e -> {
                    long days = java.time.temporal.ChronoUnit.DAYS.between(today, e.getResultDate());
                    sb.append("  + `").append(String.format("%-12s", e.getSymbol())).append("`")
                      .append(" 📆 ").append(e.getResultDate().format(DATE_FMT))
                      .append("  ⏳ ").append(days).append("d away\n");
                });
                sb.append("\n");
            }

            if (!newPost.isEmpty()) {
                sb.append("✅ *Post-Earnings*\n");
                newPost.forEach(e -> {
                    long daysAgo = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(today, e.getResultDate()));
                    String label = daysAgo == 0 ? "today" : daysAgo + "d ago";
                    sb.append("  + `").append(String.format("%-12s", e.getSymbol())).append("`")
                      .append(" 📆 ").append(e.getResultDate().format(DATE_FMT))
                      .append("  ✅ ").append(label).append("\n");
                });
                sb.append("\n");
            }
        }

        // ── STOCKS TO REMOVE ─────────────────────────────────────────────────
        if (!toRemove.isEmpty()) {
            sb.append("❌ *Remove from watchlist* (").append(toRemove.size()).append(" exited window)\n");
            toRemove.forEach(s -> sb.append("  - `").append(s).append("`\n"));
            sb.append("\n");
        }

        // ── FOOTER ───────────────────────────────────────────────────────────
        sb.append("━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("Unchanged: ").append(unchanged.size())
          .append(" | Total now: ").append(toAddRows.size() + unchanged.size());

        telegramService.sendMessage(sb.toString());
        log.info("Diff alert sent to Telegram");
    }
}