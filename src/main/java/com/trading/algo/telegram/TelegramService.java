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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class TelegramService {

    private final String      botToken;
    private final String      chatId;
    private final String      investmentPicksBotToken;
    private final String      intradayBotToken;
    private final String      earningsBotToken;
    private final String      moverBotToken;
    private final String      indexBotToken;
    private final String      holdingsBotToken;
    private final String      usBotToken;
    private final RestTemplate restTemplate = new RestTemplate();

    public TelegramService(Environment env) {
        this.botToken = env.getProperty("telegram.bot.token");
        this.chatId   = env.getProperty("telegram.chat.id");
        this.investmentPicksBotToken = env.getProperty("telegram.investment-picks.bot.token");
        this.intradayBotToken = env.getProperty("telegram.intraday.bot.token");
        this.earningsBotToken = env.getProperty("telegram.earnings.bot.token");
        this.moverBotToken = env.getProperty("telegram.mover.bot.token");
        this.indexBotToken = env.getProperty("telegram.index.bot.token");
        this.holdingsBotToken = env.getProperty("telegram.holdings.bot.token");
        this.usBotToken = env.getProperty("telegram.us.bot.token");
    }

    // ── Text message (existing — unchanged) ──────────────────────────────────

    public void sendMessage(String message) {
        sendChunkedMessage(botToken, chatId, message, "Telegram");
    }

    /**
     * Splits a long message into chunks and sends them sequentially.
     * Telegram has a 4096 character limit per message.
     */
    private void sendChunkedMessage(String token, String chatId, String message, String botName) {
        final int MAX_LENGTH = 4096;
        
        if (message.length() <= MAX_LENGTH) {
            sendSingleMessage(token, chatId, message, botName);
            return;
        }

        List<String> chunks = splitMessageIntoChunks(message, MAX_LENGTH);
        log.info("Sending {} chunks for {} (total {} chars)", chunks.size(), botName, message.length());
        
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            String chunkLabel = chunks.size() > 1 ? String.format(" [%d/%d]", i + 1, chunks.size()) : "";
            sendSingleMessage(token, chatId, chunk + chunkLabel, botName);
        }
    }

    /**
     * Splits a message into chunks that don't exceed maxLength.
     * Tries to split at newlines to preserve formatting.
     */
    private List<String> splitMessageIntoChunks(String message, int maxLength) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        
        while (start < message.length()) {
            int end = Math.min(start + maxLength, message.length());
            
            // If we're not at the end and the chunk is at max length, try to split at a newline
            if (end < message.length() && end == start + maxLength) {
                int lastNewline = message.lastIndexOf('\n', end - 1);
                if (lastNewline > start) {
                    end = lastNewline + 1;
                }
            }
            
            chunks.add(message.substring(start, end));
            start = end;
        }
        
        return chunks;
    }

    /**
     * Sends a single message to the specified bot/chat.
     */
    private void sendSingleMessage(String token, String chatId, String message, String botName) {
        try {
            String url = "https://api.telegram.org/bot" + token + "/sendMessage";

            Map<String, String> body = new HashMap<>();
            body.put("chat_id",    chatId);
            body.put("text",       message);
            body.put("parse_mode", "Markdown");

            restTemplate.postForObject(url, body, String.class);
            log.info("{} message sent ({} chars)", botName, message.length());

        } catch (Exception e) {
            log.error("{} sendMessage failed: {}", botName, e.getMessage());
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
        if (investmentPicksBotToken == null) {
            log.warn("Investment picks bot token not configured, message not sent");
            return;
        }
        sendChunkedMessage(investmentPicksBotToken, chatId, message, "Investment picks");
    }

    /**
     * Sends a document to the Investment Picks bot/channel.
     */
    public void sendDocumentToInvestmentPicks(byte[] fileBytes, String fileName, String caption) {
        if (investmentPicksBotToken == null) {
            log.warn("Investment picks bot token not configured, document not sent");
            return;
        }
        try {
            String url = "https://api.telegram.org/bot" + investmentPicksBotToken + "/sendDocument";

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
         if (intradayBotToken == null) {
             log.warn("Intraday bot token not configured, message not sent");
             return;
         }
         sendChunkedMessage(intradayBotToken, chatId, message, "Intraday");
     }

     /**
      * Sends a document to the Intraday Scans & Data bot.
      * Used for intraday CSV reports (top gainers/losers, advance/decline data, etc.).
      */
     public void sendDocumentToIntraday(byte[] fileBytes, String fileName, String caption) {
         if (intradayBotToken == null) {
             log.warn("Intraday bot token not configured, document not sent");
             return;
         }
         try {
             String url = "https://api.telegram.org/bot" + intradayBotToken + "/sendDocument";

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

             log.info("Intraday document sent: {} ({} bytes)", fileName, fileBytes.length);

         } catch (Exception e) {
             log.error("Intraday sendDocument failed for {}: {}", fileName, e.getMessage());
         }
     }

     // ── Earnings Bot (earnings-related alerts) ───────────────────────────────

     /**
      * Sends a message to the Earnings bot.
      * Used for earnings-related alerts: weekly summaries, 7-day ahead, day ahead, today alerts.
      */
     public void sendMessageToEarnings(String message) {
         if (earningsBotToken == null) {
             log.warn("Earnings bot token not configured, message not sent");
             return;
         }
         sendChunkedMessage(earningsBotToken, chatId, message, "Earnings");
     }

     /**
      * Sends a document to the Earnings bot.
      * Used for earnings-related CSV reports.
      */
     public void sendDocumentToEarnings(byte[] fileBytes, String fileName, String caption) {
         if (earningsBotToken == null) {
             log.warn("Earnings bot token not configured, document not sent");
             return;
         }
         try {
             String url = "https://api.telegram.org/bot" + earningsBotToken + "/sendDocument";

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

             log.info("Earnings document sent: {} ({} bytes)", fileName, fileBytes.length);

         } catch (Exception e) {
             log.error("Earnings sendDocument failed for {}: {}", fileName, e.getMessage());
         }
     }

     // ── Mover Bot (mover analysis alerts) ───────────────────────────────────

     /**
      * Sends a message to the Mover bot.
      * Used for mover analysis alerts: stock movers, sector analysis, etc.
      */
     public void sendMessageToMover(String message) {
         if (moverBotToken == null) {
             log.warn("Mover bot token not configured, message not sent");
             return;
         }
         sendChunkedMessage(moverBotToken, chatId, message, "Mover");
     }

     /**
      * Sends a document to the Mover bot.
      * Used for mover analysis CSV reports.
      */
     public void sendDocumentToMover(byte[] fileBytes, String fileName, String caption) {
         if (moverBotToken == null) {
             log.warn("Mover bot token not configured, document not sent");
             return;
         }
         try {
             String url = "https://api.telegram.org/bot" + moverBotToken + "/sendDocument";

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

             log.info("Mover document sent: {} ({} bytes)", fileName, fileBytes.length);

         } catch (Exception e) {
             log.error("Mover sendDocument failed for {}: {}", fileName, e.getMessage());
         }
     }

     // ── Index Bot (Nifty / Bank Nifty strategy alerts) ────────────────────────

     /**
      * Sends a message to the Index bot.
      * Used for: Consolidation Breakout, Index Futures Volume Spike,
      * and Index Strength alerts for Nifty 50 and Bank Nifty.
      */
     public void sendMessageToIndex(String message) {
         if (indexBotToken == null) {
             log.warn("Index bot token not configured, message not sent");
             return;
         }
         sendChunkedMessage(indexBotToken, chatId, message, "Index");
     }

    /** Sends an alert produced by the consolidated Upstox/Dhan holdings scan. */
    public void sendMessageToHoldings(String message) {
        if (holdingsBotToken == null || holdingsBotToken.isBlank()) {
            log.warn("Holdings bot token not configured, message not sent");
            return;
        }
        sendChunkedMessage(holdingsBotToken, chatId, message, "Holdings");
    }

    // ── US Bot (US Weekly Breakout alerts) ───────────────────────────────────

    /**
     * Sends a message to the US bot.
     * Used for US Weekly Breakout strategy alerts.
     */
    public void sendMessageToUs(String message) {
        if (usBotToken == null) {
            log.warn("US bot token not configured, message not sent");
            return;
        }
        sendChunkedMessage(usBotToken, chatId, message, "US");
    }

 }
