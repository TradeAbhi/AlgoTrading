package com.trading.algo.earning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.algo.entity.Earnings;
import com.trading.algo.repo.EarningsRepository;
import lombok.RequiredArgsConstructor;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.cookie.BasicCookieStore;
import org.apache.hc.client5.http.cookie.CookieStore;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EarningsService {

    private final EarningsRepository repo;

    public void fetchAndStoreData() {
        try {
            CookieStore cookieStore = new BasicCookieStore();
            CloseableHttpClient httpClient = HttpClients.custom()
                    .setDefaultCookieStore(cookieStore)
                    .build();

            // Step 1: Hit homepage to get session cookies
            HttpGet homepageRequest = new HttpGet("https://www.nseindia.com");
            homepageRequest.setHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            homepageRequest.setHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            homepageRequest.setHeader("Accept-Language", "en-US,en;q=0.5");
            homepageRequest.setHeader("Connection", "keep-alive");
            httpClient.execute(homepageRequest, response -> null);

            Thread.sleep(2000);

            // Step 2: Fetch event calendar (all upcoming corporate events)
            String url = "https://www.nseindia.com/api/event-calendar";
            HttpGet apiRequest = new HttpGet(url);
            apiRequest.setHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            apiRequest.setHeader("Accept", "application/json, text/plain, */*");
            apiRequest.setHeader("Accept-Language", "en-US,en;q=0.5");
            apiRequest.setHeader("Referer", "https://www.nseindia.com/");
            apiRequest.setHeader("X-Requested-With", "XMLHttpRequest");
            apiRequest.setHeader("Connection", "keep-alive");

            String responseBody = httpClient.execute(apiRequest, r -> EntityUtils.toString(r.getEntity()));

            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(responseBody);

            // Load existing keys once
            Set<String> existingKeys = repo.findAll().stream()
                    .map(e -> e.getSymbol() + "_" + e.getResultDate())
                    .collect(Collectors.toSet());

            List<Earnings> toSave = new ArrayList<>();
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd-MMM-yyyy");

            for (JsonNode node : root) {
                if (node.get("symbol") == null || node.get("company") == null ||
                    node.get("date") == null || node.get("purpose") == null) continue;

                String symbol   = node.get("symbol").asText();
                String company  = node.get("company").asText();
                String dateStr  = node.get("date").asText();
                String purpose  = node.get("purpose").asText();

                if (dateStr.isBlank() || dateStr.equals("null")) continue;

                LocalDate eventDate;
                try {
                    eventDate = LocalDate.parse(dateStr, fmt);
                } catch (Exception ex) {
                    System.err.println("Skipping bad date for " + symbol + ": " + dateStr);
                    continue;
                }

                String key = symbol + "_" + eventDate;
                if (!existingKeys.contains(key)) {
                    toSave.add(new Earnings(null, symbol, company, eventDate, purpose, false, false, false));
                    existingKeys.add(key);
                }
            }

            repo.saveAll(toSave);
            System.out.println("Saved " + toSave.size() + " new records.");
            httpClient.close();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

//Step 3: Hit the actual API with cookies now set
			//String url = "https://www.nseindia.com/api/corporate-announcements?index=equities";
			//String url = "https://www.nseindia.com/api/corporate-announcements?index=equities&subject=Board Meeting";

//for (JsonNode node : root) {
//// Guard: skip if required fields are missing
//if (node.get("subject") == null || node.get("symbol") == null ||
//    node.get("sm_name") == null || node.get("bm_date") == null) {
//    continue;
//}
//
//// String subject = node.get("subject").asText();
//String subject = node.get("desc").asText();
//
//if (subject.toLowerCase().contains("board meeting") ||
//    subject.toLowerCase().contains("financial results")) {
//
//    String symbol  = node.get("symbol").asText();
//    String company = node.get("sm_name").asText();
//    String dateStr = node.get("bm_date").asText();
//
//    // Also guard against blank date string
//    if (dateStr.isBlank() || dateStr.equals("null")) continue;
//
//    LocalDate resultDate;
//    try {
//        resultDate = LocalDate.parse(dateStr, fmt);
//    } catch (Exception ex) {
//        System.err.println("Skipping unparseable date for " + symbol + ": " + dateStr);
//        continue;
//    }
//
//    String key = symbol + "_" + resultDate;
//    if (!existingKeys.contains(key)) {
//        toSave.add(new Earnings(null, symbol, company, resultDate, false, false));
//        existingKeys.add(key);
//    }
//}
//}

