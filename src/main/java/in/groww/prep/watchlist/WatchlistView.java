package in.groww.prep.watchlist;

import java.time.Instant;
import java.util.List;

/**
 * The whole response for one watchlist load. Feed health rides alongside the
 * rows rather than in a separate endpoint, so the client can never render
 * prices without also knowing whether they're live — the two facts arrive
 * together or not at all.
 */
public record WatchlistView(
        List<WatchlistRow> rows,
        boolean feedHealthy,
        Instant lastSuccessfulFetch
) {
}
