# Opening Candle Strategy

## Overview

The Opening Candle strategy is a Fibonacci-based intraday trading system that uses the first two 15-minute candles (9:15 and 9:30 AM) to identify high-probability directional setups. It combines candlestick analysis, Fibonacci extensions, volume confirmation, and market breadth filters.

## Strategy Concept

The strategy is based on the premise that the first 30 minutes of trading establishes the day's directional bias. A strong opening candle followed by a consolidation candle at Fibonacci levels provides a high-probability entry with defined risk.

### Key Principles

1. **Strong Opening Candle**: C1 (9:15) must show institutional participation
2. **Fibonacci Consolidation**: C2 (9:30) must consolidate at key Fib levels
3. **Volume Confirmation**: C1 volume must be above average, C2 volume below C1
4. **Market Breadth Filter**: A/D ratio filters based on overall market direction
5. **Partial Exit + Trail**: Books 50% at 1.5R, trails SL to breakeven
6. **EOD Exit**: All positions closed at 3:15 PM

## Strategy Rules

### Step 1: C1 (9:15 AM) Qualification

The opening candle must satisfy ALL of the following:

| Filter | Condition | Purpose |
|--------|-----------|---------|
| Wick Ratio | ≥ 0.65 | Small wicks = strong conviction |
| Body Size | ≥ 5 points | Minimum body size (configurable) |
| Body % of Open | ≥ 0.3% | Body as % of open price |
| Range vs ATR | ≥ 0.5× 20-day ATR | Range must be significant |
| Volume | ≥ 1.5× avg C1 volume | Institutional participation |

**Direction Determination:**
- Bullish C1 (close > open) → BUY setup
- Bearish C1 (close < open) → SELL setup

### Step 2: A/D Trend Filter

Based on Advance/Decline ratio of the market:

| A/D Ratio | BUY Direction | SELL Direction |
|-----------|---------------|----------------|
| < 0.7 | BLOCKED | Allowed |
| 0.7 - 1.5 | Allowed | Allowed |
| > 1.5 | Allowed | BLOCKED |

**Purpose:** Aligns trades with overall market breadth.

### Step 3: C2 (9:30 AM) Confirmation

The consolidation candle must satisfy ALL of the following:

**Volume Filter:**
- C2 volume < C1 volume (genuine consolidation, not distribution)

**BUY Setup Conditions:**
- C2 low > 50% of C1 range (stays in upper half)
- C2 high ≤ C1 high + (C1 range × 0.414) [1.414 Fib extension]
- If C2 low ≤ 38.2% retracement level → C2 close must be above it

**SELL Setup Conditions:**
- C2 high < 50% of C1 range (stays in lower half)
- C2 low ≥ C1 low - (C1 range × 0.414) [1.414 Fib extension]
- If C2 high ≥ 38.2% retracement level → C2 close must be below it

### Step 4: Entry and Risk Management

**Entry:** C2 close price

**Stop Loss:**
- BUY: C2 low × (1 - SL margin %) [default 0.45%]
- SELL: C2 high × (1 + SL margin %) [default 0.45%]

**Risk:** Entry - SL (BUY) or SL - Entry (SELL)

**Target:** Entry ± (Risk × 2.5) [2.5R target]

### Step 5: Trade Simulation

**Partial Exit:**
- Book 50% of position at 1.5R
- Trail SL to breakeven (entry price)
- Remaining 50% runs to 2.5R target

**Exit Conditions (in order of priority):**
1. SL hit (or breakeven after partial)
2. Full target hit (2.5R)
3. EOD exit at 3:15 PM

**Outcomes:**
- `TARGET_HIT`: Full 2.5R achieved
- `SL_HIT`: Stop loss hit before partial
- `BREAKEVEN_EXIT`: SL hit at breakeven after partial
- `EOD_EXIT`: Neither target nor SL hit by 3:15 PM

## Strategy Parameters

| Parameter | Default Value | Description |
|-----------|---------------|-------------|
| C1_TIME | 9:15 AM | Opening candle time |
| C2_TIME | 9:30 AM | Consolidation candle time |
| EOD_TIME | 3:15 PM | End of day exit time |
| minWickRatio | 0.65 | Minimum wick ratio for C1 |
| minCandleBodyPoints | 5 | Minimum body size in points |
| minC1BodyPct | 0.3% | Minimum body as % of open |
| minC1AtrRatio | 0.5× | Minimum range vs ATR |
| minC1VolumeMultiplier | 1.5× | Minimum volume vs average |
| slMarginPercent | 0.45% | SL margin around C2 high/low |
| targetRR | 2.5R | Full target R-multiple |
| partialExitRR | 1.5R | Partial exit R-multiple |
| partialExitQtyPct | 50% | Quantity to exit at partial |
| fixedRiskRupees | ₹10,000 | Fixed risk per trade |

## Workflow

### 1. Data Fetching

For each symbol and date:
1. Fetch 15-minute candles for the day
2. Extract C1 (9:15) and C2 (9:30) candles
3. Fetch optional external data:
   - 20-day ATR
   - 5-day average C1 volume
   - A/D ratio

### 2. C1 Qualification

Check if C1 satisfies all filters:
- Wick ratio ≥ 0.65
- Body ≥ 5 points
- Body ≥ 0.3% of open
- Range ≥ 0.5× ATR (if ATR available)
- Volume ≥ 1.5× average (if average available)

If C1 fails → No trade for the day.

### 3. Direction Determination

- If C1 close > open → BUY direction
- If C1 close < open → SELL direction

### 4. A/D Filter

Apply A/D ratio filter:
- If A/D < 0.7 and direction = BUY → Block
- If A/D > 1.5 and direction = SELL → Block
- Otherwise → Allow

### 5. C2 Confirmation

Check if C2 satisfies all conditions:
- C2 volume < C1 volume
- BUY: C2 low > 50% of C1, within 1.414 ext, 38.2% rejection OK
- SELL: C2 high < 50% of C1, within 1.414 ext, 38.2% rejection OK

If C2 fails → No trade for the day.

### 6. Trade Setup

Calculate trade levels:
- Entry = C2 close
- SL = C2 low/high ± margin
- Risk = Entry - SL (BUY) or SL - Entry (SELL)
- Target = Entry ± (Risk × 2.5)
- Partial Target = Entry ± (Risk × 1.5)

### 7. Trade Simulation

Simulate trade candle-by-candle:
1. For each candle after C2:
   - Check if partial target hit (1.5R) → Book 50%, trail SL to entry
   - Check if SL hit → Exit (SL_HIT or BREAKEVEN_EXIT)
   - Check if full target hit (2.5R) → Exit (TARGET_HIT)
   - Check if EOD (3:15 PM) → Exit (EOD_EXIT)

### 8. P&L Calculation

Calculate weighted P&L:
- If partial hit: Weighted average of partial + remainder legs
- If no partial: Simple P&L from entry to exit
- Calculate points, rupees, percent, and actual R

## Fibonacci Levels

### Key Levels Used

**1.414 Extension:**
- BUY: C1 high + (C1 range × 0.414)
- SELL: C1 low - (C1 range × 0.414)
- Purpose: Maximum extension for C2 consolidation

**38.2% Retracement:**
- BUY: C1 high - (C1 range × 0.382)
- SELL: C1 low + (C1 range × 0.382)
- Purpose: Rejection level - if touched, close must be on correct side

**50% Level:**
- BUY: (C1 high + C1 low) / 2
- SELL: (C1 high + C1 low) / 2
- Purpose: Midpoint of C1 range - C2 must stay on correct side

## Risk Management

### Position Sizing

**Fixed Risk Method:**
```
Quantity = floor(riskRupees / riskPoints)
```

Example:
```
Risk per trade: ₹10,000
Risk points: 20
Quantity = 10000 / 20 = 500 shares
```

### Partial Exit Logic

**At 1.5R:**
- Book 50% of position at partial target
- Move SL to entry (breakeven)
- Remaining 50% runs to 2.5R

**P&L Calculation:**
```
Partial Leg P&L = (partialExit - entry) × 50%
Remainder Leg P&L = (exit - entry) × 50%
Total P&L = Partial Leg + Remainder Leg
```

### Stop Loss

**Initial SL:**
- BUY: C2 low × (1 - 0.45%) = C2 low × 0.9955
- SELL: C2 high × (1 + 0.45%) = C2 high × 1.0045

**Breakeven SL:**
- After partial exit at 1.5R, SL moves to entry
- Prevents giving back profits

## API Endpoints

### Backtest Endpoints

- `POST /fibo/backtest` - Run backtest for symbol/date range
- `POST /fibo/index-backtest` - Run backtest for index

### Live Strategy Endpoints

- `POST /fibo/live-alert` - Trigger live strategy alert
- `GET /fibo/live-status` - Check live strategy status

## Data Models

### BacktestTrade

```java
class BacktestTrade {
    String symbol;
    LocalDate tradeDate;
    Direction direction;           // BUY or SELL
    
    // C1 data
    double c1Open, c1High, c1Low, c1Close;
    double c1WickRatio;
    
    // C2 data
    double c2Open, c2High, c2Low, c2Close;
    
    // Trade levels
    double entryPrice;
    double stopLoss;
    double target;
    double riskPoints;
    double rewardPoints;
    
    // Position sizing
    int quantity;
    double riskRupees;
    
    // Results
    double pnlRupees;
    double pnlPoints;
    double pnlPercent;
    double actualRR;
    Outcome outcome;              // TARGET_HIT, SL_HIT, BREAKEVEN_EXIT, EOD_EXIT
    double exitPrice;
    LocalDateTime exitCandleTime;
    LocalDateTime createdAt;
}
```

## Advantages

1. **Fibonacci Precision**: Uses mathematically-defined levels for entries
2. **Volume Confirmation**: Ensures institutional participation
3. **Market Context**: A/D filter aligns with market breadth
4. **Defined Risk**: Clear SL and target before entry
5. **Partial Exit**: Locks in profits while letting winners run
6. **Breakeven Trail**: Protects profits after 1.5R move
7. **EOD Exit**: No overnight risk

## Limitations

1. **Time-Sensitive**: Only works at 9:15-9:30 AM window
2. **No Gap Filter**: Doesn't filter pre-market gaps
3. **No Earnings Filter**: Doesn't exclude earnings-day stocks
4. **Fixed Risk**: Uses fixed rupees, not % of capital
5. **No Daily Loss Cap**: Can have multiple losses in a day
6. **C2 Direction**: Doesn't check C2 close direction
7. **Strict Trend Filter**: A/D filter can block good setups

## Best Practices

1. **Market Context**: Consider overall market conditions beyond A/D ratio
2. **Gap Analysis**: Check for pre-market gaps before entering
3. **Earnings Check**: Avoid stocks with earnings near trade date
4. **Position Sizing**: Use fixed risk based on account size
5. **Time of Day**: Early signals (9:30-10:00 AM) are typically stronger
6. **Paper Trade**: Test strategy live before committing capital
7. **Review C2**: Check C2 close direction for additional confirmation

## Example Trade

### Setup

```
Symbol: RELIANCE
Date: 2026-06-29
```

### C1 (9:15 AM)

```
Open: ₹2500
High: ₹2520
Low: ₹2490
Close: ₹2515
Volume: 5M (avg 3M)

Analysis:
- Wick ratio: 0.72 ✓ (≥ 0.65)
- Body: 15 points ✓ (≥ 5)
- Body % of open: 0.6% ✓ (≥ 0.3%)
- Range: 30 points (ATR 50, 0.6× ATR) ✓
- Volume: 1.67× average ✓
→ Direction: BUY
```

### A/D Filter

```
A/D Ratio: 1.2
→ BUY allowed (0.7 ≤ 1.2 ≤ 1.5) ✓
```

### C2 (9:30 AM)

```
Open: ₹2512
High: ₹2518
Low: ₹2505
Close: ₹2514
Volume: 3.5M

Analysis:
- Volume (3.5M) < C1 volume (5M) ✓
- C2 low (2505) > 50% of C1 (2505) ✓
- C2 high (2518) ≤ 1.414 ext (2525) ✓
- 38.2% level: 2512, C2 low (2505) ≤ level, C2 close (2514) > level ✓
→ C2 confirmation passed ✓
```

### Trade Setup

```
Entry: ₹2514
SL: 2505 × 0.9955 = ₹2489
Risk: 2514 - 2489 = 25 points
Target: 2514 + (25 × 2.5) = ₹2576.5
Partial Target: 2514 + (25 × 1.5) = ₹2551.5
```

### Simulation

```
10:00 AM: High ₹2540, Low ₹2510, Close ₹2535
→ Partial target (2551.5) not hit

10:15 AM: High ₹2555, Low ₹2520, Close ₹2550
→ Partial target (2551.5) hit ✓
→ Book 50% at ₹2551.5
→ SL moved to entry (₹2514)

10:30 AM: High ₹2560, Low ₹2545, Close ₹2555
→ SL (2514) not hit
→ Full target (2576.5) not hit

11:00 AM: High ₹2580, Low ₹2550, Close ₹2575
→ Full target (2576.5) hit ✓
→ TARGET_HIT

P&L Calculation:
Partial leg: (2551.5 - 2514) × 50% = 18.75 points
Remainder leg: (2576.5 - 2514) × 50% = 31.25 points
Total P&L: 50 points
Actual R: 50 / 25 = 2.0R
```

## Troubleshooting

### No Trades Generated

- Check if C1 candles satisfy all filters
- Verify A/D ratio is not blocking direction
- Ensure C2 volume < C1 volume
- Check if C2 stays within Fibonacci levels

### Low Win Rate

- Review C1 qualification filters (may be too loose)
- Check if A/D filter is too restrictive
- Verify C2 confirmation logic
- Consider adding gap filter

### Large Drawdowns

- Implement daily loss cap (max N losses per day)
- Use risk as % of capital instead of fixed rupees
- Add earnings day exclusion
- Consider reducing position size

### Breakeven Exits

- This is expected behavior (protects profits)
- Consider moving breakeven later (e.g., 2R instead of 1.5R)
- Review if partial exit percentage is appropriate

## Improvements Implemented

The strategy has been enhanced with the following improvements (see `OPENING_CANDLE_STRATEGY_IMPROVEMENTS.md` for details):

1. **Breakeven Trail After 1.5R** - Moves SL to entry after partial exit
2. **C2 Volume < C1 Volume** - Ensures genuine consolidation
3. **Partial Exit at 1.5R** - Books 50% and trails SL
4. **Risk as % of Capital** - Configurable risk instead of fixed rupees

## References

- **Service**: `OpeningCandleStrategyService.java`
- **Backtest Service**: `BacktestRunnerService.java`
- **Index Backtest**: `IndexBacktestService.java`
- **Live Strategy**: `LiveStrategyAlertService.java`
- **Controller**: `LiveStrategyController.java`
- **Config**: `BacktestConfig.java`

---

**Last Updated:** June 2026

**Note:** For detailed improvement suggestions and bug fixes, refer to `OPENING_CANDLE_STRATEGY_IMPROVEMENTS.md`
