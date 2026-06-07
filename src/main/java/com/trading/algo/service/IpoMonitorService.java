package com.trading.algo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.algo.ipo.Ipo;
import com.trading.algo.ipo.IpoRepository;
import com.trading.algo.telegram.TelegramService;
import com.trading.algo.upstox.UpstoxInstrumentMasterService;
import com.trading.algo.upstox.UpstoxMarketDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Monitors IPO listing performance on listing day using Upstox live quotes.
 *
 * Flow:
 *   1. Listing day morning (9:30 AM) — send listing open alert with listing price vs issue price
 *   2. Listing day EOD (3:35 PM)     — send full performance: open, high, low, close, gain%
 *   3. Resolves NSE symbol from Upstox instrument master using company name search
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IpoMonitorService {

    private final IpoRepository                 ipoRepo;
    private final UpstoxMarketDataService       marketDataService;
    private final UpstoxInstrumentMasterService instrumentMaster;
    private final TelegramService               telegramService;
    private final ObjectMapper                  objectMapper;

    // =========================================================================
    // Morning listing alert — called at 9:30 AM on listing day
    // =========================================================================

    @Transactional
    public void sendListingOpenAlert() {
        LocalDate today = LocalDate.now();
        List<Ipo> listingToday = ipoRepo.findByListingDate(today);

        if (listingToday.isEmpty()) {
            log.info("No IPOs listing today ({})", today);
            return;
        }

        for (Ipo ipo : listingToday) {
            try {
                // Resolve symbol from instrument master if not already set
                if (ipo.getSymbol() == null || ipo.getSymbol().isBlank()) {
                //    resolveSymbol(ipo);
                }

                if (ipo.getSymbol() == null) {
                    log.warn("Could not resolve symbol for IPO: {}", ipo.getName());
                    sendUnresolvedAlert(ipo, "LISTING");
                    continue;
                }

                // Fetch live quote from Upstox
                JsonNode quote = fetchLiveQuote(ipo.getSymbol());
                if (quote == null) {
                    log.warn("No live quote for {}", ipo.getSymbol());
                    continue;
                }

                double ltp       = quote.path("last_price").asDouble();
                double dayOpen   = quote.path("ohlc").path("open").asDouble();
                double issuePrice = ipo.getIssuePrice() != null ? ipo.getIssuePrice() : 0;
                double gainPct   = issuePrice > 0 ? ((ltp - issuePrice) / issuePrice) * 100 : 0;

                // Update entity
                ipo.setListingPrice(dayOpen);
                ipo.setListingGainPct(gainPct);
                ipo.setListingMonitoredAt(LocalDateTime.now());
                ipoRepo.save(ipo);

                // Send Telegram alert
                String emoji = gainPct >= 20 ? "🚀" : gainPct >= 10 ? "📈" :
                               gainPct >= 0  ? "🟢" : gainPct >= -5 ? "🟡" : "🔴";

                StringBuilder sb = new StringBuilder();
                sb.append(emoji).append(" *IPO Listing Today*\n");
                sb.append("━━━━━━━━━━━━━━━━━━━━\n");
                sb.append("🏢 ").append(ipo.getName()).append("\n");
                sb.append("📌 Symbol   : `").append(ipo.getSymbol()).append("`\n");
                sb.append("💰 Issue Price : ₹").append(fmt(issuePrice)).append("\n");
                sb.append("🔔 Listing Open: ₹").append(fmt(dayOpen)).append("\n");
                sb.append("📊 LTP         : ₹").append(fmt(ltp)).append("\n");
                sb.append("📈 Gain        : *").append(fmt(gainPct)).append("%*\n");
                sb.append("━━━━━━━━━━━━━━━━━━━━\n");
                sb.append("_EOD performance update at 3:35 PM_");

                telegramService.sendMessageToInvestmentPicks(sb.toString());
                log.info("Listing open alert sent for {} — gain={}%", ipo.getName(), fmt(gainPct));

            } catch (Exception e) {
                log.error("Error monitoring listing for {}: {}", ipo.getName(), e.getMessage());
            }
        }
    }

    // =========================================================================
    // EOD listing performance — called at 3:35 PM on listing day
    // =========================================================================

    @Transactional
    public void sendListingEodAlert() {
        LocalDate today = LocalDate.now();
        List<Ipo> listingToday = ipoRepo.findByListingDateAndAlertListingPerfSentFalse(today);

        if (listingToday.isEmpty()) return;

        for (Ipo ipo : listingToday) {
            try {
                if (ipo.getSymbol() == null || ipo.getSymbol().isBlank()) {
                    //resolveSymbol(ipo);
                }
                if (ipo.getSymbol() == null) {
                    sendUnresolvedAlert(ipo, "EOD");
                    ipo.setAlertListingPerfSent(true);
                    ipoRepo.save(ipo);
                    continue;
                }

                JsonNode quote    = fetchLiveQuote(ipo.getSymbol());
                if (quote == null) continue;

                double issuePrice = ipo.getIssuePrice() != null ? ipo.getIssuePrice() : 0;
                double open       = quote.path("ohlc").path("open").asDouble();
                double high       = quote.path("ohlc").path("high").asDouble();
                double low        = quote.path("ohlc").path("low").asDouble();
                double close      = quote.path("last_price").asDouble();
                double gainPct    = issuePrice > 0 ? ((close - issuePrice) / issuePrice) * 100 : 0;
                double fromOpenPct = open > 0     ? ((close - open) / open)             * 100 : 0;

                ipo.setListingPrice(open);
                ipo.setListingGainPct(gainPct);
                ipo.setListingHigh(high);
                ipo.setListingLow(low);
                ipo.setListingClose(close);
                ipo.setAlertListingPerfSent(true);
                ipo.setListingMonitoredAt(LocalDateTime.now());
                ipoRepo.save(ipo);

                String emoji = gainPct >= 20 ? "🚀" : gainPct >= 10 ? "📈" :
                               gainPct >= 0  ? "🟢" : gainPct >= -5 ? "🟡" : "🔴";

                StringBuilder sb = new StringBuilder();
                sb.append(emoji).append(" *IPO Listing Day Performance*\n");
                sb.append("━━━━━━━━━━━━━━━━━━━━\n");
                sb.append("🏢 ").append(ipo.getName()).append("\n");
                sb.append("📌 Symbol   : `").append(ipo.getSymbol()).append("`\n");
                sb.append("━━━━━━━━━━━━━━━━━━━━\n");
                sb.append("💰 Issue Price : ₹").append(fmt(issuePrice)).append("\n");
                sb.append("🔔 Open        : ₹").append(fmt(open)).append("\n");
                sb.append("⬆️ High         : ₹").append(fmt(high)).append("\n");
                sb.append("⬇️ Low          : ₹").append(fmt(low)).append("\n");
                sb.append("🔚 Close       : ₹").append(fmt(close)).append("\n");
                sb.append("━━━━━━━━━━━━━━━━━━━━\n");
                sb.append("📊 vs Issue    : *").append(fmt(gainPct)).append("%*\n");
                sb.append("📉 Open→Close  : ").append(fmt(fromOpenPct)).append("%\n");

                telegramService.sendMessageToInvestmentPicks(sb.toString());
                log.info("Listing EOD alert sent for {} — close=₹{} gain={}%",
                        ipo.getName(), fmt(close), fmt(gainPct));

            } catch (Exception e) {
                log.error("EOD listing error for {}: {}", ipo.getName(), e.getMessage());
            }
        }
    }

    // =========================================================================
    // Upcoming listings summary — called on demand or scheduled
    // =========================================================================

    public void sendUpcomingListingsSummary() {
        LocalDate today   = LocalDate.now();
        LocalDate nextTwo = today.plusDays(14);

        List<Ipo> upcoming = ipoRepo.findByListingDateBetweenOrderByListingDateAsc(today, nextTwo);

        if (upcoming.isEmpty()) {
            telegramService.sendMessageToInvestmentPicks("📋 No IPO listings in the next 14 days.");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("📋 *Upcoming IPO Listings*\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━\n");

        for (Ipo ipo : upcoming) {
            long days = java.time.temporal.ChronoUnit.DAYS.between(today, ipo.getListingDate());
            String when = days == 0 ? "Today" : days == 1 ? "Tomorrow" : "in " + days + " days";
            sb.append("🏢 *").append(ipo.getName()).append("*\n");
            sb.append("   📅 ").append(ipo.getListingDate()).append(" (").append(when).append(")\n");
            if (ipo.getIssuePrice() != null) {
                sb.append("   💰 Issue: ₹").append(fmt(ipo.getIssuePrice())).append("\n");
            }
            sb.append("\n");
         }
         sb.append("Total: ").append(upcoming.size()).append(" upcoming listings");
         telegramService.sendMessageToInvestmentPicks(sb.toString());
     }

    // =========================================================================
    // Symbol resolution — try to find NSE symbol from instrument master
    // =========================================================================

//    private void resolveSymbol(Ipo ipo) {
//        try {
//            // Try searching instrument master using company name keywords
//            // Strip common suffixes like "Ltd", "Limited", "IPO" from name
//            String searchName = ipo.getName()
//                    .replaceAll("(?i)\\s+(limited|ltd|pvt|private|ipo).*", "")
//                    .trim();
//
//            java.util.Optional<String> symbol = instrumentMaster.findSymbolByName(searchName);
//            if (symbol.isPresent()) {
//                ipo.setSymbol(symbol.get());
//                log.info("Resolved symbol for {}: {}", ipo.getName(), symbol.get());
//            }
//        } catch (Exception e) {
//            log.warn("Symbol resolution failed for {}: {}", ipo.getName(), e.getMessage());
//        }
//    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private JsonNode fetchLiveQuote(String symbol) {
        try {
            // Use existing market data service pattern
            return marketDataService.getLiveQuote("NSE_EQ|" + symbol);
        } catch (Exception e) {
            log.error("Failed to fetch quote for {}: {}", symbol, e.getMessage());
            return null;
        }
    }

     private void sendUnresolvedAlert(Ipo ipo, String type) {
         telegramService.sendMessageToInvestmentPicks(
             "⚠️ *IPO " + type + " Alert*\n" +
             "🏢 " + ipo.getName() + "\n" +
             "📅 Listing: " + ipo.getListingDate() + "\n" +
             "_Could not resolve NSE symbol — check manually_"
         );
     }

    private String fmt(double v) {
        return String.format("%.2f", v);
    }
}