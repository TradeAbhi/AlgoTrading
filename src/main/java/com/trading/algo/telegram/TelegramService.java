package com.trading.algo.telegram;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class TelegramService {

    private final String      botToken;
    private final String      chatId;
    private final String      investmentPicksBotToken;
    private final String      investmentPicksChatId;
    private final String      intradayBotToken;
    private final String      intradayChatId;
    private final RestTemplate restTemplate = new RestTemplate();

    public TelegramService(Environment env) {
        this.botToken = env.getProperty("telegram.bot.token");
        this.chatId   = env.getProperty("telegram.chat.id");
        this.investmentPicksBotToken = env.getProperty("telegram.investment-picks.bot.token");
        this.investmentPicksChatId = env.getProperty("telegram.investment-picks.chat.id");
        this.intradayBotToken = env.getProperty("telegram.intraday.bot.token");
        this.intradayChatId = env.getProperty("telegram.intraday.chat.id");
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

    // ── Investment Picks Bot (separate channel) ───────────────────────────────

    /**
     * Sends a message to the Investment Picks bot/channel.
     * Used for weekly/daily breakout strategy alerts.
     */
    public void sendMessageToInvestmentPicks(String message) {
        if (investmentPicksBotToken == null || investmentPicksChatId == null) {
            log.warn("Investment picks bot token/chat ID not configured, message not sent");
            return;
        }
        try {
            String url = "https://api.telegram.org/bot" + investmentPicksBotToken + "/sendMessage";

            Map<String, String> body = new HashMap<>();
            body.put("chat_id",    investmentPicksChatId);
            body.put("text",       message);
            body.put("parse_mode", "Markdown");

            restTemplate.postForObject(url, body, String.class);
            log.info("Investment picks message sent ({} chars)", message.length());

        } catch (Exception e) {
            log.error("Investment picks sendMessage failed: {}", e.getMessage());
        }
    }

    /**
     * Sends a document to the Investment Picks bot/channel.
     */
    public void sendDocumentToInvestmentPicks(byte[] fileBytes, String fileName, String caption) {
        if (investmentPicksBotToken == null || investmentPicksChatId == null) {
            log.warn("Investment picks bot token/chat ID not configured, document not sent");
            return;
        }
        try {
            String url = "https://api.telegram.org/bot" + investmentPicksBotToken + "/sendDocument";

            // Build multipart body
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("chat_id",    investmentPicksChatId);
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

             log.info("Investment picks document sent: {} ({} bytes)", fileName, fileBytes.length);

         } catch (Exception e) {
             log.error("Investment picks sendDocument failed for {}: {}", fileName, e.getMessage());
         }
     }

     // ── Intraday Scans & Data Bot (15-min intraday scans) ───────────────────────

     /**
      * Sends a message to the Intraday Scans & Data bot.
      * Used for intraday trading data: top gainers/losers, advance/decline ratio, etc.
      * Triggered every 15 minutes during trading hours.
      */
     public void sendMessageToIntraday(String message) {
         if (intradayBotToken == null || intradayChatId == null) {
             log.warn("Intraday bot token/chat ID not configured, message not sent");
             return;
         }
         try {
             String url = "https://api.telegram.org/bot" + intradayBotToken + "/sendMessage";

             Map<String, String> body = new HashMap<>();
             body.put("chat_id",    intradayChatId);
             body.put("text",       message);
             body.put("parse_mode", "Markdown");

             restTemplate.postForObject(url, body, String.class);
             log.info("Intraday message sent ({} chars)", message.length());

         } catch (Exception e) {
             log.error("Intraday sendMessage failed: {}", e.getMessage());
         }
     }

     /**
      * Sends a document to the Intraday Scans & Data bot.
      * Used for intraday CSV reports (top gainers/losers, advance/decline data, etc.).
      */
     public void sendDocumentToIntraday(byte[] fileBytes, String fileName, String caption) {
         if (intradayBotToken == null || intradayChatId == null) {
             log.warn("Intraday bot token/chat ID not configured, document not sent");
             return;
         }
         try {
             String url = "https://api.telegram.org/bot" + intradayBotToken + "/sendDocument";

             // Build multipart body
             MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
             body.add("chat_id",    intradayChatId);
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

             log.info("Intraday document sent: {} ({} bytes)", fileName, fileBytes.length);

         } catch (Exception e) {
             log.error("Intraday sendDocument failed for {}: {}", fileName, e.getMessage());
         }
     }

 }
