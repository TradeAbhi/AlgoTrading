package com.trading.algo.usmarket;


import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.trading.algo.entity.UsCandle;

/**
 * Fetches US market data from two sources:
 *
 * 1. Finviz screener — 52-week high stock list (NYSE + NASDAQ)
 *    URL: https://finviz.com/screener.ashx?v=111&f=ta_highlow52w_nh,sh_avgvol_o500
 *    Parses the HTML table of tickers — no API key needed.
 *    Returns: List<String> tickers e.g. ["AAPL", "NVDA", "MSFT"]
 *
 * 2. Yahoo Finance v8 chart API — weekly + daily OHLCV
 *    URL: https://query1.finance.yahoo.com/v8/finance/chart/{ticker}
 *    No API key needed. Requires User-Agent header.
 *    Response: chart.result[0].timestamp + indicators.quote[0].{open,high,low,close,volume}
 *
 * US Market close: 4:00 PM EST = 1:30 AM IST (next day)
 * Seed runs:  Monday 6:00 AM IST  (after US weekend)
 * Scan runs:  Mon–Fri 2:00 AM IST (after US market close at 1:30 AM IST)
 */
@Service
public class UsMarketDataService {

    private static final Logger log = LoggerFactory.getLogger(UsMarketDataService.class);

    // Finviz filter codes:
    // ta_highlow52w_nh = new 52-week high
    // sh_avgvol_o500   = average volume over 500k (liquid stocks only)
    // exch_nyse|nasd   = NYSE and NASDAQ only
    // We paginate through all results (20 per page)
    private static final String FINVIZ_BASE =
            "https://finviz.com/screener.ashx?v=111&f=ta_highlow52w_nh,sh_avgvol_o500&o=-volume&r=";

    private static final String YAHOO_BASE =
            "https://query1.finance.yahoo.com/v8/finance/chart/";

    // Yahoo Finance requires a browser-like User-Agent to avoid 401/403
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private final RestTemplate restTemplate;

    public UsMarketDataService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    // ── Finviz: fetch all 52-week high tickers ────────────────────────────────
    /**
     * Scrapes Finviz screener for stocks at 52-week highs.
     * Finviz shows 20 results per page — we paginate until no more results.
     * Returns ticker symbols e.g. ["AAPL", "NVDA", "MSFT", ...]
     *
     * Note: Finviz data is delayed 15-20 min — fine for EOD swing strategy.
     */
    public List<String> fetch52WeekHighTickers() {
        List<String> tickers = new ArrayList<>();
        int page = 1;
        int rowsPerPage = 20;

        try {
            while (true) {
                int startRow = ((page - 1) * rowsPerPage) + 1;
                String url = FINVIZ_BASE + startRow;

                HttpHeaders headers = new HttpHeaders();
                headers.set("User-Agent", USER_AGENT);
                headers.set("Accept", "text/html,application/xhtml+xml");
                HttpEntity<Void> entity = new HttpEntity<>(headers);

                ResponseEntity<String> response = restTemplate.exchange(
                        url, HttpMethod.GET, entity, String.class);

                if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                    log.warn("[US] Finviz page {} returned {}", page, response.getStatusCode());
                    break;
                }

                List<String> pageTickers = parseFinvizTickers(response.getBody());
                if (pageTickers.isEmpty()) break; // no more results

                tickers.addAll(pageTickers);
                log.debug("[US] Finviz page {} — {} tickers", page, pageTickers.size());

                if (pageTickers.size() < rowsPerPage) break; // last page
                page++;

                // Polite delay between pages
                Thread.sleep(500);
            }

            log.info("[US] Finviz 52-week high tickers fetched: {}", tickers.size());

        } catch (Exception e) {
            log.error("[US] fetch52WeekHighTickers failed: {}", e.getMessage());
        }

        return tickers;
    }

    /**
     * Parses ticker symbols from Finviz HTML screener response.
     * Finviz renders tickers in anchor tags with class "screener-link-primary":
     * <a class="screener-link-primary" href="quote.ashx?t=AAPL">AAPL</a>
     */
    private List<String> parseFinvizTickers(String html) {
        List<String> tickers = new ArrayList<>();
        // Simple regex-free parse — look for quote.ashx?t= pattern
        String marker = "quote.ashx?t=";
        int idx = 0;
        while ((idx = html.indexOf(marker, idx)) != -1) {
            int start = idx + marker.length();
            int end   = html.indexOf("\"", start);
            if (end == -1) end = html.indexOf("&", start);
            if (end > start && end - start <= 10) { // ticker max 10 chars
                String ticker = html.substring(start, end).trim().toUpperCase();
                if (!ticker.isEmpty() && !tickers.contains(ticker)) {
                    tickers.add(ticker);
                }
            }
            idx = start;
        }
        return tickers;
    }

    // ── Yahoo Finance: weekly OHLCV ───────────────────────────────────────────
    /**
     * Fetches last N weeks of weekly OHLCV for a ticker.
     * Returns list of UsCandle sorted oldest → newest.
     *
     * Yahoo v8 response structure:
     * chart.result[0].timestamp[]           — Unix timestamps
     * chart.result[0].indicators.quote[0].open/high/low/close/volume[]
     */
    @SuppressWarnings("unchecked")
    public List<UsCandle> fetchWeeklyCandles(String ticker, int weeks) {
        try {
            String url = YAHOO_BASE + ticker + "?interval=1wk&range=" + weeks + "wk";

            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", USER_AGENT);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, Map.class);

            return parseYahooCandles(response.getBody(), ticker);

        } catch (Exception e) {
            log.error("[US] fetchWeeklyCandles failed for {}: {}", ticker, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Fetches last N days of daily OHLCV for a ticker.
     */
    @SuppressWarnings("unchecked")
    public List<UsCandle> fetchDailyCandles(String ticker, int days) {
        try {
            String url = YAHOO_BASE + ticker + "?interval=1d&range=" + days + "d";

            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", USER_AGENT);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, Map.class);

            return parseYahooCandles(response.getBody(), ticker);

        } catch (Exception e) {
            log.error("[US] fetchDailyCandles failed for {}: {}", ticker, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Parses Yahoo Finance v8 chart response into UsCandle list.
     * Returns oldest → newest order.
     */
    @SuppressWarnings("unchecked")
    private List<UsCandle> parseYahooCandles(Map<String, Object> body, String ticker) {
        if (body == null) return Collections.emptyList();

        Map<String, Object> chart  = (Map<String, Object>) body.get("chart");
        if (chart == null) return Collections.emptyList();

        List<Map<String, Object>> results = (List<Map<String, Object>>) chart.get("result");
        if (results == null || results.isEmpty()) return Collections.emptyList();

        Map<String, Object> result     = results.get(0);
        List<Long> timestamps          = (List<Long>) result.get("timestamp");
        Map<String, Object> indicators = (Map<String, Object>) result.get("indicators");
        List<Map<String, Object>> quotes = (List<Map<String, Object>>)
                indicators.get("quote");
        Map<String, Object> quote = quotes.get(0);

        List<Double> opens   = (List<Double>) quote.get("open");
        List<Double> highs   = (List<Double>) quote.get("high");
        List<Double> lows    = (List<Double>) quote.get("low");
        List<Double> closes  = (List<Double>) quote.get("close");
        List<Long>   volumes = (List<Long>)   quote.get("volume");

        List<UsCandle> candles = new ArrayList<>();
        for (int i = 0; i < timestamps.size(); i++) {
            if (closes.get(i) == null) continue; // skip incomplete candles

            LocalDate date = Instant.ofEpochSecond(timestamps.get(i))
                    .atZone(ZoneId.of("America/New_York"))
                    .toLocalDate();

            candles.add(new UsCandle(
                    ticker,
                    date,
                    opens.get(i)   != null ? opens.get(i)   : 0,
                    highs.get(i)   != null ? highs.get(i)   : 0,
                    lows.get(i)    != null ? lows.get(i)    : 0,
                    closes.get(i),
                    volumes.get(i) != null ? volumes.get(i) : 0L
            ));
        }
        return candles; // Yahoo returns oldest first
    }
}