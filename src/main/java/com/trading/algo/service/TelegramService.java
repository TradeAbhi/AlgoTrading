package com.trading.algo.service;

import java.util.HashMap;
import java.util.Map;

import org.springframework.core.env.Environment;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class TelegramService {

    private final String      botToken;
    private final String      chatId;
    private final RestTemplate restTemplate = new RestTemplate();

    public TelegramService(Environment env) {
        this.botToken = env.getProperty("telegram.bot.token");
        this.chatId   = env.getProperty("telegram.chat.id");
    }

    // ── Text message (existing — unchanged) ──────────────────────────────────

    public void sendMessage(String message) {
        try {
            String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";

            Map<String, String> body = new HashMap<>();
            body.put("chat_id",    chatId);
            body.put("text",       message);
            body.put("parse_mode", "Markdown");

            restTemplate.postForObject(url, body, String.class);
            log.info("Telegram message sent ({} chars)", message.length());

        } catch (Exception e) {
            log.error("Telegram sendMessage failed: {}", e.getMessage());
        }
    }

    // ── File/document (new — for CSV, PDF etc.) ───────────────────────────────

    /**
     * Sends a file as a Telegram document with an optional caption.
     *
     * Uses multipart/form-data — same as attaching a file in Telegram.
     * The file appears as a downloadable attachment in the chat.
     *
     * @param fileBytes  raw bytes of the file (e.g. CSV content)
     * @param fileName   filename shown in Telegram (e.g. "52_week_highs.csv")
     * @param caption    optional message above the file (supports Markdown)
     */
    public void sendDocument(byte[] fileBytes, String fileName, String caption) {
        try {
            String url = "https://api.telegram.org/bot" + botToken + "/sendDocument";

            // Build multipart body
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("chat_id",    chatId);
            body.add("parse_mode", "Markdown");

            if (caption != null && !caption.isBlank()) {
                body.add("caption", caption);
            }

            // Wrap bytes as a named resource so Telegram knows the filename
            ByteArrayResource fileResource = new ByteArrayResource(fileBytes) {
                @Override
                public String getFilename() {
                    return fileName;
                }
            };
            body.add("document", fileResource);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);
            restTemplate.postForObject(url, request, String.class);

            log.info("Telegram document sent: {} ({} bytes)", fileName, fileBytes.length);

        } catch (Exception e) {
            log.error("Telegram sendDocument failed for {}: {}", fileName, e.getMessage());
        }
    }
}