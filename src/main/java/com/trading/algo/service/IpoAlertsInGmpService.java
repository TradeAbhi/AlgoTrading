package com.trading.algo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.algo.ipo.Ipo;
import com.trading.algo.ipo.IpoRepository;
import com.trading.algo.telegram.TelegramService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service to fetch live GMP (Grey Market Premium) data from ipoalerts.in API.
 *
 * When GMP > 15%, sends alerts to buy the IPO.
 * Tracks IPOs to avoid duplicate alerts.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IpoAlertsInGmpService {

    private final IpoRepository ipoRepository;
    private final TelegramService telegramService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${ipoalerts.enabled:false}")
    private boolean enabled;

    @Value("${ipoalerts.api.base-url:https://ipoalerts.in/api}")
    private String baseUrl;

    @Value("${ipoalerts.api.key:}")
    private String apiKey;

    @Value("${ipoalerts.gmp.threshold:15.0}")
    private double gmpThreshold;

    // Track IPOs already alerted today to avoid duplicates
    private final Map<String, LocalDateTime> lastAlertByIpoSymbol = new ConcurrentHashMap<>();

    /**
     * Scheduled task to fetch GMP data and check for alerts.
     * Runs during market hours (9 AM to 5 PM, MON-FRI).
     */
    @Scheduled(cron = "${ipoalerts.gmp.scheduler.cron:0 0/15 9-17 * * MON-FRI}", zone = "Asia/Kolkata")
    @Transactional
    public void fetchAndAlertHighGmp() {
        if (!enabled) {
            log.debug("IPO Alerts.in service disabled");
            return;
        }

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("IPO Alerts.in API key not configured. Set IPOALERTS_API_KEY environment variable.");
            return;
        }

        try {
            log.info("Fetching live GMP data from ipoalerts.in");
            fetchGmpDataAndAlert();
        } catch (Exception e) {
            log.error("Error fetching GMP data: {}", e.getMessage());
        }
    }

    /**
     * Fetch GMP data and process each IPO
     */
    private void fetchGmpDataAndAlert() {
        try {
            // Call ipoalerts.in API to get GMP data
            JsonNode gmpData = fetchGmpFromApi();
            if (gmpData == null) {
                log.warn("No GMP data received from API - response was null");
                return;
            }

            if (!gmpData.isArray()) {
                log.warn("GMP data is not an array. Type: {}, Content: {}",
                    gmpData.getNodeType(), gmpData.toString());
                return;
            }

            log.info("═══════════════════════════════════════════════════════════");
            log.info("📊 GMP DATA RECEIVED FROM API");
            log.info("═══════════════════════════════════════════════════════════");
            log.info("Total IPOs in response: {}", gmpData.size());

            if (gmpData.size() > 0) {
                log.info("Sample first IPO object: {}", gmpData.get(0).toString());
            }
            log.info("═══════════════════════════════════════════════════════════");

            // Process each IPO with GMP data
            gmpData.forEach(ipoNode -> {
                try {
                    log.debug("Processing IPO node: {}", ipoNode.toString());
                    processIpoGmpData(ipoNode);
                } catch (Exception e) {
                    log.error("Error processing GMP data: {}", e.getMessage(), e);
                }
            });

        } catch (Exception e) {
            log.error("Failed to fetch and process GMP data: {}", e.getMessage(), e);
        }
    }

    /**
     * Process individual IPO GMP data and send alert if GMP > threshold.
     * Tries multiple field name variations to handle different API response formats.
     */
    private void processIpoGmpData(JsonNode ipoNode) {
        try {
            log.debug("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━���━━━━━━━━━━━━");
            log.debug("Processing new IPO node:");

            // Try multiple field name variations for company name
            // API returns: "name" (primary), fallback to "company_name"
            String ipoName = ipoNode.path("name").asText();
            if (ipoName.isBlank()) {
                ipoName = ipoNode.path("company_name").asText();
            }
            if (ipoName.isBlank()) {
                ipoName = ipoNode.path("company").asText();
            }
            log.debug("  Company Name: {} (tried: name, company_name, company)", ipoName);

            String symbol = ipoNode.path("symbol").asText();
            log.debug("  Symbol: {}", symbol);

            // Try multiple field name variations for GMP
            // NOTE: The API might not return GMP field - it could be on a different endpoint
            double gmp = ipoNode.path("gmp_percentage").asDouble(-999);
            String gmpSource = "gmp_percentage";

            if (gmp == -999) {
                gmp = ipoNode.path("gmp").asDouble(-999);
                gmpSource = "gmp";
            }
            if (gmp == -999) {
                gmp = ipoNode.path("grey_market_premium").asDouble(-999);
                gmpSource = "grey_market_premium";
            }

            // Default to 0 if none found
            if (gmp == -999) {
                gmp = 0;
                gmpSource = "NOT_FOUND";
                log.warn("⚠️  GMP field not found in API response for {}! API may not provide GMP on this endpoint.", ipoName);
                log.warn("    Available fields: {}", ipoNode.fieldNames().hasNext() ?
                    String.join(", ", (Iterable<String>) () -> ipoNode.fieldNames()) : "none");
            }

            log.debug("  GMP: {}% (from field: {})", gmp, gmpSource);
            log.debug("  Status: {}", ipoNode.path("status").asText("unknown"));
            log.debug("  Price Range: {}", ipoNode.path("priceRange").asText("unknown"));
            log.debug("  Start Date: {}", ipoNode.path("startDate").asText("unknown"));
            log.debug("  End Date: {}", ipoNode.path("endDate").asText("unknown"));
            log.debug("  Listing Date: {}", ipoNode.path("listingDate").asText("unknown"));
            log.debug("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

            if (ipoName.isBlank()) {
                log.debug("⚠️  Skipping IPO with no name");
                return;
            }

            // Check if GMP exceeds threshold
            if (gmp > gmpThreshold) {
                log.info("✅ HIGH GMP DETECTED: {} has GMP = {}% (threshold: {}%)",
                    ipoName, gmp, gmpThreshold);

                // Find or create IPO record
                Ipo ipo = findOrCreateIpo(ipoName, symbol);

                // Check if we should send alert (not alerted today for this IPO)
                if (shouldSendAlert(ipo)) {
                    log.info("📤 Sending alert for high GMP: {}", ipoName);
                    sendGmpAlert(ipo, gmp);
                    markAlertSent(ipo, gmp);
                    log.info("✅ GMP Alert sent for {} — GMP = {}%", ipoName, gmp);
                } else {
                    log.debug("⏭️  Alert already sent today for {}", ipoName);
                }
            } else if (gmp == 0 && gmpSource.equals("NOT_FOUND")) {
                log.debug("ℹ️  GMP field not available for {}", ipoName);
            } else {
                log.debug("ℹ️  GMP {} < threshold {} for {}", gmp, gmpThreshold, ipoName);
            }

        } catch (Exception e) {
            log.error("❌ Error processing IPO GMP data: {}", e.getMessage(), e);
        }
    }

    /**
     * Fetch GMP data from ipoalerts.in API.
     * Official API format per https://ipoalerts.in/docs
     * Endpoint: GET https://api.ipoalerts.in/ipos?status=open
     * Header: x-api-key (NOT Authorization: Bearer)
     */
    private JsonNode fetchGmpFromApi() {
        try {
            // Official endpoint with pagination - get 100 IPOs per request
            String url = "https://api.ipoalerts.in/ipos?status=open&limit=100";

            log.info("═══════════════════════════════════════════════════════════");
            log.info("🔄 FETCHING GMP DATA FROM API");
            log.info("═══════════════════════════════════════════════════════════");
            log.info("API Endpoint: {}", url);
            log.info("Authentication: x-api-key header");
            log.info("Query: status=open&limit=100 (open IPOs, 100 per page)");

            JsonNode result = tryFetchFromEndpoint(url);

            if (result != null) {
                log.info("═══════════════════════════════════════════════════════════");
                log.info("✅ Successfully fetched GMP data from: {}", url);
                log.info("═══════════════════════════════════════════════════════════");
                return result;
            }

            log.error("═══════════════════════════════════════════════════════════");
            log.error("❌ Failed to fetch from ipoalerts.in API");
            log.error("Verify API key is correct at https://ipoalerts.in/dashboard/api-keys");
            log.error("═══════════════════════════════════════════════════════════");
            return null;

        } catch (Exception e) {
            log.error("❌ Error calling GMP API: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Try to fetch from endpoint using x-api-key header (official API format).
     */
    private JsonNode tryFetchFromEndpoint(String url) {
        try {
            HttpHeaders headers = new HttpHeaders();
            // Official header format: x-api-key (NOT Authorization: Bearer)
            headers.set("x-api-key", apiKey);
            headers.set("Content-Type", "application/json");
            headers.set("Accept", "application/json");

            HttpEntity<String> request = new HttpEntity<>(headers);

            log.info("🌐 Calling API endpoint: {}", url);
            log.debug("📤 Request headers: x-api-key=*****, Content-Type=application/json, Accept=application/json");

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, request, String.class);

            log.info("📥 API Response Status: {}", response.getStatusCode());

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                String responseBody = response.getBody();

                log.info("✅ API returned 2xx success");
                log.info("📦 Response body length: {} characters", responseBody.length());

                // Log first 1000 chars of response for debugging
                if (responseBody.length() <= 2000) {
                    log.info("📄 Full API Response:\n{}", responseBody);
                } else {
                    log.info("📄 API Response (first 2000 chars):\n{}", responseBody.substring(0, 2000));
                    log.info("   ... (response truncated, total length: {} chars)", responseBody.length());
                }

                JsonNode root = objectMapper.readTree(responseBody);

                log.debug("✔️  Successfully parsed JSON");
                log.debug("   Root node type: {}", root.getNodeType());

                // Handle different API response formats
                if (root.has("data")) {
                    log.info("📊 Response format: wrapped in 'data' field");
                    return root.path("data");
                } else if (root.isArray()) {
                    log.info("📊 Response format: direct array with {} items", root.size());
                    return root;
                } else if (root.has("ipos")) {
                    log.info("📊 Response format: wrapped in 'ipos' field");
                    return root.path("ipos");
                }

                // Return root if it's an object or array
                log.info("📊 Response format: direct object");
                return root;
            }

            log.error("❌ API returned error status: {}", response.getStatusCode());
            if (response.getBody() != null) {
                log.error("   Error response: {}", response.getBody().substring(0, Math.min(500, response.getBody().length())));
            }
            return null;

        } catch (Exception e) {
            log.error("❌ Failed to fetch from {}: {} - {}", url, e.getClass().getSimpleName(), e.getMessage());
            log.debug("Stack trace:", e);
            return null;
        }
    }

    /**
     * Find existing IPO or create new one from GMP data.
     */
    private Ipo findOrCreateIpo(String ipoName, String symbol) {
        // Try to find existing IPO by name
        var existing = ipoRepository.findByName(ipoName);
        if (existing.isPresent()) {
            return existing.get();
        }

        // Create new IPO if not found
        Ipo ipo = new Ipo();
        ipo.setName(ipoName);
        ipo.setSymbol(symbol.isBlank() ? null : symbol);
        ipo.setStatus("UPCOMING");
        ipo.setAlertGmpSent(false);

        return ipoRepository.save(ipo);
    }

    /**
     * Check if alert should be sent (not already sent today).
     */
    private boolean shouldSendAlert(Ipo ipo) {
        LocalDateTime today = LocalDateTime.now();
        LocalDateTime lastAlert = lastAlertByIpoSymbol.get(alertKey(ipo));

        // If no alert sent yet, or last alert was yesterday or earlier
        if (lastAlert == null) {
            return true;
        }

        // Check if it's a different calendar day
        return !today.toLocalDate().equals(lastAlert.toLocalDate());
    }

    /**
     * Mark alert as sent for the IPO.
     */
    private void markAlertSent(Ipo ipo, double gmp) {
        lastAlertByIpoSymbol.put(alertKey(ipo), LocalDateTime.now());

        // Update GMP data in database
        ipo.setGmp(gmp);
        ipo.setGmpUpdatedAt(LocalDateTime.now());
        ipo.setAlertGmpSent(true);
        ipoRepository.save(ipo);
    }

    /**
     * Send GMP alert via Telegram.
     */
    private void sendGmpAlert(Ipo ipo, double gmp) {
        String emoji = gmp > 50 ? "🚀" : gmp > 30 ? "🔥" : gmp > 15 ? "💪" : "⭐";

        StringBuilder sb = new StringBuilder();
        sb.append(emoji).append(" *High GMP Alert - BUY Opportunity*\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━\n");
        sb.append(ipo.getName()).append("\n");

        if (ipo.getSymbol() != null) {
            sb.append("Symbol: `").append(ipo.getSymbol()).append("`\n");
        }

        sb.append("━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("📊 *GMP: ").append(String.format("%.2f", gmp)).append("%*\n");

        if (ipo.getIssuePrice() != null) {
            sb.append("💰 Issue Price: ₹").append(String.format("%.2f", ipo.getIssuePrice())).append("\n");
        }

        if (ipo.getListingDate() != null) {
            sb.append("📅 Listing Date: ").append(ipo.getListingDate()).append("\n");
        }

        sb.append("━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("_Strong GMP indicates positive sentiment. Consider applying in IPO._");

        telegramService.sendMessageToInvestmentPicks(sb.toString());
    }

    /**
     * Get alert tracking key for IPO.
     */
    private String alertKey(Ipo ipo) {
        return (ipo.getSymbol() != null ? ipo.getSymbol() : ipo.getName()) + "|GMP";
    }

    /**
     * Manual trigger for testing.
     */
    public void manualGmpFetch() {
        log.info("Manual GMP fetch triggered");
        fetchAndAlertHighGmp();
    }

    /**
     * Get current GMP status summary for all tracked IPOs.
     */
    public String getGmpStatusSummary() {
        List<Ipo> allIpos = ipoRepository.findAll();

        if (allIpos.isEmpty()) {
            return "No IPOs tracked yet";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("📊 *IPO GMP Status Summary*\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━\n");

        allIpos.stream()
                .filter(ipo -> ipo.getGmp() != null && ipo.getGmp() > gmpThreshold)
                .sorted((a, b) -> Double.compare(b.getGmp(), a.getGmp()))
                .forEach(ipo -> {
                    sb.append(String.format("🏢 %s (GMP: %.2f%%)\n", ipo.getName(), ipo.getGmp()));
                    if (ipo.getGmpUpdatedAt() != null) {
                        sb.append(String.format("   Updated: %s\n", ipo.getGmpUpdatedAt()));
                    }
                });

        sb.append("━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("Threshold: ").append(gmpThreshold).append("%");

        return sb.toString();
    }

    /**
     * Update GMP for specific IPO (can be called from API endpoint).
     */
    @Transactional
    public void updateGmpForIpo(String ipoSymbol, double gmpValue) {
        var ipo = ipoRepository.findByName(ipoSymbol)
                .or(() -> ipoRepository.findAll().stream()
                        .filter(i -> ipoSymbol.equals(i.getSymbol()))
                        .findFirst());

        if (ipo.isPresent()) {
            Ipo ipoRecord = ipo.get();
            ipoRecord.setGmp(gmpValue);
            ipoRecord.setGmpUpdatedAt(LocalDateTime.now());
            ipoRepository.save(ipoRecord);
            log.info("GMP updated for {}: {}%", ipoSymbol, gmpValue);
        }
    }
}


