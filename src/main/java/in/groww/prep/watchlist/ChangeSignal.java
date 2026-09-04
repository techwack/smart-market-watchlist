package in.groww.prep.watchlist;

/**
 * One reason a row is worth the user's attention, in language they can act
 * on rather than a raw number they have to interpret.
 *
 * Severity exists so the UI can rank rows without re-implementing judgement
 * on the client: the backend decides what matters most, the frontend decides
 * how to draw it.
 */
public record ChangeSignal(Type type, Severity severity, String message) {

    public enum Type {
        /** Moved past the threshold since the user last acknowledged it. */
        PRICE_MOVE_SINCE_LAST_SEEN,
        /** Trading far above its own normal volume — something is happening. */
        VOLUME_SPIKE,
        /** Broke its 52-week ceiling or floor. */
        FIFTY_TWO_WEEK_HIGH,
        FIFTY_TWO_WEEK_LOW,
        /** Crossed the price the user actually paid — the one that stings. */
        FELL_BELOW_COST,
        RECOVERED_ABOVE_COST,
        /**
         * The comparison anchor is too old to be meaningful. Better to say
         * so than to present a number nobody should act on.
         */
        ANCHOR_EXPIRED,
        /**
         * The move since the anchor is too large to be an ordinary price
         * move — a split, a bonus issue, or bad data. Reporting it as a
         * price change would be actively misleading.
         */
        PRICE_DISCONTINUITY
    }

    public enum Severity {
        INFO, NOTABLE, URGENT
    }
}
