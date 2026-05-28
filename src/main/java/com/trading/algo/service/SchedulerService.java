package com.trading.algo.service;


import java.time.LocalDate;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.trading.algo.earning.EarningsService;
import com.trading.algo.earning.EarningsWatchlistDiffService;
import com.trading.algo.entity.Earnings;
import com.trading.algo.entity.EarningsWatchlist;
import com.trading.algo.entity.EarningsWatchlist.WatchPhase;
import com.trading.algo.fibostrategy.LiveStrategyAlertService;
import com.trading.algo.ipo.IpoRepository;
import com.trading.algo.repo.EarningsRepository;
import com.trading.algo.repo.EarningsWatchlistRepository;
import com.trading.algo.telegram.TelegramService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class SchedulerService {

    private final EarningsService            earningsService;
    private final EarningsRepository         earningsRepo;
    private final EarningsWatchlistRepository earningsWatchlistRepo;   // ← NEW
    private final IpoRepository              ipoRepo;
    private final TelegramService            telegramService;
    private final IpoService                 ipoService;
    private final EarningsWatchlistDiffService diffService;  // ← NEW
    private final LiveStrategyAlertService     liveStrategyAlertService;
    private final NseWeekHighService           nseWeekHighService;
    private final MarketSentimentService       marketSentimentService;
    private final IpoMonitorService            ipoMonitorService;

    // =========================================================================
    // EARNINGS — fetch & save (DO NOT TOUCH)
    // Fetches from NSE and saves into the earnings table — no changes here.
    // =========================================================================

    @Scheduled(cron = "0 0 7 * * ?")
    public void fetchData() {
        earningsService.fetchAndStoreData();
    }

    // =========================================================================
    // EARNINGS — Telegram alerts
    //
    // All alerts now read from earnings_watchlist (F&O only, pre-10/post-3 window).
    // The earnings table and its DB-saving logic are NOT touched here.
    // =========================================================================

    /**
     * Daily 9AM — alert for F&O stocks whose result is exactly 7 days away.
     * Only stocks already in the earnings_watchlist (F&O + within window).
     */
    @Scheduled(cron = "0 0 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void weekAheadAlerts() {
        LocalDate target = LocalDate.now().plusDays(7);

        List<EarningsWatchlist> list = earningsWatchlistRepo
                .findByPhase(WatchPhase.PRE_EARNINGS)
                .stream()
                .filter(e -> e.getResultDate().equals(target))
                .toList();

        if (list.isEmpty()) return;

        StringBuilder sb = new StringBuilder();
        sb.append("📅 *7-Day Earnings Heads Up* (F&O Only)\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━\n");
        for (EarningsWatchlist e : list) {
            sb.append("🏢 ").append(e.getSymbol())
              .append(" — ").append(e.getEventType())
              .append("\n📆 ").append(e.getResultDate())
              .append("\n━━━━━━━━━━━━━━━━━━━━\n");
        }
        sb.append("Total: ").append(list.size()).append(" stocks");

        telegramService.sendMessage(sb.toString());
    }

    /**
     * Daily 9AM — alert for F&O stocks whose result is tomorrow.
     */
    @Scheduled(cron = "0 5 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void dayAheadAlerts() {
        LocalDate target = LocalDate.now().plusDays(1);

        List<EarningsWatchlist> list = earningsWatchlistRepo
                .findByPhase(WatchPhase.PRE_EARNINGS)
                .stream()
                .filter(e -> e.getResultDate().equals(target))
                .toList();

        if (list.isEmpty()) return;

        StringBuilder sb = new StringBuilder();
        sb.append("⏰ *Result Tomorrow* (F&O Only)\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━\n");
        for (EarningsWatchlist e : list) {
            sb.append("🏢 ").append(e.getSymbol())
              .append(" — ").append(e.getEventType())
              .append("\n━━━━━━━━━━━━━━━━━━━━\n");
        }
        sb.append("Total: ").append(list.size()).append(" stocks");

        telegramService.sendMessage(sb.toString());
    }

    /**
     * Daily 9AM — alert for F&O stocks whose result is today.
     */
    @Scheduled(cron = "0 10 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void todayAlerts() {
        LocalDate today = LocalDate.now();

        List<EarningsWatchlist> list = earningsWatchlistRepo
                .findByPhase(WatchPhase.POST_EARNINGS)
                .stream()
                .filter(e -> e.getResultDate().equals(today))
                .toList();

        if (list.isEmpty()) return;

        StringBuilder sb = new StringBuilder();
        sb.append("🚨 *Result Today* (F&O Only)\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━\n");
        for (EarningsWatchlist e : list) {
            sb.append("🏢 ").append(e.getSymbol())
              .append(" — ").append(e.getEventType())
              .append("\n━━━━━━━━━━━━━━━━━━━━\n");
        }
        sb.append("Total: ").append(list.size()).append(" stocks");

        telegramService.sendMessage(sb.toString());
    }

    /**
     * Every Monday 8AM — weekly summary of F&O stocks with results in the next 7 days.
     * Reads from earnings_watchlist so only F&O stocks within the pre-10 window are shown.
     */
    @Scheduled(cron = "0 0 8 * * MON", zone = "Asia/Kolkata")
    public void weeklySummaryAlert() {
        LocalDate today    = LocalDate.now();
        LocalDate nextWeek = today.plusDays(7);

        List<EarningsWatchlist> list = earningsWatchlistRepo
                .findByPhase(WatchPhase.PRE_EARNINGS)
                .stream()
                .filter(e -> !e.getResultDate().isBefore(today)
                          && !e.getResultDate().isAfter(nextWeek))
                .sorted((a, b) -> a.getResultDate().compareTo(b.getResultDate()))
                .toList();

        if (list.isEmpty()) {
            telegramService.sendMessage(
                "📋 No F&O earnings results scheduled in the next 7 days."
            );
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("📋 *F&O Results This Week*\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━\n");
        for (EarningsWatchlist e : list) {
            sb.append("📅 ").append(e.getResultDate()).append("\n")
              .append("🏢 ").append(e.getSymbol())
              .append(" — ").append(e.getEventType()).append("\n")
              .append("━━━━━━━━━━━━━━━━━━━━\n");
        }
        sb.append("Total: ").append(list.size()).append(" F&O stocks");

        telegramService.sendMessage(sb.toString());
    }


    // =========================================================================
    // EARNINGS — Full pre-10 / post-3 window alert (manual trigger)
    // =========================================================================

    public void sendFullEarningsWindowAlert() {
        LocalDate today = LocalDate.now();

        // Fetch ALL active rows (PRE + POST) and split by resultDate vs today.
        // This avoids relying on the phase column which is only updated by the
        // nightly scheduler — so stocks like HDFCBANK whose result was yesterday
        // will still appear even if the phase transition hasn't run yet.
        List<EarningsWatchlist> allActive = earningsWatchlistRepo
                .findByPhaseNot(WatchPhase.EXPIRED);

        List<EarningsWatchlist> preList = allActive.stream()
                .filter(e -> e.getResultDate().isAfter(today))          // result still upcoming
                .sorted((a, b) -> a.getResultDate().compareTo(b.getResultDate()))
                .toList();

        List<EarningsWatchlist> postList = allActive.stream()
                .filter(e -> !e.getResultDate().isAfter(today))         // result today or past
                .sorted((a, b) -> a.getResultDate().compareTo(b.getResultDate()))
                .toList();

        if (preList.isEmpty() && postList.isEmpty()) {
            telegramService.sendMessage("📋 No F&O stocks currently in the earnings window.");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("📊 *Earnings Window — F&O Stocks*\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━\n\n");

        sb.append("🕐 *Pre-Earnings* (next 10 days)\n");
        if (preList.isEmpty()) {
            sb.append("_None_\n");
        } else {
            for (EarningsWatchlist e : preList) {
                sb.append("🏢 ").append(e.getSymbol())
                  .append(" | 📆 ").append(e.getResultDate())
                  .append(" | ⏳ ").append(e.getDaysToResult()).append(" day(s) away\n");
            }
        }

        sb.append("\n━━━━━━━━━━━━━━━━━━━━\n\n");

        sb.append("✅ *Post-Earnings* (last 3 days)\n");
        if (postList.isEmpty()) {
            sb.append("_None_\n");
        } else {
            for (EarningsWatchlist e : postList) {
                int daysAgo = Math.abs(e.getDaysToResult());
                sb.append("🏢 ").append(e.getSymbol())
                  .append(" | 📆 ").append(e.getResultDate())
                  .append(" | ").append(daysAgo == 0 ? "today" : daysAgo + " day(s) ago").append("\n");
            }
        }

        sb.append("\n━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("Pre: ").append(preList.size())
          .append(" | Post: ").append(postList.size())
          .append(" | Total: ").append(preList.size() + postList.size()).append(" F&O stocks");

        telegramService.sendMessage(sb.toString());

        // Save snapshot of what was just sent — used by /diff endpoint
        List<String> sentSymbols = new java.util.ArrayList<>();
        preList.forEach(e -> sentSymbols.add(e.getSymbol()));
        postList.forEach(e -> sentSymbols.add(e.getSymbol()));
        diffService.saveSnapshot(sentSymbols);
        log.info("Window snapshot saved: {} symbols", sentSymbols.size());
    }

    // =========================================================================
    // Opening Candle Strategy — live market alert at 9:46 AM
    // =========================================================================

    /**
     * Runs every weekday at 9:46 AM IST.
     * By 9:46 the 9:15 and 9:30 candles are fully formed and confirmed.
     * Fetches today's candles for all F&O stocks, runs strategy rules,
     * sends Telegram alert with valid BUY/SELL setups.
     */
    @Scheduled(cron = "0 46 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void openingCandleStrategyAlert() {
        log.info("Opening Candle Strategy scheduler fired — 9:46 AM");
        liveStrategyAlertService.scanAndAlert();
    }

    // =========================================================================
    // Advance / Decline Ratio — every 5 min from 9:15 to 9:45, then every 15 min till 3:30
    // =========================================================================

    /**
     * Every 5 minutes from 9:15 to 9:45 — early market breadth monitoring.
     * Fires at: 9:15, 9:20, 9:25, 9:30, 9:35, 9:40, 9:45
     * Cron: every 5 min within the 9:15–9:45 window on weekdays.
     */
    @Scheduled(cron = "0 15/5 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void adRatioEvery5Min() {
        java.time.LocalTime now = java.time.LocalTime.now();
        // Only fire between 9:15 and 9:45 (inclusive)
        if (now.isBefore(java.time.LocalTime.of(9, 15)) ||
            now.isAfter(java.time.LocalTime.of(9, 46))) return;

        log.info("A/D Ratio 5-min trigger at {}", now);
        marketSentimentService.sendAdvanceDeclineAlert();
    }

    /**
     * Every 15 minutes from 9:45 to 3:30 PM — regular market breadth monitoring.
     * Fires at: 9:45, 10:00, 10:15, ..., 15:15, 15:30
     */
    @Scheduled(cron = "0 45/15 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void adRatioEvery15MinMorning() {
        log.info("A/D Ratio 15-min trigger (morning) at {}", java.time.LocalTime.now());
        marketSentimentService.sendAdvanceDeclineAlert();
    }

    @Scheduled(cron = "0 0/15 10-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void adRatioEvery15Min() {
        java.time.LocalTime now = java.time.LocalTime.now();
        // Stop at 3:30 PM
        if (now.isAfter(java.time.LocalTime.of(15, 31))) return;
        log.info("A/D Ratio 15-min trigger at {}", now);
        marketSentimentService.sendAdvanceDeclineAlert();
    }

    // =========================================================================
    // 52-week high / low - Friday at 4:00 PM after weekly close
    // =========================================================================

    /**
     * Runs every Friday at 4:00 PM IST after the weekly candle is complete.
     * Fetches 52-week high and low stocks from NSE and sends CSV files to Telegram.
     */
    @Scheduled(cron = "0 0 16 * * FRI", zone = "Asia/Kolkata")
    public void weekHighLowAlert() {
        log.info("52-week high/low scheduler fired - Friday 4:00 PM");
        nseWeekHighService.sendBothCsv();
    }

    // =========================================================================
    // IPO — completely unchanged
    // =========================================================================

    // ── 9:00 AM — sync IPOs + 10-day / open day alerts (unchanged) ──────────
    @Scheduled(cron = "0 0 9 * * *")
    public void ipoAlertScheduler() {
        LocalDate today = LocalDate.now();

        // 10 days before listing
        List<com.trading.algo.ipo.Ipo> upcoming = ipoRepo.findByListingDateBetween(
                today.plusDays(10), today.plusDays(10));
        for (com.trading.algo.ipo.Ipo ipo : upcoming) {
            if (!ipo.isAlert10DaySent()) {
                telegramService.sendMessage(
                    "📅 IPO Listing Soon (10 Days)\n" + ipo.getName() +
                    "\nListing: " + ipo.getListingDate());
                ipo.setAlert10DaySent(true);
                ipoRepo.save(ipo);
            }
        }

        // IPO open day
        List<com.trading.algo.ipo.Ipo> opening = ipoRepo.findByOpenDate(today);
        for (com.trading.algo.ipo.Ipo ipo : opening) {
            if (!ipo.isAlertOpenSent()) {
                telegramService.sendMessage("🚀 IPO OPEN TODAY\n" + ipo.getName());
                ipo.setAlertOpenSent(true);
                ipoRepo.save(ipo);
            }
        }

        // Listing day — basic alert
        List<com.trading.algo.ipo.Ipo> listing = ipoRepo.findByListingDate(today);
        for (com.trading.algo.ipo.Ipo ipo : listing) {
            if (!ipo.isAlertListingSent()) {
                telegramService.sendMessage("📈 IPO LISTING TODAY\n" + ipo.getName());
                ipo.setAlertListingSent(true);
                ipoRepo.save(ipo);
            }
        }
    }

    // ── 9:30 AM — listing open performance (price vs issue price) ───────────
    @Scheduled(cron = "0 30 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void ipoListingOpenAlert() {
        log.info("IPO listing open alert — 9:30 AM");
        ipoMonitorService.sendListingOpenAlert();
    }

    // ── 3:35 PM — listing EOD performance (full OHLC vs issue price) ────────
    @Scheduled(cron = "0 35 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void ipoListingEodAlert() {
        log.info("IPO listing EOD alert — 3:35 PM");
        ipoMonitorService.sendListingEodAlert();
    }

    @Scheduled(cron = "0 0 8 * * *")
    public void dailyIpoSync() throws Exception {
        ipoService.syncIpos();
    }

    // =========================================================================
    // HELPER — kept for reference but no longer used in alerts
    // (alerts now rely on earnings_watchlist F&O filter instead)
    // =========================================================================

    private boolean isTradeableEvent(Earnings e) {
        return e.getEventType() != null &&
                e.getEventType().toLowerCase().contains("financial results");
    }
}
