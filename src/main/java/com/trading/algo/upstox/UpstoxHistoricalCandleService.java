package com.trading.algo.upstox;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.algo.dtos.Candle;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Fetches 15-minute historical OHLC candles from Upstox v2 API.
 *
 * Endpoint:
 *   GET /v3/historical-candle/{instrument_key}/minutes/15/{to_date}/{from_date}
 *
 * Upstox returns candles oldest-first in the "candles" array.
 * Each element: [timestamp, open, high, low, close, volume, oi]
 *
 * Rate limit: ~10 req/sec on free tier → use BacktestConfig.apiDelayMs between calls.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UpstoxHistoricalCandleService {

    // Historical candle — for past dates
    private static final String BASE_URL      =
            "https://api.upstox.com/v3/historical-candle/%s/minutes/15/%s/%s";

    // Intraday candle — for today's live candles (no date params needed)
    private static final String INTRADAY_URL  =
            "https://api.upstox.com/v3/historical-candle/intraday/%s/minutes/15";

    private static final String WEEKLY_URL =
            "https://api.upstox.com/v3/historical-candle/%s/weeks/1/%s/%s";

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private final RestTemplate        restTemplate;
    private final UpstoxTokenService  upstoxTokenService;
    private final ObjectMapper        objectMapper;

    /**
     * Fetches all 15-min candles for a single instrument on a single date.
     *
     * @param instrumentKey  e.g. "NSE_EQ|INE002A01018"
     * @param date           trading date
     * @return list of Candle objects sorted by timestamp ASC, empty if API fails
     */
    public List<Candle> fetchDayCandles(String instrumentKey, LocalDate date) {
        // Build URI with build(true) so Spring does NOT double-encode the already-encoded %7C.
        // Passing the raw string to restTemplate.exchange() causes double-encoding:
        //   | -> %7C (our replace) -> %257C (RestTemplate encodes again) -> 400 Bad Request
        String encodedKey = instrumentKey.replace("|", "%7C").replace(" ", "%20");
        String dateStr    = date.format(DATE_FMT);
        String rawUrl;

        if (date.equals(LocalDate.now())) {
            // TODAY — use intraday endpoint (historical returns empty [] for current day)
            rawUrl = String.format(INTRADAY_URL, encodedKey);
            log.debug("Using INTRADAY url for {} on {}", instrumentKey, date);
        } else {
            // PAST DATE — use historical endpoint
            rawUrl = String.format(BASE_URL, encodedKey, dateStr, dateStr);
        }

        java.net.URI uri;
        try {
            uri = org.springframework.web.util.UriComponentsBuilder
                    .fromUriString(rawUrl)
                    .build(true)   // true = already encoded — skip re-encoding
                    .toUri();
        } catch (Exception e) {
            log.error("Failed to build URI for {}: {}", instrumentKey, e.getMessage());
            return Collections.emptyList();
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(upstoxTokenService.getAccessToken());
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));

            ResponseEntity<String> response = restTemplate.exchange(
                    uri, HttpMethod.GET, new HttpEntity<>(headers), String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("Upstox historical API returned {} for {} on {} — body: {}",
                        response.getStatusCode(), instrumentKey, date,
                        response.getBody() != null ? response.getBody().substring(0, Math.min(200, response.getBody().length())) : "null");
                return Collections.emptyList();
            }
            // Log first 200 chars of successful response for index debugging
            log.info("Upstox response for {} on {} (first 200): {}",
                    instrumentKey, date,
                    response.getBody().substring(0, Math.min(200, response.getBody().length())));

            return parseCandles(response.getBody(), instrumentKey, date);

        } catch (HttpClientErrorException.TooManyRequests e) {
            log.warn("Rate limited by Upstox for {} on {} — backing off", instrumentKey, date);
            return Collections.emptyList();
        } catch (Exception e) {
            log.error("Failed to fetch candles for {} on {}: {}", instrumentKey, date, e.getMessage());
            return Collections.emptyList();
        }
    }

    public List<Candle> fetchWeeklyCandles(String instrumentKey, LocalDate fromDate, LocalDate toDate) {
        String encodedKey = instrumentKey.replace("|", "%7C").replace(" ", "%20");
        String rawUrl = String.format(WEEKLY_URL, encodedKey, toDate.format(DATE_FMT), fromDate.format(DATE_FMT));

        java.net.URI uri;
        try {
            uri = org.springframework.web.util.UriComponentsBuilder
                    .fromUriString(rawUrl)
                    .build(true)
                    .toUri();
        } catch (Exception e) {
            log.error("Failed to build weekly URI for {}: {}", instrumentKey, e.getMessage());
            return Collections.emptyList();
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(upstoxTokenService.getAccessToken());
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));

            ResponseEntity<String> response = restTemplate.exchange(
                    uri, HttpMethod.GET, new HttpEntity<>(headers), String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("Upstox weekly API returned {} for {}", response.getStatusCode(), instrumentKey);
                return Collections.emptyList();
            }

            return parseCandles(response.getBody(), instrumentKey, toDate);
        } catch (Exception e) {
            log.error("Failed to fetch weekly candles for {}: {}", instrumentKey, e.getMessage());
            return Collections.emptyList();
        }
    }

    // -------------------------------------------------------------------------
    // Parsing
    // -------------------------------------------------------------------------

    /**
     * Upstox response structure:
     * {
     *   "status": "success",
     *   "data": {
     *     "candles": [
     *       ["2024-04-22T09:15:00+05:30", 2900.0, 2950.0, 2880.0, 2920.0, 123456, 0],
     *       ...
     *     ]
     *   }
     * }
     *
     * Array index: [0]=timestamp, [1]=open, [2]=high, [3]=low, [4]=close, [5]=volume, [6]=oi
     */
    private List<Candle> parseCandles(String json, String instrumentKey, LocalDate date) {
        List<Candle> candles = new ArrayList<>();

        try {
            JsonNode root = objectMapper.readTree(json);

            if (!"success".equals(root.path("status").asText())) {
                log.warn("Non-success status for {} on {}: {}", instrumentKey, date,
                        root.path("status").asText());
                return candles;
            }

            JsonNode candleArray = root.path("data").path("candles");
            if (!candleArray.isArray()) {
                log.warn("No candles array in response for {} on {}", instrumentKey, date);
                return candles;
            }

            for (JsonNode c : candleArray) {
                if (!c.isArray() || c.size() < 6) continue;

                try {
                    LocalDateTime ts = LocalDateTime.parse(c.get(0).asText(), TS_FMT);
                    Candle candle = Candle.builder()
                            .timestamp(ts)
                            .open(c.get(1).asDouble())
                            .high(c.get(2).asDouble())
                            .low(c.get(3).asDouble())
                            .close(c.get(4).asDouble())
                            .volume(c.get(5).asLong())
                            .build();
                    candles.add(candle);
                } catch (Exception e) {
                    log.warn("Failed to parse candle element: {}", c);
                }
            }

            // Upstox returns newest-first — reverse to get chronological order
            Collections.reverse(candles);

            log.debug("Fetched {} candles for {} on {}", candles.size(), instrumentKey, date);

        } catch (Exception e) {
            log.error("JSON parse error for {} on {}: {}", instrumentKey, date, e.getMessage());
        }

        return candles;
    }
}


//package com.trading.algo.service;
//
//import java.time.LocalDate;
//import java.time.LocalDateTime;
//import java.time.format.DateTimeFormatter;
//import java.util.ArrayList;
//import java.util.Collections;
//import java.util.List;
//
//import org.springframework.http.HttpEntity;
//import org.springframework.http.HttpHeaders;
//import org.springframework.http.HttpMethod;
//import org.springframework.http.MediaType;
//import org.springframework.http.ResponseEntity;
//import org.springframework.stereotype.Service;
//import org.springframework.web.client.HttpClientErrorException;
//import org.springframework.web.client.RestTemplate;
//
//import com.fasterxml.jackson.databind.JsonNode;
//import com.fasterxml.jackson.databind.ObjectMapper;
//import com.trading.algo.dtos.Candle;
//
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//
///**
// * Fetches 15-minute historical OHLC candles from Upstox v2 API.
// *
// * Endpoint:
// *   GET /v3/historical-candle/{instrument_key}/minutes/15/{to_date}/{from_date}
// *
// * Upstox returns candles oldest-first in the "candles" array.
// * Each element: [timestamp, open, high, low, close, volume, oi]
// *
// * Rate limit: ~10 req/sec on free tier → use BacktestConfig.apiDelayMs between calls.
// */
//@Slf4j
//@Service
//@RequiredArgsConstructor
//public class UpstoxHistoricalCandleService {
//
//    private static final String BASE_URL =
//            "https://api.upstox.com/v3/historical-candle/%s/minutes/15/%s/%s";
//
//    private static final DateTimeFormatter DATE_FMT =
//            DateTimeFormatter.ofPattern("yyyy-MM-dd");
//
//    private static final DateTimeFormatter TS_FMT =
//            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
//
//    private final RestTemplate        restTemplate;
//    private final UpstoxTokenService  upstoxTokenService;
//    private final ObjectMapper        objectMapper;
//
//    /**
//     * Fetches all 15-min candles for a single instrument on a single date.
//     *
//     * @param instrumentKey  e.g. "NSE_EQ|INE002A01018"
//     * @param date           trading date
//     * @return list of Candle objects sorted by timestamp ASC, empty if API fails
//     */
//    public List<Candle> fetchDayCandles(String instrumentKey, LocalDate date) {
//        // Build URI with build(true) so Spring does NOT double-encode the already-encoded %7C.
//        // Passing the raw string to restTemplate.exchange() causes double-encoding:
//        //   | -> %7C (our replace) -> %257C (RestTemplate encodes again) -> 400 Bad Request
//        String dateStr = date.format(DATE_FMT);
//        // Encode both | and space — space in index names like "Nifty 50" causes invalid URI
//        // OLD: only encoded | → "NSE_INDEX%7CNifty 50" ← space breaks URI
//        // NEW: encode both  → "NSE_INDEX%7CNifty%2050" ← valid URI
//        String rawUrl  = String.format(BASE_URL,
//                instrumentKey.replace("|", "%7C").replace(" ", "%20"), dateStr, dateStr);
//
//        java.net.URI uri;
//        try {
//            uri = org.springframework.web.util.UriComponentsBuilder
//                    .fromUriString(rawUrl)
//                    .build(true)   // true = already encoded — skip re-encoding
//                    .toUri();
//        } catch (Exception e) {
//            log.error("Failed to build URI for {}: {}", instrumentKey, e.getMessage());
//            return Collections.emptyList();
//        }
//
//        try {
//            HttpHeaders headers = new HttpHeaders();
//            headers.setBearerAuth(upstoxTokenService.getAccessToken());
//            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
//
//            ResponseEntity<String> response = restTemplate.exchange(
//                    uri, HttpMethod.GET, new HttpEntity<>(headers), String.class);
//
//            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
//                log.warn("Upstox historical API returned {} for {} on {}",
//                        response.getStatusCode(), instrumentKey, date);
//                return Collections.emptyList();
//            }
//
//            return parseCandles(response.getBody(), instrumentKey, date);
//
//        } catch (HttpClientErrorException.TooManyRequests e) {
//            log.warn("Rate limited by Upstox for {} on {} — backing off", instrumentKey, date);
//            return Collections.emptyList();
//        } catch (Exception e) {
//            log.error("Failed to fetch candles for {} on {}: {}", instrumentKey, date, e.getMessage());
//            return Collections.emptyList();
//        }
//    }
//
//    // -------------------------------------------------------------------------
//    // Parsing
//    // -------------------------------------------------------------------------
//
//    /**
//     * Upstox response structure:
//     * {
//     *   "status": "success",
//     *   "data": {
//     *     "candles": [
//     *       ["2024-04-22T09:15:00+05:30", 2900.0, 2950.0, 2880.0, 2920.0, 123456, 0],
//     *       ...
//     *     ]
//     *   }
//     * }
//     *
//     * Array index: [0]=timestamp, [1]=open, [2]=high, [3]=low, [4]=close, [5]=volume, [6]=oi
//     */
//    private List<Candle> parseCandles(String json, String instrumentKey, LocalDate date) {
//        List<Candle> candles = new ArrayList<>();
//
//        try {
//            JsonNode root = objectMapper.readTree(json);
//
//            if (!"success".equals(root.path("status").asText())) {
//                log.warn("Non-success status for {} on {}: {}", instrumentKey, date,
//                        root.path("status").asText());
//                return candles;
//            }
//
//            JsonNode candleArray = root.path("data").path("candles");
//            if (!candleArray.isArray()) {
//                log.warn("No candles array in response for {} on {}", instrumentKey, date);
//                return candles;
//            }
//
//            for (JsonNode c : candleArray) {
//                if (!c.isArray() || c.size() < 6) continue;
//
//                try {
//                    LocalDateTime ts = LocalDateTime.parse(c.get(0).asText(), TS_FMT);
//                    Candle candle = Candle.builder()
//                            .timestamp(ts)
//                            .open(c.get(1).asDouble())
//                            .high(c.get(2).asDouble())
//                            .low(c.get(3).asDouble())
//                            .close(c.get(4).asDouble())
//                            .volume(c.get(5).asLong())
//                            .build();
//                    candles.add(candle);
//                } catch (Exception e) {
//                    log.warn("Failed to parse candle element: {}", c);
//                }
//            }
//
//            // Upstox returns newest-first — reverse to get chronological order
//            Collections.reverse(candles);
//
//            log.debug("Fetched {} candles for {} on {}", candles.size(), instrumentKey, date);
//
//        } catch (Exception e) {
//            log.error("JSON parse error for {} on {}: {}", instrumentKey, date, e.getMessage());
//        }
//
//        return candles;
//    }
//}
