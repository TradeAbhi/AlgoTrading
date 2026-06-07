package com.trading.algo.usmarket;


import com.trading.algo.entity.UsCandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Fetches US market data using Twelve Data API.
 *
 * Why Twelve Data:
 *   - Free tier: 800 API credits/day (weekly candle = 2 credits, daily = 1 credit)
 *   - Server-side friendly — no browser simulation needed, standard REST API
 *   - Covers NYSE + NASDAQ, clean JSON, reliable uptime
 *   - Free API key at: https://twelvedata.com (takes 30 seconds to register)
 *
 * application.yml:
 *   twelvedata:
 *     api-key: YOUR_FREE_API_KEY_HERE
 *
 * 52-week high list:
 *   Finviz and Yahoo Finance both block server-side requests (HTTP 403).
 *   Instead we use a static S&P 500 CSV (sp500.csv) — same approach as
 *   ind_nifty500list.csv — and compute 52-week high status ourselves from
 *   Twelve Data's weekly candle data during the seed step.
 *   This is more reliable and doesn't depend on any screener staying accessible.
 *
 * Credit budget per week (Mon seed):
 *   500 tickers × weekly candle (2 credits) = 1000 credits
 *   500 tickers × daily candle  (1 credit)  = 500 credits
 *   Total per day: ~1500 credits → need paid plan ($8/mo for 800 credits/min)
 *   OR use a curated 200-ticker watchlist (Nasdaq 100 + high-volume NYSE) → fits free tier
 *
 * Free tier approach: load from sp500_top200.csv (top 200 by market cap)
 * Paid approach:      load full sp500.csv (505 tickers)
 */
@Service
public class UsMarketDataService {

    private static final Logger log = LoggerFactory.getLogger(UsMarketDataService.class);

    private static final String TWELVE_DATA_BASE = "https://api.twelvedata.com";

    // Twelve Data requires a standard User-Agent
    private static final String USER_AGENT = "AlgoTrading/1.0";
    private static final long MIN_REQUEST_INTERVAL_MS = 8_000L;

    @Value("${twelvedata.api-key:}")
    private String apiKey;

    private final RestTemplate restTemplate;
    private final Object rateLimitLock = new Object();
    private long lastRequestAtMs = 0L;

    public UsMarketDataService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    // ── Weekly OHLCV ──────────────────────────────────────────────────────────
    /**
     * Fetches last N weekly candles for a ticker using Twelve Data.
     * Returns oldest → newest order.
     *
     * Twelve Data weekly endpoint:
     * GET /time_series?symbol=AAPL&interval=1week&outputsize=3&apikey=KEY
     *
     * Response:
     * {
     *   "meta": { "symbol": "AAPL", "interval": "1week", ... },
     *   "values": [
     *     { "datetime": "2024-06-03", "open": "...", "high": "...",
     *       "low": "...", "close": "...", "volume": "..." },
     *     ...
     *   ],
     *   "status": "ok"
     * }
     * Values returned newest first — we reverse to get oldest first.
     */
    @SuppressWarnings("unchecked")
    public List<UsCandle> fetchWeeklyCandles(String ticker, int weeks) {
        if (!hasApiKey()) {
            log.warn("[US] Twelve Data API key not configured; skipping weekly fetch for {}", ticker);
            return Collections.emptyList();
        }

        try {
            String url = String.format(
                    "%s/time_series?symbol=%s&interval=1week&outputsize=%d&apikey=%s",
                    TWELVE_DATA_BASE, ticker, weeks, apiKey);

            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", USER_AGENT);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            waitForTwelveDataSlot();
            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, Map.class);

            return parseTwelveDataCandles(response.getBody(), ticker);

        } catch (Exception e) {
            log.error("[US] fetchWeeklyCandles failed for {}: {}", ticker, e.getMessage());
            return Collections.emptyList();
        }
    }

    // ── Daily OHLCV ───────────────────────────────────────────────────────────
    /**
     * Fetches last N daily candles for a ticker using Twelve Data.
     * Returns oldest → newest order.
     */
    @SuppressWarnings("unchecked")
    public List<UsCandle> fetchDailyCandles(String ticker, int days) {
        if (!hasApiKey()) {
            log.warn("[US] Twelve Data API key not configured; skipping daily fetch for {}", ticker);
            return Collections.emptyList();
        }

        try {
            String url = String.format(
                    "%s/time_series?symbol=%s&interval=1day&outputsize=%d&apikey=%s",
                    TWELVE_DATA_BASE, ticker, days, apiKey);

            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", USER_AGENT);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            waitForTwelveDataSlot();
            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, Map.class);

            return parseTwelveDataCandles(response.getBody(), ticker);

        } catch (Exception e) {
            log.error("[US] fetchDailyCandles failed for {}: {}", ticker, e.getMessage());
            return Collections.emptyList();
        }
    }

    // ── 52-Week High Check ────────────────────────────────────────────────────
    /**
     * Checks if a ticker is currently at or near its 52-week high
     * by comparing this week's high against the highest high of the last 52 weeks.
     *
     * Called during Monday seed — uses the weekly candle data already fetched.
     * "Near 52-week high" = within 2% of the 52-week high.
     *
     * @param weeklyCandles  list of weekly candles (oldest → newest), at least 52 entries
     * @return true if the latest week's high is within 2% of the 52-week high
     */
    public boolean is52WeekHigh(List<UsCandle> weeklyCandles) {
        if (weeklyCandles.size() < 2) return false;

        // Latest completed week = second from end (last entry may be current incomplete week)
        UsCandle latestWeek = weeklyCandles.get(weeklyCandles.size() - 2);

        // 52-week high = max high across all candles in the list
        double high52wk = weeklyCandles.stream()
                .mapToDouble(UsCandle::getHigh)
                .max()
                .orElse(0);

        if (high52wk <= 0) return false;

        // Within 2% of 52-week high = at or near new high
        double distancePct = ((high52wk - latestWeek.getHigh()) / high52wk) * 100.0;
        return distancePct <= 2.0;
    }

    // ── Twelve Data response parser ───────────────────────────────────────────
    @SuppressWarnings("unchecked")
    private List<UsCandle> parseTwelveDataCandles(Map<String, Object> body, String ticker) {
        if (body == null) return Collections.emptyList();

        // Check for API error
        String status = (String) body.get("status");
        if (!"ok".equals(status)) {
            String code    = String.valueOf(body.getOrDefault("code", ""));
            String message = String.valueOf(body.getOrDefault("message", ""));
            log.warn("[US] Twelve Data error for {}: {} — {}", ticker, code, message);
            return Collections.emptyList();
        }

        List<Map<String, Object>> values = (List<Map<String, Object>>) body.get("values");
        if (values == null || values.isEmpty()) return Collections.emptyList();

        List<UsCandle> candles = new ArrayList<>();
        for (Map<String, Object> v : values) {
            try {
                LocalDate date = LocalDate.parse((String) v.get("datetime"));
                candles.add(new UsCandle(
                        ticker,
                        date,
                        parseDouble(v.get("open")),
                        parseDouble(v.get("high")),
                        parseDouble(v.get("low")),
                        parseDouble(v.get("close")),
                        parseLong(v.get("volume"))
                ));
            } catch (Exception e) {
                log.debug("[US] Skipping malformed candle for {}: {}", ticker, e.getMessage());
            }
        }

        // Twelve Data returns newest first — reverse to oldest first
        Collections.reverse(candles);
        return candles;
    }

    // ── Type helpers ──────────────────────────────────────────────────────────
    private boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    private void waitForTwelveDataSlot() throws InterruptedException {
        synchronized (rateLimitLock) {
            long now = System.currentTimeMillis();
            long waitMs = MIN_REQUEST_INTERVAL_MS - (now - lastRequestAtMs);
            if (waitMs > 0) {
                Thread.sleep(waitMs);
            }
            lastRequestAtMs = System.currentTimeMillis();
        }
    }

    private double parseDouble(Object val) {
        if (val == null) return 0;
        return val instanceof Number ? ((Number) val).doubleValue()
                : Double.parseDouble(val.toString());
    }

    private long parseLong(Object val) {
        if (val == null) return 0;
        return val instanceof Number ? ((Number) val).longValue()
                : Long.parseLong(val.toString());
    }
}
