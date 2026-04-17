package com.trading.algo.service;

import java.time.LocalDate;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.trading.algo.entity.Earnings;
import com.trading.algo.entity.Ipo;
import com.trading.algo.repo.EarningsRepository;
import com.trading.algo.repo.IpoRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SchedulerService {

    private final EarningsService earningsService;
    private final EarningsRepository earningsRepo;   // ✅ FIXED
    private final IpoRepository ipoRepo;             // ✅ NEW
    private final TelegramService telegramService;
    private final IpoService ipoService;

    // -------------------- EARNINGS --------------------

    @Scheduled(cron = "0 0 7 * * ?")
    public void fetchData() {
        earningsService.fetchAndStoreData();
    }

    @Scheduled(cron = "0 0 9 * * ?")
    public void weekAheadAlerts() {
        LocalDate target = LocalDate.now().plusDays(7);
        List<Earnings> list = earningsRepo.findByResultDate(target);

        for (Earnings e : list) {
            if (!isTradeableEvent(e)) continue;

            if (!e.isAlertSentWeek()) {
                telegramService.sendMessage(
                        "📅 7-Day Heads Up!\n" +
                        "🏢 " + e.getSymbol() +
                        "\n📆 " + e.getResultDate()
                );
                e.setAlertSentWeek(true);
                earningsRepo.save(e);
            }
        }
    }

    @Scheduled(cron = "0 0 9 * * ?")
    public void dayAheadAlerts() {
        LocalDate target = LocalDate.now().plusDays(1);
        List<Earnings> list = earningsRepo.findByResultDate(target);

        for (Earnings e : list) {
            if (!isTradeableEvent(e)) continue;

            if (!e.isAlertSentDay()) {
                telegramService.sendMessage(
                        "⏰ Tomorrow Result\n" +
                        e.getSymbol()
                );
                e.setAlertSentDay(true);
                earningsRepo.save(e);
            }
        }
    }

    @Scheduled(cron = "0 0 9 * * ?")
    public void todayAlerts() {
        LocalDate today = LocalDate.now();
        List<Earnings> list = earningsRepo.findByResultDate(today);

        for (Earnings e : list) {
            if (!isTradeableEvent(e)) continue;

            if (!e.isAlertSentToday()) {
                telegramService.sendMessage(
                        "🚨 Result Today\n" +
                        e.getSymbol()
                );
                e.setAlertSentToday(true);
                earningsRepo.save(e);
            }
        }
    }
    
    @Scheduled(cron = "0 0 8 * * MON")
    public void weeklySummaryAlert() {

        LocalDate today = LocalDate.now();
        LocalDate nextWeek = today.plusDays(7);

        List<Earnings> list = earningsRepo.findByResultDateBetween(today, nextWeek)
                .stream()
                .filter(this::isTradeableEvent)
                .sorted((a, b) -> a.getResultDate().compareTo(b.getResultDate()))
                .toList();

        // ✅ No events case
        if (list.isEmpty()) {
            telegramService.sendMessage(
                "📋 No Financial Results scheduled in the next 7 days."
            );
            return;
        }

        // ✅ Build summary message
        StringBuilder sb = new StringBuilder();
        sb.append("📋 Results This Week\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━\n");

        for (Earnings e : list) {
            sb.append("📅 ").append(e.getResultDate()).append("\n")
              .append("🏢 ").append(e.getSymbol())
              .append(" — ").append(e.getEventType()).append("\n")
              .append("━━━━━━━━━━━━━━━━━━━━\n");
        }

        sb.append("Total: ").append(list.size()).append(" results");

        // ✅ Send to Telegram
        telegramService.sendMessage(sb.toString());
    }

    // -------------------- IPO --------------------

    @Scheduled(cron = "0 0 9 * * *")
    public void ipoAlertScheduler() {

        LocalDate today = LocalDate.now();

        // 🔔 10 days before listing
        List<Ipo> upcoming = ipoRepo.findByListingDateBetween(
                today.plusDays(10), today.plusDays(10)
        );

        for (Ipo ipo : upcoming) {
            if (!ipo.isAlert10DaySent()) {

                telegramService.sendMessage(
                        "📅 IPO Listing Soon (10 Days)\n" +
                        ipo.getName() +
                        "\nListing: " + ipo.getListingDate()
                );

                ipo.setAlert10DaySent(true);
                ipoRepo.save(ipo);   // ✅ FIXED
            }
        }

        // 🚀 IPO OPEN
        List<Ipo> opening = ipoRepo.findByOpenDate(today);

        for (Ipo ipo : opening) {
            if (!ipo.isAlertOpenSent()) {

                telegramService.sendMessage(
                        "🚀 IPO OPEN TODAY\n" +
                        ipo.getName()
                );

                ipo.setAlertOpenSent(true);
                ipoRepo.save(ipo);   // ✅ FIXED
            }
        }

        // 📈 LISTING DAY
        List<Ipo> listing = ipoRepo.findByListingDate(today);

        for (Ipo ipo : listing) {
            if (!ipo.isAlertListingSent()) {

                telegramService.sendMessage(
                        "📈 IPO LISTING TODAY\n" +
                        ipo.getName()
                );

                ipo.setAlertListingSent(true);
                ipoRepo.save(ipo);   // ✅ FIXED
            }
        }
    }

    // -------------------- SYNC --------------------

    @Scheduled(cron = "0 0 8 * * *")
    public void dailyIpoSync() throws Exception {
        ipoService.syncIpos();
    }

    // -------------------- HELPER --------------------

    private boolean isTradeableEvent(Earnings e) {
        return e.getEventType() != null &&
                e.getEventType().toLowerCase().contains("financial results");
    }
}
//@Service
//@RequiredArgsConstructor
//public class SchedulerService {
//
//    private final EarningsService earningsService;
//    private final EarningsRepository repo;
//    private final TelegramService telegramService;
//
//    // Refresh event calendar daily at 7am
//    @Scheduled(cron = "0 0 7 * * ?")
//    public void fetchData() {
//        earningsService.fetchAndStoreData();
//    }
//
//    // 7-day advance alert — runs daily at 9am
//    @Scheduled(cron = "0 0 9 * * ?")
//    public void weekAheadAlerts() {
//        LocalDate target = LocalDate.now().plusDays(7);
//
//        List<Earnings> list = repo.findByResultDate(target);
//     // In SchedulerService.weekAheadAlerts()
//        for (Earnings e : list) {
//            if (!e.isAlertSentWeek()) {
//                try {
//                	telegramService.sendMessage(
//                		    "📅 7-Day Heads Up: " + e.getSymbol() + " (" + e.getCompanyName() + ")\n" +
//                		    "📌 Event: " + e.getEventType() + "\n" +
//                		    "📆 Date: " + e.getResultDate()
//                		);
//                    e.setAlertSentWeek(true); // only set flag if send succeeded
//                    repo.save(e);
//                } catch (Exception ex) {
//                    System.err.println("Telegram failed for " + e.getSymbol() + ": " + ex.getMessage());
//                    // flag NOT set — will retry next run
//                }
//            }
//        }
//    }
//
//    // 1-day advance alert — runs daily at 9am
//    @Scheduled(cron = "0 0 9 * * ?")
//    public void dayAheadAlerts() {
//        LocalDate target = LocalDate.now().plusDays(1);
//
//        List<Earnings> list = repo.findByResultDate(target);
//        for (Earnings e : list) {
//            if (!e.isAlertSentDay()) {
//                telegramService.sendMessage(
//                    "⏰ Tomorrow: " + e.getSymbol() + " (" + e.getCompanyName() + ")\n" +
//                    "📌 Event: " + e.getEventType() + "\n" +
//                    "📆 Date: " + e.getResultDate()
//                );
//                e.setAlertSentDay(true);
//                repo.save(e);
//            }
//        }
//    }
//
//    // Morning of event — runs at 9am on event day
//    @Scheduled(cron = "0 0 9 * * ?")
//    public void todayAlerts() {
//        LocalDate today = LocalDate.now();
//
//        List<Earnings> list = repo.findByResultDate(today);
//        for (Earnings e : list) {
//            if (!e.isAlertSentToday()) {
//                telegramService.sendMessage(
//                    "🚨 Today: " + e.getSymbol() + " (" + e.getCompanyName() + ")\n" +
//                    "📌 Event: " + e.getEventType() + "\n" +
//                    "🕘 Check NSE for updates!"
//                );
//                e.setAlertSentToday(true);
//                repo.save(e);
//            }
//        }
//    }
//    
// // Weekly summary — runs every Monday at 8am
//    @Scheduled(cron = "0 0 8 * * MON")
//    public void weeklySummaryAlert() {
//        LocalDate today = LocalDate.now();
//        LocalDate nextWeek = today.plusDays(7);
//
//        List<Earnings> list = repo.findByResultDateBetween(today, nextWeek);
//
//        if (list.isEmpty()) {
//            telegramService.sendMessage("📋 No upcoming events in the next 7 days.");
//            return;
//        }
//
//        StringBuilder sb = new StringBuilder("📋 *Upcoming Events This Week*\n");
//        sb.append("━━━━━━━━━━━━━━━━━━━━\n");
//
//        // Group by date for clean formatting
//        list.stream()
//            .sorted((a, b) -> a.getResultDate().compareTo(b.getResultDate()))
//            .forEach(e -> {
//                sb.append("📅 ").append(e.getResultDate()).append("\n")
//                  .append("🏢 ").append(e.getSymbol())
//                  .append(" — ").append(e.getEventType()).append("\n")
//                  .append("━━━━━━━━━━━━━━━━━━━━\n");
//            });
//
//        sb.append("Total: ").append(list.size()).append(" events");
//        telegramService.sendMessage(sb.toString());
//    }
//}
