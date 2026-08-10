package com.trading.algo.gapbreakout;

import com.trading.algo.dtos.Candle;
import com.trading.algo.upstox.UpstoxHistoricalCandleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gap-breakout-only, read-through candle cache. Completed trading days are
 * persisted as CSV so subsequent backtests avoid refetching the same data.
 */
@Service
public class GapBreakoutCandleCache {

    private static final Logger log = LoggerFactory.getLogger(GapBreakoutCandleCache.class);
    private static final String HEADER = "timestamp,open,high,low,close,volume";

    private final UpstoxHistoricalCandleService candleService;
    private final Path cacheDirectory;
    private final Map<String, List<Candle>> memoryCache = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public GapBreakoutCandleCache(UpstoxHistoricalCandleService candleService,
                                  @Value("${gap-breakout.cache-directory:cache/gap-breakout-candles}") String cacheDirectory) {
        this.candleService = candleService;
        this.cacheDirectory = Path.of(cacheDirectory);
    }

    /** Returns 15-minute candles from memory/CSV first, then Upstox on a cache miss. */
    public List<Candle> getDayCandles(String instrumentKey, LocalDate date) {
        String cacheKey = instrumentKey + '|' + date;
        List<Candle> cached = memoryCache.get(cacheKey);
        if (cached != null) {
            return copy(cached);
        }

        synchronized (locks.computeIfAbsent(cacheKey, ignored -> new Object())) {
            cached = memoryCache.get(cacheKey);
            if (cached != null) {
                return copy(cached);
            }

            if (date.isBefore(LocalDate.now())) {
                cached = readCsv(cacheFile(instrumentKey, date));
                if (cached != null) {
                    memoryCache.put(cacheKey, cached);
                    return copy(cached);
                }
            }

            List<Candle> fetched = candleService.fetchDayCandles(instrumentKey, date);
            List<Candle> candles = fetched == null ? new ArrayList<>() : copy(fetched);
            candles.sort(Comparator.comparing(Candle::getTimestamp));

            // Never persist today's incomplete intraday session.
            if (!candles.isEmpty() && date.isBefore(LocalDate.now())) {
                writeCsv(cacheFile(instrumentKey, date), candles);
                memoryCache.put(cacheKey, candles);
            }
            return copy(candles);
        }
    }

    /** Builds daily candles from the same cached 15-minute data. */
    public List<Candle> getDailyCandles(String instrumentKey, LocalDate from, LocalDate to) {
        List<Candle> daily = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
                continue;
            }
            List<Candle> intraday = getDayCandles(instrumentKey, date);
            if (!intraday.isEmpty()) {
                daily.add(toDailyCandle(intraday));
            }
        }
        return daily;
    }

    private Path cacheFile(String instrumentKey, LocalDate date) {
        String encodedKey = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(instrumentKey.getBytes(StandardCharsets.UTF_8));
        return cacheDirectory.resolve(encodedKey).resolve(String.valueOf(date.getYear()))
                .resolve(date + ".csv");
    }

    private List<Candle> readCsv(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            List<Candle> candles = new ArrayList<>();
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 1; i < lines.size(); i++) {
                String[] values = lines.get(i).split(",", -1);
                if (values.length != 6) {
                    throw new IllegalArgumentException("invalid column count");
                }
                candles.add(new Candle(LocalDateTime.parse(values[0]), Double.parseDouble(values[1]),
                        Double.parseDouble(values[2]), Double.parseDouble(values[3]), Double.parseDouble(values[4]),
                        Long.parseLong(values[5])));
            }
            candles.sort(Comparator.comparing(Candle::getTimestamp));
            return candles;
        } catch (Exception e) {
            log.warn("Ignoring unreadable gap-breakout candle cache {}: {}", file, e.getMessage());
            return null;
        }
    }

    private void writeCsv(Path file, List<Candle> candles) {
        try {
            Files.createDirectories(file.getParent());
            List<String> lines = new ArrayList<>(candles.size() + 1);
            lines.add(HEADER);
            for (Candle c : candles) {
                lines.add(String.join(",", c.getTimestamp().toString(), Double.toString(c.getOpen()),
                        Double.toString(c.getHigh()), Double.toString(c.getLow()), Double.toString(c.getClose()),
                        Long.toString(c.getVolume())));
            }
            Path tempFile = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
            Files.write(tempFile, lines, StandardCharsets.UTF_8);
            try {
                Files.move(tempFile, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("Could not persist gap-breakout candle cache {}: {}", file, e.getMessage());
        }
    }

    private Candle toDailyCandle(List<Candle> intraday) {
        List<Candle> sorted = copy(intraday);
        sorted.sort(Comparator.comparing(Candle::getTimestamp));
        Candle first = sorted.get(0);
        Candle last = sorted.get(sorted.size() - 1);
        return new Candle(first.getTimestamp(), first.getOpen(),
                sorted.stream().mapToDouble(Candle::getHigh).max().orElse(first.getHigh()),
                sorted.stream().mapToDouble(Candle::getLow).min().orElse(first.getLow()), last.getClose(),
                sorted.stream().mapToLong(Candle::getVolume).sum());
    }

    private List<Candle> copy(List<Candle> candles) {
        return new ArrayList<>(candles);
    }
}
