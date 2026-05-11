package com.trading.algo.service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.cookie.BasicCookieStore;
import org.apache.hc.client5.http.cookie.CookieStore;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Fetches 52-week high and low stocks from NSE India and sends CSV via Telegram.
 *
 * Uses Apache HttpClient with CookieStore — SAME pattern as EarningsService.
 * Java's built-in HttpClient was failing with 403 because NSE requires
 * a proper browser-like session with cookies established via homepage warm-up.
 *
 * NSE API:
 *   Highs: /api/live-analysis-variations?index=52Week&type=highs
 *   Lows:  /api/live-analysis-variations?index=52Week&type=lows
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NseWeekHighService {

    private static final String NSE_HOME  = "https://www.nseindia.com";
    private static final String HIGH_URL  = "https://www.nseindia.com/api/live-analysis-variations?index=52Week&type=highs";
    private static final String LOW_URL   = "https://www.nseindia.com/api/live-analysis-variations?index=52Week&type=lows";
    private static final String UA        = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private final TelegramService telegramService;
    private final ObjectMapper    objectMapper;

    // ── Public API ────────────────────────────────────────────────────────────

    public int sendWeekHighCsv() { return fetchAndSend("HIGHS", HIGH_URL); }
    public int sendWeekLowCsv()  { return fetchAndSend("LOWS",  LOW_URL);  }

    public void sendBothCsv() {
        int h = sendWeekHighCsv();
        int l = sendWeekLowCsv();
        log.info("52-week CSV sent — highs={} lows={}", h, l);
    }

    // ── Core ──────────────────────────────────────────────────────────────────

    private int fetchAndSend(String type, String apiUrl) {
        // Guard: NSE does not publish 52-week data on weekends or holidays
        java.time.DayOfWeek day = LocalDate.now().getDayOfWeek();
        if (day == java.time.DayOfWeek.SATURDAY || day == java.time.DayOfWeek.SUNDAY) {
            log.info("Skipping 52-week {} fetch — today is {}", type, day);
            telegramService.sendMessage("📊 52-Week " + type
                    + ": Market closed (" + day + "). Run on a trading day.");
            return 0;
        }

        try {
            log.info("Fetching 52-week {} from NSE", type);

            // Apache HttpClient with CookieStore — same as EarningsService
            // Reason: NSE returns 403 to Java's built-in HttpClient because it
            // doesn't persist the session cookies set by the homepage response
            CookieStore         cookieStore = new BasicCookieStore();
            CloseableHttpClient client      = HttpClients.custom()
                    .setDefaultCookieStore(cookieStore)
                    .build();

            // Step 1 — warm up: hit homepage so NSE sets session cookies
            HttpGet homeReq = new HttpGet(NSE_HOME);
            setHeaders(homeReq, "text/html,application/xhtml+xml,*/*");
            client.execute(homeReq).close();

            // Step 2 — mandatory delay before API call (NSE blocks without it)
            Thread.sleep(2000);

            // Step 3 — fetch the data using the session cookies from step 1
            HttpGet apiReq = new HttpGet(apiUrl);
            setHeaders(apiReq, "application/json, text/plain, */*");

            CloseableHttpResponse response = client.execute(apiReq);
            String body = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            response.close();
            client.close();

            // Step 4 — parse
            JsonNode root     = objectMapper.readTree(body);
            JsonNode dataNode = root.path("data");

            if (!dataNode.isArray() || dataNode.isEmpty()) {
                // Log actual response body to diagnose — iterator object was being logged before
                String preview = body.length() > 300 ? body.substring(0, 300) + "..." : body;
                log.warn("No data for 52-week {}. Response preview: {}", type, preview);

                // NSE returned empty data — could be holiday or data not yet published
                telegramService.sendMessage("📊 52-Week " + type + ": No data available today.");
                return 0;
            }

            log.info("52-week {} — {} stocks", type, dataNode.size());

            // Step 5 — build CSV and send as document
            String date    = LocalDate.now().format(DateTimeFormatter.ofPattern("dd-MMM-yyyy"));
            String caption = "📈 *52-Week " + type + "* — " + date
                           + "\nTotal stocks: " + dataNode.size();

            telegramService.sendDocument(
                    buildCsv(dataNode, type).getBytes(StandardCharsets.UTF_8),
                    "52_week_" + type.toLowerCase() + "_" + LocalDate.now() + ".csv",
                    caption
            );

            return dataNode.size();

        } catch (Exception e) {
            log.error("Failed to fetch 52-week {}: {}", type, e.getMessage());
            telegramService.sendMessage("❌ Failed to fetch 52-week " + type + ": " + e.getMessage());
            return 0;
        }
    }

    // ── Headers — same set as EarningsService ─────────────────────────────────

    private void setHeaders(HttpGet req, String accept) {
        req.setHeader("User-Agent",       UA);
        req.setHeader("Accept",           accept);
        req.setHeader("Accept-Language",  "en-US,en;q=0.9");
        req.setHeader("Referer",          "https://www.nseindia.com/");
        req.setHeader("X-Requested-With", "XMLHttpRequest");
        req.setHeader("sec-fetch-dest",   "empty");
        req.setHeader("sec-fetch-mode",   "cors");
        req.setHeader("sec-fetch-site",   "same-origin");
    }

    // ── CSV builder ───────────────────────────────────────────────────────────

    private String buildCsv(JsonNode dataNode, String type) {
        StringBuilder sb = new StringBuilder();

        if (type.equals("HIGHS")) {
            sb.append("Symbol,Series,LTP,Change%,Prev Close,New 52W High,52W High Date,% From 52W High\n");
            for (JsonNode r : dataNode) {
                sb.append(String.format("%s,%s,%.2f,%.2f%%,%.2f,%.2f,%s,%.2f%%\n",
                        r.path("symbol").asText("-"),
                        r.path("series").asText("-"),
                        r.path("ltp").asDouble(),
                        r.path("pChange").asDouble(),
                        r.path("previousClose").asDouble(),
                        r.path("prev52WkHigh").asDouble(),
                        r.path("date52WkHigh").asText("-"),
                        r.path("per52WkHighChange").asDouble()));
            }
        } else {
            sb.append("Symbol,Series,LTP,Change%,Prev Close,New 52W Low,52W Low Date,% From 52W Low\n");
            for (JsonNode r : dataNode) {
                sb.append(String.format("%s,%s,%.2f,%.2f%%,%.2f,%.2f,%s,%.2f%%\n",
                        r.path("symbol").asText("-"),
                        r.path("series").asText("-"),
                        r.path("ltp").asDouble(),
                        r.path("pChange").asDouble(),
                        r.path("previousClose").asDouble(),
                        r.path("prev52WkLow").asDouble(),
                        r.path("date52WkLow").asText("-"),
                        r.path("per52WkLowChange").asDouble()));
            }
        }

        return sb.toString();
    }
}