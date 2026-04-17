package com.trading.algo.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.algo.entity.Ipo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class IpoFetcherService {

    // Base URL for ipoalerts.in — no API key required for free tier
    private static final String IPO_API_URL = "https://api.ipoalerts.in/ipos?status=upcoming&status=open";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public List<Ipo> fetchUpcomingIpos() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        headers.set(HttpHeaders.USER_AGENT, "AlgoTradingApp/1.0");

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(
                IPO_API_URL, HttpMethod.GET, entity, String.class);

        log.info("IPO API response status: {}", response.getStatusCode());

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            log.error("Failed to fetch IPOs: {}", response.getStatusCode());
            return List.of();
        }

        return parseIpoResponse(response.getBody());
    }

    private List<Ipo> parseIpoResponse(String json) throws Exception {
        List<Ipo> ipos = new ArrayList<>();
        JsonNode root = objectMapper.readTree(json);

        // ipoalerts.in returns { "data": [ {...}, {...} ] }
        JsonNode dataNode = root.has("data") ? root.get("data") : root;

        if (!dataNode.isArray()) {
            log.warn("Unexpected response structure: {}", json.substring(0, Math.min(300, json.length())));
            return ipos;
        }

        for (JsonNode node : dataNode) {
            try {
                Ipo ipo = new Ipo();
                ipo.setName(getText(node, "name"));
                ipo.setOpenDate(parseDate(getText(node, "startDate")));
                ipo.setCloseDate(parseDate(getText(node, "endDate")));
                ipo.setListingDate(parseDate(getText(node, "listingDate")));

                // Map API status to your status field
                String apiStatus = getText(node, "status");
                ipo.setStatus(mapStatus(apiStatus));

                log.info("Parsed IPO → {} | Open: {} | Close: {} | Listing: {} | Status: {}",
                        ipo.getName(), ipo.getOpenDate(), ipo.getCloseDate(),
                        ipo.getListingDate(), ipo.getStatus());

                ipos.add(ipo);
            } catch (Exception e) {
                log.warn("Failed to parse IPO node: {} | Error: {}", node, e.getMessage());
            }
        }

        log.info("Total IPOs fetched: {}", ipos.size());
        return ipos;
    }

    private String getText(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull()
                ? node.get(field).asText().trim()
                : null;
    }

    private LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank() || dateStr.equals("null")) return null;
        try {
            // ipoalerts.in returns ISO format: "2024-03-01"
            return LocalDate.parse(dateStr);
        } catch (Exception e) {
            log.warn("Could not parse date: '{}'", dateStr);
            return null;
        }
    }

    private String mapStatus(String apiStatus) {
        if (apiStatus == null) return "UPCOMING";
        return switch (apiStatus.toLowerCase()) {
            case "open"    -> "OPEN";
            case "closed"  -> "CLOSED";
            case "listed"  -> "LISTED";
            default        -> "UPCOMING";
        };
    }
}
//
//
//import java.time.LocalDate;
//import java.time.format.DateTimeFormatter;
//import java.util.ArrayList;
//import java.util.List;
//
//import org.jsoup.Jsoup;
//import org.jsoup.nodes.Document;
//import org.jsoup.nodes.Element;
//import org.jsoup.select.Elements;
//import org.springframework.stereotype.Service;
//
//import com.trading.algo.entity.Ipo;
//
//@Service
//public class IpoFetcherService {
//
//    private static final String IPO_URL = "https://www.chittorgarh.com/ipo/ipo_list.asp";
//
//    public List<Ipo> fetchUpcomingIpos() throws Exception {
//
//    	Document doc = Jsoup.connect(IPO_URL)
//    	        .userAgent("Mozilla/5.0")
//    	        .timeout(10000)
//    	        .get();
//
//
//    	// Find correct table dynamically
//    	Elements tables = doc.select("table");
//
//    	List<Ipo> ipos = new ArrayList<>();
//
//    	for (Element table : tables) {
//
//    		 Elements rows = table.select("tr");
//
//    		    for (Element row : rows) {
//
//    		        Elements cols = row.select("td");
//
//    		        if (cols.size() < 5) continue;
//
//    		        String name = cols.get(0).text();
//    		        String openDateStr = cols.get(2).text();
//    		        String closeDateStr = cols.get(3).text();
//    		        String listingDateStr = cols.get(4).text();
//
//    		        // DEBUG PRINT
//    		        System.out.println("Row: " + row.text());
//
//    		        if (name.toLowerCase().contains("ipo")) {
//
//    		            Ipo ipo = new Ipo();
//    		            ipo.setName(name);
//    		            ipo.setOpenDate(parseDate(openDateStr));
//    		            ipo.setCloseDate(parseDate(closeDateStr));
//    		            ipo.setListingDate(parseDate(listingDateStr));
//    		            ipo.setStatus("UPCOMING");
//
//    		            ipos.add(ipo);
//           
//        }
//    		    }}
//        System.out.println("Fetched IPO count: " + ipos.size());
//
//        return ipos;
//    }
//
//    // ✅ Date parser (handles multiple formats safely)
//    private LocalDate parseDate(String dateStr) {
//        try {
//            return LocalDate.parse(dateStr, DateTimeFormatter.ofPattern("dd-MMM-yyyy"));
//        } catch (Exception e) {
//            try {
//                return LocalDate.parse(dateStr, DateTimeFormatter.ofPattern("dd-MM-yyyy"));
//            } catch (Exception ex) {
//                return null;
//            }
//        }
//    }
//}