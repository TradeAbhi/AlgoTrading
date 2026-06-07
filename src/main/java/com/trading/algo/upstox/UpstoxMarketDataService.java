package com.trading.algo.upstox;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.algo.config.WatchlistConfig;
import com.trading.algo.dtos.WatchlistItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Fetches live market quotes from Upstox Market Quote API.
 *
 * Upstox endpoint:
 *   GET /v2/market-quote/quotes?instrument_key=NSE_EQ|RELIANCE,NSE_EQ|TCS,...
 *
 * Upstox batch limit: 500 instruments per request.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UpstoxMarketDataService {

    private static final String UPSTOX_QUOTE_URL =
            "https://api.upstox.com/v2/market-quote/quotes";

    private static final String UPSTOX_OI_URL =
            "https://api.upstox.com/v2/market-quote/option-greeks"; // for F&O OI

    private static final int BATCH_SIZE = 500;

    private final RestTemplate restTemplate;
    private final UpstoxTokenService upstoxTokenService;
    private final WatchlistConfig watchlistConfig;
    private final ObjectMapper objectMapper;

    /**
     * Fetches live quotes for a list of instrument keys.
     * Automatically batches requests if > 500 instruments.
     *
     * @param instrumentKeys e.g. ["NSE_EQ|RELIANCE", "NSE_EQ|TCS"]
     * @return list of WatchlistItem with live data populated
     */
    public List<WatchlistItem> fetchLiveQuotes(List<String> instrumentKeys) {
        if (instrumentKeys == null || instrumentKeys.isEmpty()) {
            log.warn("No instrument keys provided for live quote fetch");
            return Collections.emptyList();
        }

        List<WatchlistItem> result = new ArrayList<>();
        List<List<String>> batches = partition(instrumentKeys, BATCH_SIZE);

        for (List<String> batch : batches) {
            try {
                List<WatchlistItem> batchResult = fetchBatch(batch);
                result.addAll(batchResult);
            } catch (Exception e) {
                log.error("Error fetching batch of {} instruments: {}", batch.size(), e.getMessage(), e);
            }
        }

        log.info("Fetched live quotes for {}/{} instruments", result.size(), instrumentKeys.size());
        return result;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private List<WatchlistItem> fetchBatch(List<String> keys) throws Exception {
        String joinedKeys = String.join(",", keys);
        String url = UPSTOX_QUOTE_URL + "?instrument_key=" + joinedKeys;

        HttpHeaders headers = buildHeaders();
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.GET, entity, String.class);

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            log.error("Upstox quote API returned: {}", response.getStatusCode());
            return Collections.emptyList();
        }

        return parseQuoteResponse(response.getBody());
    }

    /**
     * Parses Upstox /v2/market-quote/quotes response.
     *
     * Sample structure:
     * {
     *   "status": "success",
     *   "data": {
     *     "NSE_EQ:RELIANCE": {
     *       "ohlc": { "open": 2900, "high": 2950, "low": 2880, "close": 2890 },
     *       "last_price": 2940,
     *       "volume": 1234567,
     *       "average_price": 2920,
     *       "oi": 0,
     *       "net_change": 50,
     *       "total_buy_quantity": 5000,
     *       "total_sell_quantity": 800,
     *       "instrument_token": "NSE_EQ|INE002A01018",
     *       ...
     *     }
     *   }
     * }
     */
    private List<WatchlistItem> parseQuoteResponse(String json) throws Exception {
        List<WatchlistItem> items = new ArrayList<>();
        JsonNode root = objectMapper.readTree(json);

        if (!"success".equals(root.path("status").asText())) {
            log.warn("Upstox API non-success status: {}", root.path("status").asText());
            return items;
        }

        JsonNode data = root.path("data");
        Iterator<Map.Entry<String, JsonNode>> fields = data.fields();

        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String key = entry.getKey();             // "NSE_EQ:RELIANCE"
            JsonNode quote = entry.getValue();

            try {
                WatchlistItem item = mapQuoteToItem(key, quote);
                items.add(item);
            } catch (Exception e) {
                log.warn("Failed to parse quote for {}: {}", key, e.getMessage());
            }
        }

        return items;
    }

    private WatchlistItem mapQuoteToItem(String key, JsonNode quote) {
        // key format: "NSE_EQ:RELIANCE"
        String[] parts = key.split(":");
        String exchange = parts.length > 0 ? parts[0] : "NSE_EQ";
        String symbol   = parts.length > 1 ? parts[1] : key;

        JsonNode ohlc = quote.path("ohlc");

        double ltp   = quote.path("last_price").asDouble();
        double open  = ohlc.path("open").asDouble();
        double high  = ohlc.path("high").asDouble();
        double low   = ohlc.path("low").asDouble();
        double close = ohlc.path("close").asDouble();   // prev close
        long   volume= quote.path("volume").asLong();
        double avgPrice = quote.path("average_price").asDouble();
        long   oi    = quote.path("oi").asLong();
        double change= quote.path("net_change").asDouble();
        long   buyQty = quote.path("total_buy_quantity").asLong();
        long   sellQty= quote.path("total_sell_quantity").asLong();

        double changePercent = (close > 0) ? (change / close) * 100.0 : 0.0;
        double tradedValue   = (volume * avgPrice) / 1_00_00_000.0; // in crores
        double buySellRatio  = (sellQty > 0) ? (double) buyQty / sellQty : Double.MAX_VALUE;

        return WatchlistItem.builder()
                .symbol(symbol)
                .exchange(exchange)
                .instrumentToken(quote.path("instrument_token").asText(""))
                .ltp(ltp)
                .open(open)
                .high(high)
                .low(low)
                .close(close)
                .change(change)
                .changePercent(changePercent)
                .volume(volume)
                .tradedValue(tradedValue)
                .openInterest(oi)
                .totalBuyQty(buyQty)
                .totalSellQty(sellQty)
                .buySelRatio(buySellRatio)
                .capturedAt(LocalDateTime.now())
                .build();
    }

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(upstoxTokenService.getAccessToken());
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return headers;
    }

    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }
    
    public JsonNode getLiveQuote(String instrumentKey) {
        try {
            String url = UPSTOX_QUOTE_URL + "?instrument_key=" + instrumentKey;

            HttpHeaders headers = buildHeaders();
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.error("Quote API failed for {}", instrumentKey);
                return null;
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode data = root.path("data");

            // key format: NSE_EQ:RELIANCE
            String key = instrumentKey.replace("|", ":");

            return data.path(key);

        } catch (Exception e) {
            log.error("Error fetching quote for {}: {}", instrumentKey, e.getMessage());
            return null;
        }
    }
}