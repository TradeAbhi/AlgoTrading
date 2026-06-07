package com.trading.algo.delta.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.algo.delta.config.DeltaAppConfig;
import com.trading.algo.delta.model.Candle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Talks to the Delta Exchange REST API.
 *
 * Candle endpoint (public, no auth required):
 *   GET /v2/history/candles?symbol=BTCUSD&resolution=15m&start=<epoch>&end=<epoch>
 *
 * Response shape:
 * {
 *   "success": true,
 *   "result": [
 *     { "time": 1700000000, "open": "...", "high": "...", "low": "...", "close": "...", "volume": "..." },
 *     ...
 *   ]
 * }
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeltaApiService {

    private static final String CANDLE_PATH   = "/v2/history/candles";
    private static final int    CANDLE_15M_SEC = 900;   // 15 * 60

    private final OkHttpClient   okHttpClient;
    private final DeltaAppConfig appConfig;
    private final ObjectMapper   objectMapper = new ObjectMapper();

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns all COMPLETED 15-minute candles for the given symbol
     * that fall within [startEpoch, endEpoch].
     *
     * @param symbol      Delta product symbol, e.g. "BTCUSD"
     * @param startEpoch  Unix epoch seconds (inclusive)
     * @param endEpoch    Unix epoch seconds (inclusive)
     */
    public List<Candle> get15mCandles(String symbol, long startEpoch, long endEpoch) {
        String url = buildCandleUrl(symbol, "15m", startEpoch, endEpoch);
        log.debug("Fetching 15m candles for {} | url={}", symbol, url);

        String json = executeGet(url);
        if (json == null) return List.of();

        return parseCandles(symbol, json);
    }

    /**
     * Returns the LAST COMPLETED 15-minute candle for the given symbol.
     * "Last completed" means the candle whose close time <= now.
     */
    public Candle getLastCompleted15mCandle(String symbol) {
        long now      = Instant.now().getEpochSecond();
        long start    = now - (CANDLE_15M_SEC * 3); // fetch last ~45 min to be safe
        List<Candle> candles = get15mCandles(symbol, start, now);

        // Filter only fully-closed candles and return the most recent one
        return candles.stream()
                .filter(Candle::isClosed)
                .reduce((first, second) -> second)  // last element
                .orElse(null);
    }

    /**
     * Returns the daily candle for the previous UTC day.
     * Used to extract previous-day high / low.
     */
    public Candle getPreviousDayCandle(String symbol) {
        // Previous day: midnight-to-midnight UTC
        ZonedDateTime todayMidnight     = ZonedDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS);
        ZonedDateTime yesterdayMidnight = todayMidnight.minusDays(1);

        long start = yesterdayMidnight.toEpochSecond();
        long end   = todayMidnight.toEpochSecond() - 1;

        String url = buildCandleUrl(symbol, "1d", start, end);
        log.debug("Fetching daily candle for {} | url={}", symbol, url);

        String json = executeGet(url);
        if (json == null) return null;

        List<Candle> candles = parseCandles(symbol, json);
        return candles.isEmpty() ? null : candles.get(candles.size() - 1);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private String buildCandleUrl(String symbol, String resolution, long start, long end) {
        return HttpUrl.parse(appConfig.getEffectiveBaseUrl() + CANDLE_PATH)
                .newBuilder()
                .addQueryParameter("symbol",     symbol)
                .addQueryParameter("resolution", resolution)
                .addQueryParameter("start",      String.valueOf(start))
                .addQueryParameter("end",        String.valueOf(end))
                .build()
                .toString();
    }

    private String executeGet(String url) {
        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .get()
                .build();

        try (Response response = okHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.error("Delta API error: HTTP {} for URL {}", response.code(), url);
                return null;
            }
            return Objects.requireNonNull(response.body()).string();
        } catch (IOException e) {
            log.error("Network error calling Delta API: {}", e.getMessage(), e);
            return null;
        }
    }

    private List<Candle> parseCandles(String symbol, String json) {
        List<Candle> candles = new ArrayList<>();
        long nowEpoch = Instant.now().getEpochSecond();

        try {
            JsonNode root = objectMapper.readTree(json);

            if (!root.path("success").asBoolean(false)) {
                log.warn("Delta API returned success=false for symbol {}: {}", symbol, json);
                return candles;
            }

            JsonNode results = root.path("result");
            if (results.isArray()) {
                for (JsonNode node : results) {
                    long   openEpoch  = node.path("time").asLong();
                    long   closeEpoch = openEpoch + CANDLE_15M_SEC;
                    boolean isClosed  = closeEpoch <= nowEpoch;

                    Candle candle = Candle.builder()
                            .symbol(symbol)
                            .openTime(Instant.ofEpochSecond(openEpoch))
                            .closeTime(Instant.ofEpochSecond(closeEpoch))
                            .open(new BigDecimal(node.path("open").asText("0")))
                            .high(new BigDecimal(node.path("high").asText("0")))
                            .low(new BigDecimal(node.path("low").asText("0")))
                            .close(new BigDecimal(node.path("close").asText("0")))
                            .volume(new BigDecimal(node.path("volume").asText("0")))
                            .closed(isClosed)
                            .build();

                    candles.add(candle);
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse candles for {}: {}", symbol, e.getMessage(), e);
        }

        return candles;
    }
}
