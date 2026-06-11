package com.trading.algo.delta.service;

import com.trading.algo.delta.model.Candle;
import com.trading.algo.delta.model.VolumeSignal;
import com.trading.algo.delta.model.VolumeSignal.Type;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scans for volume spikes on 15-min candles and classifies them as:
 *  - BREAKOUT   : big body (body >= 50% of range)
 *  - ABSORPTION : small body (body < 50% of range) — supply/demand absorbed
 *  - CLIMAX     : extreme spike (>= climaxMultiplier) after a trend move
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VolumeScannerService {

    private static final int    LOOKBACK       = 20;   // candles for avg volume
    private static final double BODY_RATIO     = 0.50; // body/range threshold

    @Value("${volume.scanner.spike-multiplier:2.0}")
    private double spikeMultiplier;

    @Value("${volume.scanner.climax-multiplier:3.0}")
    private double climaxMultiplier;

    private final DeltaApiService  deltaApiService;
    private final TelegramServices telegramService;

    // cooldown: one alert per symbol per candle close time
    private final Map<String, Instant> lastAlerted = new ConcurrentHashMap<>();

    public void scan(String symbol) {
        long now   = Instant.now().getEpochSecond();
        long start = now - (900L * (LOOKBACK + 2)); // 22 candles back for safety

        List<Candle> candles = deltaApiService.get15mCandles(symbol, start, now)
                .stream().filter(Candle::isClosed).toList();

        if (candles.size() < LOOKBACK + 1) {
            log.debug("Not enough candles for {} (got {})", symbol, candles.size());
            return;
        }

        // Last closed candle is the one we evaluate
        Candle current = candles.get(candles.size() - 1);
        List<Candle> lookback = candles.subList(candles.size() - 1 - LOOKBACK, candles.size() - 1);

        // Deduplicate: skip if we already alerted on this candle
        String cooldownKey = symbol + ":" + current.getCloseTime().getEpochSecond();
        if (lastAlerted.containsKey(cooldownKey)) return;

        BigDecimal avgVolume = average(lookback);
        if (avgVolume.compareTo(BigDecimal.ZERO) == 0) return;

        BigDecimal ratio = current.getVolume().divide(avgVolume, 4, RoundingMode.HALF_UP);
        double ratioD = ratio.doubleValue();

        if (ratioD < spikeMultiplier) {
            log.debug("{} | volume ratio={} — no spike", symbol, ratio.toPlainString());
            return;
        }

        Type type = classify(current, lookback, ratioD);
        VolumeSignal signal = VolumeSignal.builder()
                .symbol(symbol)
                .type(type)
                .currentVolume(current.getVolume())
                .avgVolume(avgVolume)
                .volumeRatio(ratio)
                .candleClose(current.getClose())
                .candleBody(current.getClose().subtract(current.getOpen()).abs())
                .candleRange(current.getHigh().subtract(current.getLow()))
                .candleCloseTime(current.getCloseTime())
                .build();

        telegramService.sendMessage(buildMessage(signal));
        lastAlerted.put(cooldownKey, Instant.now());
        log.info("Volume spike | {} | type={} | ratio={}", symbol, type, ratio.toPlainString());
    }

    // -------------------------------------------------------------------------

    private Type classify(Candle c, List<Candle> lookback, double ratio) {
        BigDecimal range = c.getHigh().subtract(c.getLow());
        BigDecimal body  = c.getClose().subtract(c.getOpen()).abs();

        boolean bigBody = range.compareTo(BigDecimal.ZERO) > 0
                && body.divide(range, 4, RoundingMode.HALF_UP).doubleValue() >= BODY_RATIO;

        if (ratio >= climaxMultiplier && isTrending(lookback)) return Type.CLIMAX;
        if (bigBody) return Type.BREAKOUT;
        return Type.ABSORPTION;
    }

    /** Simple trend check: last 5 closes all going same direction */
    private boolean isTrending(List<Candle> lookback) {
        int n = lookback.size();
        if (n < 5) return false;
        List<Candle> last5 = lookback.subList(n - 5, n);
        long ups   = last5.stream().filter(c -> c.getClose().compareTo(c.getOpen()) > 0).count();
        long downs = last5.stream().filter(c -> c.getClose().compareTo(c.getOpen()) < 0).count();
        return ups == 5 || downs == 5;
    }

    private BigDecimal average(List<Candle> candles) {
        BigDecimal sum = candles.stream()
                .map(Candle::getVolume)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(candles.size()), 4, RoundingMode.HALF_UP);
    }

    private String buildMessage(VolumeSignal s) {
        String emoji = switch (s.getType()) {
            case BREAKOUT   -> "🚀";
            case ABSORPTION -> "🧱";
            case CLIMAX     -> "🔥";
        };
        return String.format("""
                %s *Volume Spike | %s | %s*

                📊 Volume: `%s` (%.1fx avg)
                🕯 Close: `%s`
                📐 Body: `%s` | Range: `%s`
                ⏰ Candle Close: `%s`

                #%s #volume #spike""",
                emoji,
                s.getSymbol(),
                s.getType(),
                s.getCurrentVolume().toPlainString(),
                s.getVolumeRatio().doubleValue(),
                s.getCandleClose().toPlainString(),
                s.getCandleBody().toPlainString(),
                s.getCandleRange().toPlainString(),
                s.getCandleCloseTime().toString(),
                s.getSymbol().replace("-", "")
        );
    }
}
