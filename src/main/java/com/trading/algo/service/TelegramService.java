package com.trading.algo.service;

import java.util.HashMap;
import java.util.Map;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class TelegramService {

    private final String botToken;
    private final String chatId;

    public TelegramService(Environment env) {
        this.botToken = env.getProperty("telegram.bot.token");
        this.chatId = env.getProperty("telegram.chat.id");
    }

    public void sendMessage(String message) {
        try {
            String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";

            RestTemplate restTemplate = new RestTemplate();

            Map<String, String> body = new HashMap<>();
            body.put("chat_id", chatId);
            body.put("text", message);

            restTemplate.postForObject(url, body, String.class);
            System.out.println("Telegram message sent: " + message);

        } catch (Exception e) {
            System.err.println("Telegram send failed: " + e.getMessage());
        }
    }
}
