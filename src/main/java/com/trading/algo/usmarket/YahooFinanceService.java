package com.trading.algo.usmarket;

import com.trading.algo.entity.UsCandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fetches US market data from Yahoo Finance chart API.
 *
 * No API key required. No hard rate limits in practice.
 * Supports weekly and daily OHLCV candles for NYSE + NASDAQ tickers.
 *
 * Endpoint:
 *   GET https://query1.finance.yahoo.com/v8/finance/chart/{ticker}
 *       ?interval=1wk&range=1mo     → weekly candles
 *       ?interval=1d&range=2wk      → daily candles
 *
 * Used as primary data source in UsMarketDataService.
 * Twelve Data is used as fallback if Yahoo returns empty/error.
 */
@Service
public class YahooFinanceService {

    private static final Logger log = LoggerFactory.getLogger(YahooFinanceService.class);

    private static final String YAHOO_BASE = "https://query1.finance.yahoo.com/v8/finance/chart";
    private static final ZoneId NY_ZONE    = ZoneId.of("America/New_York");

    // Small courtesy delay between calls to avoid IP throttling (not a hard limit)
    private static final long COURTESY_DELAY_MS = 200L;

    private final RestTemplate restTemplate;

    public YahooFinanceService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    // ── Batch fetch — weekly ──────────────────────────────────────────────────
    /**
     * Fetches weekly candles for all tickers sequentially.
     * ~200ms per ticker → 239 tickers ≈ 48 seconds total.
     */
    public Map<String, List<UsCandle>> fetchWeeklyBatch(List<String> tickers, int weeks) {
        Map<String, List<UsCandle>> result = new HashMap<>();
        if (tickers.isEmpty()) return result;

        // Yahoo range: weeks × 7 days, rounded up to nearest supported range
        String range = weeksToRange(weeks);
        log.info("[YAHOO] fetchWeeklyBatch: {} tickers, range={}", tickers.size(), range);

        for (String ticker : tickers) {
            try {
                Thread.sleep(COURTESY_DELAY_MS);
                List<UsCandle> candles = fetchYahoo(ticker, "1wk", range);
                if (!candles.isEmpty()) result.put(ticker, candles);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("[YAHOO] fetchWeeklyBatch failed for {}: {}", ticker, e.getMessage());
            }
        }
        log.info("[YAHOO] fetchWeeklyBatch done: {}/{} tickers fetched", result.size(), tickers.size());
        return result;
    }

    // ── Batch fetch — daily ───────────────────────────────────────────────────
    /**
     * Fetches daily candles for all tickers sequentially.
     * ~200ms per ticker → 239 tickers ≈ 48 seconds total.
     */
    public Map<String, List<UsCandle>> fetchDailyBatch(List<String> tickers, int days) {
        Map<String, List<UsCandle>> result = new HashMap<>();
        if (tickers.isEmpty()) return result;

        String range = daysToRange(days);
        log.info("[YAHOO] fetchDailyBatch: {} tickers, range={}", tickers.size(), range);

        for (String ticker : tickers) {
            try {
                Thread.sleep(COURTESY_DELAY_MS);
                List<UsCandle> candles = fetchYahoo(ticker, "1d", range);
                if (!candles.isEmpty()) result.put(ticker, candles);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("[YAHOO] fetchDailyBatch failed for {}: {}", ticker, e.getMessage());
            }
        }
        log.info("[YAHOO] fetchDailyBatch done: {}/{} tickers fetched", result.size(), tickers.size());
        return result;
    }

    // ── Single ticker fetches ─────────────────────────────────────────────────
    public List<UsCandle> fetchWeeklyCandles(String ticker, int weeks) {
        return fetchYahoo(ticker, "1wk", weeksToRange(weeks));
    }

    public List<UsCandle> fetchDailyCandles(String ticker, int days) {
        return fetchYahoo(ticker, "1d", daysToRange(days));
    }

    // ── Core Yahoo Finance fetch ──────────────────────────────────────────────
    @SuppressWarnings("unchecked")
    private List<UsCandle> fetchYahoo(String ticker, String interval, String range) {
        String url = String.format("%s/%s?interval=%s&range=%s", YAHOO_BASE, ticker, interval, range);
        log.info("=== NEW SAFE PARSER ACTIVE ===");
        try {
            HttpHeaders headers = new HttpHeaders();
            // Yahoo requires a browser-like User-Agent
            headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            headers.set("Accept", "application/json");

            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);

            Map<String, Object> body = response.getBody();
            if (body == null) return Collections.emptyList();

            // Response structure: {chart: {result: [{meta, timestamp, indicators}], error: null}}
            Map<String, Object> chart = (Map<String, Object>) body.get("chart");
            if (chart == null) return Collections.emptyList();

            Object errorObj = chart.get("error");
            if (errorObj != null) {
                log.warn("[YAHOO] Error for {}: {}", ticker, errorObj);
                return Collections.emptyList();
            }

            List<Map<String, Object>> results = (List<Map<String, Object>>) chart.get("result");
            if (results == null || results.isEmpty()) return Collections.emptyList();

            Map<String, Object> result = results.get(0);

          //  List<Long> timestamps = (List<Long>) result.get("timestamp");
            List<?> timestamps = (List<?>) result.get("timestamp");
            if (timestamps == null || timestamps.isEmpty()) return Collections.emptyList();

            Map<String, Object> indicators = (Map<String, Object>) result.get("indicators");
            List<Map<String, Object>> quoteList = (List<Map<String, Object>>) indicators.get("quote");
            if (quoteList == null || quoteList.isEmpty()) return Collections.emptyList();

            Map<String, Object> quote = quoteList.get(0);
//            List<Double> opens   = (List<Double>) quote.get("open");
//            List<Double> highs   = (List<Double>) quote.get("high");
//            List<Double> lows    = (List<Double>) quote.get("low");
//            List<Double> closes  = (List<Double>) quote.get("close");
//            List<Long>   volumes = (List<Long>)   quote.get("volume");
            List<?> opens   = (List<?>) quote.get("open");
            List<?> highs   = (List<?>) quote.get("high");
            List<?> lows    = (List<?>) quote.get("low");
            List<?> closes  = (List<?>) quote.get("close");
            List<?> volumes = (List<?>) quote.get("volume");

            List<UsCandle> candles = new ArrayList<>();
            for (int i = 0; i < timestamps.size(); i++) {
                try {
                    // Skip null bars (market closed / incomplete candle)
                 //   if (closes == null || i >= closes.size() || closes.get(i) == null) continue;
                    if (closes == null || i >= closes.size() || closes.get(i) == null) continue;
                   // LocalDate date = Instant.ofEpochSecond(timestamps.get(i))
                    //     .atZone(NY_ZONE).toLocalDate();
                    long epoch = safeLong(timestamps, i);

                    LocalDate date = Instant.ofEpochSecond(epoch)
                            .atZone(NY_ZONE)
                            .toLocalDate();

                    candles.add(new UsCandle(
                            ticker,
                            date,
                            safeDouble(opens,   i),
                            safeDouble(highs,   i),
                            safeDouble(lows,    i),
                            safeDouble(closes,  i),
                            safeLong(volumes,   i)
                    ));
                } catch (Exception e) {
                    // log.debug("[YAHOO] Skipping malformed bar {} for {}: {}", i, ticker, e.getMessage());
                    log.warn("[YAHOO] Skipping malformed bar {} for {}: {} — {}",
                            i, ticker, e.getClass().getSimpleName(), e.getMessage());
                }
            }
            // Yahoo returns oldest first — already in correct order
            return candles;

        } catch (Exception e) {
            log.error("[YAHOO] fetchYahoo failed for {} {}/{}: {}", ticker, interval, range, e.getMessage());
            return Collections.emptyList();
        }
    }

    // ── Range helpers ─────────────────────────────────────────────────────────
    private String weeksToRange(int weeks) {
        if (weeks <= 1)  return "5d";
        if (weeks <= 2)  return "1mo";
        if (weeks <= 8)  return "3mo";
        if (weeks <= 26) return "6mo";
        return "1y";
    }

    private String daysToRange(int days) {
        if (days <= 5)  return "5d";
        if (days <= 10) return "1mo";
        return "3mo";
    }

    // ── Safe list accessors ───────────────────────────────────────────────────
//    private double safeDouble(List<Double> list, int i) {
//        if (list == null || i >= list.size() || list.get(i) == null) return 0.0;
//        return list.get(i);
//    }
//
//    private long safeLong(List<Long> list, int i) {
//        if (list == null || i >= list.size() || list.get(i) == null) return 0L;
//        return list.get(i);
//    }
    private double safeDouble(List<?> list, int i) {
        if (list == null || i >= list.size() || list.get(i) == null) return 0.0;
        Object val = list.get(i);
        if (val instanceof Number) return ((Number) val).doubleValue();
        return 0.0;
    }

    private long safeLong(List<?> list, int i) {
        if (list == null || i >= list.size() || list.get(i) == null) return 0L;
        Object val = list.get(i);
        if (val instanceof Number) return ((Number) val).longValue();
        return 0L;
    }
}
