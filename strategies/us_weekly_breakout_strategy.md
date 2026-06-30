# US Weekly Breakout Strategy

## Overview

The US Weekly Breakout strategy identifies stocks making new All-Time Highs (ATH) or 52-week highs and trades weekly breakout patterns. It focuses on high-conviction setups by tracking stocks that have already demonstrated strength by hitting new highs.

## Strategy Concept

The strategy is based on the premise that stocks making new highs tend to continue moving higher (momentum). By tracking the previous week's range and watching for breakouts with volume confirmation, we can catch strong momentum moves early.

### Key Principles

1. **High Conviction Universe**: Only stocks that have hit 52-week highs or ATH are tracked
2. **Weekly Range as Reference**: Previous week's high/low acts as dynamic support/resistance
3. **Volume Confirmation**: Breakouts must be accompanied by above-average volume
4. **Dynamic Stop Loss**: Stop loss is set at the previous day's low (for buys) or high (for sells)

## Entry Conditions

### BUY Entry

**Trigger Conditions:**
- Daily close **above** the previous week's high
- Volume ≥ 1.5× average daily volume (calculated as weekly volume / 5)
- Stock has not already triggered a buy alert this week

**Stop Loss:**
- Previous day's low
- Risk percentage calculated as: `(close - SL) / close × 100`

**Example:**
```
Previous Week: High $150, Low $145
Today: Close $152, Volume 2M (avg 1M)
→ BUY signal triggered
SL: Previous day's low (e.g., $148)
Risk: (152 - 148) / 152 = 2.63%
```

### SELL Entry

**Trigger Conditions:**
- Daily close **below** the previous week's low
- Volume ≥ 1.5× average daily volume
- Stock has not already triggered a sell alert this week

**Stop Loss:**
- Previous day's high
- Risk percentage calculated as: `(SL - close) / close × 100`

**Example:**
```
Previous Week: High $150, Low $145
Today: Close $143, Volume 2M (avg 1M)
→ SELL signal triggered
SL: Previous day's high (e.g., $147)
Risk: (147 - 143) / 143 = 2.80%
```

## Filtering Criteria

### Weekly Range Filter

During the seeding phase (Monday 6:00 AM IST), stocks are filtered based on their previous week's range:

- **Minimum Range**: 1.5% (to avoid stocks with no volatility)
- **Maximum Range**: 8.0% (to avoid stocks that are too extended)

**Formula:**
```
Range % = ((Weekly High - Weekly Low) / Weekly Low) × 100
```

**Examples:**
```
Stock A: High $150, Low $145 → Range = (150-145)/145 × 100 = 3.45% ✓
Stock B: High $150, Low $149 → Range = (150-149)/149 × 100 = 0.67% ✗ (too narrow)
Stock C: High $150, Low $135 → Range = (150-135)/135 × 100 = 11.11% ✗ (too wide)
```

### Volume Filter

- **Minimum Volume Multiplier**: 1.5× average daily volume
- Average daily volume calculated as: `Weekly Volume / 5`

This ensures breakouts are backed by institutional participation.

## Dynamic Level Adjustment

The strategy dynamically adjusts the weekly high/low levels based on intraday price action:

### BUY Side
- If daily **high** exceeds weekly high (but close doesn't), the weekly high is **raised** to the new daily high
- This allows the strategy to track the true resistance level as it evolves

### SELL Side
- If daily **low** drops below weekly low (but close doesn't), the weekly low is **lowered** to the new daily low
- This allows the strategy to track the true support level as it evolves

**Example:**
```
Initial: Weekly High = $150
Day 1: High $152, Close $149 → Weekly High updated to $152
Day 2: High $155, Close $154 → Weekly High updated to $155
Day 3: Close $156 (above $155) → BUY triggered
```

## Strategy Parameters

| Parameter | Value | Description |
|-----------|-------|-------------|
| MIN_RANGE_PCT | 1.5% | Minimum weekly range for stock to be tracked |
| MAX_RANGE_PCT | 8.0% | Maximum weekly range for stock to be tracked |
| MIN_VOLUME_MULTIPLIER | 1.5× | Minimum volume multiplier for breakout confirmation |
| Seed Schedule | Monday 6:00 AM IST | Automatic seeding of previous week's range |
| Scan Schedule | Mon-Fri 10:00 AM IST | Daily scan after US market close |

## Workflow

### 1. Ticker Selection (Friday - Manual)

**Source:** WSJ 52-week high page or similar
- Copy NYSE + NASDAQ 52-week high tickers
- Upload via API: `POST /us-weekly/upload-tickers`
- Or update `sp500.csv` file manually

### 2. Seeding Phase (Monday 6:00 AM IST - Automated)

For each ticker:
1. Fetch last 3 weekly candles from Yahoo Finance
2. Extract previous week's data (index size-2)
3. Calculate weekly range percentage
4. Filter by range (1.5% - 8.0%)
5. Store state in memory: weekly high/low, volume, etc.

### 3. Daily Scan (Mon-Fri 10:00 AM IST - Automated)

For each tracked ticker:
1. Fetch latest daily candle
2. Check BUY conditions:
   - Close > weekly high?
   - Volume ≥ 1.5× average?
   - Not already alerted?
3. Check SELL conditions:
   - Close < weekly low?
   - Volume ≥ 1.5× average?
   - Not already alerted?
4. Update dynamic levels if pierced but not closed
5. Send Telegram alert if conditions met

### 4. Alert Structure

**BUY Alert Format:**
```
🇺🇸🟢 US WEEKLY BUY BREAKOUT
📌 TICKER
────────────────
📈 Daily Close:      $XXX.XX
🎯 Above level:      $XXX.XX
🛑 SL:               $XXX.XX  (X.XX% risk)
📊 Move this week:   +X.XX%
📅 Prev week close:  $XXX.XX  (+X.XX%)
📦 Day High/Low:     $XXX.XX / $XXX.XX
📊 Volume:           X,XXX,XXX
🏆 52-Week High:     ✅ Yes
📅 Date (EST):       YYYY-MM-DD
```

**SELL Alert Format:**
```
🇺🇸🔴 US WEEKLY SELL BREAKDOWN
📌 TICKER
────────────────
📉 Daily Close:      $XXX.XX
🎯 Below level:      $XXX.XX
🛑 SL:               $XXX.XX  (X.XX% risk)
📊 Move this week:   -X.XX%
📅 Prev week close:  $XXX.XX  (-X.XX%)
📦 Day High/Low:     $XXX.XX / $XXX.XX
📊 Volume:           X,XXX,XXX
📅 Date (EST):       YYYY-MM-DD
```

## State Management

Each ticker maintains the following state:

| Field | Description |
|-------|-------------|
| ticker | Stock symbol |
| weeklyHigh | Previous week's high (dynamically updated) |
| weeklyLow | Previous week's low (dynamically updated) |
| weeklyOpen | Previous week's open |
| weeklyVolume | Previous week's total volume |
| buyAlerted | Whether buy alert has fired this week |
| sellAlerted | Whether sell alert has fired this week |
| prevDailyHigh | Previous day's high |
| prevDailyLow | Previous day's low |
| prevWeekClose | Previous week's close |
| weekStartOpen | Monday's opening price |
| is52WeekHigh | Whether stock is a 52-week high |

## API Endpoints

### Manual Triggers

- `GET /us-weekly/capture` - Manually trigger seeding
- `GET /us-weekly/scan` - Manually trigger daily scan
- `GET /us-weekly/scan-week` - Replay full week (Mon-Fri)
- `POST /us-weekly/upload-tickers` - Upload ticker list

### Monitoring

- `GET /us-weekly/state` - View all ticker states
- `GET /us-weekly/watching` - View unalerted tickers only
- `GET /us-weekly/alerted` - View tickers that already alerted
- `GET /us-weekly/52wk` - View only 52-week high tickers

## Risk Management

### Stop Loss Logic

- **BUY**: SL = Previous day's low
- **SELL**: SL = Previous day's high

This provides a tight stop loss that adapts to recent price action, typically resulting in 2-4% risk per trade.

### Position Sizing

While the strategy doesn't enforce position sizing, the risk percentage provided in alerts can be used to calculate position size based on your risk tolerance.

**Example:**
```
Account: $100,000
Risk per trade: 1% ($1,000)
Trade risk: 2.5%
Position size = $1,000 / 0.025 = $40,000
```

## Advantages

1. **High Conviction**: Only trades stocks hitting new highs
2. **Momentum Capture**: Catches strong moves early
3. **Volume Confirmation**: Filters out false breakouts
4. **Dynamic Levels**: Adapts to evolving price action
5. **Tight Stops**: Risk typically 2-4% per trade
6. **Automated**: Minimal manual intervention required

## Limitations

1. **Gap Risk**: Gaps can skip stop loss levels
2. **Whipsaws**: Can get stopped out in choppy markets
3. **Late Entries**: Already in uptrend when signal fires
4. **Volume Anomalies**: Unusual volume can create false signals
5. **Market Conditions**: Underperforms in range-bound markets

## Best Practices

1. **Weekly Review**: Check 52-week high list every Friday
2. **Monitor Alerts**: Review all alerts before taking action
3. **Position Sizing**: Use the provided risk percentage for sizing
4. **Diversification**: Don't over-concentrate in one sector
5. **Market Context**: Consider overall market conditions
6. **Paper Trade**: Test strategy live before committing capital

## Example Trade

### Setup
```
Ticker: NVDA
Previous Week: High $150, Low $145, Volume 50M
Monday Open: $148
```

### Day 1
```
Daily: High $152, Low $147, Close $149, Volume 15M
→ High pierced weekly high ($150) but close didn't
→ Weekly high updated to $152
```

### Day 2
```
Daily: High $155, Low $150, Close $154, Volume 18M
→ Close above weekly high ($152)
→ Volume (18M) > 1.5× avg (10M)
→ BUY alert triggered
SL: Previous day's low ($150)
Risk: (154 - 150) / 154 = 2.60%
```

### Outcome
```
Entry: $154
SL: $150
Target: Not defined (momentum play)
Exit: When momentum fades or SL hit
```

## Troubleshooting

### No Alerts Firing
- Check if state store is populated: `GET /us-weekly/state`
- Verify scan schedule is running (check logs)
- Ensure tickers were uploaded successfully

### False Breakouts
- Check volume condition (must be ≥ 1.5× average)
- Verify weekly range filter (1.5% - 8.0%)
- Review if stock is actually a 52-week high

### Missed Signals
- Use `GET /us-weekly/scan-week` to replay missed days
- Check if app was down during market hours
- Verify ticker list is up to date

## References

- **Controller**: `UsWeeklyBreakoutController.java`
- **Service**: `UsWeeklyBreakoutScannerService.java`
- **State Store**: `UsWeeklyBreakoutStateStore.java`
- **Data Service**: `UsMarketDataService.java`
