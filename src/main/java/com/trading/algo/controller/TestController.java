package com.trading.algo.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.trading.algo.entity.Earnings;
import com.trading.algo.repo.EarningsRepository;
import com.trading.algo.service.EarningsService;
import com.trading.algo.service.GlobalMarketService;
import com.trading.algo.service.MarketSentimentService;
import com.trading.algo.service.SchedulerService;
import com.trading.algo.service.TelegramService;

import lombok.RequiredArgsConstructor;

//Only keep these 2:
//1. Quarterly/Annual Results — 🏆 Primary
//
//Biggest intraday candles of the year for a stock
//Gap up/down on open, then continuation or reversal — both tradeable
//Volume spikes 5–10x normal → tight spreads, easy entries/exits
//Works for both breakout and reversal intraday strategies
//
//2. Board Meetings (with Results agenda only) — ✅ Secondary
//
//Board meetings called specifically to declare results behave identically to results day
//NSE's event-calendar includes the agenda in the purpose field — you can filter precisely
@RestController
@RequiredArgsConstructor
public class TestController {

	private final EarningsService earningsService;
	private final SchedulerService schedulerService;
	private final EarningsRepository repo;
	private final TelegramService telegramService;
	private final MarketSentimentService marketSentimentService;

private final GlobalMarketService globalMarketService;
//    @GetMapping("/callback")
//    public String handleCallback(@RequestParam String code) {
//        return code;
//    }

@PostMapping("/api/earnings/refresh")
public ResponseEntity<Map<String, String>> refreshEarnings() {
    earningsService.fetchAndStoreData();
    return ResponseEntity.ok(Map.of("status", "Earnings table refreshed from NSE"));
}

@GetMapping("/test/global")
public String testGlobal() {
	globalMarketService.preMarketGlobalCues();
    return "Global cues sent. Check Telegram.";
}

	@GetMapping("/test/sentiment")
	public String testSentiment() {
		marketSentimentService.morningSentimentAlert();
		return "Sentiment alert sent. Check Telegram.";
	}

	@GetMapping("/test/daily")
	public String daily() {
		earningsService.fetchAndStoreData(); // Pull from NSE
		schedulerService.weekAheadAlerts(); // 7-day alerts
		schedulerService.dayAheadAlerts(); // Tomorrow alerts
		schedulerService.todayAlerts(); // Today alerts
		schedulerService.weeklySummaryAlert();
		return "Daily job complete. Check Telegram.";
	}

	@GetMapping("/test/debug/week-ahead")
	public String debugWeekAhead() {
		LocalDate target = LocalDate.now().plusDays(7);
		List<Earnings> list = repo.findByResultDate(target);

		if (list.isEmpty()) {
			return "No events found for date: " + target;
		}

		StringBuilder sb = new StringBuilder("Target date: " + target + "\n");
		for (Earnings e : list) {
			sb.append(e.getSymbol()).append(" | alertSentWeek=").append(e.isAlertSentWeek()).append(" | event=")
					.append(e.getEventType()).append("\n");
		}
		return sb.toString();
	}

	// Fetch & store latest events from NSE
	@GetMapping("/test/fetch")
	public String fetch() {
		earningsService.fetchAndStoreData();
		return "Fetch complete. Check logs for saved count.";
	}

	// Trigger 7-day ahead alerts manually
	@GetMapping("/test/week-ahead")
	public String weekAhead() {
		schedulerService.weekAheadAlerts();
		return "7-day ahead alerts sent.";
	}

	// Trigger 1-day ahead alerts manually
	@GetMapping("/test/day-ahead")
	public String dayAhead() {
		schedulerService.dayAheadAlerts();
		return "1-day ahead alerts sent.";
	}

	// Trigger today's alerts manually
	@GetMapping("/test/today")
	public String today() {
		schedulerService.todayAlerts();
		return "Today alerts sent.";
	}

	// View all stored events (with optional date range filter)
	@GetMapping("/test/events")
	public List<Earnings> getAllEvents(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

		if (from != null && to != null) {
			return repo.findByResultDateBetween(from, to);
		}
		return repo.findAll();
	}

	// View only today's events
	@GetMapping("/test/events/today")
	public List<Earnings> todayEvents() {
		return repo.findByResultDate(LocalDate.now());
	}

	// View events for next 7 days
	@GetMapping("/test/events/upcoming")
	public List<Earnings> upcomingEvents() {
		return repo.findByResultDateBetween(LocalDate.now(), LocalDate.now().plusDays(7));
	}

	// Manually reset all alert flags (useful during testing)
	@GetMapping("/test/reset-flags")
	public String resetFlags() {
		List<Earnings> all = repo.findAll();
		all.forEach(e -> {
			e.setAlertSentWeek(false);
			e.setAlertSentDay(false);
			e.setAlertSentToday(false);
		});
		repo.saveAll(all);
		return "Reset alert flags for " + all.size() + " records.";
	}

	// Trigger all alerts at once (full test run)
	@GetMapping("/test/run-all")
	public String runAll() {
		schedulerService.weekAheadAlerts();
		schedulerService.dayAheadAlerts();
		schedulerService.todayAlerts();
		return "All alert schedulers triggered.";
	}

	@GetMapping("/test/telegram")
	public String testTelegram() {
		telegramService.sendMessage("✅ AlgoTrading bot is working!");
		return "Message sent. Check Telegram.";
	}

	@GetMapping("/test/weekly-summary")
	public String weeklySummary() {
		schedulerService.weeklySummaryAlert();
		return "Weekly summary sent to Telegram.";
	}
}