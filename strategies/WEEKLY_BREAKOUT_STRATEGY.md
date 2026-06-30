# Weekly Breakout Strategy (Indian Market)

## Overview

The Weekly Breakout strategy identifies stocks breaking out of their previous week's trading range with volume confirmation. It tracks Nifty 500 stocks and sends Telegram alerts when confirmed daily breakouts occur after market close.

## Strategy Concept

The strategy is based on the premise that weekly ranges act as significant support/resistance levels. Breakouts from these ranges with volume confirmation often signal the start of sustained multi-day moves.

### Key Principles

1. **Weekly Range Reference**: Previous week's high/low acts as dynamic support/resistance
2. **Daily Confirmation**: Uses daily close for breakout confirmation (stronger than intraday)
3. **Volume Confirmation**: Breakouts must be backed by above-average volume
4. **Quality Filters**: Multiple quality filters to ensure high-conviction setups
5. **Dynamic Levels**: Resistance/Support levels adjust based on daily price action

## Entry Conditions

### BUY Entry

**Trigger Conditions:**
- Daily close **above** the weekly high
- Volume ≥ 1.5× average weekly volume (calculated as weekly volume / 5)
- Stock has not already triggered a buy alert this week
- Passes quality filters (see below)

**Stop Loss:**
- Previous day's low (shifts when buy triggers)
- Risk percentage calculated as: `(close - SL) / close × 100`

**Dynamic Level Adjustment:**
- If daily high pierces weekly high but close doesn't confirm → weekly high is raised to the new high
- Weekly low stays frozen until buy triggers, then shifts to previous day low

### SELL Entry

**Trigger Conditions:**
- Daily close **below** the weekly low
- Volume ≥ 1.5× average weekly volume
- Stock has not already triggered a sell alert this week
- Passes quality filters (see below)

**Stop Loss:**
- Previous day's high (shifts when sell triggers)
- Risk percentage calculated as: `(SL - close) / close × 100`

**Dynamic Level Adjustment:**
- If daily low pierces weekly low but close doesn't confirm → weekly low is dropped to the new low
- Weekly high stays frozen until sell triggers, then shifts to previous day high

## Filtering Criteria

### Weekly Range Filter

During the seeding phase (Monday 9:31 AM), stocks are filtered based on their previous week's range:

- **Minimum Range**: 1.5% (to avoid stocks with no volatility)
- **Maximum Range**: 8.0% (to avoid stocks that are too extended)

**Formula:**
```
Range % = ((Weekly High - Weekly Low) / Weekly Low) × 100
```

**Examples:**
```
Stock A: High ₹150, Low ₹145 → Range = (150-145)/145 × 100 = 3.45% ✓
Stock B: High ₹150, Low ₹149 → Range = (150-149)/149 × 100 = 0.67% ✗ (too narrow)
Stock C: High ₹150, Low ₹135 → Range = (150-135)/135 × 100 = 11.11% ✗ (too wide)
```

### Volume Filter

- **Minimum Volume Multiplier**: 1.5× average weekly volume
- **Quality Volume Multiplier**: 2.0× average weekly volume (for quality filter)
- Average daily volume calculated as: `Weekly Volume / 5`

### Quality Filters

Additional quality filters applied to ensure high-conviction setups:

1. **Minimum Close Price**: ₹100 (filters penny stocks)
2. **Minimum Turnover**: ₹20 crores (ensures liquidity)
3. **Quality Volume**: ≥ 2.0× average daily volume
4. **Intraday Move Range**: 0.8% - 12.0% from open (filters extreme moves)
5. **Close Location**:
   - BUY: Close must be in upper 65% of daily range
   - SELL: Close must be in lower 35% of daily range

## Strategy Parameters

| Parameter | Value | Description |
|-----------|-------|-------------|
| MIN_WEEKLY_RANGE_PCT | 1.5% | Minimum weekly range for stock to be tracked |
| MAX_WEEKLY_RANGE_PCT | 8.0% | Maximum weekly range for stock to be tracked |
| MIN_VOLUME_MULTIPLIER | 1.5× | Minimum volume multiplier for breakout confirmation |
| MIN_QUALITY_VOLUME_MULTIPLIER | 2.0× | Quality volume filter |
| MIN_CLOSE_PRICE | ₹100 | Minimum closing price |
| MIN_TURNOVER_CRORES | ₹20 crores | Minimum daily turnover |
| MIN_INTRADAY_MOVE_PCT | 0.8% | Minimum intraday move from open |
| MAX_INTRADAY_MOVE_PCT | 12.0% | Maximum intraday move from open |
| MIN_BUY_CLOSE_LOCATION | 0.65 | Minimum close location for BUY (65% of range) |
| MAX_SELL_CLOSE_LOCATION | 0.35 | Maximum close location for SELL (35% of range) |
| Seed Schedule | Monday 9:31 AM IST | Automatic seeding of previous week's range |
| Scan Schedule | Mon-Fri 3:31 PM IST | Daily scan after market close |

## Workflow

### 1. Weekly Range Seeding (Monday 9:31 AM - Automated)

For each Nifty 500 symbol:
1. Fetch last 2 weekly candles from Upstox
2. Extract previous week's data (index 1)
3. Calculate weekly range percentage
4. Filter by range (1.5% - 8.0%)
5. Fetch Monday's opening price for % move context
6. Store state in memory: weekly high/low, volume, etc.

### 2. Daily Scan (Mon-Fri 3:31 PM - Automated)

For each tracked symbol:
1. Fetch latest completed daily candle
2. Check BUY conditions:
   - Close > weekly high?
   - Volume ≥ 1.5× average?
   - Passes quality filters?
   - Not already alerted?
3. Check SELL conditions:
   - Close < weekly low?
   - Volume ≥ 1.5× average?
   - Passes quality filters?
   - Not already alerted?
4. Update dynamic levels if pierced but not closed
5. Send Telegram + Discord alert if conditions met

### 3. Alert Structure

**BUY Alert Format:**
```
🟢 WEEKLY BUY BREAKOUT
📌 SYMBOL
────────────────
📈 Daily Close:     ₹XXX.XX
🎯 Above level:     ₹XXX.XX
🛑 SL:              ₹XXX.XX  (X.XX% risk)
📊 Move this week:  +X.XX%
📅 Prev week close: ₹XXX.XX  (+X.XX%)
📦 Daily High/Low:  ₹XXX.XX / ₹XXX.XX
📊 Volume:          X,XXX,XXX
🏦 Nifty:           🟢 +X.XX% (₹XXXX)
```

**SELL Alert Format:**
```
🔴 WEEKLY SELL BREAKDOWN
📌 SYMBOL
────────────────
📉 Daily Close:     ₹XXX.XX
🎯 Below level:     ₹XXX.XX
🛑 SL:              ₹XXX.XX  (X.XX% risk)
📊 Move this week:  -X.XX%
📅 Prev week close: ₹XXX.XX  (-X.XX%)
📦 Daily High/Low:  ₹XXX.XX / ₹XXX.XX
📊 Volume:          X,XXX,XXX
🏦 Nifty:           🔴 -X.XX% (₹XXXX)
```

## State Management

Each symbol maintains the following state:

| Field | Description |
|-------|-------------|
| symbol | Stock symbol |
| instrumentKey | Upstox instrument key |
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

## API Endpoints

### Manual Triggers

- `GET /weekly/capture` - Manually trigger seeding
- `GET /weekly/scan` - Manually trigger daily scan

### Monitoring

- `GET /weekly/state` - View all ticker states
- `GET /weekly/watching` - View unalerted tickers only

## Risk Management

### Stop Loss Logic

- **BUY**: SL = Previous day's low
- **SELL**: SL = Previous day's high

This provides a tight stop loss that adapts to recent price action, typically resulting in 2-4% risk per trade.

### Position Sizing

While the strategy doesn't enforce position sizing, the risk percentage provided in alerts can be used to calculate position size based on your risk tolerance.

**Example:**
```
Account: ₹100,000
Risk per trade: 1% (₹1,000)
Trade risk: 2.5%
Position size = ₹1,000 / 0.025 = ₹40,000
```

## Advantages

1. **High Conviction**: Multiple quality filters ensure strong setups
2. **Daily Confirmation**: Uses daily close for stronger signals
3. **Volume Confirmation**: Filters out false breakouts
4. **Dynamic Levels**: Adapts to evolving price action
5. **Tight Stops**: Risk typically 2-4% per trade
6. **Automated**: Minimal manual intervention required
7. **Multi-Channel Alerts**: Both Telegram and Discord notifications

## Limitations

1. **Gap Risk**: Gaps can skip stop loss levels
2. **Whipsaws**: Can get stopped out in choppy markets
3. **Late Entries**: Already in trend when signal fires
4. **Volume Anomalies**: Unusual volume can create false signals
5. **Market Conditions**: Underperforms in range-bound markets
6. **Weekly Reset**: State resets every Monday, losing context

## Best Practices

1. **Weekly Review**: Check seeded stocks on Monday
2. **Monitor Alerts**: Review all alerts before taking action
3. **Position Sizing**: Use the provided risk percentage for sizing
4. **Diversification**: Don't over-concentrate in one sector
5. **Market Context**: Consider overall market conditions (Nifty trend shown in alerts)
6. **Paper Trade**: Test strategy live before committing capital

## Example Trade

### Setup
```
Symbol: TCS
Previous Week: High ₹3500, Low ₹3400, Volume 100M
Monday Open: ₹3420
```

### Monday 9:31 AM - Seeding
```
Weekly Range: ₹3400 - ₹3500 (2.94% ✓ within 1.5-8%)
State seeded: weeklyHigh=3500, weeklyLow=3400
```

### Tuesday 3:31 PM - Scan
```
Daily: High ₹3520, Low ₹3410, Close ₹3490, Volume 25M
→ High pierced weeklyHigh (3520 > 3500) but close didn't confirm
→ weeklyHigh raised to ₹3520
```

### Wednesday 3:31 PM - Scan
```
Daily: High ₹3540, Low ₹3480, Close ₹3535, Volume 30M
→ Close above weeklyHigh (3535 > 3520) ✓
→ Volume (30M) > 1.5× avg (20M) ✓
→ Quality filters passed ✓
→ BUY alert triggered
SL: Previous day low (₹3480)
Risk: (3535 - 3480) / 3535 = 1.56%
```

### Outcome
```
Entry: ₹3535
SL: ₹3480
Target: Not defined (momentum play)
Exit: When momentum fades or SL hit
```

## Troubleshooting

### No Alerts Firing
- Check if state store is populated: `GET /weekly/state`
- Verify seed schedule ran on Monday 9:31 AM (check logs)
- Ensure app was running during market hours

### False Breakouts
- Check volume condition (must be ≥ 1.5× average)
- Verify weekly range filter (1.5% - 8.0%)
- Review quality filters (price, turnover, volume, close location)

### Missed Signals
- Use `GET /weekly/capture` to manually seed if missed Monday
- Use `GET /weekly/scan` to manually trigger scan
- Check if app was down during market hours

## Differences from US Weekly Breakout

| Feature | Indian Weekly Breakout | US Weekly Breakout |
|---------|------------------------|-------------------|
| Market | NSE (Indian) | NYSE/NASDAQ (US) |
| Universe | Nifty 500 | 52-week high stocks |
| Scan Time | 3:31 PM IST | 10:00 AM IST |
| Candle Type | Daily | Daily |
| Quality Filters | Yes (multiple) | No |
| Minimum Price | ₹100 | None |
| Turnover Filter | Yes (₹20 Cr) | No |
| Close Location Filter | Yes | No |
| Intraday Move Filter | Yes | No |
| Alert Channels | Telegram + Discord | Telegram only |

## References

- **Service**: `WeeklyBreakoutScannerService.java`
- **Controller**: `WeeklyBreakoutController.java`
- **State Store**: `WeeklyBreakoutStateStore.java`
- **State Model**: `WeeklyBreakoutState.java`
- **Backtest Service**: `WeeklyBreakoutBacktestService.java`

---

**Last Updated:** June 2026
