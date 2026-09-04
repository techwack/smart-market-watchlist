package in.groww.prep.marketdata;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Random-walk price simulator standing in for a real market data feed.
 *
 * Trade-off worth defending out loud: a production build consumes a broker or
 * exchange feed, most likely off a queue. In-memory ticking is the right
 * amount of complexity for a single-instance build, and the MarketDataProvider
 * seam means swapping in a real feed touches one class. The moment this runs
 * on more than one instance, the maps below must move to Redis (or one
 * instance ticks and fans out via pub/sub) so every node quotes the same price.
 *
 * setFailing(true) simulates the upstream feed going down — that's what the
 * resilience path in ResilientMarketDataProvider is demonstrated against,
 * rather than asking anyone to take graceful degradation on faith.
 */
@Component
public class SimulatedMarketDataProvider implements MarketDataProvider {

    private static final List<String> UNIVERSE = List.of(
            "RELIANCE", "TCS", "INFY", "HDFCBANK", "ICICIBANK",
            "TATAMOTORS", "ITC", "SBIN", "WIPRO", "BAJFINANCE"
    );

    private static final Duration TICK = Duration.ofSeconds(3);
    /** A notional trading session, so volume can be modelled as a pace. */
    private static final Duration SESSION = Duration.ofHours(6);
    /**
     * Bounded history per symbol — enough for a sparkline, never unbounded.
     * 600 points at a 3s tick is ~30 minutes. Sized so that a user who last
     * looked a while ago still has their acknowledgement point inside the
     * retained window: if the split falls off the back, the sparkline loses
     * its grey "already seen" half and silently stops answering the question
     * it exists to answer.
     */
    private static final int MAX_HISTORY = 600;

    private final Map<String, Quote> latest = new ConcurrentHashMap<>();
    private final Map<String, Double> openPrice = new ConcurrentHashMap<>();
    private final Map<String, Long> avgDailyVolume = new ConcurrentHashMap<>();
    private final Map<String, Integer> burstTicksRemaining = new ConcurrentHashMap<>();
    private final Map<String, Deque<PricePoint>> history = new ConcurrentHashMap<>();

    private final AtomicBoolean failing = new AtomicBoolean(false);
    private volatile long ticksElapsed = 1;

    public SimulatedMarketDataProvider() {
        Instant now = Instant.now();
        for (String symbol : UNIVERSE) {
            double seed = round2(100 + ThreadLocalRandom.current().nextDouble(0, 2500));
            openPrice.put(symbol, seed);
            avgDailyVolume.put(symbol, ThreadLocalRandom.current().nextLong(2_000_000, 9_000_000));
            burstTicksRemaining.put(symbol, 0);

            Deque<PricePoint> points = new ArrayDeque<>();
            points.add(new PricePoint(seed, now));
            history.put(symbol, points);

            latest.put(symbol, new Quote(
                    symbol, seed, 0.0, 0L, expectedVolumeByNow(symbol),
                    round2(seed * 1.18), round2(seed * 0.72), now
            ));
        }
    }

    /** Test/demo hook: flip the upstream feed into failure. */
    public void setFailing(boolean value) {
        failing.set(value);
    }

    public boolean isFailing() {
        return failing.get();
    }

    @Override
    public Optional<Quote> getQuote(String symbol) {
        requireHealthy();
        return Optional.ofNullable(latest.get(symbol.toUpperCase()));
    }

    @Override
    public List<Quote> getQuotes(List<String> symbols) {
        requireHealthy();
        return symbols.stream()
                .map(s -> Optional.ofNullable(latest.get(s.toUpperCase())))
                .flatMap(Optional::stream)
                .toList();
    }

    @Override
    public List<PricePoint> getHistory(String symbol) {
        requireHealthy();
        Deque<PricePoint> points = history.get(symbol.toUpperCase());
        return points == null ? List.of() : List.copyOf(points);
    }

    @Override
    public List<String> supportedSymbols() {
        return UNIVERSE;
    }

    /**
     * Ticks every symbol once per interval — O(symbols), independent of how
     * many viewers or watchlist rows reference them. This is what makes the
     * design scale with users: the 10,000th viewer of RELIANCE costs one
     * cache read on their request, not another upstream fetch.
     */
    @Scheduled(fixedRate = 3000)
    public void tick() {
        if (failing.get()) {
            return; // feed is "down" — deliberately stop refreshing asOf
        }
        Instant now = Instant.now();
        ticksElapsed++;

        for (String symbol : UNIVERSE) {
            Quote previous = latest.get(symbol);
            double drift = ThreadLocalRandom.current().nextDouble(-0.008, 0.008);
            double newPrice = Math.max(1.0, previous.price() * (1 + drift));
            double open = openPrice.get(symbol);

            latest.put(symbol, new Quote(
                    symbol,
                    round2(newPrice),
                    round2(((newPrice - open) / open) * 100.0),
                    previous.volumeToday() + volumeForThisTick(symbol),
                    expectedVolumeByNow(symbol),
                    round2(Math.max(previous.fiftyTwoWeekHigh(), newPrice)),
                    round2(Math.min(previous.fiftyTwoWeekLow(), newPrice)),
                    now
            ));
            recordHistory(symbol, round2(newPrice), now);
        }
    }

    /**
     * Volume generated around the symbol's own expected pace, so the ratio
     * sits near 1.0 on an ordinary tick. Occasionally a symbol enters a
     * multi-tick burst — that's what a genuine volume spike looks like, and
     * it's what makes the VOLUME_SPIKE signal mean something instead of
     * firing for everything all the time.
     */
    private long volumeForThisTick(String symbol) {
        long expectedPerTick = Math.max(1, avgDailyVolume.get(symbol) * TICK.toSeconds() / SESSION.toSeconds());
        int burstLeft = burstTicksRemaining.getOrDefault(symbol, 0);

        double multiplier;
        if (burstLeft > 0) {
            burstTicksRemaining.put(symbol, burstLeft - 1);
            multiplier = ThreadLocalRandom.current().nextDouble(4.0, 9.0);
        } else if (ThreadLocalRandom.current().nextDouble() < 0.02) {
            burstTicksRemaining.put(symbol, ThreadLocalRandom.current().nextInt(4, 12));
            multiplier = ThreadLocalRandom.current().nextDouble(4.0, 9.0);
        } else {
            multiplier = ThreadLocalRandom.current().nextDouble(0.6, 1.4);
        }
        return Math.round(expectedPerTick * multiplier);
    }

    private long expectedVolumeByNow(String symbol) {
        long expectedPerTick = Math.max(1, avgDailyVolume.get(symbol) * TICK.toSeconds() / SESSION.toSeconds());
        return expectedPerTick * ticksElapsed;
    }

    private void recordHistory(String symbol, double price, Instant at) {
        Deque<PricePoint> points = history.computeIfAbsent(symbol, s -> new ArrayDeque<>());
        synchronized (points) {
            points.addLast(new PricePoint(price, at));
            while (points.size() > MAX_HISTORY) {
                points.removeFirst();
            }
        }
    }

    private void requireHealthy() {
        if (failing.get()) {
            throw new MarketDataUnavailableException("Simulated upstream feed outage");
        }
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
