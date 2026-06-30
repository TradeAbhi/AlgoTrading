# Earnings & Schedulers API Documentation

## Overview
This document lists all REST APIs and scheduled tasks related to **Earnings Management** and **Scheduler Services** in the AlgoTrading application.

---

## 📋 REST APIs

### 1. Earnings Alerts Controller
**Base Path:** `/api/earnings-alerts`
**File:** `EarningsAlertController.java`
**Description:** Manual triggers for earnings Telegram alerts. All endpoints mirror what the scheduler runs automatically — useful for testing or re-sending a missed alert.

#### Endpoints:

| Method | Endpoint | Description | Curl Example |
|--------|----------|-------------|--------------|
| POST | `/api/earnings-alerts/weekly-summary` | F&O stocks with results in the next 7 days (Same as Monday 8AM alert) | `curl -X POST http://localhost:8080/api/earnings-alerts/weekly-summary` |
| POST | `/api/earnings-alerts/window` | Full pre-10/post-3 window stock list. PRE_EARNINGS (next 10 days) + POST_EARNINGS (last 3 days) | `curl -X POST http://localhost:8080/api/earnings-alerts/window` |

**Status:** 2 active endpoints (4 additional endpoints commented out: `/7-day`, `/tomorrow`, `/today`)

---

### 2. Earnings Watchlist Controller
**Base Path:** `/api/earnings-watchlist`
**File:** `EarningsWatchlistController.java`
**Description:** REST controller for the earnings-based watchlist management.

#### Endpoints:

| Method | Endpoint | Description | Curl Example |
|--------|----------|-------------|--------------|
| GET | `/api/earnings-watchlist` | All active stocks (PRE + POST phases), sorted by result date ascending | `curl http://localhost:8080/api/earnings-watchlist` |
| GET | `/api/earnings-watchlist/pre` | PRE_EARNINGS only - stocks where result is still upcoming (within next 10 days) | `curl http://localhost:8080/api/earnings-watchlist/pre` |
| GET | `/api/earnings-watchlist/post` | POST_EARNINGS only - stocks where result already happened (within last 3 days) | `curl http://localhost:8080/api/earnings-watchlist/post` |
| POST | `/api/earnings-watchlist/sync` | Manually trigger full sync: expires stale rows, transitions phases, ingests new Earnings | `curl -X POST http://localhost:8080/api/earnings-watchlist/sync` |
| GET | `/api/earnings-watchlist/diff` | Compares current active earnings window against last snapshot sent via `/window` endpoint. Add `?notify=true` to send diff as Telegram message | `curl http://localhost:8080/api/earnings-watchlist/diff?notify=true` |

**Status:** 5 active endpoints

---

### 3. Test Controller (Earnings Operations)
**Base Path:** `/test` and `/api/earnings`
**File:** `TestController.java`
**Description:** Test and debug endpoints for earnings and scheduler operations.

#### Endpoints:

| Method | Endpoint | Description | Curl Example |
|--------|----------|-------------|--------------|
| POST | `/api/earnings/refresh` | Manually refresh earnings table from NSE | `curl -X POST http://localhost:8080/api/earnings/refresh` |
| GET | `/test/daily` | Execute all daily earnings jobs (fetch, 7-day alerts, day-ahead alerts, today alerts, weekly summary) | `curl http://localhost:8080/test/daily` |
| GET | `/test/week-ahead` | Manually trigger 7-day ahead alerts | `curl http://localhost:8080/test/week-ahead` |
| GET | `/test/day-ahead` | Manually trigger 1-day ahead alerts | `curl http://localhost:8080/test/day-ahead` |
| GET | `/test/today` | Manually trigger today's alerts | `curl http://localhost:8080/test/today` |
| GET | `/test/fetch` | Manually fetch and store latest events from NSE | `curl http://localhost:8080/test/fetch` |
| GET | `/test/events` | View all stored earnings events (with optional date range filter via `?from=YYYY-MM-DD&to=YYYY-MM-DD`) | `curl http://localhost:8080/test/events` |
| GET | `/test/events/today` | View only today's earnings events | `curl http://localhost:8080/test/events/today` |
| GET | `/test/events/upcoming` | View earnings events for next 7 days | `curl http://localhost:8080/test/events/upcoming` |
| GET | `/test/debug/week-ahead` | Debug 7-day ahead earnings - shows symbol, alert flags, and event type | `curl http://localhost:8080/test/debug/week-ahead` |
| GET | `/test/reset-flags` | Manually reset all alert flags (useful during testing) | `curl http://localhost:8080/test/reset-flags` |
| GET | `/test/run-all` | Trigger all alert schedulers at once (full test run) | `curl http://localhost:8080/test/run-all` |
| GET | `/test/weekly-summary` | Manually trigger weekly summary alert | `curl http://localhost:8080/test/weekly-summary` |

**Status:** 13 active endpoints

---

## ⏰ Scheduled Tasks (SchedulerService.java)

### A. Earnings Data Fetching

| Cron Expression | Time | Method Name | Description |
|-----------------|------|-------------|-------------|
| `0 0 7 * * ?` | 7:00 AM (Daily) | `fetchData()` | Fetches from NSE and saves into the earnings table (all events) |

---

### B. Earnings Telegram Alerts (F&O Only)

All alerts read from `earnings_watchlist` (F&O stocks within pre-10/post-3 window only).

| Cron Expression | Time | Method Name | Description | Details |
|-----------------|------|-------------|-------------|---------|
| `0 0 9 * * MON-FRI` (Asia/Kolkata) | 9:00 AM (Weekdays) | `weekAheadAlerts()` | Alert for F&O stocks whose result is exactly 7 days away | Only PRE_EARNINGS phase stocks |
| `0 5 9 * * MON-FRI` (Asia/Kolkata) | 9:05 AM (Weekdays) | `dayAheadAlerts()` | Alert for F&O stocks whose result is tomorrow | Only PRE_EARNINGS phase stocks |
| `0 10 9 * * MON-FRI` (Asia/Kolkata) | 9:10 AM (Weekdays) | `todayAlerts()` | Alert for F&O stocks whose result is today | Only POST_EARNINGS phase stocks |
| `0 0 8 * * MON` (Asia/Kolkata) | 8:00 AM (Every Monday) | `weeklySummaryAlert()` | Weekly summary of F&O stocks with results in next 7 days | Only PRE_EARNINGS phase stocks within 7-day window |

**Note:** The following alerts have schedulers but NO scheduled timing - they are only triggered manually:
- `sendFullEarningsWindowAlert()` - Shows complete pre-10/post-3 window (triggered via `/api/earnings-alerts/window` endpoint or manually in tests)

---

### C. Market Sentiment & Advance/Decline Ratio

| Cron Expression | Time | Method Name | Description |
|-----------------|------|-------------|-------------|
| `0 15/5 9 * * MON-FRI` (Asia/Kolkata) | 9:15-9:45 AM (Every 5 minutes, Weekdays) | `adRatioEvery5Min()` | Early market breadth monitoring - 5 min intervals |
| `0 45/15 9 * * MON-FRI` (Asia/Kolkata) | 9:45 AM + every 15 min (Weekdays) | `adRatioEvery15MinMorning()` | Market breadth monitoring - 15 min intervals (morning) |
| `0 0/15 10-15 * * MON-FRI` (Asia/Kolkata) | 10:00 AM - 3:30 PM, every 15 min (Weekdays) | `adRatioEvery15Min()` | Market breadth monitoring - 15 min intervals (throughout day) |

---

### D. Opening Candle Strategy

| Cron Expression | Time | Method Name | Description |
|-----------------|------|-------------|-------------|
| `0 46 9 * * MON-FRI` (Asia/Kolkata) | 9:46 AM (Weekdays) | `openingCandleStrategyAlert()` | By 9:46 the 9:15 and 9:30 candles are fully formed. Fetches today's F&O candles, runs strategy rules, sends Telegram alert with BUY/SELL setups. |

---

### E. 52-Week High/Low Scanning (Weekly)

| Cron Expression | Time | Method Name | Description |
|-----------------|------|-------------|-------------|
| `0 0 10 * * FRI` (Asia/Kolkata) | 10:00 AM (Every Friday) | `csvUploadMorningReminder()` | Morning reminder: 52-week high CSV scan scheduled for today at 3:50 PM and 4:00 PM |
| `0 50 15 * * FRI` (Asia/Kolkata) | 3:50 PM (Every Friday) | `csvUploadReminder()` | Reminder to upload NSE 52-week high CSV (10 minutes before scan runs) |
| `0 0 16 * * FRI` (Asia/Kolkata) | 4:00 PM (Every Friday) | `weekHighLowAlert()` | Runs after weekly candle closes. Fetches 52-week high/low stocks from NSE, sends CSV files to Telegram. |

---

### F. IPO Monitoring

| Cron Expression | Time | Method Name | Description |
|-----------------|------|-------------|-------------|
| `0 0 9 * * *` | 9:00 AM (Daily) | `ipoAlertScheduler()` | Syncs IPOs from database. Sends alerts for: 10 days before listing, IPO open day, listing day |
| `0 30 9 * * MON-FRI` (Asia/Kolkata) | 9:30 AM (Weekdays) | `ipoListingOpenAlert()` | IPO listing open performance alert (price vs issue price) |
| `0 35 15 * * MON-FRI` (Asia/Kolkata) | 3:35 PM (Weekdays) | `ipoListingEodAlert()` | IPO listing EOD performance alert (full OHLC vs issue price) |
| `0 0 8 * * *` | 8:00 AM (Daily) | `dailyIpoSync()` | Daily IPO data sync from external source |

---

## 📊 Data Models

### EarningsWatchlist (Relevant Fields)
- `symbol` - Stock ticker
- `resultDate` - Date of earnings result
- `phase` - WatchPhase (PRE_EARNINGS, POST_EARNINGS, EXPIRED)
- `eventType` - Type of event (Q1/Q2/Q3/Q4 Results, etc.)
- `daysToResult` - Calculated days until result date

### WatchPhase Enum
- **PRE_EARNINGS** - Result is upcoming (within next 10 days)
- **POST_EARNINGS** - Result already happened (within last 3 days)
- **EXPIRED** - Outside the observation window

---

## 🔍 Key Observations

1. **Earnings Window:** Pre-10 days (upcoming) and Post-3 days (past) only
2. **F&O Only:** All watchlist-based alerts filter for F&O stocks only
3. **Daily Earnings Fetch:** Runs at 7:00 AM daily from NSE
4. **Alert Cascade:**
   - 7-day ahead alert: 9:00 AM
   - 1-day ahead alert: 9:05 AM  
   - Today alert: 9:10 AM
   - Weekly summary: Monday 8:00 AM (7-day window)
5. **Market Hours Coverage:** Multiple breadth monitoring throughout trading hours
6. **Friday Schedule:** 52-week high/low scan with multiple reminders

---

## 📝 Summary Statistics

| Category | Count |
|----------|-------|
| Earnings Alert APIs | 2 |
| Earnings Watchlist APIs | 5 |
| Test/Debug APIs (Earnings) | 13 |
| **Total REST APIs** | **20** |
| **Scheduled Tasks** | **14** |

---

**Last Updated:** June 6, 2026

