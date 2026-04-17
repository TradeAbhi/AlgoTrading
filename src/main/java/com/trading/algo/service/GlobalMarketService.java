package com.trading.algo.service;


import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import jakarta.annotation.PostConstruct;

import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * GlobalMarketService
 *
 * Fetches and sends Telegram alerts for global market cues:
 *   ① S&P 500       — US equity trend
 *   ② Nasdaq        — US tech trend
 *   ③ US 10Y Yield  — Bond market (rising = bearish equities)
 *   ④ DXY           — Dollar Index (strong dollar = FII outflows from India)
 *   ⑤ Crude Oil     — Brent (high crude = inflationary pressure on India)
 *
 * Data source: Yahoo Finance API (free, no auth needed)
 *
 * Add to application.properties:
 *   yahoo.finance.api.key=   (optional — leave blank for free tier)
 */
@Service
@RequiredArgsConstructor
public class GlobalMarketService {

    private final TelegramService telegramService;
    private final ObjectMapper    objectMapper = new ObjectMapper();

    // Yahoo Finance quote endpoint — free, no API key needed
    private static final String YAHOO_QUOTE = "https://query1.finance.yahoo.com/v8/finance/chart/";

    // Symbols
    private static final String SP500   = "%5EGSPC";   // ^GSPC
    private static final String NASDAQ  = "%5EIXIC";   // ^IXIC
    private static final String US10Y   = "%5ETNX";    // ^TNX  (10-year yield)
    private static final String DXY     = "DX-Y.NYB";  // Dollar Index
    private static final String CRUDE   = "BZ%3DF";    // BZ=F  (Brent Crude)
    private static final String SGX_NIFTY = "^NSEI";   // Nifty (for overnight gap context)

    private HttpClient httpClient;

    @PostConstruct
    public void init() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // -------------------------------------------------------------------------
    // SCHEDULED JOBS
    // -------------------------------------------------------------------------

    /**
     * Pre-market global cues — 8:45 AM every weekday
     * Key alert before Indian market opens at 9:15 AM
     */
    @Scheduled(cron = "0 45 8 * * MON-FRI")
    public void preMarketGlobalCues() {
        sendGlobalSnapshot("Pre-Market Global Cues");
    }

    /**
     * Post US market close summary — 6:30 AM IST (US markets close ~4 AM IST)
     * Shows how US closed overnight
     */
    @Scheduled(cron = "0 30 6 * * TUE-SAT")
    public void usMarketCloseSummary() {
        sendGlobalSnapshot("US Market Close Summary");
    }

    /**
     * Intraday global check — 12:00 PM
     * Checks if any major global moves happening during Indian market hours
     */
    @Scheduled(cron = "0 0 12 * * MON-FRI")
    public void middayGlobalCheck() {
        sendGlobalSnapshot("Midday Global Check");
    }

    /**
     * Extreme move alert — checks every 30 min during market hours
     * Only fires when a major threshold is breached
     */
    @Scheduled(cron = "0 */30 6-16 * * MON-FRI")
    public void extremeMoveAlert() {
        try {
            StringBuilder alerts = new StringBuilder();

            double sp500Change  = fetchPctChange(SP500);
            double nasdaqChange = fetchPctChange(NASDAQ);
            double crudeChange  = fetchPctChange(CRUDE);
            double dxyChange    = fetchPctChange(DXY);
            double yieldValue   = fetchLastPrice(US10Y);

            // S&P 500 drop > 1.5% — significant bearish signal for India
            if (sp500Change <= -1.5) {
                alerts.append("S&P 500 DOWN ").append(fmt2(sp500Change)).append("% — Expect bearish open in India\n");
            } else if (sp500Change >= 1.5) {
                alerts.append("S&P 500 UP ").append(fmt2(sp500Change)).append("% — Bullish cue for India\n");
            }

            // Nasdaq move > 2%
            if (nasdaqChange <= -2.0) {
                alerts.append("Nasdaq DOWN ").append(fmt2(nasdaqChange)).append("% — Tech selloff, watch IT stocks\n");
            } else if (nasdaqChange >= 2.0) {
                alerts.append("Nasdaq UP ").append(fmt2(nasdaqChange)).append("% — IT stocks may rally\n");
            }

            // Crude spike > 2% — inflationary, bad for India
            if (crudeChange >= 2.0) {
                alerts.append("Crude OIL UP ").append(fmt2(crudeChange)).append("% — Inflationary pressure, watch OMCs\n");
            } else if (crudeChange <= -2.0) {
                alerts.append("Crude OIL DOWN ").append(fmt2(crudeChange)).append("% — Positive for India macro\n");
            }

            // DXY spike > 0.5% — FII outflow risk
            if (dxyChange >= 0.5) {
                alerts.append("DXY (Dollar) UP ").append(fmt2(dxyChange)).append("% — FII outflow risk for India\n");
            }

            // 10Y yield > 4.5% is historically bearish for equities
            if (yieldValue >= 4.5) {
                alerts.append("US 10Y Yield at ").append(fmt2(yieldValue)).append("% — Elevated, risk-off mood\n");
            }

            if (alerts.length() > 0) {
                telegramService.sendMessage(
                    "GLOBAL MARKET ALERT\n" +
                    "------------------------\n" +
                    alerts.toString() +
                    "Time: " + timeNow()
                );
            }

        } catch (Exception e) {
            System.err.println("[Global extreme alert] " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // SNAPSHOT BUILDER
    // -------------------------------------------------------------------------

    private void sendGlobalSnapshot(String title) {
        StringBuilder sb = new StringBuilder();
        sb.append(title).append("\n");
        sb.append("------------------------\n");
        sb.append("Time: ").append(timeNow()).append("\n\n");

        appendSP500(sb);
        appendNasdaq(sb);
        appendBondYield(sb);
        appendDXY(sb);
        appendCrude(sb);
        appendOverallBias(sb);

        telegramService.sendMessage(sb.toString());
    }

    // -------------------------------------------------------------------------
    // SECTION BUILDERS
    // -------------------------------------------------------------------------

    private void appendSP500(StringBuilder sb) {
        try {
            double price  = fetchLastPrice(SP500);
            double change = fetchPctChange(SP500);
            String signal = change >= 0.5  ? "Bullish"
                          : change <= -0.5 ? "Bearish"
                          :                  "Flat";
            sb.append("S&P 500\n");
            sb.append("Price:  ").append(fmt2(price)).append("\n");
            sb.append("Change: ").append(signedPct(change)).append("\n");
            sb.append("Signal: ").append(signal).append("\n\n");
        } catch (Exception e) {
            sb.append("S&P 500: Unavailable\n\n");
            System.err.println("[SP500] " + e.getMessage());
        }
    }

    private void appendNasdaq(StringBuilder sb) {
        try {
            double price  = fetchLastPrice(NASDAQ);
            double change = fetchPctChange(NASDAQ);
            String signal = change >= 0.5  ? "Bullish (IT stocks may rally)"
                          : change <= -0.5 ? "Bearish (watch IT/tech stocks)"
                          :                  "Flat";
            sb.append("Nasdaq\n");
            sb.append("Price:  ").append(fmt2(price)).append("\n");
            sb.append("Change: ").append(signedPct(change)).append("\n");
            sb.append("Signal: ").append(signal).append("\n\n");
        } catch (Exception e) {
            sb.append("Nasdaq: Unavailable\n\n");
            System.err.println("[Nasdaq] " + e.getMessage());
        }
    }

    private void appendBondYield(StringBuilder sb) {
        try {
            double yield  = fetchLastPrice(US10Y);
            double change = fetchPctChange(US10Y);
            String signal = yield >= 4.5  ? "High — Risk-off, bearish for equities"
                          : yield >= 4.0  ? "Elevated — Watch for equity pressure"
                          : yield >= 3.5  ? "Moderate — Neutral"
                          :                 "Low — Risk-on, bullish for equities";
            sb.append("US 10-Year Bond Yield\n");
            sb.append("Yield:  ").append(fmt2(yield)).append("%\n");
            sb.append("Change: ").append(signedPct(change)).append("\n");
            sb.append("Signal: ").append(signal).append("\n\n");
        } catch (Exception e) {
            sb.append("US 10Y Yield: Unavailable\n\n");
            System.err.println("[US10Y] " + e.getMessage());
        }
    }

    private void appendDXY(StringBuilder sb) {
        try {
            double price  = fetchLastPrice(DXY);
            double change = fetchPctChange(DXY);
            String signal = change >= 0.3  ? "Strengthening — FII outflow risk from India"
                          : change <= -0.3 ? "Weakening — FII inflow positive for India"
                          :                  "Stable";
            sb.append("Dollar Index (DXY)\n");
            sb.append("Price:  ").append(fmt2(price)).append("\n");
            sb.append("Change: ").append(signedPct(change)).append("\n");
            sb.append("Signal: ").append(signal).append("\n\n");
        } catch (Exception e) {
            sb.append("DXY: Unavailable\n\n");
            System.err.println("[DXY] " + e.getMessage());
        }
    }

    private void appendCrude(StringBuilder sb) {
        try {
            double price  = fetchLastPrice(CRUDE);
            double change = fetchPctChange(CRUDE);
            String signal = price >= 90   ? "Very High — Strong inflationary pressure"
                          : price >= 80   ? "High — Negative for India (import cost)"
                          : price >= 70   ? "Moderate — Manageable for India"
                          :                 "Low — Positive for India macro";
            sb.append("Brent Crude Oil\n");
            sb.append("Price:  $").append(fmt2(price)).append("\n");
            sb.append("Change: ").append(signedPct(change)).append("\n");
            sb.append("Signal: ").append(signal).append("\n\n");
        } catch (Exception e) {
            sb.append("Crude Oil: Unavailable\n\n");
            System.err.println("[Crude] " + e.getMessage());
        }
    }

    /**
     * Combines all signals to give a net India market bias.
     */
    private void appendOverallBias(StringBuilder sb) {
        try {
            double sp500Change  = fetchPctChange(SP500);
            double nasdaqChange = fetchPctChange(NASDAQ);
            double dxyChange    = fetchPctChange(DXY);
            double crudeChange  = fetchPctChange(CRUDE);
            double yield        = fetchLastPrice(US10Y);

            int bullPoints = 0, bearPoints = 0;

            if (sp500Change  >= 0.5)  bullPoints++; else if (sp500Change  <= -0.5) bearPoints++;
            if (nasdaqChange >= 0.5)  bullPoints++; else if (nasdaqChange <= -0.5) bearPoints++;
            if (dxyChange    <= -0.3) bullPoints++; else if (dxyChange    >= 0.3)  bearPoints++;
            if (crudeChange  <= -1.0) bullPoints++; else if (crudeChange  >= 1.0)  bearPoints++;
            if (yield        <= 4.0)  bullPoints++; else if (yield        >= 4.5)  bearPoints++;

            String bias;
            if      (bullPoints >= 4) bias = "STRONGLY BULLISH for India";
            else if (bullPoints >= 3) bias = "BULLISH for India";
            else if (bearPoints >= 4) bias = "STRONGLY BEARISH for India";
            else if (bearPoints >= 3) bias = "BEARISH for India";
            else if (bullPoints > bearPoints) bias = "MILDLY BULLISH for India";
            else if (bearPoints > bullPoints) bias = "MILDLY BEARISH for India";
            else                              bias = "NEUTRAL / MIXED";

            sb.append("------------------------\n");
            sb.append("Overall Global Bias: ").append(bias).append("\n");
            sb.append("(Bull signals: ").append(bullPoints)
              .append(" | Bear signals: ").append(bearPoints).append(")\n");

        } catch (Exception e) {
            sb.append("Overall Bias: Unavailable\n");
        }
    }

    // -------------------------------------------------------------------------
    // DATA FETCHERS — Yahoo Finance
    // -------------------------------------------------------------------------

    private double fetchLastPrice(String symbol) throws Exception {
        JsonNode chart = fetchYahoo(symbol);
        return chart.path("meta").path("regularMarketPrice").asDouble(0);
    }

    private double fetchPctChange(String symbol) throws Exception {
        JsonNode chart = fetchYahoo(symbol);
        double price    = chart.path("meta").path("regularMarketPrice").asDouble(0);
        double prevClose = chart.path("meta").path("chartPreviousClose").asDouble(0);
        if (prevClose == 0) return 0;
        return ((price - prevClose) / prevClose) * 100;
    }

    /**
     * Yahoo Finance chart API — free, no auth needed.
     * Returns the "chart.result[0]" node.
     */
    private JsonNode fetchYahoo(String symbol) throws Exception {
        String url = YAHOO_QUOTE + symbol + "?interval=1d&range=1d";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent",      "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("Accept",          "application/json")
                .header("Accept-Language", "en-US,en;q=0.9")
                .GET()
                .build();

        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() != 200)
            throw new Exception("Yahoo HTTP " + response.statusCode() + " for " + symbol);

        byte[] body = decompress(response);
        JsonNode root = objectMapper.readTree(body);

        JsonNode result = root.path("chart").path("result");
        if (!result.isArray() || result.size() == 0)
            throw new Exception("No data for symbol: " + symbol);

        return result.get(0);
    }

    // -------------------------------------------------------------------------
    // HTTP
    // -------------------------------------------------------------------------

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

    private String fmt2(double v)      { return String.format("%.2f", v); }
    private String signedPct(double v) { return (v >= 0 ? "+" : "") + String.format("%.2f", v) + "%"; }
    private String timeNow()           { return LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")); }
}