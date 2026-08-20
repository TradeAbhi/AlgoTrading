package com.trading.algo.service;

import com.trading.algo.telegram.TelegramService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class IpoGmpRedirectService {

    private static final String IPO_GMP_URL = "https://www.investorgain.com/report/ipo-gmp-live/331/?filter=ipo";
    private final TelegramService telegramService;

    /**
     * Trigger redirect to IPO GMP URL
     * This method will send a Telegram message with the link when called by the scheduler
     */
    public void triggerRedirect() {
        try {
            log.info("Triggering IPO GMP URL notification: {}", IPO_GMP_URL);
            
            // Send Telegram message with clickable link
            StringBuilder sb = new StringBuilder();
            sb.append("📊 *IPO GMP Live Report*\n");
            sb.append("━━━━━━━━━━━━━━━━━━━━\n");
            sb.append("Click below to view live IPO GMP data:\n\n");
            sb.append("[🔗 Open IPO GMP Report](").append(IPO_GMP_URL).append(")\n");
            sb.append("━━━━━━━━━━━━━━━━━━━━");
            
            telegramService.sendMessage(sb.toString());
            log.info("IPO GMP URL notification sent via Telegram");
        } catch (Exception e) {
            log.error("Failed to send IPO GMP URL notification: {}", e.getMessage(), e);
        }
    }

    /**
     * Get the IPO GMP URL
     */
    public String getIpoGmpUrl() {
        return IPO_GMP_URL;
    }
}
