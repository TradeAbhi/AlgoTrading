# ORB (Opening Range Breakout) Strategy

## Overview

The Opening Range Breakout (ORB) strategy identifies stocks that break out of their initial trading range (first 15 minutes) with volume confirmation. It tracks Nifty 500 stocks and sends Telegram alerts when confirmed breakouts occur during intraday trading.

## Strategy Concept

The strategy is based on the premise that the first 15 minutes of trading (9:15-9:30 AM) establishes the day's initial range. Breakouts from this range with volume confirmation often signal the start of a sustained directional move.

### Key Principles

1. **Opening Range Reference**: 9:15-9:30 AM candle defines the initial high/low levels
2. **Volume Confirmation**: Breakouts must be backed by above-average volume
3. **Dynamic Levels**: Resistance/Support levels adjust based on intraday price action
4. **15-Minute Confirmation**: Uses 15-minute candles for stronger signals

## Entry Conditions

### BUY Entry

**Trigger Conditions:**
- 15-minute candle close **above** the rolling high (initially 9:15 candle high)
- Volume ≥ 1.5× opening candle volume
- Stock has not already triggered a buy alert today

**Stop Loss:**
- Previous candle's low (shifts when buy triggers)
- Risk percentage calculated as: `(close - SL) / close × 100`

**Dynamic Level Adjustment:**
- If candle high pierces rolling high but close doesn't confirm → rolling high is raised to the new high
- Rolling low stays frozen until buy triggers, then shifts to previous candle low

### SELL Entry

**Trigger Conditions:**
- 15-minute candle close **below** the rolling low (initially 9:15 candle low)
- Volume ≥ 1.5× opening candle volume
- Stock has not already triggered a sell alert today

**Stop Loss:**
- Previous candle's high (shifts when sell triggers)
- Risk percentage calculated as: `(SL - close) / close × 100`

**Dynamic Level Adjustment:**
- If candle low pierces rolling low but close doesn't confirm → rolling low is dropped to the new low
- Rolling high stays frozen until sell triggers, then shifts to previous candle high

## Filtering Criteria

### Opening Range Filter

During the seeding phase (9:31 AM), stocks are filtered based on their opening range width:

- **Minimum Range**: 0.5% of candle low (to avoid stocks with no volatility)
- Stocks below this threshold are skipped for the day

**Formula:**
```
Range % = ((High - Low) / Low) × 100
```

### Volume Filter

- **Minimum Volume Multiplier**: 1.5× opening candle volume
- Ensures breakouts are backed by institutional participation

## Strategy Parameters

| Parameter | Value | Description |
|-----------|-------|-------------|
| MIN_RANGE_PCT | 0.5% | Minimum opening range width |
| MIN_VOLUME_MULTIPLIER | 1.5× | Minimum volume multiplier for breakout confirmation |
| Capture Schedule | 9:31 AM (Mon-Fri) | Capture 9:15-9:30 opening candle |
| Scan Schedule | 9:46, 10:01, 10:16...15:16 (Mon-Fri) | Scan every 15 minutes |
| Candle Interval | 15 minutes | Uses 15-minute candles for confirmation |

## Workflow

### 1. Opening Range Capture (9:31 AM - Automated)

For each Nifty 500 symbol:
1. Fetch the completed 9:15-9:30 candle (15-minute interval)
2. Calculate opening range percentage
3. Filter by minimum range (0.5%)
4. Fetch previous day's close for gap context
5. Store state in memory: rolling high/low, opening volume, etc.

### 2. Breakout Scanning (9:46 AM - 3:16 PM - Automated)

For each tracked symbol (every 15 minutes):
1. Fetch the latest completed 15-minute candle
2. Check BUY conditions:
   - Close > rolling high?
   - Volume ≥ 1.5× opening volume?
   - Not already alerted?
3. Check SELL conditions:
   - Close < rolling low?
   - Volume ≥ 1.5× opening volume?
   - Not already alerted?
4. Update dynamic levels if pierced but not closed
5. Send Telegram alert if conditions met

### 3. Alert Structure

**BUY Alert Format:**
```
🟢 ORB BUY BREAKOUT
📌 SYMBOL
────────────────
📈 Close:        ₹XXX.XX
🎯 Above level:  ₹XXX.XX
🛑 SL:           ₹XXX.XX  (X.XX% risk)
📊 Move from open: +X.XX%
🌅 Gap from prev:  +X.XX%
📅 Prev close:   ₹XXX.XX
🕐 Candle:       HH:mm–HH:mm
📊 Volume:       X,XXX,XXX
🏦 Nifty:        🟢 +X.XX% (₹XXXX)
```

**SELL Alert Format:**
```
🔴 ORB SELL BREAKDOWN
📌 SYMBOL
────────────────
📉 Close:        ₹XXX.XX
🎯 Below level:  ₹XXX.XX
🛑 SL:           ₹XXX.XX  (X.XX% risk)
📊 Move from open: +X.XX%
🌅 Gap from prev:  +X.XX%
📅 Prev close:   ₹XXX.XX
🕐 Candle:       HH:mm–HH:mm
📊 Volume:       X,XXX,XXX
🏦 Nifty:        🔴 -X.XX% (₹XXXX)
```

## State Management

Each symbol maintains the following state:

| Field | Description |
|-------|-------------|
| symbol | Stock symbol |
| instrumentKey | Upstox instrument key |
| rollingHigh | Current resistance level (only moves up) |
| rollingLow | Current support level (only moves down) |
| buyAlerted | Whether buy alert has fired today |
| sellAlerted | Whether sell alert has fired today |
| prevCandleHigh | Previous candle's high (for SL calculation) |
| prevCandleLow | Previous candle's low (for SL calculation) |
| openingCandleVolume | 9:15 candle volume (for volume filter) |
| openPrice | 9:15 candle open price |
| prevDayClose | Previous day's close (for gap calculation) |

## API Endpoints

### Manual Triggers

- `GET /orb/capture` - Manually trigger opening range capture
- `GET /orb/scan` - Manually trigger breakout scan

### Monitoring

- `GET /orb/state` - View all symbol states

## Risk Management

### Stop Loss Logic

- **BUY**: SL = Previous candle's low (shifts when buy triggers)
- **SELL**: SL = Previous candle's high (shifts when sell triggers)

This provides a tight stop loss that adapts to recent price action, typically resulting in 1-3% risk per trade.

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

1. **Early Entry**: Catches moves early in the trading day
2. **Volume Confirmation**: Filters out false breakouts
3. **Dynamic Levels**: Adapts to evolving price action
4. **Tight Stops**: Risk typically 1-3% per trade
5. **Automated**: Minimal manual intervention required
6. **15-Minute Confirmation**: Stronger signals than 1-minute candles

## Limitations

1. **Gap Risk**: Pre-market gaps can skip opening range
2. **Whipsaws**: Can get stopped out in choppy markets
3. **False Breakouts**: Volume filter helps but doesn't eliminate all
4. **Time-Sensitive**: Missed 9:31 capture = no signals for the day
5. **Market Conditions**: Underperforms in range-bound days

## Best Practices

1. **Market Context**: Consider overall market conditions (Nifty trend shown in alerts)
2. **Gap Analysis**: Pay attention to gap from previous close
3. **Position Sizing**: Use the provided risk percentage for sizing
4. **Time of Day**: Early signals (9:46-10:30 AM) are typically stronger
5. **Paper Trade**: Test strategy live before committing capital

## Example Trade

### Setup
```
Symbol: RELIANCE
9:15 Candle: High ₹2500, Low ₹2480, Volume 5M
Previous Close: ₹2470
```

### 9:31 AM - Capture
```
Opening Range: ₹2480 - ₹2500 (0.81% ✓ above 0.5% threshold)
State seeded: rollingHigh=2500, rollingLow=2480
```

### 10:01 AM - First Scan
```
9:45-10:00 Candle: High ₹2510, Close ₹2495, Volume 6M
→ High pierced rollingHigh (2510 > 2500) but close didn't confirm
→ rollingHigh raised to ₹2510
```

### 10:16 AM - Second Scan
```
10:00-10:15 Candle: High ₹2515, Close ₹2512, Volume 8M
→ Close above rollingHigh (2512 > 2510) ✓
→ Volume (8M) > 1.5× opening (5M) ✓
→ BUY alert triggered
SL: Previous candle low (₹2495)
Risk: (2512 - 2495) / 2512 = 0.68%
```

### Outcome
```
Entry: ₹2512
SL: ₹2495
Target: Not defined (momentum play)
Exit: When momentum fades or SL hit
```

## Troubleshooting

### No Alerts Firing
- Check if state store is populated: `GET /orb/state`
- Verify capture schedule ran at 9:31 AM (check logs)
- Ensure app was running during market hours

### False Breakouts
- Check volume condition (must be ≥ 1.5× opening volume)
- Verify opening range filter (≥ 0.5%)
- Review if stock is F&O eligible

### Missed Signals
- Use `GET /orb/capture` to manually seed if missed 9:31 AM
- Use `GET /orb/scan` to manually trigger scan
- Check if app was down during market hours

## References

- **Service**: `OrbScannerServiceFinal.java`
- **Controller**: `OrbController.java`
- **State Store**: `OrbStateStore.java`
- **Config**: `OrbConfig.java`
- **Backtest Service**: `OrbBacktestService.java`

---

**Last Updated:** June 2026
