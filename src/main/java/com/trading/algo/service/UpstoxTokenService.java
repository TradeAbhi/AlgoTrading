package com.trading.algo.service;


import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

/**
 * UpstoxTokenService
 *
 * Manages the Upstox OAuth access token lifecycle:
 *   - Stores the token in memory (set either via OAuth callback or application.properties)
 *   - Auto-refreshes daily at 8:30 AM using the stored authorization code
 *
 * Flow:
 *   1. On first run, visit: http://localhost:8080/upstox/login  → redirects to Upstox login
 *   2. After login, Upstox redirects to: http://localhost:8080/callback?code=XXXX
 *   3. UpstoxAuthController calls setCodeAndFetchToken(code) → stores access token
 *   4. Token auto-refreshes every morning at 8:30 AM (Upstox tokens last 1 day)
 */
@Service
@RequiredArgsConstructor
public class UpstoxTokenService {

    private final Environment  env;
    private final TelegramService telegramService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    // In-memory token storage
    private volatile String accessToken  = "";
    private volatile String lastAuthCode = "";

    private static final String TOKEN_URL    = "https://api.upstox.com/v2/login/authorization/token";
    private static final String AUTH_URL     = "https://api.upstox.com/v2/login/authorization/dialog";

    // -------------------------------------------------------------------------
    // PUBLIC API
    // -------------------------------------------------------------------------

    /**
     * Returns current access token.
     * Falls back to application.properties value if not yet set via OAuth.
     */
    public String getAccessToken() {
        if (!accessToken.isBlank()) return accessToken;
        String propToken = env.getProperty("upstox.access.token", "");
        return propToken;
    }

    /**
     * Called by UpstoxAuthController when Upstox redirects back with auth code.
     * Exchanges the code for an access token and stores it.
     */
    public String setCodeAndFetchToken(String code) {
        try {
            this.lastAuthCode = code;
            String token = exchangeCodeForToken(code);
            this.accessToken = token;
            System.out.println("[Upstox] Token obtained successfully via OAuth callback.");
            telegramService.sendMessage("Upstox token refreshed successfully at " + java.time.LocalTime.now());
            return "Token obtained successfully!";
        } catch (Exception e) {
            System.err.println("[Upstox] Token exchange failed: " + e.getMessage());
            return "Token exchange failed: " + e.getMessage();
        }
    }

    /**
     * Builds the Upstox login URL to redirect the user to.
     */
    public String buildLoginUrl() {
        String apiKey      = env.getProperty("upstox.api.key", "");
        String redirectUri = env.getProperty("upstox.redirect.uri", "http://localhost:8080/callback");
        return AUTH_URL
             + "?response_type=code"
             + "&client_id=" + apiKey
             + "&redirect_uri=" + redirectUri;
    }

    // -------------------------------------------------------------------------
    // SCHEDULED DAILY REFRESH — 8:30 AM every weekday
    // -------------------------------------------------------------------------

    /**
     * Upstox tokens expire daily. This job sends a Telegram alert reminding
     * you to re-login if no auto-refresh is possible.
     *
     * Full auto-refresh is not possible without user interaction because
     * Upstox requires browser-based OAuth login each day (security requirement).
     *
     * So instead: sends a Telegram message with the login link every morning.
     */
    @Scheduled(cron = "0 25 8 * * MON-FRI")
    public void sendDailyLoginReminder() {
        String loginUrl = "http://localhost:8080/upstox/login";
        telegramService.sendMessage(
            "Upstox Token Refresh Needed\n" +
            "------------------------\n" +
            "Open this link to refresh your token:\n" +
            loginUrl + "\n\n" +
            "Do this once before market opens (9:15 AM)."
        );
        System.out.println("[Upstox] Daily login reminder sent.");
    }

    // -------------------------------------------------------------------------
    // PRIVATE
    // -------------------------------------------------------------------------

    private String exchangeCodeForToken(String code) throws Exception {
        String apiKey      = env.getProperty("upstox.api.key",      "");
        String apiSecret   = env.getProperty("upstox.api.secret",   "");
        String redirectUri = env.getProperty("upstox.redirect.uri", "http://localhost:8080/callback");

        if (apiKey.isBlank() || apiSecret.isBlank()) {
            throw new Exception("upstox.api.key or upstox.api.secret not set in application.properties");
        }

        String formBody = "code="          + java.net.URLEncoder.encode(code,        "UTF-8")
                + "&client_id="     + java.net.URLEncoder.encode(apiKey,      "UTF-8")
                + "&client_secret=" + java.net.URLEncoder.encode(apiSecret,   "UTF-8")
                + "&redirect_uri="  + java.net.URLEncoder.encode(redirectUri, "UTF-8")
                + "&grant_type=authorization_code";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_URL))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept",       "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        System.out.println("[Upstox] Token response: " + response.statusCode() + " " + response.body());

        if (response.statusCode() != 200) {
            throw new Exception("HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode json = objectMapper.readTree(response.body());
        String token  = json.path("access_token").asText("");

        if (token.isBlank()) throw new Exception("access_token missing in response: " + response.body());
        return token;
    }
}