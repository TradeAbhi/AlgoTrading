package com.trading.algo.discord;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class DiscordService {

    private final String webhookUrl;
    private final RestTemplate restTemplate = new RestTemplate();

    public DiscordService() {
        // Discord webhook URL provided by user
        this.webhookUrl = "https://discord.com/api/webhooks/1517752098110836790/_X9tA7FRqbtQagC1BAm_1etzSOxnn71xwUc6zEBEITPQVvjzyKfXeWQaYqL3jJtNvGea";
    }

    private static final int DISCORD_LIMIT = 1990;

    /**
     * Sends a message to Discord webhook, splitting into multiple posts if over 1990 chars.
     */
    public void sendMessage(String message) {
        if (message == null || message.isEmpty()) return;
        if (message.length() <= DISCORD_LIMIT) {
            post(message);
            return;
        }
        // Split on newlines to avoid cutting mid-line
        String[] lines = message.split("\n");
        StringBuilder chunk = new StringBuilder();
        for (String line : lines) {
            if (chunk.length() + line.length() + 1 > DISCORD_LIMIT) {
                post(chunk.toString());
                chunk.setLength(0);
            }
            if (chunk.length() > 0) chunk.append("\n");
            chunk.append(line);
        }
        if (chunk.length() > 0) post(chunk.toString());
    }

    private void post(String message) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("content", message);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            restTemplate.postForObject(webhookUrl, request, String.class);

            log.info("Discord message sent ({} chars)", message.length());

        } catch (Exception e) {
            log.error("Discord sendMessage failed: {}", e.getMessage());
        }
    }
}
