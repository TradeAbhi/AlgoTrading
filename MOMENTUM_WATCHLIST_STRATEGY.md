# Momentum/Watchlist Strategy

## Overview

The Momentum/Watchlist strategy provides real-time market surveillance by categorizing F&O stocks into 7 distinct watchlist categories based on price action, volume, and order flow. It serves as a market scanner to identify stocks exhibiting strong momentum, unusual activity, or directional bias.

## Strategy Concept

The strategy continuously monitors the entire F&O universe and categorizes stocks based on real-time metrics. Unlike traditional signal-based strategies, this is a surveillance tool that highlights stocks requiring immediate attention.

### Key Principles

1. **Real-Time Surveillance**: Continuous monitoring during market hours
2. **F&O Focus**: Only tracks F&O eligible stocks for liquidity
3. **Multiple Categories**: 7 distinct watchlist types for different market conditions
4. **Volume Ratio Analysis**: Compares current volume to 20-day average
5. **Order Flow Analysis**: Tracks buy/sell quantity imbalances
6. **Cached Results**: Optimized performance with configurable cache TTL

## Watchlist Categories

### 1. High OI (Open Interest)

**Description**: Stocks/contracts with highest open interest.

**Significance**: Identifies where smart money is positioned. High OI indicates institutional activity and potential support/resistance levels.

**Filter**: Open Interest ≥ minimum threshold (configurable)

**Sorting**: Descending by OI

**Limit**: Top N stocks (configurable)

---

### 2. Top Gainers

**Description**: Stocks with highest positive % change.

**Significance**: Identifies stocks with strong upward momentum and buying pressure.

**Filter**: Change % > 0 (only positive changers)

**Sorting**: Descending by change %

**Limit**: Top N stocks (configurable)

---

### 3. Top Losers

**Description**: Stocks with highest negative % change.

**Significance**: Identifies stocks under selling pressure and potential short opportunities.

**Filter**: Change % < 0 (only negative changers)

**Sorting**: Ascending by change % (most negative first)

**Limit**: Top N stocks (configurable)

---

### 4. Active by Value

**Description**: Stocks with highest traded value (crores).

**Significance**: Identifies where the most money is flowing. High value indicates institutional participation and liquidity.

**Sorting**: Descending by traded value (crores)

**Limit**: Top N stocks (configurable)

---

### 5. Volume Shockers

**Description**: Stocks where current volume is ≥ N× the 20-day average volume.

**Significance**: Indicates unusual activity, potential breakout/breakdown, or news-driven moves.

**Filter**: Volume Ratio ≥ threshold (default 2.0×)

**Sorting**: Descending by volume ratio

**Limit**: Top N stocks (configurable)

---

### 6. Only Buyers

**Description**: Stocks where buy quantity significantly exceeds sell quantity.

**Significance**: Signals strong demand, aggressive buying, and potential upside momentum.

**Filter**: Buy/Sell Ratio ≥ threshold (default 3.0×) OR totalSellQty = 0

**Sorting**: Descending by buy/sell ratio

**Limit**: Top N stocks (configurable)

---

### 7. Only Sellers

**Description**: Stocks where sell quantity significantly exceeds buy quantity.

**Significance**: Signals strong selling pressure, distribution, and potential downside momentum.

**Filter**: Sell/Buy Ratio ≥ threshold (default 3.0×) OR totalBuyQty = 0

**Sorting**: Descending by total sell quantity

**Limit**: Top N stocks (configurable)

---

## Filtering Criteria

### Base Filters (Applied to All Categories)

1. **F&O Eligibility**: Only stocks in Nifty F&O symbol set
2. **Valid LTP**: Last traded price > 0 (filters circuit limits/halted stocks)
3. **Minimum Liquidity**: Traded value ≥ minimum threshold (configurable, default ₹20 crores)

### Category-Specific Filters

Each category has its own additional filters as described above.

## Strategy Parameters

| Parameter | Default Value | Description |
|-----------|---------------|-------------|
| cacheTtlSeconds | 60 | Cache refresh interval during market hours |
| minTradedValueCrores | 20 | Minimum traded value for liquidity filter |
| minOpenInterest | Configurable | Minimum OI for High OI category |
| highOiLimit | Configurable | Number of stocks in High OI list |
| topGainersLimit | Configurable | Number of stocks in Top Gainers list |
| topLosersLimit | Configurable | Number of stocks in Top Losers list |
| activeByValueLimit | Configurable | Number of stocks in Active by Value list |
| volumeShockerThreshold | 2.0× | Minimum volume ratio for Volume Shockers |
| volumeShockerLimit | Configurable | Number of stocks in Volume Shockers list |
| onlyBuyersRatioThreshold | 3.0× | Minimum buy/sell ratio for Only Buyers |
| onlyBuyersLimit | Configurable | Number of stocks in Only Buyers list |
| onlySellersRatioThreshold | 3.0× | Minimum sell/buy ratio for Only Sellers |
| onlySellersLimit | Configurable | Number of stocks in Only Sellers list |

## Workflow

### 1. Universe Fetching

- Fetch entire F&O universe from UniverseService
- Uses predefined NIFTY_FNO_SYMBOLS set for fast O(1) eligibility check

### 2. Live Quote Fetching

- Fetch live quotes for all universe symbols from Upstox
- Retrieves LTP, change %, volume, OI, traded value, buy/sell quantities

### 3. Volume Ratio Enrichment

- For each stock, compute volume ratio using AverageVolumeService
- Volume Ratio = Current Volume / 20-Day Average Volume
- Enriches each item with volumeRatio field

### 4. Base Filtering

- Filter out non-F&O stocks
- Filter out stocks with zero/negative LTP
- Filter out stocks below minimum traded value threshold

### 5. Category Building

- Build each of the 7 categories independently
- Apply category-specific filters
- Sort and limit to configured size
- Assign category label to each item

### 6. Caching

- Cache the complete watchlist response
- Refresh cache every `cacheTtlSeconds` during market hours
- Cache evicted automatically by scheduler

### 7. Response Generation

- Return structured response with all categories
- Include metadata: generation time, market status, total symbols scanned

## API Endpoints

### Main Endpoints

- `GET /watchlist/live` - Returns full watchlist with all categories (cached)
- `GET /watchlist/category/{category}` - Returns specific category only

**Category Values:**
- `HIGH_OI`
- `TOP_GAINER`
- `TOP_LOSER`
- `ACTIVE_BY_VALUE`
- `VOLUME_SHOCKER`
- `ONLY_BUYERS`
- `ONLY_SELLERS`

### Response Structure

```json
{
  "highOiStocks": [...],
  "topGainers": [...],
  "topLosers": [...],
  "activeByValue": [...],
  "volumeShockers": [...],
  "onlyBuyers": [...],
  "onlySellers": [...],
  "generatedAt": "2026-06-29T10:30:00",
  "marketStatus": "OPEN",
  "totalSymbolsScanned": 250
}
```

## Data Models

### Watchlist Item

```java
class WatchlistItem {
    String symbol;
    String name;
    double ltp;              // Last traded price
    double changePercent;    // % change from previous close
    long volume;             // Current volume
    double volumeRatio;      // Current / 20-day average
    long openInterest;       // Open interest
    double tradedValue;      // Traded value in crores
    long totalBuyQty;        // Total buy quantity
    long totalSellQty;       // Total sell quantity
    double buySelRatio;      // Buy/Sell ratio
    WatchlistCategory category;
}
```

### Watchlist Category

```java
enum WatchlistCategory {
    HIGH_OI,
    TOP_GAINER,
    TOP_LOSER,
    ACTIVE_BY_VALUE,
    VOLUME_SHOCKER,
    ONLY_BUYERS,
    ONLY_SELLERS
}
```

## Scheduling

### Cache Refresh

- **Schedule**: Every `cacheTtlSeconds` (default 60 seconds)
- **Condition**: Only during market hours (9:15 AM - 3:30 PM IST)
- **Action**: Evicts cache, forcing refresh on next request

### Market Hours Detection

**Market Status:**
- `CLOSED`: Before 9:00 AM or after 3:30 PM
- `PRE_OPEN`: 9:00 AM - 9:15 AM
- `OPEN`: 9:15 AM - 3:30 PM

## Advantages

1. **Real-Time Surveillance**: Continuous monitoring during market hours
2. **Comprehensive Coverage**: 7 distinct categories for different market conditions
3. **F&O Focus**: Only liquid, tradeable stocks
4. **Volume Analysis**: Identifies unusual activity via volume ratio
5. **Order Flow Insight**: Buy/sell quantity analysis reveals market bias
6. **Cached Performance**: Optimized for frequent requests
7. **Configurable**: All thresholds and limits are configurable

## Limitations

1. **No Signals**: This is a surveillance tool, not a signal generator
2. **No Entry/Exit**: Does not provide trade recommendations
3. **No Stop Loss**: Traders must determine their own risk management
4. **Lag Dependency**: Depends on Upstox data feed latency
5. **Cache Delay**: Data is up to `cacheTtlSeconds` old
6. **No Historical Context**: Only shows current state, no historical comparison

## Best Practices

1. **Cross-Reference**: Use multiple categories for confirmation
2. **Volume Confirmation**: Verify volume shockers with price action
3. **Order Flow**: Pay attention to Only Buyers/Sellers for short-term direction
4. **Market Context**: Consider overall market conditions
5. **Liquidity Check**: Always verify traded value before trading
6. **Time of Day**: Early signals (9:15-10:30 AM) are typically stronger
7. **Combine with Strategies**: Use as a screener for other strategies

## Use Cases

### 1. Pre-Market Preparation

- Review Active by Value to identify where money is flowing
- Check Volume Shockers for unusual overnight activity
- Monitor High OI for key support/resistance levels

### 2. Intraday Trading

- Watch Only Buyers for aggressive buying opportunities
- Track Top Gainers for momentum plays
- Monitor Volume Shockers for breakout confirmations

### 3. Short-Term Trading

- Use Only Sellers for short-selling opportunities
- Track Top Losers for potential bounce plays
- Monitor Volume Shockers for breakdown confirmations

### 4. Position Management

- Check High OI for key levels to watch
- Monitor Active by Value for institutional activity
- Review category changes for shifts in market sentiment

## Example Scenarios

### Scenario 1: Breakout Confirmation

```
9:30 AM: Stock appears in Volume Shockers (3.2× average volume)
9:45 AM: Stock appears in Top Gainers (+2.5%)
10:00 AM: Stock appears in Only Buyers (buy/sell ratio 5.0×)
→ Strong buy signal with multiple confirmations
```

### Scenario 2: Distribution Pattern

```
10:30 AM: Stock appears in Active by Value (₹50 crores)
11:00 AM: Stock appears in Only Sellers (sell/buy ratio 4.0×)
11:30 AM: Stock appears in Top Losers (-1.8%)
→ Distribution pattern, potential short setup
```

### Scenario 3: Institutional Activity

```
9:15 AM: Stock in High OI (top 10)
10:00 AM: Stock in Active by Value (₹100 crores)
10:30 AM: Stock in Volume Shockers (2.5× average)
→ Institutional accumulation, monitor for breakout
```

## Troubleshooting

### Empty Categories

- Check if market is open (market status in response)
- Verify cache is refreshing (check logs)
- Ensure F&O universe is populated
- Check if filters are too restrictive

### Stale Data

- Verify cache refresh scheduler is running
- Check if `cacheTtlSeconds` is appropriate
- Ensure app is running during market hours

### Missing Symbols

- Verify symbol is in F&O universe
- Check if Upstox data feed is returning quotes
- Ensure symbol passes base filters (LTP, traded value)

### Performance Issues

- Increase `cacheTtlSeconds` to reduce refresh frequency
- Check if universe size is too large
- Verify Upstox API response times

## References

- **Service**: `WatchlistService.java`
- **Controller**: `WatchlistController.java`
- **Config**: `WatchlistConfig.java`
- **Volume Service**: `AverageVolumeService.java`
- **Universe Service**: `UniverseService.java`
- **Market Data Service**: `UpstoxMarketDataService.java`

---

**Last Updated:** June 2026
