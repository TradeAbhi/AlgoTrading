package com.trading.algo.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.algo.upstox.PortfolioHolding;
import com.trading.algo.upstox.UpstoxInstrumentMasterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Dhan implementation of BrokerPortfolioService.
 * Retrieves Dhan holdings and resolves each NSE equity symbol to an Upstox
 * instrument key so the common alert scanner can evaluate it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DhanBrokerService implements BrokerPortfolioService {

    private static final String HOLDINGS_URL = "https://api.dhan.co/v2/holdings";

    private final DhanTokenService dhanTokenService;
    private final UpstoxInstrumentMasterService upstoxInstrumentMasterService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    
    @Value("${dhan.enabled:false}")
    private boolean enabled;
    
    @Override
    public List<PortfolioHolding> fetchHoldings() {
        if (!isEnabled()) {
            log.warn("Dhan broker service is disabled");
            return List.of();
        }
        
        String accessToken = dhanTokenService.getAccessToken();
        if (accessToken.isBlank()) {
            log.warn("Dhan holdings skipped because dhan.access.token is not configured");
            return List.of();
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(HOLDINGS_URL))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .header("access-token", accessToken)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Dhan holdings request failed: HTTP {}", response.statusCode());
                return List.of();
            }

            return parseHoldings(response.body());
        } catch (Exception e) {
            log.error("Failed to fetch Dhan holdings: {}", e.getMessage(), e);
            return List.of();
        }
    }
    
    @Override
    public String getBrokerName() {
        return "DHAN";
    }
    
    @Override
    public boolean isEnabled() {
        return enabled;
    }

    private List<PortfolioHolding> parseHoldings(String responseBody) throws Exception {
        JsonNode holdings = objectMapper.readTree(responseBody);
        if (!holdings.isArray()) {
            log.warn("Dhan holdings response was not an array");
            return List.of();
        }

        List<PortfolioHolding> result = new ArrayList<>();
        for (JsonNode item : holdings) {
            String symbol = item.path("tradingSymbol").asText();
            String instrumentKey = upstoxInstrumentMasterService.getInstrumentKey(symbol).orElse("");
            if (instrumentKey.isBlank()) {
                log.warn("Skipping Dhan holding {} because no Upstox NSE instrument key was found", symbol);
                continue;
            }

            int quantity = item.path("totalQty").asInt();
            double averagePrice = item.path("avgCostPrice").asDouble();
            result.add(PortfolioHolding.builder()
                    .tradingSymbol(symbol)
                    .instrumentToken(instrumentKey)
                    .exchange(item.path("exchange").asText())
                    .quantity(quantity)
                    .averagePrice(averagePrice)
                    .lastPrice(averagePrice)
                    .closePrice(averagePrice)
                    .companyName(symbol)
                    .isin(item.path("isin").asText())
                    .broker("DHAN")
                    .build());
        }
        log.info("Fetched {} scan-ready holdings from Dhan", result.size());
        return result;
    }
}
