# Opening Candle Strategy — Improvement Suggestions

## Current Strategy Summary

| Step | Rule |
|------|------|
| C1 (9:15) | Strong directional candle: wick ratio >= 0.65, body >= 5pts, body >= 0.3% of open, range >= 0.5× ATR, volume >= 1.5× avg C1 volume |
| A/D Filter | adRatio > 1.5 → BUY only \| adRatio < 0.7 → SELL only \| 0.7–1.5 → both |
| C2 (9:30) | Stays above/below 50% of C1, within 1.414 ext, 38.2% rejection if touched |
| Entry | C2 close |
| SL | C2 low (BUY) or C2 high (SELL) ± 0.45% margin |
| Target | Entry ± 2.5R |
| Exit | Target hit / SL hit / EOD at 3:15 PM |

---

## Improvements — Ordered by Impact on Live Performance

---

### 1. Breakeven Trail After 1R ⭐ Highest Priority

**Problem:** SL stays fixed at C2 low/high for the entire day. A trade that goes 1.5R in your favour and then reverses all the way back hits SL for a full -1R loss. This is the single biggest drag on expectancy.

**Fix:** Once price moves 1R in your favour, move SL to breakeven (entry price).

**Logic:**
- BUY: when `candle.high >= entry + risk` → set `sl = entry`
- SELL: when `candle.low <= entry - risk` → set `sl = entry`
- Exit reason becomes `BREAKEVEN_EXIT` if stopped at entry

**Note:** `BREAKEVEN_EXIT` outcome already exists in `BacktestTrade.Outcome` — it just needs to be triggered from `simulateTrade()`.

**Expected impact:** Converts full -1R losses into 0R scratch trades after 1R move. Dramatically improves expectancy without changing win rate.

---

### 2. Earnings Day Exclusion ⭐ High Priority

**Problem:** A stock on results day will have an abnormal C1 by definition — huge range, huge volume — and passes every filter. But the move is event-driven, not structural. The Fib setup doesn't hold because price behaviour post-earnings is random relative to the C1/C2 pattern.

**Fix:** Exclude any symbol that has earnings within ±1 day of the trade date.

**Implementation:** `EarningsService` already exists in this project. Call it before `isStrongCandle()`:

```java
if (earningsService.hasEarningsNear(symbol, date, 1)) {
    log.info("{} {} — SKIPPED: earnings within 1 day", symbol, date);
    return Optional.empty();
}
```

**Expected impact:** Removes a category of high-noise, low-edge setups entirely.

---

### 3. C2 Close Direction Check ⭐ High Priority

**Problem:** C2 only needs to stay in the zone (above 50%, within 1.414 ext). But you never check which direction C2 itself closed. A BUY setup where C2 closes near its own low is a weak confirmation — buyers are losing control within the consolidation candle itself.

**Fix:**
- BUY setup: C2 must close in the **upper half** of its own range → `c2.close > (c2.high + c2.low) / 2`
- SELL setup: C2 must close in the **lower half** of its own range → `c2.close < (c2.high + c2.low) / 2`

**Code location:** Add inside `isValidC2()` before returning true.

**Expected impact:** Eliminates weak confirmations where the consolidation candle itself shows reversal pressure.

---

### 4. Gap Filter — Exclude Pre-Market Gap Stocks ⭐ High Priority

**Problem:** If C1 opens with a 2%+ gap from the previous day's close, the range and body look huge and pass all filters. But the "move" already happened pre-market. You are entering after the easy money is gone, chasing a move that has already extended.

**Fix:** Filter out stocks where the opening gap is too large:

```
abs(c1.open - prevDayClose) / prevDayClose > maxGapPct (e.g. 1.5%)
```

**Requires:** Previous day close price — fetch from `prevDay` candles already available in `BacktestRunnerService`.

**Expected impact:** Stops chasing pre-market driven moves that have no intraday follow-through.

---

### 5. C2 Volume Must Be Lower Than C1 Volume

**Problem:** You check C1 volume to confirm institutional participation. But you never check C2 volume. A valid consolidation candle (C2) should have *lower* volume than C1 — it means the market paused, not that it's fighting back. If C2 volume > C1 volume, it signals distribution or absorption against your direction.

**Fix:** Add to `isValidC2()`:

```java
boolean c2VolumeLower = c2.getVolume() < c1.getVolume();
// If C2 volume >= C1 volume, it's not a genuine consolidation
```

**Expected impact:** Filters out C2 candles where smart money is quietly exiting during the "consolidation".

---

### 6. Prior Day Trend Alignment

**Problem:** You check A/D ratio for broad market direction but not the individual stock's own recent trend. A bearish C1 setup on a stock that closed up 3 days in a row is fighting the stock's own momentum.

**Fix:**
- BUY setup: previous day must have closed bullish (`prevDay.close > prevDay.open`)
- SELL setup: previous day must have closed bearish (`prevDay.close < prevDay.open`)

**Stronger version:** Previous 2 out of 3 days closed in the setup direction.

**Expected impact:** Aligns individual stock direction with the trade, improving follow-through probability.

---

### 7. EOD Exit Time — Move from 3:15 PM to 2:45 PM

**Problem:** Current EOD exit is at 3:15 PM. The last 15–30 minutes of Indian markets are often choppy with wide spreads and program-driven flows. Many trades that are profitable at 2:30 give back gains in closing auction noise.

**Fix:** Change `EOD_TIME` from `LocalTime.of(15, 15)` to `LocalTime.of(14, 45)`.

**Trade-off:** You lose 30 minutes of potential trend continuation. Backtest both values to confirm which is better for your symbol universe.

---

### 8. Partial Exit at 1.5R + Trail

**Problem:** Fixed 2.5R target is all-or-nothing. Many trades reach 1.5R–2R and then reverse before hitting 2.5R, resulting in an EOD exit at breakeven or a small loss.

**Fix:** Book 50% of position at 1.5R, trail SL to breakeven, let remaining 50% run to 2.5R.

**Outcomes this creates:**
| Scenario | Result |
|----------|--------|
| Hits 2.5R after partial | +2.0R weighted (0.5×1.5 + 0.5×2.5) |
| Hits 1.5R then SL at BE | +0.75R (0.5×1.5 + 0) |
| Never hits 1.5R, SL hit | -1R |

**Note:** `BacktestEngine` in the delta module already implements this exact logic (`partialExitRR`, `partialExitQtyPct`). The same pattern can be ported to `simulateTrade()`.

---

### 9. Daily Loss Cap — Stop After N Losses

**Problem:** On a bad day where the market setup is wrong, the strategy can fire 5–10 setups and lose most of them. Fixed ₹10,000 risk × 5 losses = ₹50,000 drawdown in a single day.

**Fix:** Add a daily loss counter in `BacktestRunnerService`. If losses on a given date exceed `maxDailyLosses` (e.g. 2), skip all remaining symbols for that day.

---

### 10. Risk as % of Capital, Not Fixed Rupees

**Problem:** ₹10,000 risk is 1% of a ₹10L account (correct) but 10% of a ₹1L account (reckless). The config expresses risk in absolute rupees which doesn't scale with account size.

**Fix:** Add `riskPercent` (e.g. 1.0%) to `BacktestConfig`. Calculate risk rupees at runtime:

```java
double riskRupees = accountCapital * (config.getRiskPercent() / 100.0);
int quantity = (int) Math.floor(riskRupees / riskPoints);
```

---

## Summary Table

| # | Improvement | Priority | Effort | Code Location |
|---|-------------|----------|--------|---------------|
| 1 | Breakeven trail after 1R | 🔴 Critical | Low | `simulateTrade()` |
| 2 | Earnings day exclusion | 🔴 Critical | Low | `evaluate()` — `EarningsService` already exists |
| 3 | C2 close direction check | 🟠 High | Low | `isValidC2()` |
| 4 | Gap filter | 🟠 High | Medium | `evaluate()` — needs prevDay close |
| 5 | C2 volume < C1 volume | 🟠 High | Low | `isValidC2()` |
| 6 | Prior day trend alignment | 🟡 Medium | Medium | `evaluate()` — needs prevDay candle |
| 7 | EOD exit at 2:45 PM | 🟡 Medium | Trivial | `EOD_TIME` constant |
| 8 | Partial exit at 1.5R + trail | 🟡 Medium | Medium | `simulateTrade()` |
| 9 | Daily loss cap | 🟡 Medium | Medium | `BacktestRunnerService` |
| 10 | Risk as % of capital | 🟢 Low | Low | `BacktestConfig` + `simulateTrade()` |

---

## What Is Already Implemented ✅

| Filter | Status |
|--------|--------|
| C1 wick ratio >= 0.65 | ✅ Done |
| C1 body >= 5 points | ✅ Done |
| C1 body >= 0.3% of open (Problem 4) | ✅ Done |
| C1 range >= 0.5× 20-day ATR (Problem 1) | ✅ Done |
| C1 volume >= 1.5× avg 9:15 candle volume (Problem 2) | ✅ Done |
| A/D ratio trend filter (Problem 3) | ✅ Done |
| C2 above/below 50% of C1 | ✅ Done |
| C2 within 1.414 Fib extension | ✅ Done |
| C2 38.2% rejection filter | ✅ Done |
| Fixed 1% risk per trade | ✅ Done |

---

*Last Updated: June 2026*
