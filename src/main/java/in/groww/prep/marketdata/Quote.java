package in.groww.prep.marketdata;

import java.time.Duration;
import java.time.Instant;

/**
 * A single point-in-time market quote, plus the reference statistics needed
 * to judge whether a move is notable. Immutable — each tick produces a new
 * Quote rather than mutating one, so "is this stale" stays a pure function
 * of asOf with no shared mutable state to race on.
 */
public record Quote(
        String symbol,
        double price,
        double changePctToday,
        long volumeToday,
        long expectedVolumeByNow,
        double fiftyTwoWeekHigh,
        double fiftyTwoWeekLow,
        Instant asOf
) {
    public boolean isStaleAsOf(Instant now, Duration staleAfter) {
        return Duration.between(asOf, now).compareTo(staleAfter) > 0;
    }

    /**
     * Volume *pace*, not cumulative total: how much has traded so far versus
     * how much you'd expect by this point in the session.
     *
     * The distinction matters. Comparing cumulative volume against a daily
     * average makes the ratio climb all day, so by the afternoon every
     * symbol looks like it's spiking and the signal means nothing. Measured
     * as pace, 1.0 is an ordinary day and 5.0 genuinely means five times the
     * usual interest right now. (The naive version shipped first and was
     * caught by running the app and watching every row light up — see
     * NOTES.md.)
     */
    public double volumeRatio() {
        return expectedVolumeByNow <= 0 ? 0 : (double) volumeToday / expectedVolumeByNow;
    }
}
