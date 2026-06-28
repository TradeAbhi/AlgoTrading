# Stock Pattern Analysis Service

Drop the `com/stockanalyzer/model`, `com/stockanalyzer/service`, and `com/stockanalyzer/exception`
packages straight into your existing Spring Boot app's source tree (adjust the package
name with a find-and-replace if your base package isn't `com.stockanalyzer`). No extra
dependencies needed beyond what `spring-boot-starter-web` already gives you.

## What it does

You give it a candle series, a consolidation range (start/end timestamp), a symbol, and
a timeframe. It:

1. Finds the breakout point (end of consolidation) and slices out the 10 candles leading
   into it and the 10 candles after it.
2. **Order flow + liquidity** - approximates buy/sell delta per candle from where the
   close sits within its high-low range, marks the range high/low and any equal
   highs/lows as liquidity pools, and detects wick-and-reclaim liquidity sweeps right
   after the range.
3. **Market structure (supply/demand)** - finds swing highs/lows via simple fractals,
   classifies the trend going into the range (uptrend/downtrend/sideways), confirms
   whether the actual break is a Break of Structure or a Change of Character, and marks
   the origin candle (demand/supply zone) the move came from.
4. **Volume confirmation** - compares the volume on the candle that actually closes
   beyond the range against the average volume during consolidation, and flags
   contracting/expanding volume through the range.
5. Synthesizes all three into a single scored conclusion (BULLISH/BEARISH/NEUTRAL,
   0-100 confidence, plain-English narrative).

## How to call it

Inject `AnalysisOrchestratorService` wherever you need it - it's a plain `@Service` bean:

```java
@RestController
@RequestMapping("/api/analysis")
public class YourController {

    private final AnalysisOrchestratorService orchestrator;

    public YourController(AnalysisOrchestratorService orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping("/consolidation-breakout")
    public AnalysisResponse analyze(@RequestBody YourRequestDto req) {
        return orchestrator.analyze(
            req.getSymbol(),
            req.getTimeframe(),       // Timeframe.FIFTEEN_MIN / DAILY / WEEKLY
            req.getCandles(),         // full chronological List<Candle>
            req.getConsolidationStart(),
            req.getConsolidationEnd()
        );
    }
}
```

The candle list needs at least 10 candles before `consolidationEnd` and 10 after it, or
`CandleWindowExtractor` throws `InsufficientDataException` telling you exactly how many
you're short.

## Important assumptions / limitations (read before trusting the output)

- **No real order flow / Level 2 data.** NSE/BSE doesn't expose historical bid-ask tape
  outside a live broker feed (Kite Connect, Upstox, Angel One, Fyers). The delta numbers
  here are an OHLCV-based proxy (close position within the candle's range, weighted by
  volume) - a standard retail-tool approximation, not the real tape. Good for directional
  signal, not literal volume splits.
- **Liquidity sweep detection only looks at the first 2 candles after the range.** A
  sweep that happens on candle 3+ won't be caught. Easy to extend `lookahead` in
  `OrderFlowLiquidityService.detectLiquiditySweep` if you want a wider window.
- **Swing/fractal detection is a simple 3-candle pattern.** It can miss structure on
  very choppy or very smoothly trending (no pullback) data. Fine for most real price
  action, just don't expect it to match a discretionary trader's eye perfectly.
- **BOS vs CHoCH labeling**: breaking out of a *sideways* range is labeled BOS (new
  structure forming, nothing to reverse); CHoCH is reserved for breaking against an
  established opposing trend.
- This is a decision-support tool, not financial advice - thresholds (1.5x volume,
  0.1% equal-level tolerance, scoring weights) are reasonable defaults, not calibrated
  on your specific stocks/timeframes. Tune them in `VolumeConfirmationService` and
  `OrderFlowLiquidityService` as you see real results.

## Tested example

A worked 15-min example (sideways consolidation 100-105, downside liquidity sweep, BOS
bullish break, confirmed volume) is in `sample-data/sample-request.json` - it produces a
100/100 confidence BULLISH conclusion with all four factors aligned, useful as a smoke
test once you wire up your own controller/DTO around `AnalysisOrchestratorService`.
