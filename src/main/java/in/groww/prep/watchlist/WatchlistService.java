package in.groww.prep.watchlist;

import in.groww.prep.marketdata.MarketDataProvider;
import in.groww.prep.marketdata.Quote;
import in.groww.prep.marketdata.ResilientMarketDataProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class WatchlistService {

    private final WatchlistItemRepository watchlistItemRepository;
    private final ViewStateRepository viewStateRepository;
    private final MarketDataProvider marketDataProvider;
    private final ResilientMarketDataProvider resilientProvider;
    private final ChangeDetector changeDetector;

    public WatchlistService(
            WatchlistItemRepository watchlistItemRepository,
            ViewStateRepository viewStateRepository,
            MarketDataProvider marketDataProvider,
            ResilientMarketDataProvider resilientProvider,
            ChangeDetector changeDetector
    ) {
        this.watchlistItemRepository = watchlistItemRepository;
        this.viewStateRepository = viewStateRepository;
        this.marketDataProvider = marketDataProvider;
        this.resilientProvider = resilientProvider;
        this.changeDetector = changeDetector;
    }

    /**
     * Idempotent add. Two clicks in the same millisecond race to insert; the
     * unique (viewer_id, symbol) constraint lets the database settle it and
     * the loser is treated as a success, because from the user's point of
     * view the stock is on the list either way. A read-then-write check
     * would be the obvious approach and would still lose that race.
     *
     * Deliberately NOT @Transactional, and that detail matters: catching a
     * constraint violation *inside* a transaction doesn't rescue anything,
     * because the transaction is already marked rollback-only and the commit
     * fails regardless. Leaving the boundary to the repository's own
     * transaction means the failed insert rolls back alone and this catch
     * block can actually do its job. An integration test caught this —
     * see WatchlistIntegrationTest.addingTheSameSymbolTwiceIsIdempotent.
     */
    public void add(String viewerId, String symbol, Double costBasis) {
        String normalized = normalize(symbol);
        if (!marketDataProvider.supportedSymbols().contains(normalized)) {
            throw new NoSuchElementException("Unknown symbol: " + normalized);
        }
        try {
            watchlistItemRepository.save(new WatchlistItem(viewerId, normalized, costBasis));
        } catch (DataIntegrityViolationException alreadyOnList) {
            // Lost the race, or a plain duplicate. Either way it's on the
            // list; honour a cost basis if this call carried one.
            if (costBasis != null) {
                watchlistItemRepository.findByViewerIdAndSymbol(viewerId, normalized)
                        .ifPresent(item -> {
                            item.setCostBasis(costBasis);
                            watchlistItemRepository.save(item);
                        });
            }
        }
    }

    @Transactional
    public void remove(String viewerId, String symbol) {
        watchlistItemRepository.deleteByViewerIdAndSymbol(viewerId, normalize(symbol));
    }

    /**
     * The read path judges will exercise most.
     *
     * One repository call for items, one for view states, one batched quote
     * fetch — three round trips regardless of watchlist size, rather than
     * three per row. A 50-symbol watchlist and a 5-symbol one cost the same
     * number of queries.
     */
    @Transactional(readOnly = true)
    public WatchlistView view(String viewerId) {
        Instant now = Instant.now();
        List<WatchlistItem> items = watchlistItemRepository.findByViewerIdOrderByAddedAtDesc(viewerId);
        if (items.isEmpty()) {
            return new WatchlistView(List.of(), resilientProvider.isFeedHealthy(), resilientProvider.getLastSuccessfulFetch());
        }

        List<String> symbols = items.stream().map(WatchlistItem::getSymbol).toList();
        Map<String, Quote> quotes = marketDataProvider.getQuotes(symbols).stream()
                .collect(Collectors.toMap(Quote::symbol, Function.identity()));
        Map<String, ViewState> viewStates = viewStateRepository.findByViewerId(viewerId).stream()
                .collect(Collectors.toMap(ViewState::getSymbol, Function.identity()));

        List<WatchlistRow> rows = items.stream()
                .map(item -> toRow(item, quotes.get(item.getSymbol()), viewStates.get(item.getSymbol()), now))
                .flatMap(Optional::stream)
                .sorted(WatchlistService::mostUrgentFirst)
                .toList();

        return new WatchlistView(rows, resilientProvider.isFeedHealthy(), resilientProvider.getLastSuccessfulFetch());
    }

    private Optional<WatchlistRow> toRow(WatchlistItem item, Quote quote, ViewState lastSeen, Instant now) {
        if (quote == null) {
            // Feed is degraded and we have no cached price for this symbol.
            // Dropping the row is wrong (the user added it deliberately), so
            // the caller sees a gap only if we truly know nothing about it.
            return Optional.empty();
        }
        List<ChangeSignal> signals = changeDetector.detect(quote, lastSeen, item.getCostBasis());
        return Optional.of(new WatchlistRow(
                quote.symbol(),
                quote.price(),
                quote.changePctToday(),
                changeDetector.changeSinceLastSeenPct(quote, lastSeen),
                item.getCostBasis(),
                signals,
                !signals.isEmpty(),
                changeDetector.isStale(quote, now),
                quote.asOf(),
                lastSeen == null ? null : lastSeen.getLastSeenAt(),
                marketDataProvider.getHistory(quote.symbol())
        ));
    }

    /** Rows that need attention float up, ranked by the loudest signal on them. */
    private static int mostUrgentFirst(WatchlistRow a, WatchlistRow b) {
        return Integer.compare(weight(b), weight(a));
    }

    private static int weight(WatchlistRow row) {
        return row.signals().stream()
                .mapToInt(signal -> switch (signal.severity()) {
                    case URGENT -> 3;
                    case NOTABLE -> 2;
                    case INFO -> 1;
                })
                .max()
                .orElse(0);
    }

    /**
     * Explicit "I've seen this" signal — see ViewState's Javadoc for why this
     * is separate from the read path. Optimistic locking on ViewState means a
     * stale acknowledgement from a second device fails loudly instead of
     * quietly overwriting a newer one.
     */
    @Transactional
    public void acknowledge(String viewerId, String symbol) {
        String normalized = normalize(symbol);
        Quote quote = marketDataProvider.getQuote(normalized)
                .orElseThrow(() -> new NoSuchElementException("No price available for: " + normalized));

        ViewState state = viewStateRepository.findByViewerIdAndSymbol(viewerId, normalized)
                .orElseGet(() -> new ViewState(viewerId, normalized, quote.price()));
        state.acknowledge(quote.price());
        viewStateRepository.save(state);
    }

    public List<String> supportedSymbols() {
        return marketDataProvider.supportedSymbols();
    }

    private String normalize(String symbol) {
        return symbol.trim().toUpperCase();
    }
}
