# Delta Strategy — Improvement Suggestions

## Priority Order
1. [Bug] Exit time always wrong
2. [Bug] `Double.MIN_VALUE` misuse
3. [Bug] Weekend/holiday previous-day key
4. [Enhancement] Session filter for signals
5. [Bug] LONG full-target dead code
6. [Enhancement] Minimum breach threshold
7. [Enhancement] Max risk cap on PDH/PDL SL
8. [Enhancement] Persist alert cooldown across restarts
9. [Enhancement] Max alerts per symbol per day
10. [Enhancement] Relax trend check in volume scanner
11. [Enhancement] Both-side S/R pivot confirmation
12. [Enhancement] Absorption confirmation body filter
13. [Enhancement] Slippage/fee modeling

---

## Bugs

### 1. Exit Time Always Wrong — `BacktestEngine.java`
`exitTime` is set to `lastCandle.getCloseTime()` for every trade regardless of when the trade actually exited.

**Fix:** Track the exit candle inside the simulation loop (like `VolumeBacktestEngine` does).

```java
// Wrong (current):
.exitTime(exitPrice != null ? lastCandle.getCloseTime() : null)

// Fix — add inside the loop:
Candle exitCandle = null;
// when SL or target is hit:
exitCandle = c;
// then:
.exitTime(exitCandle != null ? exitCandle.getCloseTime() : null)
```

---

### 2. `Double.MIN_VALUE` Misuse — `BacktestEngine.java` & `VolumeBacktestEngine.java`
`Double.MIN_VALUE` is nearly **zero** (smallest positive double), not negative infinity. Using it to initialise `maxWinR` means the largest-win comparison is almost always wrong.

**Fix:**
```java
// Wrong:
BigDecimal maxWinR = BigDecimal.valueOf(Double.MIN_VALUE);

// Fix:
BigDecimal maxWinR = null;
// then when comparing:
if (maxWinR == null || r.compareTo(maxWinR) > 0) maxWinR = r;
// and at the end:
.largestWinR(maxWinR != null ? maxWinR.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO)
```

---

### 3. Weekend/Holiday Previous-Day Key — `BacktestEngine.java`
`prevDayKey()` does a simple `-1 day`, so Monday candles look up Sunday which has no trading data, causing the signal to be silently skipped.

**Fix:** Walk back until a valid trading day is found.
```java
private String prevDayKey(Candle c) {
    LocalDate d = c.getOpenTime().atZone(ZoneOffset.UTC).toLocalDate().minusDays(1);
    while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
        d = d.minusDays(1);
    }
    return d.toString();
}
```

---

### 5. LONG Full-Target Dead Code — `BacktestEngine.java`
Inside the LONG simulation block, the first full-target check is redundant because the partial check already ran above it.

```java
// Dead code — remove this block:
if (c.getHigh().compareTo(partialTarget) >= 0
        && partialHit
        && c.getHigh().compareTo(fullTarget) >= 0) { ... }

// Keep only:
if (!partialHit && c.getHigh().compareTo(fullTarget) >= 0) { ... }
if (partialHit && c.getHigh().compareTo(fullTarget) >= 0) { ... }
```

---

## Enhancements

### 4. Session Filter for Signals — `BacktestEngine.java`
PDH/PDL breakouts during low-liquidity hours (e.g., 2 AM) are mostly noise. Filter signal candles to active market hours.

```java
// Add before signal evaluation:
int hour = signal.getOpenTime().atZone(ZoneOffset.UTC).getHour();
if (hour < 9 || hour >= 17) continue; // adjust to your instrument's session
```

---

### 6. Minimum Breach Threshold — `BacktestEngine.java`
A close that is only 0.01% below PDL is likely a false breakout. Require a minimum distance before triggering.

```java
// In BacktestRequest — add field:
private double minBreachPct = 0.1; // 0.1% minimum breach

// In BacktestEngine signal check:
BigDecimal breachPct = pdl.low.subtract(signal.getClose())
    .divide(pdl.low, 8, RoundingMode.HALF_UP).abs();
if (breachPct.doubleValue() < req.getMinBreachPct() / 100.0) continue;
```

---

### 7. Cap Maximum Risk on PDH/PDL SL — `BacktestEngine.java`
The ref-candle SL can be very far away on volatile days, making risk disproportionately large. Apply the same `computeRisk` cap used in `VolumeBacktestEngine`.

```java
// After computing risk:
BigDecimal maxRisk = entry.multiply(bd(req.getMaxRiskPct() / 100.0), MC);
if (risk.compareTo(maxRisk) > 0) {
    // skip trade — risk too large
    return null; // or log and skip
}
```

---

### 8. Persist Alert Cooldown Across Restarts — `AlertService.java`
`lastAlertTime` is in-memory only. Every app restart clears it and causes duplicate alerts to fire immediately.

**Options (pick one):**
- Store last-alerted candle close time per symbol in Redis with a TTL equal to the cooldown window.
- Persist to a lightweight DB table `(symbol, direction, last_alert_time)`.
- At minimum, use the **candle close time** as the dedup key (not wall-clock time), so the same candle never fires twice even after restart.

```java
// Simpler approach — key on candle close time:
String cooldownKey = signal.getSymbol() + ":" + signal.getDirection() + ":" 
    + signal.getCandleCloseTime().getEpochSecond();
if (lastAlertTime.containsKey(cooldownKey)) return; // already sent for this candle
```

---

### 9. Max Alerts Per Symbol Per Day — `AlertService.java`
During a trending day a symbol can repeatedly breach PDH/PDL, flooding Telegram even with cooldown.

```java
// Add to AlertService:
private final Map<String, Integer> dailyAlertCount = new ConcurrentHashMap<>();
private static final int MAX_ALERTS_PER_DAY = 3;

// In fireAlert():
String dayKey = signal.getSymbol() + ":" + LocalDate.now();
if (dailyAlertCount.getOrDefault(dayKey, 0) >= MAX_ALERTS_PER_DAY) return;
dailyAlertCount.merge(dayKey, 1, Integer::sum);
```
Reset `dailyAlertCount` at the start of each trading day via the scheduler.

---

### 10. Relax Trend Check — `VolumeScannerService.java` & `VolumeBacktestEngine.java`
`isTrending()` requires exactly 5/5 closes in the same direction. A single doji breaks the signal entirely.

```java
// Current (too strict):
return ups == 5 || downs == 5;

// Fix — allow 4 out of 5:
return ups >= 4 || downs >= 4;
```

---

### 11. Both-Side S/R Pivot Confirmation — `VolumeBacktestEngine.java`
`findNearestSrLevel` only checks candles to the **left** of the pivot. This produces weak pivots that may not be respected by price.

```java
// Current: left-only check [j-strength .. j-1]
// Fix: also check right side [j+1 .. j+strength] if available

boolean isPivotHigh = true;
for (int k = j - strength; k < j; k++) {
    if (lookback.get(k).getHigh().compareTo(c.getHigh()) >= 0) { isPivotHigh = false; break; }
}
// Add right-side check:
for (int k = j + 1; k <= Math.min(j + strength, n - 1) && isPivotHigh; k++) {
    if (lookback.get(k).getHigh().compareTo(c.getHigh()) >= 0) isPivotHigh = false;
}
```

---

### 12. Absorption Confirmation Body Filter — `VolumeBacktestEngine.java`
Currently any next-candle close higher/lower triggers an absorption trade, even a 1-tick move.

```java
// Add minimum body size check on confirmation candle:
BigDecimal confirmBody = confirm.getClose().subtract(confirm.getOpen()).abs();
BigDecimal confirmRange = confirm.getHigh().subtract(confirm.getLow());
if (confirmRange.compareTo(BigDecimal.ZERO) == 0) return null;
double bodyRatio = confirmBody.divide(confirmRange, 4, RoundingMode.HALF_UP).doubleValue();
if (bodyRatio < 0.3) return null; // require at least 30% body for confirmation
```

---

### 13. Slippage & Fee Modeling — `BacktestEngine.java` & `VolumeBacktestEngine.java`
Entering at exact candle close price is optimistic. Real orders fill slightly worse.

```java
// Add to BacktestRequest / VolumeBacktestRequest:
private double slippagePct = 0.05; // 0.05% default

// Apply on entry:
BigDecimal slippage = entry.multiply(bd(req.getSlippagePct() / 100.0), MC);
BigDecimal adjustedEntry = isLong
    ? entry.add(slippage, MC)      // long fills higher
    : entry.subtract(slippage, MC); // short fills lower
```

---

## Summary Table

| # | File | Type | Impact |
|---|------|------|--------|
| 1 | `BacktestEngine.java` | Bug | High — all exit times are wrong |
| 2 | `BacktestEngine.java`, `VolumeBacktestEngine.java` | Bug | High — largest win stat is wrong |
| 3 | `BacktestEngine.java` | Bug | Medium — Monday signals silently skipped |
| 4 | `BacktestEngine.java` | Enhancement | Medium — reduces false signals |
| 5 | `BacktestEngine.java` | Bug | Low — dead code, no behavioral impact |
| 6 | `BacktestEngine.java` | Enhancement | Medium — filters micro-breaches |
| 7 | `BacktestEngine.java` | Enhancement | Medium — prevents oversized risk |
| 8 | `AlertService.java` | Enhancement | High — prevents duplicate alerts on restart |
| 9 | `AlertService.java` | Enhancement | Medium — prevents alert flooding |
| 10 | `VolumeScannerService.java`, `VolumeBacktestEngine.java` | Enhancement | Low — more signals captured |
| 11 | `VolumeBacktestEngine.java` | Enhancement | Medium — stronger S/R levels |
| 12 | `VolumeBacktestEngine.java` | Enhancement | Medium — fewer weak entries |
| 13 | `BacktestEngine.java`, `VolumeBacktestEngine.java` | Enhancement | Medium — more realistic results |
