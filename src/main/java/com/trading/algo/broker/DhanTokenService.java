package com.trading.algo.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.algo.telegram.TelegramService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * DhanTokenService
 *
 * Manages the Dhan OAuth access token lifecycle:
 *   - Stores the token in memory (set either via OAuth callback or application.properties)
 *   - Auto-refreshes daily using the stored authorization code
 *
 * Flow:
 *   1. On first run, visit: http://localhost:8080/dhan/login  → redirects to Dhan login
 *   2. After login, Dhan redirects to: http://localhost:8080/api/dhan/callback?code=XXXX
 *   3. DhanController calls setCodeAndFetchToken(code) → stores access token
 *   4. Token auto-refreshes every morning at 8:30 AM
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DhanTokenService {

    private final Environment env;
    private final TelegramService telegramService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    // In-memory token storage
    private volatile String accessToken = "";
    private volatile String lastAuthCode = "";
    private volatile boolean authenticated = false;

    private static final String TOKEN_URL = "https://api.dhan.co/oauth/token";
    private static final String AUTH_URL = "https://api.dhan.co/oauth/authorize";

    // -------------------------------------------------------------------------
    // PUBLIC API
    // -------------------------------------------------------------------------

    /**
     * Returns current access token.
     * Falls back to application.properties value if not yet set via OAuth.
     */
    public String getAccessToken() {
        if (!accessToken.isBlank()) return accessToken;
        String propToken = env.getProperty("dhan.access.token", "");
        return propToken;
    }

    /**
     * Called by DhanController when Dhan redirects back with auth code.
     * Exchanges the code for an access token and stores it.
     */
    public String setCodeAndFetchToken(String code) {
        try {
            this.lastAuthCode = code;
            String token = exchangeCodeForToken(code);
            this.accessToken = token;
            this.authenticated = true;
            log.info("Dhan token obtained successfully via OAuth callback.");
            telegramService.sendMessage("Dhan token refreshed successfully at " + java.time.LocalTime.now());
            return "Token obtained successfully!";
        } catch (Exception e) {
            log.error("Dhan token exchange failed: {}", e.getMessage(), e);
            return "Token exchange failed: " + e.getMessage();
        }
    }

    public boolean isAuthenticated() {
        return authenticated;
    }

    /**
     * Builds the Dhan login URL to redirect the user to.
     */
    public String buildLoginUrl() {
        String apiKey = env.getProperty("dhan.api.key", "");
        String redirectUri = env.getProperty("dhan.redirect.uri", "http://localhost:8080/api/dhan/callback");
        return AUTH_URL
                + "?response_type=code"
                + "&client_id=" + apiKey
                + "&redirect_uri=" + redirectUri;
    }

    // -------------------------------------------------------------------------
    // SCHEDULED DAILY REFRESH — 8:30 AM every weekday
    // -------------------------------------------------------------------------

    /**
     * Dhan tokens expire daily. This job sends a Telegram alert reminding
     * you to re-login if no auto-refresh is possible.
     */
    @Scheduled(cron = "0 30 8 * * MON-FRI")
    public void sendDailyLoginReminder() {
        String loginUrl = "http://localhost:8080/dhan/login";
        telegramService.sendMessage(
            "Dhan Token Refresh Needed\n" +
            "------------------------\n" +
            "Open this link to refresh your token:\n" +
            loginUrl + "\n\n" +
            "Do this once before market opens (9:15 AM)."
        );
        log.info("Dhan daily login reminder sent.");
    }

    // -------------------------------------------------------------------------
    // PRIVATE
    // -------------------------------------------------------------------------

    private String exchangeCodeForToken(String code) throws Exception {
        String apiKey = env.getProperty("dhan.api.key", "");
        String apiSecret = env.getProperty("dhan.api.secret", "");
        String redirectUri = env.getProperty("dhan.redirect.uri", "http://localhost:8080/api/dhan/callback");

        if (apiKey.isBlank() || apiSecret.isBlank()) {
            throw new Exception("dhan.api.key or dhan.api.secret not set in application.yml");
        }

        String formBody = "code=" + java.net.URLEncoder.encode(code, "UTF-8")
                + "&client_id=" + java.net.URLEncoder.encode(apiKey, "UTF-8")
                + "&client_secret=" + java.net.URLEncoder.encode(apiSecret, "UTF-8")
                + "&redirect_uri=" + java.net.URLEncoder.encode(redirectUri, "UTF-8")
                + "&grant_type=authorization_code";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_URL))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        log.info("Dhan token response: {} {}", response.statusCode(), response.body());

        if (response.statusCode() != 200) {
            throw new Exception("HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode json = objectMapper.readTree(response.body());
        String token = json.path("access_token").asText("");

        if (token.isBlank()) {
            throw new Exception("access_token missing in response: " + response.body());
        }
        return token;
    }
}
