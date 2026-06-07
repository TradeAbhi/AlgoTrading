package com.trading.algo.service;

import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Service
public class SmsService {

    private final String API_KEY = "sbomEFpzgR3j7nu9M6V8W4UHwBrCN1Tf0lLqhPQi5YkJDKAxtchrFoYpPgcakAO4KRzJZlIG1UEeTWV2";
    private final String URL = "https://www.fast2sms.com/dev/bulkV2";

    public String sendSms(String message, String phoneNumber) {
        RestTemplate restTemplate = new RestTemplate();

        // Prepare headers
        HttpHeaders headers = new HttpHeaders();
        headers.set("authorization", API_KEY);
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Prepare request body (Fast2SMS 'POST' method parameters)
        Map<String, Object> body = new HashMap<>();
        body.put("route", "q"); // 'q' for Quick SMS
        body.put("message", message);
        body.put("language", "english");
        body.put("numbers", phoneNumber);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(URL, HttpMethod.POST, entity, String.class);
            return response.getBody();
        } catch (Exception e) {
            return "Error sending SMS: " + e.getMessage();
        }
    }
}