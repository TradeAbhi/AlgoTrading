package com.trading.algo.upstox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Service to fetch portfolio holdings from Upstox API.
 *
 * Endpoint:
 *   GET /v2/portfolio/long-term-holdings
 *
 * Returns the user's long-term holdings (delivery positions).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UpstoxPortfolioService {

    private static final String HOLDINGS_URL = "https://api.upstox.com/v2/portfolio/long-term-holdings";

    private final RestTemplate restTemplate;
    private final UpstoxTokenService upstoxTokenService;
    private final ObjectMapper objectMapper;

    /**
     * Fetches all long-term holdings from Upstox portfolio.
     *
     * @return list of PortfolioHolding objects
     */
    public List<PortfolioHolding> fetchHoldings() {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(upstoxTokenService.getAccessToken());
            headers.setAccept(List.of(org.springframework.http.MediaType.APPLICATION_JSON));

            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    HOLDINGS_URL, HttpMethod.GET, entity, String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.error("Upstox holdings API returned: {}", response.getStatusCode());
                return List.of();
            }

            return parseHoldingsResponse(response.getBody());

        } catch (Exception e) {
            log.error("Failed to fetch holdings from Upstox: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Parses Upstox holdings API response.
     *
     * Sample structure:
     * {
     *   "status": "success",
     *   "data": [
     *     {
     *       "trading_symbol": "RELIANCE",
     *       "instrument_token": "NSE_EQ|INE002A01018",
     *       "exchange": "NSE_EQ",
     *       "quantity": 100,
     *       "last_price": 2940.0,
     *       "close_price": 2890.0,
     *       "pnl": 5000.0,
     *       "day_change": 50.0,
     *       "day_change_percentage": 1.73,
     *       "average_price": 2890.0,
     *       "company_name": "Reliance Industries Ltd",
     *       "isin": "INE002A01018"
     *     }
     *   ]
     * }
     */
    private List<PortfolioHolding> parseHoldingsResponse(String json) {
        List<PortfolioHolding> holdings = new ArrayList<>();

        try {
            JsonNode root = objectMapper.readTree(json);

            if (!"success".equals(root.path("status").asText())) {
                log.warn("Upstox API non-success status: {}", root.path("status").asText());
                return holdings;
            }

            JsonNode dataArray = root.path("data");
            if (!dataArray.isArray()) {
                log.warn("No data array in holdings response");
                return holdings;
            }

            for (JsonNode item : dataArray) {
                try {
                    PortfolioHolding holding = PortfolioHolding.builder()
                            .tradingSymbol(item.path("trading_symbol").asText())
                            .instrumentToken(item.path("instrument_token").asText())
                            .exchange(item.path("exchange").asText())
                            .quantity(item.path("quantity").asInt())
                            .lastPrice(item.path("last_price").asDouble())
                            .closePrice(item.path("close_price").asDouble())
                            .pnl(item.path("pnl").asDouble())
                            .dayChange(item.path("day_change").asDouble())
                            .dayChangePercentage(item.path("day_change_percentage").asDouble())
                            .averagePrice(item.path("average_price").asDouble())
                            .companyName(item.path("company_name").asText())
                            .isin(item.path("isin").asText())
                            .build();

                    holdings.add(holding);
                } catch (Exception e) {
                    log.warn("Failed to parse holding item: {}", e.getMessage());
                }
            }

            log.info("Fetched {} holdings from Upstox", holdings.size());

        } catch (Exception e) {
            log.error("JSON parse error for holdings response: {}", e.getMessage());
        }

        return holdings;
    }
}
