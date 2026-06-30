# Delta Strategy

## Overview

The Delta strategy is a comprehensive trading system that combines daily breakout detection with volume spike analysis. It monitors specific symbols for breakouts from reference levels and classifies volume spikes into actionable signals.

## Strategy Components

The Delta strategy consists of two main components:

### 1. Daily Breakout Strategy
Detects breakouts from static reference levels based on daily candle closes.

### 2. Volume Scanner Strategy
Classifies volume spikes on 15-minute candles into three signal types.

---

## Daily Breakout Strategy

### Strategy Concept

The Daily Breakout strategy uses static reference levels that only update when a daily candle closes above the reference high. This is different from traditional previous-day strategies where levels change every day.

### Key Principles

1. **Static Reference Levels**: Reference high/low stay fixed until a daily close above reference high
2. **Daily Update Check**: Reference levels only update when daily candle closes above current reference high
3. **15-Minute Alerts**: Intraday alerts triggered when 15-minute candle closes above/below reference levels
4. **Cooldown Mechanism**: Prevents alert spam with configurable cooldown period

### Reference Level Logic

**Initialization:**
- On startup, initialize reference levels using the most recent daily candle
- Reference high = daily candle high
- Reference low = daily candle low
- Reference date = daily candle date

**Update Condition:**
- Check daily candle after market close
- If daily close > reference high → update reference levels to this candle's high/low
- If daily close ≤ reference high → keep same reference levels

**Alert Condition:**
- Check every 15 minutes during market hours
- If 15-minute candle close > reference high → BULLISH BREAKOUT alert
- If 15-minute candle close < reference low → BEARISH BREAKDOWN alert

### Strategy Parameters

| Parameter | Default Value | Description |
|-----------|---------------|-------------|
| cooldownMinutes | Configurable | Cooldown period between alerts for same symbol + direction |
| monitoredSymbols | Configurable | List of symbols to monitor |

### Workflow

#### 1. Initialization (Startup)

For each monitored symbol:
1. Fetch most recent daily candle
2. Set reference high/low to daily candle high/low
3. Store reference date and last check date

#### 2. Daily Reference Check (After Market Close)

For each monitored symbol:
1. Fetch most recent daily candle
2. Check if it's a new day (not same as last check)
3. If daily close > reference high:
   - Update reference high/low to this candle's high/low
   - Update reference date
4. Update last check date

#### 3. Intraday Alert Check (Every 15 Minutes)

For each monitored symbol:
1. Fetch last completed 15-minute candle
2. Check if close > reference high → BULLISH BREAKOUT
3. Check if close < reference low → BEARISH BREAKDOWN
4. Apply cooldown check before sending alert
5. Send Telegram alert if conditions met

### Alert Structure

**BULLISH BREAKOUT Alert:**
```
🚨 DAILY BREAKOUT ALERT
Symbol: XXX
Close: ₹XXX.XX
Reference High: ₹XXX.XX
Reference Date: YYYY-MM-DD
Direction: BULLISH_BREAKOUT
```

**BEARISH BREAKDOWN Alert:**
```
🚨 DAILY BREAKOUT ALERT
Symbol: XXX
Close: ₹XXX.XX
Reference Low: ₹XXX.XX
Reference Date: YYYY-MM-DD
Direction: BEARISH_BREAKDOWN
```

### Cooldown Mechanism

- Key format: `SYMBOL:DIRECTION`
- Prevents duplicate alerts for same symbol + direction within cooldown period
- Configurable cooldown duration (in minutes)
- Resets on application restart

---

## Volume Scanner Strategy

### Strategy Concept

The Volume Scanner identifies unusual volume activity on 15-minute candles and classifies it into three types based on candle body characteristics and trend context.

### Key Principles

1. **Volume Spike Detection**: Identifies volume ≥ N× 20-candle average
2. **Body Ratio Classification**: Uses candle body/range ratio to classify signal type
3. **Trend Context**: Considers prior trend for climax detection
4. **Deduplication**: One alert per symbol per candle close time

### Signal Types

#### 1. BREAKOUT (🚀)

**Conditions:**
- Volume ratio ≥ spike multiplier (default 2.0×)
- Body ratio ≥ 50% of candle range (big body candle)

**Significance:** Strong directional move with institutional participation. Indicates potential trend continuation or start of new trend.

#### 2. ABSORPTION (🧱)

**Conditions:**
- Volume ratio ≥ spike multiplier (default 2.0×)
- Body ratio < 50% of candle range (small body candle)

**Significance:** High volume but small price movement indicates supply/demand absorption. Smart money may be accumulating or distributing at current levels.

#### 3. CLIMAX (🔥)

**Conditions:**
- Volume ratio ≥ climax multiplier (default 3.0×)
- Last 5 candles showing strong trend (all closes in same direction)

**Significance:** Extreme volume after a trend move often signals exhaustion. Potential trend reversal or climax top/bottom.

### Strategy Parameters

| Parameter | Default Value | Description |
|-----------|---------------|-------------|
| LOOKBACK | 20 candles | Number of candles for average volume calculation |
| BODY_RATIO | 0.50 (50%) | Body/range threshold for big body classification |
| spikeMultiplier | 2.0× | Minimum volume ratio for spike detection |
| climaxMultiplier | 3.0× | Minimum volume ratio for climax classification |

### Workflow

#### 1. Volume Spike Detection

For each monitored symbol:
1. Fetch last 22 candles (20 lookback + current + 1 buffer)
2. Filter to only closed candles
3. Calculate average volume of last 20 candles
4. Calculate volume ratio = current volume / average volume

#### 2. Signal Classification

If volume ratio ≥ spike multiplier:
1. Calculate candle range = high - low
2. Calculate candle body = |close - open|
3. Calculate body ratio = body / range
4. Check trend in last 5 candles (all closes in same direction?)

**Classification Logic:**
- If volume ratio ≥ climax multiplier AND trending → CLIMAX
- Else if body ratio ≥ 50% → BREAKOUT
- Else → ABSORPTION

#### 3. Alert Generation

1. Check deduplication (already alerted on this candle close time?)
2. Build alert message with signal details
3. Send Telegram alert
4. Mark candle as alerted

### Alert Structure

**Volume Spike Alert:**
```
🚀 *Volume Spike | SYMBOL | BREAKOUT*

📊 Volume: `XXXXX` (2.5x avg)
🕯 Close: `XXX.XX`
📐 Body: `X.XX` | Range: `X.XX`
⏰ Candle Close: `YYYY-MM-DDTHH:mm:ssZ`

#SYMBOL #volume #spike
```

### Trend Detection

**Trend Check Logic:**
- Examine last 5 closed candles
- Count candles with close > open (green candles)
- Count candles with close < open (red candles)
- Trending if all 5 are green OR all 5 are red

**Note:** This is a strict trend check (5/5 in same direction).

---

## API Endpoints

### Daily Breakout Endpoints

- `GET /delta/daily-breakout/reference/{symbol}` - Get reference level for symbol
- `GET /delta/daily-breakout/references` - Get all reference levels
- `POST /delta/daily-breakout/check/{symbol}` - Manual reference check
- `POST /delta/daily-breakout/alert/{symbol}` - Manual alert check

### Volume Scanner Endpoints

- `POST /delta/volume-scan/{symbol}` - Manual volume scan for symbol

### Backtest Endpoints

- `POST /delta/backtest` - Run daily breakout backtest
- `POST /delta/volume-backtest` - Run volume scanner backtest

---

## Data Models

### DailyBreakoutLevel

```java
class DailyBreakoutLevel {
    String symbol;
    LocalDate referenceDate;      // Date of reference candle
    BigDecimal referenceHigh;     // Reference high level
    BigDecimal referenceLow;      // Reference low level
    LocalDate lastCheckDate;      // Last daily check date
}
```

### DailyBreakoutAlert

```java
class DailyBreakoutAlert {
    String symbol;
    Direction direction;          // BULLISH_BREAKOUT or BEARISH_BREAKDOWN
    BigDecimal candleClose;       // 15-minute candle close
    BigDecimal referenceLevel;    // Reference level breached
    Instant candleCloseTime;      // Candle close time
    LocalDate referenceDate;      // Reference level date
}
```

### VolumeSignal

```java
class VolumeSignal {
    String symbol;
    Type type;                    // BREAKOUT, ABSORPTION, or CLIMAX
    BigDecimal currentVolume;      // Current candle volume
    BigDecimal avgVolume;          // Average volume (20 candles)
    BigDecimal volumeRatio;       // Current / Average
    BigDecimal candleClose;        // Candle close price
    BigDecimal candleBody;         // |close - open|
    BigDecimal candleRange;        // high - low
    Instant candleCloseTime;      // Candle close time
}
```

---

## Backtesting

### Daily Breakout Backtest

**Request Parameters:**
- Symbol
- Start date
- End date
- Risk percentage
- Target R-multiple
- Max risk percentage

**Metrics Tracked:**
- Total trades
- Win rate
- Profit factor
- Average R per trade
- Largest win R
- Largest loss R
- Max drawdown

### Volume Scanner Backtest

**Request Parameters:**
- Symbol
- Start date
- End date
- Signal type filter (BREAKOUT, ABSORPTION, CLIMAX, or ALL)
- Risk percentage
- Target R-multiple
- Max risk percentage

**Metrics Tracked:**
- Total trades
- Win rate
- Profit factor
- Average R per trade
- Largest win R
- Largest loss R
- Max drawdown

---

## Advantages

### Daily Breakout Strategy

1. **Static Levels**: Reference levels don't change daily, reducing noise
2. **Strong Signals**: Only updates on confirmed daily close above reference
3. **Intraday Alerts**: 15-minute granularity for timely entries
4. **Cooldown Protection**: Prevents alert spam

### Volume Scanner Strategy

1. **Multiple Signal Types**: Captures different market conditions
2. **Volume Confirmation**: All signals backed by unusual volume
3. **Body Classification**: Distinguishes between breakouts and absorption
4. **Trend Context**: Climax detection considers prior trend

---

## Limitations

### Daily Breakout Strategy

1. **Static Levels**: Can become outdated if price moves significantly
2. **No Volume Filter**: Breakouts not confirmed by volume
3. **No Target**: Strategy is signal-only, no profit targets
4. **Reference Lag**: Reference levels only update on daily close
5. **Cooldown Reset**: Alert deduplication resets on app restart

### Volume Scanner Strategy

1. **No Entry/Exit**: Does not provide trade recommendations
2. **No Stop Loss**: Traders must determine their own risk management
3. **Strict Trend Check**: Requires 5/5 candles in same direction
4. **No Price Context**: Doesn't consider support/resistance levels
5. **False Signals**: Volume spikes can occur without price follow-through

---

## Best Practices

### Daily Breakout Strategy

1. **Volume Confirmation**: Verify volume on your chart before entering
2. **Market Context**: Consider overall market conditions
3. **Reference Age**: Check how old the reference level is
4. **Stop Loss**: Use reference low for BUY, reference high for SELL
5. **Position Sizing**: Apply standard risk management (1-2% per trade)

### Volume Scanner Strategy

1. **Signal Priority**: BREAKOUT > ABSORPTION > CLIMAX for entries
2. **Price Action**: Verify signal with price action analysis
3. **Support/Resistance**: Consider key levels before acting
4. **Time of Day**: Early signals (9:15-11:00 AM) are typically stronger
5. **Paper Trade**: Test each signal type separately

---

## Example Scenarios

### Daily Breakout Example

**Setup:**
```
Symbol: NIFTY
Reference High: 18500 (from Jan 15)
Reference Low: 18300 (from Jan 15)
```

**Jan 16 - Daily Check:**
```
Daily Close: 18480
→ Close (18480) ≤ Reference High (18500)
→ Reference levels unchanged
```

**Jan 17 - Daily Check:**
```
Daily Close: 18520
→ Close (18520) > Reference High (18500) ✓
→ Reference updated: High=18550, Low=18400
```

**Jan 18 - 10:00 AM - Intraday Check:**
```
15-min Candle: Close=18560
→ Close (18560) > Reference High (18550) ✓
→ BULLISH BREAKOUT alert triggered
```

### Volume Scanner Example

**Scenario 1: BREAKOUT**
```
15-min Candle:
- Open: 1000
- High: 1020
- Low: 995
- Close: 1015
- Volume: 500000 (2.5× average)

Analysis:
- Volume ratio (2.5×) ≥ spike multiplier (2.0×) ✓
- Body (15) / Range (25) = 60% ≥ 50% ✓
→ BREAKOUT signal 🚀
```

**Scenario 2: ABSORPTION**
```
15-min Candle:
- Open: 1000
- High: 1005
- Low: 998
- Close: 1000
- Volume: 400000 (2.0× average)

Analysis:
- Volume ratio (2.0×) ≥ spike multiplier (2.0×) ✓
- Body (2) / Range (7) = 28% < 50% ✓
→ ABSORPTION signal 🧱
```

**Scenario 3: CLIMAX**
```
Last 5 candles: All green (close > open)
15-min Candle:
- Open: 1000
- High: 1020
- Low: 995
- Close: 1010
- Volume: 600000 (3.5× average)

Analysis:
- Volume ratio (3.5×) ≥ climax multiplier (3.0×) ✓
- Trending (5/5 green candles) ✓
→ CLIMAX signal 🔥
```

---

## Troubleshooting

### Daily Breakout Strategy

**No Alerts Firing:**
- Check if reference levels are initialized: `GET /delta/daily-breakout/references`
- Verify daily check is running (check logs)
- Ensure cooldown is not blocking alerts

**Reference Levels Not Updating:**
- Verify daily candle close > reference high
- Check if daily check is running after market close
- Ensure lastCheckDate is updating

**Stale Reference Levels:**
- Manually trigger reference check: `POST /delta/daily-breakout/check/{symbol}`
- Consider reinitializing if levels are very old

### Volume Scanner Strategy

**No Volume Spikes Detected:**
- Verify spike multiplier is appropriate (default 2.0×)
- Check if 20-candle lookback is available
- Ensure symbol is being scanned

**Too Many Alerts:**
- Increase spike multiplier threshold
- Check if deduplication is working (candle close time key)
- Consider adding cooldown mechanism

**Incorrect Classification:**
- Verify body ratio calculation (body/range)
- Check trend detection logic (5/5 requirement)
- Review candle data for anomalies

---

## References

### Daily Breakout

- **Service**: `DailyBreakoutService.java`
- **Model**: `DailyBreakoutAlert.java`, `DailyBreakoutLevel.java`
- **API Service**: `DeltaApiService.java`
- **Backtest Engine**: `BacktestEngine.java`

### Volume Scanner

- **Service**: `VolumeScannerService.java`
- **Model**: `VolumeSignal.java`
- **API Service**: `DeltaApiService.java`
- **Backtest Engine**: `VolumeBacktestEngine.java`

### Configuration

- **Config**: `DeltaAppConfig.java`
- **Controllers**: `DeltaBacktestController.java`, `VolumeBacktestController.java`

---

**Last Updated:** June 2026

**Note:** For detailed improvement suggestions and bug fixes, refer to `DELTA_STRATEGY_IMPROVEMENTS.md`
