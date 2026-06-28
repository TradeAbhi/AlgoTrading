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

    /**
     * Sends a message to Discord webhook.
     * Discord supports markdown-like formatting.
     *
     * @param message the message content
     */
    public void sendMessage(String message) {
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
