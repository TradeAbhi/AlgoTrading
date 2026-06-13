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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fetches US market data.
 *
 * Primary source:  Yahoo Finance (YahooFinanceService) — free, no API key, fast
 * Fallback source: Twelve Data API — used if Yahoo returns empty/error
 *
 * Twelve Data free tier limits (preserved for fallback):
 *   - 8 credits/minute, 800 credits/day
 *   - Weekly candle = 2 credits/ticker → wait 15s between calls
 *   - Daily candle  = 1 credit/ticker  → wait 8s between calls
 *
 * Seed time with Yahoo primary:
 *   239 tickers × 200ms = ~48 seconds (vs ~36 min with Twelve Data only)
 */
@Service
public class UsMarketDataService {

    private static final Logger log = LoggerFactory.getLogger(UsMarketDataService.class);

    // Commented out Twelve Data constants and API key as per request
    /*
    private static final String TWELVE_DATA_BASE = "https://api.twelvedata.com";
    private static final String USER_AGENT       = "AlgoTrading/1.0";

    // Twelve Data rate limits (fallback)
    private static final long WEEKLY_INTERVAL_MS = 15_000L;
    private static final long DAILY_INTERVAL_MS  = 8_000L;

    @Value("${twelvedata.api-key:}")
    private String apiKey;
    */

    private final RestTemplate        restTemplate;
    private final YahooFinanceService yahooFinanceService;
    // Commented out Twelve Data rate limiting lock
    /*
    private final Object              lock = new Object();
    private long lastRequestAtMs = 0L;
    */

    public UsMarketDataService(RestTemplate restTemplate,
                               YahooFinanceService yahooFinanceService) {
        this.restTemplate        = restTemplate;
        this.yahooFinanceService = yahooFinanceService;
    }

    // ── Batch fetch — Yahoo primary, Twelve Data fallback (fallback logic commented out) ────────────────────

    public Map<String, List<UsCandle>> fetchWeeklyBatch(List<String> tickers, int weeks) {
        log.info("[US] fetchWeeklyBatch: {} tickers — trying Yahoo Finance first", tickers.size());
        Map<String, List<UsCandle>> result = yahooFinanceService.fetchWeeklyBatch(tickers, weeks);

        // Fallback: any ticker Yahoo missed → try Twelve Data (commented out)
        /*
        List<String> missed = new ArrayList<>();
        for (String t : tickers) {
            if (!result.containsKey(t) || result.get(t).isEmpty()) missed.add(t);
        }
        if (!missed.isEmpty() && hasApiKey()) {
            log.info("[US] fetchWeeklyBatch: {} tickers missed by Yahoo — falling back to Twelve Data", missed.size());
            for (String ticker : missed) {
                try {
                    waitMs(WEEKLY_INTERVAL_MS);
                    List<UsCandle> candles = fetchTwelveDataCandles(ticker, "1week", weeks);
                    if (!candles.isEmpty()) result.put(ticker, candles);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        */
        return result;
    }

    public Map<String, List<UsCandle>> fetchDailyBatch(List<String> tickers, int days) {
        log.info("[US] fetchDailyBatch: {} tickers — trying Yahoo Finance first", tickers.size());
        Map<String, List<UsCandle>> result = yahooFinanceService.fetchDailyBatch(tickers, days);

        // Fallback: any ticker Yahoo missed → try Twelve Data (commented out)
        /*
        List<String> missed = new ArrayList<>();
        for (String t : tickers) {
            if (!result.containsKey(t) || result.get(t).isEmpty()) missed.add(t);
        }
        if (!missed.isEmpty() && hasApiKey()) {
            log.info("[US] fetchDailyBatch: {} tickers missed by Yahoo — falling back to Twelve Data", missed.size());
            for (String ticker : missed) {
                try {
                    waitMs(DAILY_INTERVAL_MS);
                    List<UsCandle> candles = fetchTwelveDataCandles(ticker, "1day", days);
                    if (!candles.isEmpty()) result.put(ticker, candles);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        */
        return result;
    }

    // ── Single ticker fetches — Yahoo primary, Twelve Data fallback (fallback logic commented out) ───────────

    public List<UsCandle> fetchWeeklyCandles(String ticker, int weeks) {
        List<UsCandle> candles = yahooFinanceService.fetchWeeklyCandles(ticker, weeks);
        if (!candles.isEmpty()) return candles;

        // Twelve Data fallback logic commented out
        /*
        if (!hasApiKey()) return Collections.emptyList();
        log.debug("[US] Yahoo missed {} weekly — trying Twelve Data", ticker);
        try {
            waitMs(WEEKLY_INTERVAL_MS);
            return fetchTwelveDataCandles(ticker, "1week", weeks);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return Collections.emptyList();
        }
        */
        return Collections.emptyList(); // Return empty if Yahoo misses and no fallback
    }

    public List<UsCandle> fetchDailyCandles(String ticker, int days) {
        List<UsCandle> candles = yahooFinanceService.fetchDailyCandles(ticker, days);
        if (!candles.isEmpty()) return candles;

        // Twelve Data fallback logic commented out
        /*
        if (!hasApiKey()) return Collections.emptyList();
        log.debug("[US] Yahoo missed {} daily — trying Twelve Data", ticker);
        try {
            waitMs(DAILY_INTERVAL_MS);
            return fetchTwelveDataCandles(ticker, "1day", days);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return Collections.emptyList();
        }
        */
        return Collections.emptyList(); // Return empty if Yahoo misses and no fallback
    }

    // ── Twelve Data core fetch (fallback) (commented out) ─────────────────────────────────────
    /*
    @SuppressWarnings("unchecked")
    private List<UsCandle> fetchTwelveDataCandles(String ticker, String interval, int outputsize) {
        String url = String.format("%s/time_series?symbol=%s&interval=%s&outputsize=%d&apikey=%s",
                TWELVE_DATA_BASE, ticker, interval, outputsize, apiKey);
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", USER_AGENT);
            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            return parseTwelveDataCandles(response.getBody(), ticker);
        } catch (Exception e) {
            log.error("[US] Twelve Data fetch failed for {} {}: {}", ticker, interval, e.getMessage());
            return Collections.emptyList();
        }
    }
    */

    // ── Twelve Data response parser (commented out) ───────────────────────────────────────────
    /*
    @SuppressWarnings("unchecked")
    private List<UsCandle> parseTwelveDataCandles(Map<String, Object> body, String ticker) {
        if (body == null) return Collections.emptyList();

        String status = (String) body.get("status");
        if (!"ok".equals(status)) {
            log.warn("[US] Twelve Data error for {}: {} — {}",
                    ticker, body.getOrDefault("code", ""), body.getOrDefault("message", ""));
            return Collections.emptyList();
        }

        List<Map<String, Object>> values = (List<Map<String, Object>>) body.get("values");
        if (values == null || values.isEmpty()) return Collections.emptyList();

        List<UsCandle> candles = new ArrayList<>();
        for (Map<String, Object> v : values) {
            try {
                candles.add(new UsCandle(
                        ticker,
                        LocalDate.parse((String) v.get("datetime")),
                        parseDouble(v.get("open")),
                        parseDouble(v.get("high")),
                        parseDouble(v.get("low")),
                        parseDouble(v.get("close")),
                        parseLong(v.get("volume"))
                ));
            } catch (Exception e) {
                log.debug("[US] Skipping malformed Twelve Data candle for {}: {}", ticker, e.getMessage());
            }
        }
        Collections.reverse(candles); // Twelve Data returns newest first
        return candles;
    }
    */

    // ── 52-Week High Check (unchanged) ────────────────────────────────────────
    public boolean is52WeekHigh(List<UsCandle> weeklyCandles) {
        if (weeklyCandles.size() < 2) return false;
        UsCandle latestWeek = weeklyCandles.get(weeklyCandles.size() - 2);
        double high52wk = weeklyCandles.stream().mapToDouble(UsCandle::getHigh).max().orElse(0);
        if (high52wk <= 0) return false;
        double distancePct = ((high52wk - latestWeek.getHigh()) / high52wk) * 100.0;
        return distancePct <= 2.0;
    }

    // ── Helpers (Twelve Data specific helpers commented out) ───────────────────────────────────────────────
    /*
    private boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    private void waitMs(long intervalMs) throws InterruptedException {
        synchronized (lock) {
            long waitMs = intervalMs - (System.currentTimeMillis() - lastRequestAtMs);
            if (waitMs > 0) Thread.sleep(waitMs);
            lastRequestAtMs = System.currentTimeMillis();
        }
    }

    private double parseDouble(Object val) {
        if (val == null) return 0;
        return val instanceof Number ? ((Number) val).doubleValue() : Double.parseDouble(val.toString());
    }

    private long parseLong(Object val) {
        if (val == null) return 0;
        return val instanceof Number ? ((Number) val).longValue() : Long.parseLong(val.toString());
    }
    */
}
