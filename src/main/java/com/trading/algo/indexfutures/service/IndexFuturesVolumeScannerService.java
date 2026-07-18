package com.trading.algo.indexfutures.service;

import com.trading.algo.dtos.Candle;
import com.trading.algo.indexfutures.model.IndexFuturesVolumeTradeRecord.SignalType;
import com.trading.algo.telegram.TelegramService;
import com.trading.algo.upstox.UpstoxHistoricalCandleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live scanner for volume spikes on Nifty 50 and Bank Nifty futures (index candles).
 *
 * Runs every 15 minutes during market hours (9:31 AM - 3:16 PM, Mon-Fri).
 * Sends Telegram alerts via the intraday bot when a volume spike is detected.
 *
 * Signal types (same logic as Delta VolumeScannerService):
 *   BREAKOUT   : volume >= 2x avg + big body (>= 50% of range) -> trade in candle direction
 *   ABSORPTION : volume >= 2x avg + small body (< 50% of range) -> wait for next candle reversal
 *   CLIMAX     : volume >= 3x avg + 5 consecutive same-direction candles -> fade the move
 *
 * Instrument keys used:
 *   Nifty 50   : NSE_INDEX|Nifty 50
 *   Bank Nifty : NSE_INDEX|Nifty Bank
 *
 * For live futures, swap to the active monthly contract key in application.yml.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndexFuturesVolumeScannerService {

    private static final int    LOOKBACK         = 20;
    private static final double BODY_RATIO        = 0.50;
    private static final double SPIKE_MULTIPLIER  = 2.0;
    private static final double CLIMAX_MULTIPLIER = 3.0;

    private static final LocalTime MARKET_OPEN  = LocalTime.of(9, 15);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    // Instrument definitions: label -> Upstox instrument key
    private static final Map<String, String> FUTURES_INSTRUMENTS = Map.of(
            "NIFTY FUT",      "NSE_INDEX|Nifty 50",
            "BANKNIFTY FUT",  "NSE_INDEX|Nifty Bank"
    );

    private final UpstoxHistoricalCandleService candleService;
    private final com.trading.algo.upstox.UpstoxTokenService upstoxTokenService;
    private final TelegramService               telegramService;

    // Deduplication: one alert per instrument per candle close time
    private final Map<String, String> lastAlertedCandle = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Scheduled scan — every 15 minutes from 9:31 AM to 3:16 PM
    // -------------------------------------------------------------------------

    @Scheduled(cron = "0 31/15 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void morningScans() {
        runScan();
    }

    @Scheduled(cron = "0 1/15 10-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void intradayScans() {
        LocalTime now = LocalTime.now();
        if (now.isAfter(MARKET_CLOSE)) return;
        runScan();
    }

    /** Manual trigger via controller */
    public void runManualScan() {
        runScan();
    }

    // -------------------------------------------------------------------------
    // Core scan logic
    // -------------------------------------------------------------------------

    private void runScan() {
        // Check if Upstox is authenticated before proceeding
        if (!upstoxTokenService.isAuthenticated()) {
            log.debug("Index futures volume scan skipped - Upstox not authenticated");
            return;
        }

        LocalDate today = LocalDate.now();
        LocalTime now   = LocalTime.now();
        log.info("Index futures volume scan triggered at {}", now.format(TIME_FMT));

        FUTURES_INSTRUMENTS.forEach((label, instrumentKey) -> {
            try {
                scanInstrument(label, instrumentKey, today);
            } catch (Exception e) {
                log.error("Volume scan failed for {}: {}", label, e.getMessage());
            }
        });
    }

    private void scanInstrument(String label, String instrumentKey, LocalDate date) {
        List<Candle> candles = candleService.fetchDayCandles(instrumentKey, date);

        if (candles == null || candles.size() < LOOKBACK + 1) {
            log.debug("{} — not enough candles ({})", label, candles == null ? 0 : candles.size());
            return;
        }

        // Evaluate the last closed candle
        Candle current  = candles.get(candles.size() - 1);
        List<Candle> lookback = candles.subList(candles.size() - 1 - LOOKBACK, candles.size() - 1);

        // Deduplication: skip if already alerted on this candle
        String dedupKey = label + ":" + current.getTimestamp().toString();
        if (lastAlertedCandle.containsKey(dedupKey)) return;

        double avgVol = lookback.stream().mapToLong(Candle::getVolume).average().orElse(0);
        if (avgVol == 0) return;

        double ratio = current.getVolume() / avgVol;
        if (ratio < SPIKE_MULTIPLIER) {
            log.debug("{} | {} | ratio={:.2f} — no spike", label, current.getTimestamp().format(TIME_FMT), ratio);
            return;
        }

        SignalType type = classify(current, lookback, ratio);
        String message  = buildAlertMessage(label, current, type, ratio, avgVol);

        telegramService.sendMessageToIndex(message);
        lastAlertedCandle.put(dedupKey, type.name());
        log.info("Volume spike alert sent | {} | type={} | ratio={:.2f}x | close={}", label, type, ratio, current.getClose());
    }

    // -------------------------------------------------------------------------
    // Classification — mirrors Delta VolumeScannerService logic
    // -------------------------------------------------------------------------

    private SignalType classify(Candle c, List<Candle> lookback, double ratio) {
        double range   = c.range();
        double body    = c.body();
        boolean bigBody = range > 0 && (body / range) >= BODY_RATIO;

        if (ratio >= CLIMAX_MULTIPLIER && isTrending(lookback)) return SignalType.CLIMAX;
        if (bigBody) return SignalType.BREAKOUT;
        return SignalType.ABSORPTION;
    }

    private boolean isTrending(List<Candle> lookback) {
        int n = lookback.size();
        if (n < 5) return false;
        List<Candle> last5 = lookback.subList(n - 5, n);
        long ups   = last5.stream().filter(Candle::isBullish).count();
        long downs = last5.stream().filter(Candle::isBearish).count();
        return ups == 5 || downs == 5;
    }

    // -------------------------------------------------------------------------
    // Alert message builder
    // -------------------------------------------------------------------------

    private String buildAlertMessage(String label, Candle c, SignalType type, double ratio, double avgVol) {
        String emoji = switch (type) {
            case BREAKOUT   -> "🚀";
            case ABSORPTION -> "🧱";
            case CLIMAX     -> "🔥";
        };

        String direction = switch (type) {
            case BREAKOUT   -> c.isBullish() ? "📈 LONG bias" : "📉 SHORT bias";
            case ABSORPTION -> "⏳ Wait for next candle confirmation";
            case CLIMAX     -> c.isBullish() ? "📉 FADE — SHORT (exhaustion)" : "📈 FADE — LONG (exhaustion)";
        };

        String bodyPct = c.range() > 0
                ? String.format("%.1f%%", (c.body() / c.range()) * 100)
                : "N/A";

        double changePct = c.getOpen() > 0
                ? ((c.getClose() - c.getOpen()) / c.getOpen()) * 100
                : 0;

        return String.format("""
                %s *Volume Spike | %s | %s*

                📊 Volume:  `%,d`  (%.1fx avg)
                📐 Body/Range: `%s`
                🕯 O: `%.2f`  H: `%.2f`  L: `%.2f`  C: `%.2f`
                📈 Candle move: `%+.2f%%`
                ⏰ Candle: `%s`

                🎯 *Action:* %s

                #%s #volumespike #indexfutures""",
                emoji, label, type,
                c.getVolume(), ratio,
                bodyPct,
                c.getOpen(), c.getHigh(), c.getLow(), c.getClose(),
                changePct,
                c.getTimestamp().format(DateTimeFormatter.ofPattern("HH:mm")),
                direction,
                label.replace(" ", "").replace(".", "")
        );
    }
}
