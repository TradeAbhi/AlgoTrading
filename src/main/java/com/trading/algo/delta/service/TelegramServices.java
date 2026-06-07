package com.trading.algo.delta.service;

import com.trading.algo.delta.model.AlertSignal;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Sends formatted alert messages to a Telegram bot/channel.
 *
 * Bot setup:
 *  1. Create a bot with @BotFather → get BOT_TOKEN
 *  2. Send /start to your bot (or add it to a group/channel)
 *  3. Get CHAT_ID from https://api.telegram.org/bot<TOKEN>/getUpdates
 */
@Slf4j
@Service
public class TelegramServices {

    private static final String TELEGRAM_API = "https://api.telegram.org";
    private static final MediaType JSON_TYPE  = MediaType.get("application/json; charset=utf-8");
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    @Value("${telegram.delta.bot.token}")
    private String botToken;

    @Value("${telegram.delta.chat.id}")
    private String chatId;

    private final OkHttpClient httpClient;

    public TelegramServices(OkHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * Sends a formatted Telegram message for the given signal.
     */
    public void sendAlert(AlertSignal signal) {
        String message = buildMessage(signal);
        sendMessage(message);
    }

    /**
     * Sends a plain text message (used for startup / daily summary).
     */
    public void sendMessage(String text) {
        String url  = TELEGRAM_API + "/bot" + botToken + "/sendMessage";
        String body = buildJsonBody(chatId, text);

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body, JSON_TYPE))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                log.info("Telegram message sent successfully.");
            } else {
                log.error("Telegram API error: HTTP {} | body={}",
                        response.code(),
                        response.body() != null ? response.body().string() : "null");
            }
        } catch (IOException e) {
            log.error("Failed to send Telegram message: {}", e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private String buildMessage(AlertSignal signal) {
        String emoji     = signal.getDirection() == AlertSignal.Direction.BEARISH_BREAKDOWN ? "🔴" : "🟢";
        String direction = signal.getDirection() == AlertSignal.Direction.BEARISH_BREAKDOWN
                ? "BEARISH BREAKDOWN — SELL SIGNAL 📉"
                : "BULLISH BREAKOUT — BUY SIGNAL 📈";
        String levelLabel = signal.getDirection() == AlertSignal.Direction.BEARISH_BREAKDOWN
                ? "Previous Day Low"
                : "Previous Day High";

        return String.format("""
                %s *%s | %s*

                🕯 Candle Closed: `%s`
                📍 %s: `%s`
                ⏰ Candle Close Time: `%s`

                #%s #futures #alert""",
                emoji,
                signal.getSymbol(),
                direction,
                signal.getCandleClose().toPlainString(),
                levelLabel,
                signal.getLevel().toPlainString(),
                FMT.format(signal.getCandleCloseTime()),
                signal.getSymbol().replace("-", "")
        );
    }

    private String buildJsonBody(String chatId, String text) {
        // Escape backslashes and double-quotes in the text for JSON safety
        String escaped = text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");

        return "{" +
               "\"chat_id\":\"" + chatId + "\"," +
               "\"text\":\"" + escaped + "\"," +
               "\"parse_mode\":\"Markdown\"" +
               "}";
    }
}
