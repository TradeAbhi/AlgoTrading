package com.trading.algo.service;

import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

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
import com.trading.algo.telegram.TelegramService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class NseWeekHighService {

    private static final String NSE_HOME = "https://www.nseindia.com";
    private static final String HIGH_URL = "https://www.nseindia.com/api/live-analysis-variations?index=52Week&type=highs";
    private static final String LOW_URL = "https://www.nseindia.com/api/live-analysis-variations?index=52Week&type=lows";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private final TelegramService telegramService;
    private final ObjectMapper objectMapper;

    public int sendWeekHighCsv() {
        return fetchAndSend("HIGHS", HIGH_URL);
    }

    public int sendWeekLowCsv() {
        return fetchAndSend("LOWS", LOW_URL);
    }

    public void sendBothCsv() {
        int highs = sendWeekHighCsv();
        int lows = sendWeekLowCsv();
        log.info("52-week CSV sent - highs={} lows={}", highs, lows);
    }

    public List<String> fetchWeekHighSymbols() {
        JsonNode dataNode = fetchData("HIGHS", HIGH_URL, false);
        if (dataNode == null || !dataNode.isArray()) {
            return List.of();
        }

        List<String> symbols = new ArrayList<>();
        for (JsonNode row : dataNode) {
            String symbol = row.path("symbol").asText("").trim().toUpperCase();
            String series = row.path("series").asText("").trim().toUpperCase();
            if (!symbol.isBlank() && ("EQ".equals(series) || series.isBlank())) {
                symbols.add(symbol);
            }
        }
        return symbols;
    }

    private int fetchAndSend(String type, String apiUrl) {
        JsonNode dataNode = fetchData(type, apiUrl, true);
        if (dataNode == null || !dataNode.isArray() || dataNode.isEmpty()) {
            return 0;
        }

        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("dd-MMM-yyyy"));
        String caption = "*52-Week " + type + "* - " + date + "\nTotal stocks: " + dataNode.size();

        telegramService.sendDocument(
                buildCsv(dataNode, type).getBytes(StandardCharsets.UTF_8),
                "52_week_" + type.toLowerCase() + "_" + LocalDate.now() + ".csv",
                caption
        );

        return dataNode.size();
    }

    private JsonNode fetchData(String type, String apiUrl, boolean notifyOnEmptyOrError) {
        DayOfWeek day = LocalDate.now().getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            log.info("Skipping 52-week {} fetch - today is {}", type, day);
            if (notifyOnEmptyOrError) {
                telegramService.sendMessage("52-Week " + type + ": Market closed (" + day + "). Run on a trading day.");
            }
            return null;
        }

        try {
            log.info("Fetching 52-week {} from NSE", type);

            CookieStore cookieStore = new BasicCookieStore();
            CloseableHttpClient client = HttpClients.custom()
                    .setDefaultCookieStore(cookieStore)
                    .build();

            HttpGet homeReq = new HttpGet(NSE_HOME);
            setHeaders(homeReq, "text/html,application/xhtml+xml,*/*");
            client.execute(homeReq).close();

            Thread.sleep(2000);

            HttpGet apiReq = new HttpGet(apiUrl);
            setHeaders(apiReq, "application/json, text/plain, */*");

            CloseableHttpResponse response = client.execute(apiReq);
            String body = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            response.close();
            client.close();

            JsonNode root = objectMapper.readTree(body);
            JsonNode dataNode = root.path("data");

            if (!dataNode.isArray() || dataNode.isEmpty()) {
                String preview = body.length() > 300 ? body.substring(0, 300) + "..." : body;
                log.warn("No data for 52-week {}. Response preview: {}", type, preview);
                if (notifyOnEmptyOrError) {
                    telegramService.sendMessage("52-Week " + type + ": No data available today.");
                }
            } else {
                log.info("52-week {} - {} stocks", type, dataNode.size());
            }

            return dataNode;
        } catch (Exception e) {
            log.error("Failed to fetch 52-week {}: {}", type, e.getMessage());
            if (notifyOnEmptyOrError) {
                telegramService.sendMessage("Failed to fetch 52-week " + type + ": " + e.getMessage());
            }
            return null;
        }
    }

    private void setHeaders(HttpGet req, String accept) {
        req.setHeader("User-Agent", UA);
        req.setHeader("Accept", accept);
        req.setHeader("Accept-Language", "en-US,en;q=0.9");
        req.setHeader("Referer", "https://www.nseindia.com/");
        req.setHeader("X-Requested-With", "XMLHttpRequest");
        req.setHeader("sec-fetch-dest", "empty");
        req.setHeader("sec-fetch-mode", "cors");
        req.setHeader("sec-fetch-site", "same-origin");
    }

    private String buildCsv(JsonNode dataNode, String type) {
        StringBuilder sb = new StringBuilder();

        if ("HIGHS".equals(type)) {
            sb.append("Symbol,Series,LTP,Change%,Prev Close,New 52W High,52W High Date,% From 52W High\n");
            for (JsonNode row : dataNode) {
                sb.append(String.format("%s,%s,%.2f,%.2f%%,%.2f,%.2f,%s,%.2f%%\n",
                        row.path("symbol").asText("-"),
                        row.path("series").asText("-"),
                        row.path("ltp").asDouble(),
                        row.path("pChange").asDouble(),
                        row.path("previousClose").asDouble(),
                        row.path("prev52WkHigh").asDouble(),
                        row.path("date52WkHigh").asText("-"),
                        row.path("per52WkHighChange").asDouble()));
            }
        } else {
            sb.append("Symbol,Series,LTP,Change%,Prev Close,New 52W Low,52W Low Date,% From 52W Low\n");
            for (JsonNode row : dataNode) {
                sb.append(String.format("%s,%s,%.2f,%.2f%%,%.2f,%.2f,%s,%.2f%%\n",
                        row.path("symbol").asText("-"),
                        row.path("series").asText("-"),
                        row.path("ltp").asDouble(),
                        row.path("pChange").asDouble(),
                        row.path("previousClose").asDouble(),
                        row.path("prev52WkLow").asDouble(),
                        row.path("date52WkLow").asText("-"),
                        row.path("per52WkLowChange").asDouble()));
            }
        }

        return sb.toString();
    }
}
