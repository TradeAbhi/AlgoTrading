package com.trading.algo.service;


import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MarketSentimentService {

    private final TelegramService    telegramService;
    private final UpstoxTokenService upstoxTokenService;
    private final ObjectMapper       objectMapper = new ObjectMapper();

    private static final String NSE_BASE         = "https://www.nseindia.com";
    private static final String UPSTOX_BASE      = "https://api.upstox.com/v2";
    private static final String NIFTY_KEY        = "NSE_INDEX|Nifty 50";

    private CookieManager cookieManager;
    private HttpClient    httpClient;

    @PostConstruct
    public void init() {
        cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .cookieHandler(cookieManager)
                .build();
        try {
            getRaw(NSE_BASE, "/api/allIndices", null);
        } catch (Exception e) {
            System.err.println("[NSE] Prime failed: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // SCHEDULED JOBS
    // -------------------------------------------------------------------------

    @Scheduled(cron = "0 20 9 * * MON-FRI")
    public void morningSentimentAlert() {
        sendSentimentSnapshot("Morning Sentiment Snapshot");
    }

    @Scheduled(cron = "0 30 12 * * MON-FRI")
    public void middaySentimentAlert() {
        sendSentimentSnapshot("Midday Sentiment Check");
    }

    @Scheduled(cron = "0 0 15 * * MON-FRI")
    public void preCloseSentimentAlert() {
        sendSentimentSnapshot("Pre-Close Sentiment Alert");
    }

    @Scheduled(cron = "0 35 15 * * MON-FRI")
    public void eodSentimentSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("End-of-Day Sentiment Summary\n");
        sb.append("------------------------\n");
        sb.append("Date: ").append(LocalDate.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy"))).append("\n\n");
        appendAdvanceDecline(sb);
        appendPCR(sb);
        appendVIX(sb);
        appendFIIDII(sb);
        appendNiftyOI(sb);
        appendSentimentConclusion(sb);
        telegramService.sendMessage(sb.toString());
    }

    @Scheduled(cron = "0 */30 9-15 * * MON-FRI")
    public void pcrExtremeAlert() {
        try {
            double pcr = fetchPCR();
            if (pcr <= 0) return;
            if (pcr < 0.7)      telegramService.sendMessage("PCR EXTREME BEARISH: " + fmt2(pcr) + " at " + timeNow());
            else if (pcr > 1.5) telegramService.sendMessage("PCR EXTREME BULLISH: " + fmt2(pcr) + " at " + timeNow());
        } catch (Exception e) { System.err.println("[PCR extreme alert] " + e.getMessage()); }
    }

    @Scheduled(cron = "0 */15 9-15 * * MON-FRI")
    public void vixSpikeAlert() {
        try {
            double vix = fetchVIX();
            if (vix > 20) telegramService.sendMessage("VIX SPIKE: " + fmt2(vix) + " at " + timeNow());
        } catch (Exception e) { System.err.println("[VIX spike alert] " + e.getMessage()); }
    }

    @Scheduled(cron = "0 0 10,14 * * MON-FRI")
    public void breadthExtremeAlert() {
        try {
            Map<String, Object> adData = fetchAdvanceDeclineData();
            int advances = (int) adData.getOrDefault("advances", 0);
            int declines = (int) adData.getOrDefault("declines", 0);
            int total    = advances + declines;
            if (total == 0) return;
            double advPct = (advances * 100.0) / total;
            if (advPct >= 80)      telegramService.sendMessage("STRONG BREADTH: " + fmt1(advPct) + "% advancing at " + timeNow());
            else if (advPct <= 20) telegramService.sendMessage("WEAK BREADTH: only " + fmt1(advPct) + "% advancing at " + timeNow());
        } catch (Exception e) { System.err.println("[Breadth extreme alert] " + e.getMessage()); }
    }

    // -------------------------------------------------------------------------
    // SNAPSHOT / SECTION BUILDERS
    // -------------------------------------------------------------------------

    private void sendSentimentSnapshot(String title) {
        StringBuilder sb = new StringBuilder();
        sb.append(title).append("\n");
        sb.append("------------------------\n");
        sb.append("Time: ").append(timeNow()).append("\n\n");
        appendAdvanceDecline(sb);
        appendPCR(sb);
        appendVIX(sb);
        telegramService.sendMessage(sb.toString());
    }

    private void appendAdvanceDecline(StringBuilder sb) {
        try {
            Map<String, Object> d = fetchAdvanceDeclineData();
            int advances  = (int) d.getOrDefault("advances",  0);
            int declines  = (int) d.getOrDefault("declines",  0);
            int unchanged = (int) d.getOrDefault("unchanged", 0);
            int total     = advances + declines;
            double adRatio   = (declines > 0) ? (double) advances / declines : advances;
            double advPct    = (total > 0) ? (advances * 100.0) / total : 0;
            String sentiment = adRatio >= 1.5 ? "Bullish Breadth" : adRatio <= 0.7 ? "Bearish Breadth" : "Neutral Breadth";
            sb.append("Advance/Decline Ratio\n");
            sb.append("Advances:  ").append(advances).append("\n");
            sb.append("Declines:  ").append(declines).append("\n");
            sb.append("Unchanged: ").append(unchanged).append("\n");
            sb.append("A/D Ratio: ").append(fmt2(adRatio)).append(" (").append(fmt1(advPct)).append("% advancing)\n");
            sb.append("Signal:    ").append(sentiment).append("\n\n");
        } catch (Exception e) {
            sb.append("A/D Ratio: Unavailable\n\n");
            System.err.println("[AD fetch] " + e.getMessage());
        }
    }

    private void appendPCR(StringBuilder sb) {
        try {
            double pcr    = fetchPCR();
            String signal = pcr < 0.7  ? "Extreme Bearish (Contrarian Buy?)"
                          : pcr < 0.85 ? "Bearish Sentiment"
                          : pcr < 1.05 ? "Neutral"
                          : pcr < 1.3  ? "Bullish Sentiment"
                          :              "Extreme Bullish (Contrarian Sell?)";
            sb.append("Put-Call Ratio (PCR)\n");
            sb.append("PCR (OI): ").append(fmt2(pcr)).append("\n");
            sb.append("Signal:   ").append(signal).append("\n\n");
        } catch (Exception e) {
            sb.append("PCR: Unavailable — ").append(e.getMessage()).append("\n\n");
            System.err.println("[PCR fetch] " + e.getMessage());
        }
    }

    private void appendVIX(StringBuilder sb) {
        try {
            double vix    = fetchVIX();
            String signal = vix < 12 ? "Extreme Complacency"
                          : vix < 16 ? "Low Volatility"
                          : vix < 20 ? "Moderate Volatility"
                          : vix < 25 ? "High Fear"
                          :            "Extreme Fear";
            sb.append("India VIX\n");
            sb.append("VIX:    ").append(fmt2(vix)).append("\n");
            sb.append("Signal: ").append(signal).append("\n\n");
        } catch (Exception e) {
            sb.append("India VIX: Unavailable\n\n");
            System.err.println("[VIX fetch] " + e.getMessage());
        }
    }

    private void appendFIIDII(StringBuilder sb) {
        try {
            Map<String, Double> flows = fetchFIIDIIFlows();
            double fii = flows.getOrDefault("fii", 0.0);
            double dii = flows.getOrDefault("dii", 0.0);
            double net = fii + dii;
            String signal = (fii > 0 && dii > 0) ? "Both FII+DII Buying"
                          : (fii < 0 && dii < 0) ? "Both FII+DII Selling"
                          : (fii > 0)             ? "FII Buying, DII Mixed"
                          :                         "DII Buying, FII Selling";
            sb.append("FII / DII Activity\n");
            sb.append("FII Net:  ").append(signedCr(fii)).append("\n");
            sb.append("DII Net:  ").append(signedCr(dii)).append("\n");
            sb.append("Combined: ").append(signedCr(net)).append("\n");
            sb.append("Signal:   ").append(signal).append("\n\n");
        } catch (Exception e) {
            sb.append("FII/DII: Unavailable\n\n");
            System.err.println("[FII/DII fetch] " + e.getMessage());
        }
    }

    private void appendNiftyOI(StringBuilder sb) {
        try {
            Map<String, Object> oi = fetchNiftyOIData();
            long totalCallOI   = (long) oi.getOrDefault("totalCallOI",   0L);
            long totalPutOI    = (long) oi.getOrDefault("totalPutOI",    0L);
            int  maxCallStrike = (int)  oi.getOrDefault("maxCallStrike", 0);
            int  maxPutStrike  = (int)  oi.getOrDefault("maxPutStrike",  0);
            double pcr         = (totalCallOI > 0) ? (double) totalPutOI / totalCallOI : 0;
            sb.append("Nifty Options OI\n");
            sb.append("Total Call OI:       ").append(String.format("%,d", totalCallOI)).append("\n");
            sb.append("Total Put OI:        ").append(String.format("%,d", totalPutOI)).append("\n");
            sb.append("Call Wall (resist):  ").append(maxCallStrike).append("\n");
            sb.append("Put Floor (support): ").append(maxPutStrike).append("\n");
            sb.append("PCR (OI):            ").append(fmt2(pcr)).append("\n\n");
        } catch (Exception e) {
            sb.append("Nifty OI: Unavailable — ").append(e.getMessage()).append("\n\n");
            System.err.println("[Nifty OI fetch] " + e.getMessage());
        }
    }

    private void appendSentimentConclusion(StringBuilder sb) {
        sb.append("------------------------\n");
        sb.append("Guide: PCR<0.7=possible bottom | PCR>1.5=possible top | VIX>20=high fear\n");
    }

    // -------------------------------------------------------------------------
    // DATA FETCHERS
    // -------------------------------------------------------------------------

    private Map<String, Object> fetchAdvanceDeclineData() throws Exception {
        JsonNode root = getNse("/api/equity-stockIndices?index=NIFTY%20500");
        JsonNode data = root.path("data");
        int advances = 0, declines = 0, unchanged = 0;
        if (data.isArray()) {
            for (JsonNode stock : data) {
                double change = stock.path("pChange").asDouble(0);
                if      (change > 0) advances++;
                else if (change < 0) declines++;
                else                 unchanged++;
            }
        }
        Map<String, Object> result = new HashMap<>();
        result.put("advances",  advances);
        result.put("declines",  declines);
        result.put("unchanged", unchanged);
        return result;
    }

    /**
     * PCR via Upstox option chain.
     * Endpoint: GET /v2/option/chain?instrument_key=NSE_INDEX|Nifty 50&expiry_date=YYYY-MM-DD
     */
    private double fetchPCR() throws Exception {
        String token = upstoxTokenService.getAccessToken();
        if (token.isBlank()) throw new Exception("Upstox token not set. Visit http://localhost:8080/upstox/login");

        String expiry = getNearestThursdayExpiry();
        String url    = UPSTOX_BASE + "/option/chain?instrument_key="
                      + java.net.URLEncoder.encode(NIFTY_KEY, "UTF-8")
                      + "&expiry_date=" + expiry;

        JsonNode root = getUpstox(url, token);
        JsonNode data = root.path("data");

        double totalCallOI = 0, totalPutOI = 0;
        if (data.isArray()) {
            for (JsonNode strike : data) {
                totalCallOI += strike.path("call_options").path("market_data").path("oi").asDouble(0);
                totalPutOI  += strike.path("put_options") .path("market_data").path("oi").asDouble(0);
            }
        }

        System.out.println("[PCR] expiry=" + expiry + " CallOI=" + totalCallOI + " PutOI=" + totalPutOI);
        if (totalCallOI == 0) return 0;
        return totalPutOI / totalCallOI;
    }

    private Map<String, Object> fetchNiftyOIData() throws Exception {
        String token = upstoxTokenService.getAccessToken();
        if (token.isBlank()) throw new Exception("Upstox token not set. Visit http://localhost:8080/upstox/login");

        String expiry = getNearestThursdayExpiry();
        String url    = UPSTOX_BASE + "/option/chain?instrument_key="
                      + java.net.URLEncoder.encode(NIFTY_KEY, "UTF-8")
                      + "&expiry_date=" + expiry;

        JsonNode root = getUpstox(url, token);
        JsonNode data = root.path("data");

        long totalCallOI = 0, totalPutOI = 0, maxCallOI = 0, maxPutOI = 0;
        int  maxCallStrike = 0, maxPutStrike = 0;

        if (data.isArray()) {
            for (JsonNode strike : data) {
                int  sp      = strike.path("strike_price").asInt(0);
                long callOI  = strike.path("call_options").path("market_data").path("oi").asLong(0);
                long putOI   = strike.path("put_options") .path("market_data").path("oi").asLong(0);
                totalCallOI += callOI;
                totalPutOI  += putOI;
                if (callOI > maxCallOI) { maxCallOI = callOI; maxCallStrike = sp; }
                if (putOI  > maxPutOI)  { maxPutOI  = putOI;  maxPutStrike  = sp; }
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("totalCallOI",   totalCallOI);
        result.put("totalPutOI",    totalPutOI);
        result.put("maxCallStrike", maxCallStrike);
        result.put("maxPutStrike",  maxPutStrike);
        return result;
    }

    private double fetchVIX() throws Exception {
        JsonNode root = getNse("/api/allIndices");
        JsonNode data = root.path("data");
        if (data.isArray()) {
            for (JsonNode idx : data) {
                if ("INDIA VIX".equalsIgnoreCase(idx.path("index").asText("")))
                    return idx.path("last").asDouble(0);
            }
        }
        return 0;
    }

    private Map<String, Double> fetchFIIDIIFlows() throws Exception {
        JsonNode root = getNse("/api/fiidiiTradeReact");
        double fiiNet = 0, diiNet = 0;
        if (root.isArray()) {
            for (JsonNode entry : root) {
                String cat  = entry.path("category").asText(entry.path("name").asText("")).toUpperCase();
                double buy  = entry.path("buyValue").asDouble(entry.path("buy_value").asDouble(0));
                double sell = entry.path("sellValue").asDouble(entry.path("sell_value").asDouble(0));
                double net  = buy - sell;
                if      (cat.contains("FII") || cat.contains("FPI")) fiiNet = net;
                else if (cat.contains("DII"))                         diiNet = net;
            }
        }
        Map<String, Double> result = new HashMap<>();
        result.put("fii", fiiNet);
        result.put("dii", diiNet);
        return result;
    }

    // -------------------------------------------------------------------------
    // HTTP
    // -------------------------------------------------------------------------

    private JsonNode getNse(String path) throws Exception {
        return objectMapper.readTree(getRaw(NSE_BASE, path, null));
    }

    private JsonNode getUpstox(String fullUrl, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(fullUrl))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + token)
                .header("Accept",        "application/json")
                .header("Api-Version",   "2.0")
                .GET()
                .build();

        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() == 401)
            throw new Exception("Upstox token expired — visit http://localhost:8080/upstox/login to refresh");
        if (response.statusCode() != 200)
            throw new Exception("Upstox HTTP " + response.statusCode() + ": " + new String(response.body()));

        return objectMapper.readTree(decompress(response));
    }

    private byte[] getRaw(String base, String path, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(base + path))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent",       "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .header("Accept",           "application/json, text/plain, */*")
                .header("Accept-Language",  "en-US,en;q=0.9")
                .header("Referer",          "https://www.nseindia.com/")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("sec-fetch-dest",   "empty")
                .header("sec-fetch-mode",   "cors")
                .header("sec-fetch-site",   "same-origin")
                .GET();

        if (token != null) b.header("Authorization", "Bearer " + token);

        HttpResponse<byte[]> response = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) throw new RuntimeException("HTTP " + response.statusCode() + " for " + path);
        return decompress(response);
    }

    private byte[] decompress(HttpResponse<byte[]> response) throws Exception {
        byte[] body = response.body();
        String enc  = response.headers().firstValue("content-encoding").orElse("");
        if (enc.equalsIgnoreCase("gzip")) {
            try (InputStream gz = new GZIPInputStream(new ByteArrayInputStream(body))) {
                body = gz.readAllBytes();
            }
        } else if (enc.equalsIgnoreCase("deflate")) {
            try (InputStream inf = new InflaterInputStream(new ByteArrayInputStream(body))) {
                body = inf.readAllBytes();
            }
        }
        return body;
    }

    // -------------------------------------------------------------------------
    // HELPERS
    // -------------------------------------------------------------------------

    private String getNearestThursdayExpiry() {
        LocalDate today = LocalDate.now();
        int daysUntil = (4 - today.getDayOfWeek().getValue() + 7) % 7;
        if (daysUntil == 0 && LocalTime.now().isAfter(LocalTime.of(15, 30))) daysUntil = 7;
        return today.plusDays(daysUntil).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    }

    private String fmt2(double v)     { return String.format("%.2f", v); }
    private String fmt1(double v)     { return String.format("%.1f", v); }
    private String signedCr(double v) { return (v >= 0 ? "+" : "") + fmt2(v) + " Cr"; }
    private String timeNow()          { return LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")); }
}
//
//import java.io.ByteArrayInputStream;
//import java.io.InputStream;
//import java.net.URI;
//import java.net.http.HttpClient;
//import java.net.http.HttpRequest;
//import java.net.http.HttpResponse;
//import java.time.Duration;
//import java.time.LocalDate;
//import java.time.LocalTime;
//import java.time.format.DateTimeFormatter;
//import java.util.ArrayList;
//import java.util.HashMap;
//import java.util.List;
//import java.util.Map;
//import java.util.zip.GZIPInputStream;
//
//import com.fasterxml.jackson.databind.JsonNode;
//import com.fasterxml.jackson.databind.ObjectMapper;
//
//import lombok.RequiredArgsConstructor;
//
//import org.springframework.scheduling.annotation.Scheduled;
//import org.springframework.stereotype.Service;
//
//@Service
//@RequiredArgsConstructor
//public class MarketSentimentService {
//
//    private final TelegramService telegramService;
//    private final ObjectMapper    objectMapper = new ObjectMapper();
//
//    private static final String NSE_BASE = "https://www.nseindia.com";
//
//    private final HttpClient httpClient = HttpClient.newBuilder()
//            .connectTimeout(Duration.ofSeconds(10))
//            .followRedirects(HttpClient.Redirect.NORMAL)
//            .build();
//
//    // -------------------------------------------------------------------------
//    // SCHEDULED JOBS
//    // -------------------------------------------------------------------------
//
//    @Scheduled(cron = "0 20 9 * * MON-FRI")
//    public void morningSentimentAlert() {
//        sendSentimentSnapshot("Morning Sentiment Snapshot");
//    }
//
//    @Scheduled(cron = "0 30 12 * * MON-FRI")
//    public void middaySentimentAlert() {
//        sendSentimentSnapshot("Midday Sentiment Check");
//    }
//
//    @Scheduled(cron = "0 0 15 * * MON-FRI")
//    public void preCloseSentimentAlert() {
//        sendSentimentSnapshot("Pre-Close Sentiment Alert");
//    }
//
//    @Scheduled(cron = "0 35 15 * * MON-FRI")
//    public void eodSentimentSummary() {
//        StringBuilder sb = new StringBuilder();
//        sb.append("End-of-Day Sentiment Summary\n");
//        sb.append("------------------------\n");
//        sb.append("Date: ").append(LocalDate.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy"))).append("\n\n");
//        appendAdvanceDecline(sb);
//        appendPCR(sb);
//        appendVIX(sb);
//        appendFIIDII(sb);
//        appendNiftyOI(sb);
//        appendSentimentConclusion(sb);
//        telegramService.sendMessage(sb.toString());
//    }
//
//    @Scheduled(cron = "0 */30 9-15 * * MON-FRI")
//    public void pcrExtremeAlert() {
//        try {
//            double pcr = fetchPCR();
//            if (pcr <= 0) return;
//            if (pcr < 0.7) {
//                telegramService.sendMessage("PCR EXTREME BEARISH: " + fmt2(pcr) + " at " + timeNow());
//            } else if (pcr > 1.5) {
//                telegramService.sendMessage("PCR EXTREME BULLISH: " + fmt2(pcr) + " at " + timeNow());
//            }
//        } catch (Exception e) {
//            System.err.println("[PCR extreme alert] " + e.getMessage());
//        }
//    }
//
//    @Scheduled(cron = "0 */15 9-15 * * MON-FRI")
//    public void vixSpikeAlert() {
//        try {
//            double vix = fetchVIX();
//            if (vix > 20) {
//                telegramService.sendMessage("VIX SPIKE: " + fmt2(vix) + " at " + timeNow());
//            }
//        } catch (Exception e) {
//            System.err.println("[VIX spike alert] " + e.getMessage());
//        }
//    }
//
//    @Scheduled(cron = "0 0 10,14 * * MON-FRI")
//    public void breadthExtremeAlert() {
//        try {
//            Map<String, Object> adData = fetchAdvanceDeclineData();
//            int advances = (int) adData.getOrDefault("advances", 0);
//            int declines = (int) adData.getOrDefault("declines", 0);
//            int total    = advances + declines;
//            if (total == 0) return;
//            double advPct = (advances * 100.0) / total;
//            if (advPct >= 80) {
//                telegramService.sendMessage("STRONG BREADTH: " + fmt1(advPct) + "% advancing at " + timeNow());
//            } else if (advPct <= 20) {
//                telegramService.sendMessage("WEAK BREADTH: only " + fmt1(advPct) + "% advancing at " + timeNow());
//            }
//        } catch (Exception e) {
//            System.err.println("[Breadth extreme alert] " + e.getMessage());
//        }
//    }
//
//    // -------------------------------------------------------------------------
//    // SNAPSHOT BUILDER
//    // -------------------------------------------------------------------------
//
//    private void sendSentimentSnapshot(String title) {
//        StringBuilder sb = new StringBuilder();
//        sb.append(title).append("\n");
//        sb.append("------------------------\n");
//        sb.append("Time: ").append(timeNow()).append("\n\n");
//        appendAdvanceDecline(sb);
//        appendPCR(sb);
//        appendVIX(sb);
//        telegramService.sendMessage(sb.toString());
//    }
//
//    // -------------------------------------------------------------------------
//    // SECTION BUILDERS
//    // -------------------------------------------------------------------------
//
//    private void appendAdvanceDecline(StringBuilder sb) {
//        try {
//            Map<String, Object> adData = fetchAdvanceDeclineData();
//            int advances  = (int) adData.getOrDefault("advances",  0);
//            int declines  = (int) adData.getOrDefault("declines",  0);
//            int unchanged = (int) adData.getOrDefault("unchanged", 0);
//            int total     = advances + declines;
//            double adRatio   = (declines > 0) ? (double) advances / declines : advances;
//            double advPct    = (total > 0) ? (advances * 100.0) / total : 0;
//            String sentiment = adRatio >= 1.5 ? "Bullish Breadth" : adRatio <= 0.7 ? "Bearish Breadth" : "Neutral Breadth";
//            sb.append("Advance/Decline Ratio\n");
//            sb.append("Advances:  ").append(advances).append("\n");
//            sb.append("Declines:  ").append(declines).append("\n");
//            sb.append("Unchanged: ").append(unchanged).append("\n");
//            sb.append("A/D Ratio: ").append(fmt2(adRatio)).append(" (").append(fmt1(advPct)).append("% advancing)\n");
//            sb.append("Signal:    ").append(sentiment).append("\n\n");
//        } catch (Exception e) {
//            sb.append("A/D Ratio: Unavailable\n\n");
//            System.err.println("[AD fetch] " + e.getMessage());
//        }
//    }
//
//    private void appendPCR(StringBuilder sb) {
//        try {
//            double pcr    = fetchPCR();
//            String signal = pcr < 0.7  ? "Extreme Bearish (Contrarian Buy?)"
//                          : pcr < 0.85 ? "Bearish Sentiment"
//                          : pcr < 1.05 ? "Neutral"
//                          : pcr < 1.3  ? "Bullish Sentiment"
//                          :              "Extreme Bullish (Contrarian Sell?)";
//            sb.append("Put-Call Ratio (PCR)\n");
//            sb.append("PCR (OI): ").append(fmt2(pcr)).append("\n");
//            sb.append("Signal:   ").append(signal).append("\n\n");
//        } catch (Exception e) {
//            sb.append("PCR: Unavailable\n\n");
//            System.err.println("[PCR fetch] " + e.getMessage());
//        }
//    }
//
//    private void appendVIX(StringBuilder sb) {
//        try {
//            double vix    = fetchVIX();
//            String signal = vix < 12 ? "Extreme Complacency"
//                          : vix < 16 ? "Low Volatility"
//                          : vix < 20 ? "Moderate Volatility"
//                          : vix < 25 ? "High Fear"
//                          :            "Extreme Fear";
//            sb.append("India VIX\n");
//            sb.append("VIX:    ").append(fmt2(vix)).append("\n");
//            sb.append("Signal: ").append(signal).append("\n\n");
//        } catch (Exception e) {
//            sb.append("India VIX: Unavailable\n\n");
//            System.err.println("[VIX fetch] " + e.getMessage());
//        }
//    }
//
//    private void appendFIIDII(StringBuilder sb) {
//        try {
//            Map<String, Double> flows = fetchFIIDIIFlows();
//            double fii = flows.getOrDefault("fii", 0.0);
//            double dii = flows.getOrDefault("dii", 0.0);
//            double net = fii + dii;
//            String signal = (fii > 0 && dii > 0) ? "Both FII+DII Buying"
//                          : (fii < 0 && dii < 0) ? "Both FII+DII Selling"
//                          : (fii > 0)             ? "FII Buying, DII Mixed"
//                          :                         "DII Buying, FII Selling";
//            sb.append("FII / DII Activity\n");
//            sb.append("FII Net:  ").append(signedCr(fii)).append("\n");
//            sb.append("DII Net:  ").append(signedCr(dii)).append("\n");
//            sb.append("Combined: ").append(signedCr(net)).append("\n");
//            sb.append("Signal:   ").append(signal).append("\n\n");
//        } catch (Exception e) {
//            sb.append("FII/DII: Unavailable\n\n");
//            System.err.println("[FII/DII fetch] " + e.getMessage());
//        }
//    }
//
//    private void appendNiftyOI(StringBuilder sb) {
//        try {
//            Map<String, Object> oiData = fetchNiftyOIData();
//            long totalCallOI   = (long) oiData.getOrDefault("totalCallOI",   0L);
//            long totalPutOI    = (long) oiData.getOrDefault("totalPutOI",    0L);
//            int  maxCallStrike = (int)  oiData.getOrDefault("maxCallStrike", 0);
//            int  maxPutStrike  = (int)  oiData.getOrDefault("maxPutStrike",  0);
//            double pcr         = (totalCallOI > 0) ? (double) totalPutOI / totalCallOI : 0;
//            sb.append("Nifty Options OI\n");
//            sb.append("Total Call OI:       ").append(String.format("%,d", totalCallOI)).append("\n");
//            sb.append("Total Put OI:        ").append(String.format("%,d", totalPutOI)).append("\n");
//            sb.append("Call Wall (resist):  ").append(maxCallStrike).append("\n");
//            sb.append("Put Floor (support): ").append(maxPutStrike).append("\n");
//            sb.append("PCR (OI):            ").append(fmt2(pcr)).append("\n\n");
//        } catch (Exception e) {
//            sb.append("Nifty OI: Unavailable\n\n");
//            System.err.println("[Nifty OI fetch] " + e.getMessage());
//        }
//    }
//
//    private void appendSentimentConclusion(StringBuilder sb) {
//        sb.append("------------------------\n");
//        sb.append("Guide: PCR<0.7=possible bottom | PCR>1.5=possible top | VIX>20=high fear\n");
//    }
//
//    // -------------------------------------------------------------------------
//    // DATA FETCHERS
//    // -------------------------------------------------------------------------
//
//    private Map<String, Object> fetchAdvanceDeclineData() throws Exception {
//        JsonNode root = get("/api/equity-stockIndices?index=NIFTY%20500");
//        JsonNode data = root.path("data");
//        int advances = 0, declines = 0, unchanged = 0;
//        if (data.isArray()) {
//            for (JsonNode stock : data) {
//                double change = stock.path("pChange").asDouble(0);
//                if      (change > 0) advances++;
//                else if (change < 0) declines++;
//                else                 unchanged++;
//            }
//        }
//        Map<String, Object> result = new HashMap<>();
//        result.put("advances",  advances);
//        result.put("declines",  declines);
//        result.put("unchanged", unchanged);
//        return result;
//    }
//
//    private double fetchPCR() throws Exception {
//        JsonNode root = get("/api/option-chain-indices?symbol=NIFTY");
//
//        // DEBUG: log top-level keys and structure so we can find the right fields
//        List<String> topKeys = new ArrayList<>();
//        root.fieldNames().forEachRemaining(topKeys::add);
//        System.out.println("[PCR DEBUG] Top keys: " + topKeys);
//
//        JsonNode filtered = root.path("filtered");
//        System.out.println("[PCR DEBUG] filtered=" + filtered.toString().substring(0, Math.min(300, filtered.toString().length())));
//
//        JsonNode records = root.path("records").path("data");
//        if (records.isArray() && records.size() > 0) {
//            System.out.println("[PCR DEBUG] records[0]=" + records.get(0).toString().substring(0, Math.min(300, records.get(0).toString().length())));
//        }
//
//        // Try filtered.CE.totOI / filtered.PE.totOI
//        double totalCallOI = filtered.path("CE").path("totOI").asDouble(0);
//        double totalPutOI  = filtered.path("PE").path("totOI").asDouble(0);
//
//        // Fallback: sum records
//        if (totalCallOI == 0 && records.isArray()) {
//            for (JsonNode item : records) {
//                JsonNode ce = item.path("CE");
//                if (!ce.isMissingNode() && !ce.isNull()) {
//                    double oi = ce.path("openInterest").asDouble(0);
//                    if (oi == 0) oi = ce.path("oi").asDouble(0);
//                    totalCallOI += oi;
//                }
//                JsonNode pe = item.path("PE");
//                if (!pe.isMissingNode() && !pe.isNull()) {
//                    double oi = pe.path("openInterest").asDouble(0);
//                    if (oi == 0) oi = pe.path("oi").asDouble(0);
//                    totalPutOI += oi;
//                }
//            }
//        }
//
//        System.out.println("[PCR] Final Call OI=" + totalCallOI + " Put OI=" + totalPutOI);
//        if (totalCallOI == 0) return 0;
//        return totalPutOI / totalCallOI;
//    }
//
//    private double fetchVIX() throws Exception {
//        JsonNode root = get("/api/allIndices");
//        JsonNode data = root.path("data");
//        if (data.isArray()) {
//            for (JsonNode index : data) {
//                if ("INDIA VIX".equalsIgnoreCase(index.path("index").asText(""))) {
//                    return index.path("last").asDouble(0);
//                }
//            }
//        }
//        return 0;
//    }
//
//    private Map<String, Double> fetchFIIDIIFlows() throws Exception {
//        JsonNode root = get("/api/fiidiiTradeReact");
//        double fiiNet = 0, diiNet = 0;
//        if (root.isArray()) {
//            for (JsonNode entry : root) {
//                String category = entry.path("category").asText(entry.path("name").asText("")).toUpperCase();
//                double buy  = entry.path("buyValue").asDouble(entry.path("buy_value").asDouble(0));
//                double sell = entry.path("sellValue").asDouble(entry.path("sell_value").asDouble(0));
//                double net  = buy - sell;
//                if      (category.contains("FII") || category.contains("FPI")) fiiNet = net;
//                else if (category.contains("DII"))                              diiNet = net;
//            }
//        }
//        Map<String, Double> result = new HashMap<>();
//        result.put("fii", fiiNet);
//        result.put("dii", diiNet);
//        return result;
//    }
//
//    private Map<String, Object> fetchNiftyOIData() throws Exception {
//        JsonNode root    = get("/api/option-chain-indices?symbol=NIFTY");
//        JsonNode records = root.path("records").path("data");
//        long totalCallOI = 0, totalPutOI = 0;
//        long maxCallOI   = 0, maxPutOI   = 0;
//        int  maxCallStrike = 0, maxPutStrike = 0;
//        if (records.isArray()) {
//            for (JsonNode item : records) {
//                int strike = item.path("strikePrice").asInt(0);
//                JsonNode ce = item.path("CE");
//                if (!ce.isMissingNode() && !ce.isNull()) {
//                    long oi = ce.path("openInterest").asLong(0);
//                    if (oi == 0) oi = ce.path("oi").asLong(0);
//                    totalCallOI += oi;
//                    if (oi > maxCallOI) { maxCallOI = oi; maxCallStrike = strike; }
//                }
//                JsonNode pe = item.path("PE");
//                if (!pe.isMissingNode() && !pe.isNull()) {
//                    long oi = pe.path("openInterest").asLong(0);
//                    if (oi == 0) oi = pe.path("oi").asLong(0);
//                    totalPutOI += oi;
//                    if (oi > maxPutOI) { maxPutOI = oi; maxPutStrike = strike; }
//                }
//            }
//        }
//        Map<String, Object> result = new HashMap<>();
//        result.put("totalCallOI",   totalCallOI);
//        result.put("totalPutOI",    totalPutOI);
//        result.put("maxCallStrike", maxCallStrike);
//        result.put("maxPutStrike",  maxPutStrike);
//        return result;
//    }
//
//    // -------------------------------------------------------------------------
//    // HTTP CORE
//    // -------------------------------------------------------------------------
//
//    private JsonNode get(String path) throws Exception {
//        HttpRequest request = HttpRequest.newBuilder()
//                .uri(URI.create(NSE_BASE + path))
//                .timeout(Duration.ofSeconds(15))
//                .header("User-Agent",       "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
//                .header("Accept",           "application/json, text/plain, */*")
//                .header("Accept-Language",  "en-US,en;q=0.9")
//                .header("Referer",          "https://www.nseindia.com/")
//                .header("X-Requested-With", "XMLHttpRequest")
//                .header("sec-fetch-dest",   "empty")
//                .header("sec-fetch-mode",   "cors")
//                .header("sec-fetch-site",   "same-origin")
//                .GET()
//                .build();
//
//        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
//
//        if (response.statusCode() != 200) {
//            throw new RuntimeException("HTTP " + response.statusCode() + " for " + path);
//        }
//
//        byte[] body = response.body();
//
//        // Decompress if gzip
//        String contentEncoding = response.headers().firstValue("content-encoding").orElse("");
//        if (contentEncoding.equalsIgnoreCase("gzip")) {
//            try (InputStream gzipStream = new GZIPInputStream(new ByteArrayInputStream(body))) {
//                body = gzipStream.readAllBytes();
//            }
//        }
//
//        return objectMapper.readTree(body);
//    }
//
//    // -------------------------------------------------------------------------
//    // HELPERS
//    // -------------------------------------------------------------------------
//
//    private String fmt2(double v)     { return String.format("%.2f", v); }
//    private String fmt1(double v)     { return String.format("%.1f", v); }
//    private String signedCr(double v) { return (v >= 0 ? "+" : "") + fmt2(v) + " Cr"; }
//    private String timeNow()          { return LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")); }
//}