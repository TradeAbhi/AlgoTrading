package com.trading.algo.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

@Slf4j
@RestController
@RequestMapping("/api/ipo-gmp")
public class IpoGmpRedirectController {

    private static final String IPO_GMP_URL = "https://www.investorgain.com/report/ipo-gmp-live/331/?filter=ipo";

    /**
     * Redirect to IPO GMP live report page
     * Access this endpoint at: http://localhost:8080/api/ipo-gmp/redirect
     */
    @GetMapping("/redirect")
    public RedirectView redirectToIpoGmp() {
        log.info("Redirecting to IPO GMP URL: {}", IPO_GMP_URL);
        return new RedirectView(IPO_GMP_URL);
    }

    /**
     * Get the IPO GMP URL as a string response
     * Access this endpoint at: http://localhost:8080/api/ipo-gmp/url
     */
    @GetMapping("/url")
    public String getIpoGmpUrl() {
        log.info("Returning IPO GMP URL: {}", IPO_GMP_URL);
        return IPO_GMP_URL;
    }
}
