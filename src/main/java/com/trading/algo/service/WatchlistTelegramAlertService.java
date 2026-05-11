package com.trading.algo.service;


import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.trading.algo.dtos.WatchlistItem;
import com.trading.algo.dtos.WatchlistResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class WatchlistTelegramAlertService {

    private static final LocalTime MARKET_OPEN  = LocalTime.of(9, 15);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    @Value("${telegram.bot.token}")
    private String botToken;

    @Value("${telegram.chat.id}")
    private String chatId;

    private final WatchlistService watchlistService;
    private final RestTemplate restTemplate;

    // -------------------------------------------------------------------------
    // Scheduled: every 15 min during market hours (auto)
    // -------------------------------------------------------------------------

    @Scheduled(cron = "0 15/15 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void scheduledDigest() {
        LocalTime now = LocalTime.now();
        if (now.isBefore(MARKET_OPEN) || now.isAfter(MARKET_CLOSE)) {
            log.debug("Scheduled alert skipped — outside market hours ({})", now.format(TIME_FMT));
            return;
        }
        log.info("Scheduled watchlist digest firing at {}", now.format(TIME_FMT));
        sendWatchlistDigest();
    }

    // -------------------------------------------------------------------------
    // Core send — called by scheduler AND by the manual /alert endpoint
    // No market-hours guard here so manual trigger always works
    // -------------------------------------------------------------------------

    public void sendWatchlistDigest() {
        try {
            WatchlistResponse watchlist = watchlistService.getLiveWatchlist();
            String message = buildMessage(watchlist);
            sendTelegramMessage(message);
            log.info("Watchlist digest sent to Telegram successfully");
        } catch (Exception e) {
            log.error("Failed to send watchlist digest: {}", e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Message builder — symbol names only, all 7 categories
    // -------------------------------------------------------------------------

    private String buildMessage(WatchlistResponse watchlist) {
        String time = watchlist.getGeneratedAt().toLocalTime().format(TIME_FMT);

        StringBuilder msg = new StringBuilder();
        msg.append("\uD83D\uDCCA *Live Market Watchlist* | ").append(time).append("\n");
        msg.append("━━━━━━━━━━━━━━━━━━━━━\n\n");

        appendGainers (msg, watchlist.getTopGainers());
        appendLosers  (msg, watchlist.getTopLosers());
        appendShockers(msg, watchlist.getVolumeShockers());
        appendByValue (msg, watchlist.getActiveByValue());
        appendHighOi  (msg, watchlist.getHighOiStocks());
        appendBuyers  (msg, watchlist.getOnlyBuyers());
        appendSellers (msg, watchlist.getOnlySellers());

        msg.append("━━━━━━━━━━━━━━━━━━━━━\n");
        msg.append("_Scanned ").append(watchlist.getTotalSymbolsScanned()).append(" F&O stocks_");

        return msg.toString();
    }

    // ── Per-category formatters ──────────────────────────────────────────────

    /** 📈 Top Gainers — symbol + % change (green arrow) */
    private void appendGainers(StringBuilder sb, List<WatchlistItem> items) {
        sb.append("\uD83D\uDCC8 *Top Gainers*\n");
        if (items == null || items.isEmpty()) { sb.append("_None_\n\n"); return; }
        items.forEach(i -> sb.append(String.format(
                "`%-12s` \u25B2 %.2f%%\n", i.getSymbol(), i.getChangePercent())));
        sb.append("\n");
    }

    /** 📉 Top Losers — symbol + % change (red arrow) */
    private void appendLosers(StringBuilder sb, List<WatchlistItem> items) {
        sb.append("\uD83D\uDCC9 *Top Losers*\n");
        if (items == null || items.isEmpty()) { sb.append("_None_\n\n"); return; }
        items.forEach(i -> sb.append(String.format(
                "`%-12s` \u25BC %.2f%%\n", i.getSymbol(), Math.abs(i.getChangePercent()))));
        sb.append("\n");
    }

    /** 🔥 Volume Shockers — symbol + volume ratio + % change */
    private void appendShockers(StringBuilder sb, List<WatchlistItem> items) {
        sb.append("\uD83D\uDD25 *Volume Shockers*\n");
        if (items == null || items.isEmpty()) { sb.append("_None_\n\n"); return; }
        items.forEach(i -> {
            String chg = i.getChangePercent() >= 0
                    ? String.format("\u25B2 %.2f%%", i.getChangePercent())
                    : String.format("\u25BC %.2f%%", Math.abs(i.getChangePercent()));
            sb.append(String.format("`%-12s` Vol: %.1fx | %s\n",
                    i.getSymbol(), i.getVolumeRatio(), chg));
        });
        sb.append("\n");
    }

    /** 💰 Active by Value — symbol + traded value + % change */
    private void appendByValue(StringBuilder sb, List<WatchlistItem> items) {
        sb.append("\uD83D\uDCB0 *Active by Value*\n");
        if (items == null || items.isEmpty()) { sb.append("_None_\n\n"); return; }
        items.forEach(i -> {
            String chg = i.getChangePercent() >= 0
                    ? String.format("\u25B2 %.2f%%", i.getChangePercent())
                    : String.format("\u25BC %.2f%%", Math.abs(i.getChangePercent()));
            sb.append(String.format("`%-12s` Val: %.1fCr | %s\n",
                    i.getSymbol(), i.getTradedValue(), chg));
        });
        sb.append("\n");
    }

    /** 📌 High OI — symbol + OI change % + price % change */
    private void appendHighOi(StringBuilder sb, List<WatchlistItem> items) {
        sb.append("\uD83D\uDCCC *High OI*\n");
        if (items == null || items.isEmpty()) { sb.append("_None_\n\n"); return; }
        items.forEach(i -> {
            String chg = i.getChangePercent() >= 0
                    ? String.format("\u25B2 %.2f%%", i.getChangePercent())
                    : String.format("\u25BC %.2f%%", Math.abs(i.getChangePercent()));
            sb.append(String.format("`%-12s` OI: %.1f%% | %s\n",
                    i.getSymbol(), i.getOiChangePercent(), chg));
        });
        sb.append("\n");
    }

    /** 🟢 Only Buyers — symbol + buy/sell ratio + % change */
    private void appendBuyers(StringBuilder sb, List<WatchlistItem> items) {
        sb.append("\uD83D\uDFE2 *Only Buyers*\n");
        if (items == null || items.isEmpty()) { sb.append("_None_\n\n"); return; }
        items.forEach(i -> sb.append(String.format(
                "`%-12s` B/S: %.1fx | \u25B2 %.2f%%\n",
                i.getSymbol(), i.getBuySelRatio(), i.getChangePercent())));
        sb.append("\n");
    }

    /** 🔴 Only Sellers — symbol + sell/buy ratio + % change */
    private void appendSellers(StringBuilder sb, List<WatchlistItem> items) {
        sb.append("\uD83D\uDD34 *Only Sellers*\n");
        if (items == null || items.isEmpty()) { sb.append("_None_\n\n"); return; }
        items.forEach(i -> {
            double sbRatio = i.getBuySelRatio() > 0 ? 1.0 / i.getBuySelRatio() : 0;
            sb.append(String.format("`%-12s` S/B: %.1fx | \u25BC %.2f%%\n",
                    i.getSymbol(), sbRatio, Math.abs(i.getChangePercent())));
        });
        sb.append("\n");
    }

    // -------------------------------------------------------------------------
    // Telegram HTTP call
    // -------------------------------------------------------------------------

    public void sendTelegramMessage(String text) {
        try {
            String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";
            var payload = new java.util.HashMap<String, String>();
            payload.put("chat_id", chatId);
            payload.put("text", text);
            payload.put("parse_mode", "Markdown");
            restTemplate.postForObject(url, payload, String.class);
        } catch (Exception e) {
            log.error("Telegram sendMessage failed: {}", e.getMessage(), e);
        }
    }
}
/**
 * Sends a single consolidated Telegram alert with all 7 watchlist categories.
 * Only stock names are shown, comma-separated per category.
 *
 * Schedule: every 15 minutes, Mon–Fri, 09:15–15:30 IST.
 */
//@Slf4j
//@Service
//@RequiredArgsConstructor
//public class WatchlistTelegramAlertService {
//
//    private static final LocalTime MARKET_OPEN  = LocalTime.of(9, 15);
//    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);
//    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
//
//    @Value("${telegram.bot.token}")
//    private String botToken;
//
//    @Value("${telegram.chat.id}")
//    private String chatId;
//
//    private final WatchlistService watchlistService;
//    private final RestTemplate restTemplate;
//
//    // -------------------------------------------------------------------------
//    // Scheduler: every 15 min, Mon-Fri, strictly between 09:15 and 15:30 IST
//    // -------------------------------------------------------------------------
//
//    @Scheduled(cron = "0 15/15 9-15 * * MON-FRI", zone = "Asia/Kolkata")
//    public void sendWatchlistDigest() {
//        LocalTime now = LocalTime.now();
//
//        // Guard: skip if outside market hours (handles 09:00–09:14 and post 15:30)
//        if (now.isBefore(MARKET_OPEN) || now.isAfter(MARKET_CLOSE)) {
//            log.debug("Watchlist alert skipped — outside market hours ({})", now.format(TIME_FMT));
//            return;
//        }
//
//        log.info("Sending watchlist Telegram digest at {}", now.format(TIME_FMT));
//
//        try {
//            WatchlistResponse watchlist = watchlistService.getLiveWatchlist();
//            String message = buildMessage(watchlist);
//            sendTelegramMessage(message);
//            log.info("Watchlist digest sent successfully");
//        } catch (Exception e) {
//            log.error("Failed to send watchlist Telegram digest: {}", e.getMessage(), e);
//        }
//    }
//
//    // -------------------------------------------------------------------------
//    // Message builder — stock names only, all 7 categories in one message
//    // -------------------------------------------------------------------------
//
//    private String buildMessage(WatchlistResponse watchlist) {
//        String time = watchlist.getGeneratedAt().toLocalTime().format(TIME_FMT);
//
//        StringBuilder msg = new StringBuilder();
//        msg.append("\uD83D\uDCCA *Live Market Watchlist* | ").append(time).append("\n");
//        msg.append("━━━━━━━━━━━━━━━━━━━━━\n\n");
//
//        appendCategory(msg, "\uD83D\uDCC8 *Top Gainers*",      watchlist.getTopGainers());
//        appendCategory(msg, "\uD83D\uDCC9 *Top Losers*",       watchlist.getTopLosers());
//        appendCategory(msg, "\uD83D\uDD25 *Volume Shockers*",  watchlist.getVolumeShockers());
//        appendCategory(msg, "\uD83D\uDCB0 *Active by Value*",  watchlist.getActiveByValue());
//        appendCategory(msg, "\uD83D\uDCCC *High OI*",          watchlist.getHighOiStocks());
//        appendCategory(msg, "\uD83D\uDFE2 *Only Buyers*",      watchlist.getOnlyBuyers());
//        appendCategory(msg, "\uD83D\uDD34 *Only Sellers*",     watchlist.getOnlySellers());
//
//        msg.append("━━━━━━━━━━━━━━━━━━━━━\n");
//        msg.append("_Scanned ").append(watchlist.getTotalSymbolsScanned()).append(" F&O stocks_");
//
//        return msg.toString();
//    }
//
//    /**
//     * Appends one category block.
//     * Format:
//     *   📈 *Top Gainers*
//     *   RELIANCE, TCS, INFY, HDFCBANK, ICICIBANK
//     */
//    private void appendCategory(StringBuilder sb, String header, List<WatchlistItem> items) {
//        if (items == null || items.isEmpty()) {
//            sb.append(header).append("\n_None_\n\n");
//            return;
//        }
//
//        String symbols = items.stream()
//                .map(WatchlistItem::getSymbol)
//                .collect(Collectors.joining(", "));
//
//        sb.append(header).append("\n");
//        sb.append(symbols).append("\n\n");
//    }
//
//    // -------------------------------------------------------------------------
//    // Telegram sender
//    // -------------------------------------------------------------------------
//
//    public void sendTelegramMessage(String text) {
//        try {
//            String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";
//
//            var payload = new java.util.HashMap<String, String>();
//            payload.put("chat_id", chatId);
//            payload.put("text", text);
//            payload.put("parse_mode", "Markdown");
//
//            restTemplate.postForObject(url, payload, String.class);
//        } catch (Exception e) {
//            log.error("Telegram sendMessage failed: {}", e.getMessage(), e);
//        }
//    }
//}
/**
 * Sends formatted Telegram alerts for watchlist categories.
 *
 * Sends a consolidated alert every 15 minutes during market hours.
 * You can also trigger individual category alerts as needed.
 */
//@Slf4j
//@Service
//@RequiredArgsConstructor
//public class WatchlistTelegramAlertService {
//
//    private static final String TELEGRAM_URL =
//            "https://api.telegram.org/bot{token}/sendMessage";
//
//    @Value("${telegram.bot.token}")
//    private String botToken;
//
//    @Value("${telegram.chat.id}")
//    private String chatId;
//
//    private final WatchlistService watchlistService;
//    private final RestTemplate restTemplate;
//
//    // Send watchlist digest every 15 minutes during market hours
//    @Scheduled(cron = "0 0/15 9-15 * * MON-FRI", zone = "Asia/Kolkata")
//    public void sendWatchlistDigest() {
//        try {
//            WatchlistResponse watchlist = watchlistService.getLiveWatchlist();
//            if (!"OPEN".equals(watchlist.getMarketStatus())) return;
//
//            StringBuilder msg = new StringBuilder();
//            msg.append("📊 *Live Market Watchlist*\n");
//            msg.append("🕐 ").append(watchlist.getGeneratedAt().toLocalTime()).append("\n\n");
//
//            appendSection(msg, "🔥 Volume Shockers",    watchlist.getVolumeShockers(),   "volumeRatio");
//            appendSection(msg, "📈 Top Gainers",         watchlist.getTopGainers(),        "changePercent");
//            appendSection(msg, "📉 Top Losers",          watchlist.getTopLosers(),         "changePercent");
//            appendSection(msg, "💰 Active by Value",     watchlist.getActiveByValue(),     "tradedValue");
//            appendSection(msg, "📌 High OI",             watchlist.getHighOiStocks(),      "oi");
//            appendSection(msg, "🟢 Only Buyers",         watchlist.getOnlyBuyers(),        "buySellRatio");
//            appendSection(msg, "🔴 Only Sellers",        watchlist.getOnlySellers(),       "buySellRatio");
//
//            sendTelegramMessage(msg.toString());
//
//        } catch (Exception e) {
//            log.error("Failed to send watchlist Telegram alert: {}", e.getMessage(), e);
//        }
//    }
//
//    // -------------------------------------------------------------------------
//
//    private void appendSection(StringBuilder sb, String header,
//                                List<WatchlistItem> items, String sortField) {
//        if (items == null || items.isEmpty()) return;
//
//        sb.append("*").append(header).append("*\n");
//        // Show top 5 in Telegram to keep message concise
//        items.stream().limit(5).forEach(item -> {
//            String line = formatItem(item, sortField);
//            sb.append(line).append("\n");
//        });
//        sb.append("\n");
//    }
//
//    private String formatItem(WatchlistItem item, String sortField) {
//        String changeEmoji = item.getChangePercent() >= 0 ? "▲" : "▼";
//        return String.format("`%-12s` %s%.2f%% | LTP: %.2f | Val: %.1fCr",
//                item.getSymbol(),
//                changeEmoji,
//                Math.abs(item.getChangePercent()),
//                item.getLtp(),
//                item.getTradedValue());
//    }
//
//    public void sendTelegramMessage(String text) {
//        try {
//            String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";
//            var request = new java.util.HashMap<String, String>();
//            request.put("chat_id", chatId);
//            request.put("text", text);
//            request.put("parse_mode", "Markdown");
//            restTemplate.postForObject(url, request, String.class);
//        } catch (Exception e) {
//            log.error("Telegram send failed: {}", e.getMessage());
//        }
//    }
//}