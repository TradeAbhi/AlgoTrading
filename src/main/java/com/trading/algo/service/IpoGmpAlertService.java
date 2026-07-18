package com.trading.algo.service;

import com.trading.algo.ipo.Ipo;
import com.trading.algo.ipo.IpoRepository;
import com.trading.algo.telegram.TelegramService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Service to monitor upcoming mainboard IPOs and send alerts.
 * 
 * Alerts for upcoming mainboard IPOs (securityType = EQ) to help identify buying opportunities.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IpoGmpAlertService {

    private final IpoRepository ipoRepo;
    private final TelegramService telegramService;
    private final IpoFetcherService ipoFetcherService;

    /**
     * Check for upcoming mainboard IPOs and send alerts.
     * This should be scheduled to run periodically (e.g., daily at 10 AM).
     */
    @Transactional
    public void checkAndAlertGoodGmp() {
        log.info("Checking for upcoming mainboard IPOs");

        LocalDate today = LocalDate.now();
        LocalDate nextTwoWeeks = today.plusDays(14);

        // Find upcoming mainboard IPOs (EQ type) that haven't been alerted yet
        List<Ipo> upcomingIpos = ipoRepo.findByListingDateBetweenOrderByListingDateAsc(today, nextTwoWeeks);
        List<Ipo> mainboardIpos = upcomingIpos.stream()
                .filter(ipo -> "EQ".equals(ipo.getSecurityType()) && (ipo.getAlertGmpSent() == null || !ipo.getAlertGmpSent()))
                .toList();

        if (mainboardIpos.isEmpty()) {
            log.info("No upcoming mainboard IPOs found");
            return;
        }

        log.info("Found {} upcoming mainboard IPOs", mainboardIpos.size());

        for (Ipo ipo : mainboardIpos) {
            try {
                sendMainboardIpoAlert(ipo);
                ipo.setAlertGmpSent(true);
                ipoRepo.save(ipo);
                log.info("Mainboard IPO alert sent for {}", ipo.getName());
            } catch (Exception e) {
                log.error("Failed to send mainboard IPO alert for {}: {}", ipo.getName(), e.getMessage());
            }
        }
    }

    /**
     * Send Telegram alert for upcoming mainboard IPO.
     */
    private void sendMainboardIpoAlert(Ipo ipo) {
        StringBuilder sb = new StringBuilder();
        sb.append("🚀 *Upcoming Mainboard IPO*\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("🏢 ").append(ipo.getName()).append("\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━\n");
        
        if (ipo.getIssuePrice() != null) {
            sb.append("💰 Issue Price: ₹").append(String.format("%.2f", ipo.getIssuePrice())).append("\n");
        }
        if (ipo.getPriceRange() != null) {
            sb.append("📊 Price Range: ").append(ipo.getPriceRange()).append("\n");
        }
        if (ipo.getLotSize() != null) {
            sb.append("📦 Lot Size: ").append(ipo.getLotSize()).append(" shares\n");
        }
        if (ipo.getIssueSize() != null) {
            sb.append("📈 Issue Size: ").append(ipo.getIssueSize()).append("\n");
        }
        
        if (ipo.getOpenDate() != null) {
            sb.append("📅 Open Date: ").append(ipo.getOpenDate()).append("\n");
        }
        if (ipo.getCloseDate() != null) {
            sb.append("📅 Close Date: ").append(ipo.getCloseDate()).append("\n");
        }
        if (ipo.getListingDate() != null) {
            sb.append("📅 Listing Date: ").append(ipo.getListingDate()).append("\n");
        }
        
        sb.append("━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("_Mainboard IPO - Consider applying_");

        telegramService.sendMessageToInvestmentPicks(sb.toString());
    }

    /**
     * Manually trigger GMP check for testing.
     */
    public void manualGmpCheck() {
        log.info("Manual GMP check triggered");
        checkAndAlertGoodGmp();
    }

    /**
     * Get summary of upcoming mainboard IPOs.
     */
    public String getGmpSummary() {
        LocalDate today = LocalDate.now();
        LocalDate nextTwoWeeks = today.plusDays(14);
        
        List<Ipo> upcomingIpos = ipoRepo.findByListingDateBetweenOrderByListingDateAsc(today, nextTwoWeeks);
        List<Ipo> mainboardIpos = upcomingIpos.stream()
                .filter(ipo -> "EQ".equals(ipo.getSecurityType()))
                .toList();
        
        if (mainboardIpos.isEmpty()) {
            return "No upcoming mainboard IPOs found";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("📊 *Upcoming Mainboard IPOs*\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━\n");

        for (Ipo ipo : mainboardIpos) {
            sb.append("🏢 ").append(ipo.getName()).append("\n");
            if (ipo.getIssuePrice() != null) {
                sb.append("   💰 Issue: ₹").append(String.format("%.2f", ipo.getIssuePrice())).append("\n");
            }
            if (ipo.getListingDate() != null) {
                sb.append("   📅 Listing: ").append(ipo.getListingDate()).append("\n");
            }
            sb.append("\n");
        }

        sb.append("━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("Total: ").append(mainboardIpos.size()).append(" mainboard IPOs");

        return sb.toString();
    }
}
