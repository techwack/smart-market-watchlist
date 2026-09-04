package in.groww.prep.watchlist;

import in.groww.prep.marketdata.PricePoint;

import java.time.Instant;
import java.util.List;

/**
 * What the frontend renders for one row. Signals are computed server-side so
 * the definition of "meaningful" can't drift between clients — a second
 * client (mobile, say) gets identical judgement for free.
 *
 * history + lastAcknowledgedAt together are what let the sparkline draw the
 * "before you looked" and "since you looked" segments differently. The split
 * point is sent as data rather than as two pre-sliced arrays, so the client
 * can re-render at any width without another round trip.
 */
public record WatchlistRow(
        String symbol,
        double price,
        double changePctToday,
        Double changeSinceLastSeenPct,   // null until first acknowledgement
        Double costBasis,                // null unless the user supplied one
        List<ChangeSignal> signals,
        boolean needsAttention,
        boolean stale,
        Instant asOf,
        Instant lastAcknowledgedAt,      // null until first acknowledgement
        List<PricePoint> history
) {
}
