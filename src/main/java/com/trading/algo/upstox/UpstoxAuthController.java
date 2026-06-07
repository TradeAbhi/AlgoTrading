package com.trading.algo.upstox;


import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

/**
 * UpstoxAuthController
 *
 * Handles Upstox OAuth 2.0 flow:
 *
 *   GET /upstox/login   → redirects browser to Upstox login page
 *   GET /callback       → Upstox calls this with ?code=XXX after login
 *                         → exchanges code for access token automatically
 *
 * Usage:
 *   1. Open browser and visit: http://localhost:8080/upstox/login
 *   2. Log in with your Upstox credentials
 *   3. You'll be redirected to /callback — token is stored automatically
 *   4. Browser shows "Token obtained successfully!"
 */
@RestController
@RequiredArgsConstructor
public class UpstoxAuthController {

    private final UpstoxTokenService upstoxTokenService;

    /**
     * Step 1: Visit this URL in browser to start OAuth flow.
     * Redirects to Upstox login page.
     */
    @GetMapping("/upstox/login")
    public RedirectView login() {
        String loginUrl = upstoxTokenService.buildLoginUrl();
        System.out.println("[Upstox] Redirecting to: " + loginUrl);
        return new RedirectView(loginUrl);
    }

    /**
     * Step 2: Upstox redirects here after successful login.
     * Automatically exchanges the auth code for an access token.
     */
    @GetMapping("/callback")
    public String callback(@RequestParam String code) {
        System.out.println("[Upstox] Received auth code: " + code);
        return upstoxTokenService.setCodeAndFetchToken(code);
    }
}