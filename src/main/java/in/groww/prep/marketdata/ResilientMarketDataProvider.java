package in.groww.prep.marketdata;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decorator that makes an upstream feed outage a degraded experience rather
 * than an error page.
 *
 * The rule it enforces: never show the user nothing, and never let stale
 * prices masquerade as live ones. On every successful read it records a
 * last-known-good quote; when the upstream throws, it serves those cached
 * quotes instead and flips feedHealthy to false, which the API surfaces so
 * the UI can show a "data delayed" banner over prices that are real but old.
 *
 * Why a decorator and not try/catch inside the service: the fallback policy
 * is one concern in one class, and the service layer stays free of feed
 * failure handling. Swapping the simulator for a real feed keeps this
 * behaviour untouched.
 *
 * @Primary so everything injecting MarketDataProvider gets the resilient
 * path by default — the fallback can't be forgotten at a call site.
 */
@Component
@Primary
public class ResilientMarketDataProvider implements MarketDataProvider {

    private final MarketDataProvider delegate;
    private final Map<String, Quote> lastKnownGood = new ConcurrentHashMap<>();
    private final Map<String, List<PricePoint>> lastKnownHistory = new ConcurrentHashMap<>();

    private volatile boolean feedHealthy = true;
    private volatile Instant lastSuccessfulFetch = Instant.now();

    public ResilientMarketDataProvider(SimulatedMarketDataProvider delegate) {
        this.delegate = delegate;
    }

    @Override
    public Optional<Quote> getQuote(String symbol) {
        try {
            Optional<Quote> quote = delegate.getQuote(symbol);
            quote.ifPresent(this::remember);
            markHealthy();
            return quote;
        } catch (RuntimeException upstreamFailed) {
            markUnhealthy();
            return Optional.ofNullable(lastKnownGood.get(symbol.toUpperCase()));
        }
    }

    @Override
    public List<Quote> getQuotes(List<String> symbols) {
        try {
            List<Quote> quotes = delegate.getQuotes(symbols);
            quotes.forEach(this::remember);
            markHealthy();
            return quotes;
        } catch (RuntimeException upstreamFailed) {
            markUnhealthy();
            // Serve what we last knew — partial data beats an empty screen,
            // as long as the client is told it's degraded (see feedHealthy).
            return symbols.stream()
                    .map(s -> Optional.ofNullable(lastKnownGood.get(s.toUpperCase())))
                    .flatMap(Optional::stream)
                    .toList();
        }
    }

    @Override
    public List<PricePoint> getHistory(String symbol) {
        try {
            List<PricePoint> points = delegate.getHistory(symbol);
            if (!points.isEmpty()) {
                lastKnownHistory.put(symbol.toUpperCase(), points);
            }
            return points;
        } catch (RuntimeException upstreamFailed) {
            markUnhealthy();
            // The sparkline still draws — it simply stops extending, which
            // is the honest picture of a feed that has gone quiet.
            return lastKnownHistory.getOrDefault(symbol.toUpperCase(), List.of());
        }
    }

    @Override
    public List<String> supportedSymbols() {
        try {
            return delegate.supportedSymbols();
        } catch (RuntimeException upstreamFailed) {
            return List.copyOf(lastKnownGood.keySet());
        }
    }

    public boolean isFeedHealthy() {
        return feedHealthy;
    }

    public Instant getLastSuccessfulFetch() {
        return lastSuccessfulFetch;
    }

    private void remember(Quote quote) {
        lastKnownGood.put(quote.symbol(), quote);
    }

    private void markHealthy() {
        feedHealthy = true;
        lastSuccessfulFetch = Instant.now();
    }

    private void markUnhealthy() {
        feedHealthy = false;
    }
}
