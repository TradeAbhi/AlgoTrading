# IPO Strategy

## Overview

The IPO strategy monitors stocks that have listed in the past 365 days and identifies weekly breakout/breakdown patterns. It focuses on recent IPO listings that are showing strong momentum or reversal signals.

## Strategy Concept

The strategy is based on the premise that recent IPOs often exhibit strong momentum as they establish their trading range. By tracking weekly price action, we can identify breakout/breakdown patterns that signal potential trend changes.

### Key Principles

1. **Recent IPO Universe**: Only stocks listed in the past 365 days
2. **Weekly Analysis**: Uses weekly candles for stronger signals
3. **Three Signal Types**: Weekly Breakout, Weekly Breakdown, Reversal Breakout
4. **Priority-Based Alerts**: Signals ranked by priority (Reversal > Breakout > Breakdown)
5. **Deduplication**: Prevents duplicate alerts for same signal type on same day

## Signal Types

### 1. Reversal Breakout (Priority 3 - Highest)

**Conditions:**
- Current weekly close > recent 4-week range high
- Previous 4 weeks show downtrend characteristics
- Downtrend defined as:
  - Total move ≤ -8% over 4 weeks, OR
  - At least 2 weeks with lower closes AND at least 2 weeks with lower highs

**Significance:** Indicates a potential trend reversal from downtrend to uptrend after a base formation.

### 2. Weekly Breakout (Priority 2 - Medium)

**Conditions:**
- Current weekly close > recent 4-week range high
- Previous 4 weeks do NOT show downtrend characteristics

**Significance:** Indicates continuation of uptrend or breakout from consolidation.

### 3. Weekly Breakdown (Priority 1 - Lowest)

**Conditions:**
- Current weekly close < recent 4-week range low

**Significance:** Indicates breakdown below recent support, potential downtrend start.

## Strategy Parameters

| Parameter | Value | Description |
|-----------|-------|-------------|
| IPO_UNIVERSE_DAYS | 365 | Lookback period for IPO listings |
| CANDLE_LOOKBACK_DAYS | 120 | Maximum daily candle lookback for data |
| WEEKLY_BREAKOUT_LOOKBACK | 4 weeks | Number of weeks to calculate range |
| REVERSAL_DOWNTREND_WEEKS | 4 weeks | Weeks to evaluate for downtrend |
| Scan Schedule | 3:45 PM IST (Mon-Fri) | Daily scan after market close |

## Workflow

### 1. Universe Selection (Automated)

- Query IPO repository for stocks listed in the past 365 days
- Filters out older IPOs to focus on recent listings
- Ensures instrument keys exist for each symbol

### 2. Data Fetching

For each IPO stock:
1. Fetch daily bars from listing date to today (max 120 days lookback)
2. Convert daily bars to weekly bars
3. Aggregate daily OHLCV into weekly candles

### 3. Signal Evaluation

For each stock with sufficient weekly data:
1. Calculate recent 4-week range (high/low)
2. Check current week's close against range
3. Evaluate downtrend conditions for reversal signals
4. Determine signal type and priority
5. Calculate % change from previous week

### 4. Alert Generation

- Filter out signals already alerted today (deduplication)
- Sort signals by priority (descending) then by % change (descending)
- Send consolidated Telegram message with all fresh signals
- Send Discord message with symbol list

### 5. Alert Structure

**Telegram Alert Format:**
```
*Past IPO Strategy Signals*
------------------------
Universe: last 365 days | Scanned: XX | Skipped: XX

*REVERSAL BREAKOUT*
`SYMBOL` - Company Name
Close: ₹XXX.XX | Change: +X.XX%
Range: ₹XXX.XX - ₹XXX.XX
Downtrend base broken on upside

*WEEKLY BREAKOUT*
`SYMBOL` - Company Name
Close: ₹XXX.XX | Change: +X.XX%
Range: ₹XXX.XX - ₹XXX.XX
Close above recent weekly range

*WEEKLY BREAKDOWN*
`SYMBOL` - Company Name
Close: ₹XXX.XX | Change: -X.XX%
Range: ₹XXX.XX - ₹XXX.XX
Close below recent weekly support
```

**Discord Alert Format:**
```
Past IPO Strategy Signals:
SYMBOL1
SYMBOL2
SYMBOL3
...
```

## Signal Logic

### Range Calculation

```
Recent 4-week range:
- Range High = Maximum of weekly highs in last 4 completed weeks
- Range Low = Minimum of weekly lows in last 4 completed weeks
- Current week is excluded from range calculation
```

### Downtrend Detection

A stock is considered in downtrend if:
- Total move over 4 weeks ≤ -8%, OR
- At least 2 out of 3 week-to-week comparisons show:
  - Lower close than previous week
  - Lower or equal high than previous week

### Signal Priority

| Signal Type | Priority | Description |
|-------------|----------|-------------|
| Reversal Breakout | 3 | Highest - trend reversal from downtrend |
| Weekly Breakout | 2 | Medium - continuation or consolidation breakout |
| Weekly Breakdown | 1 | Lowest - breakdown below support |

## State Management

### Alert Deduplication

- Uses in-memory map to track last alert date per signal type
- Key format: `SYMBOL|SIGNAL_TYPE`
- Prevents duplicate alerts for same signal on same day
- Map resets on application restart (acceptable for daily strategy)

## API Endpoints

### Manual Triggers

- Manual scan can be triggered by calling `scanAndAlert()` method directly
- No dedicated REST endpoint (can be added if needed)

## Risk Management

### Position Sizing

The strategy does not enforce position sizing. Traders should:
- Use the provided % change to gauge momentum strength
- Consider the signal type when sizing (Reversal > Breakout > Breakdown)
- Apply standard risk management (1-2% risk per trade)

### Stop Loss

The strategy does not provide stop loss levels. Traders should:
- Use recent weekly low for BUY signals
- Use recent weekly high for SELL signals
- Consider the 4-week range for risk assessment

## Advantages

1. **Fresh Universe**: Focuses on recent IPOs with active interest
2. **Weekly Timeframe**: Reduces noise compared to daily signals
3. **Multiple Signal Types**: Captures different market conditions
4. **Priority-Based**: Highlights highest-conviction setups first
5. **Downtrend Detection**: Identifies reversal opportunities
6. **Consolidated Alerts**: Single message with all signals

## Limitations

1. **No Stop Loss**: Traders must determine their own exit points
2. **No Volume Filter**: Breakouts not confirmed by volume
3. **Limited Universe**: Only covers past 365 days of IPOs
4. **No Target**: Strategy is signal-only, no profit targets
5. **In-Memory State**: Alert deduplication resets on restart
6. **Weekly Lag**: Signals based on weekly close (delayed)

## Best Practices

1. **Signal Priority**: Focus on Reversal Breakouts first
2. **Volume Check**: Verify volume on your chart before entering
3. **Market Context**: Consider overall market conditions
4. **Position Sizing**: Size positions based on signal priority
5. **Risk Management**: Always use stop losses (weekly high/low)
6. **Paper Trade**: Test strategy live before committing capital

## Example Trade

### Setup
```
IPO: XYZ Ltd
Listing Date: 3 months ago
Recent 4 Weeks: High ₹200, Low ₹180
Previous Week Close: ₹185
```

### Signal Evaluation

**Scenario 1: Reversal Breakout**
```
Week 1: Close ₹190, High ₹195
Week 2: Close ₹185, High ₹190 (lower close, lower high)
Week 3: Close ₹180, High ₹185 (lower close, lower high)
Week 4: Close ₹178, High ₹182 (lower close, lower high)
Current Week: Close ₹205

Analysis:
- 4-week range: ₹178 - ₹195
- Current close (₹205) > range high (₹195) ✓
- Downtrend detected: 3/3 weeks with lower closes and highs ✓
→ REVERSAL BREAKOUT (Priority 3)
```

**Scenario 2: Weekly Breakout**
```
Week 1: Close ₹190, High ₹195
Week 2: Close ₹192, High ₹198
Week 3: Close ₹188, High ₹194
Week 4: Close ₹195, High ₹200
Current Week: Close ₹205

Analysis:
- 4-week range: ₹188 - ₹200
- Current close (₹205) > range high (₹200) ✓
- No downtrend detected (mixed signals)
→ WEEKLY BREAKOUT (Priority 2)
```

**Scenario 3: Weekly Breakdown**
```
Week 1: Close ₹190, High ₹195
Week 2: Close ₹185, High ₹190
Week 3: Close ₹188, High ₹193
Week 4: Close ₹182, High ₹188
Current Week: Close ₹175

Analysis:
- 4-week range: ₹182 - ₹195
- Current close (₹175) < range low (₹182) ✓
→ WEEKLY BREAKDOWN (Priority 1)
```

## Troubleshooting

### No Signals
- Check if IPO universe has recent listings (past 365 days)
- Verify instrument keys exist for all IPO symbols
- Ensure sufficient weekly data (at least 5 weeks)

### Duplicate Alerts
- Check if app restarted (in-memory state reset)
- Verify alert key format: `SYMBOL|SIGNAL_TYPE`
- Check if scan ran multiple times on same day

### Missing IPOs
- Verify IPO listing dates in repository
- Check if listing date is within 365-day window
- Ensure symbol has valid instrument key

## Data Models

### IPO Signal
```java
record IpoSignal(
    String name,           // Company name
    String symbol,         // Trading symbol
    String type,           // Signal type
    int priority,          // 1=Breakdown, 2=Breakout, 3=Reversal
    double close,          // Current week close
    double rangeHigh,      // 4-week range high
    double rangeLow,       // 4-week range low
    double changePct,      // % change from previous week
    String reason          // Signal explanation
)
```

### Weekly Bar
```java
record WeeklyBar(
    LocalDate weekStart,  // Monday of the week
    double open,           // Week open
    double high,           // Week high
    double low,            // Week low
    double close,          // Week close
    long volume            // Week volume
)
```

## References

- **Service**: `IpoStrategyMonitorService.java`
- **Repository**: `IpoRepository.java`
- **IPO Model**: `Ipo.java`
- **Candle Service**: `UpstoxHistoricalCandleService.java`
- **Instrument Service**: `UpstoxInstrumentMasterService.java`

---

**Last Updated:** June 2026
