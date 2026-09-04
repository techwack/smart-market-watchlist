package in.groww.prep;

import in.groww.prep.marketdata.Quote;
import in.groww.prep.watchlist.ChangeDetector;
import in.groww.prep.watchlist.ChangeSignal;
import in.groww.prep.watchlist.ViewState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The definition of "meaningful" is the heart of this build, so it's the
 * thing worth testing hardest. Pure unit tests — no Spring context, no DB,
 * runs in milliseconds.
 */
class ChangeDetectorTest {

    // thresholds: change 1%, urgent 3%, volume spike 3x, implausible 40%,
    // stale after 30s, anchor good for 7 days.
    private final ChangeDetector detector = new ChangeDetector(1.0, 3.0, 3.0, 40.0, 30, 7);

    private Quote quote(double price, long volumeToday, long expectedVolumeByNow, double high, double low) {
        return new Quote("TEST", price, 0.0, volumeToday, expectedVolumeByNow, high, low, Instant.now());
    }

    private ViewState seenAt(double price) {
        return new ViewState("viewer-1", "TEST", price);
    }

    @Test
    void firstEverView_producesNoSignals() {
        // Nothing to compare against yet — must not invent a change.
        List<ChangeSignal> signals = detector.detect(quote(100, 1000, 1000, 200, 50), null, null);

        assertThat(signals).isEmpty();
    }

    @Test
    void moveBelowThreshold_isTreatedAsNoise() {
        // 0.5% move with a 1% threshold: real, but not worth interrupting anyone.
        List<ChangeSignal> signals = detector.detect(quote(100.5, 1000, 1000, 200, 50), seenAt(100), null);

        assertThat(signals).isEmpty();
    }

    @Test
    void moveAboveThreshold_isReported() {
        List<ChangeSignal> signals = detector.detect(quote(102, 1000, 1000, 200, 50), seenAt(100), null);

        assertThat(signals)
                .extracting(ChangeSignal::type)
                .contains(ChangeSignal.Type.PRICE_MOVE_SINCE_LAST_SEEN);
    }

    @Test
    void crossingBelowCostBasis_isUrgent() {
        // Was above their entry price when last seen, now below it.
        List<ChangeSignal> signals = detector.detect(quote(95, 1000, 1000, 200, 50), seenAt(105), 100.0);

        assertThat(signals)
                .filteredOn(s -> s.type() == ChangeSignal.Type.FELL_BELOW_COST)
                .singleElement()
                .extracting(ChangeSignal::severity)
                .isEqualTo(ChangeSignal.Severity.URGENT);
    }

    @Test
    void stayingBelowCostBasis_doesNotRepeatTheAlarmEveryVisit() {
        // Already underwater last time they looked — crossing already happened.
        List<ChangeSignal> signals = detector.detect(quote(94, 1000, 1000, 200, 50), seenAt(95), 100.0);

        assertThat(signals)
                .extracting(ChangeSignal::type)
                .doesNotContain(ChangeSignal.Type.FELL_BELOW_COST);
    }

    @Test
    void volumeSpike_isMeasuredAgainstTheSymbolsOwnAverage() {
        // 5x the volume expected by this point, with a flat price: no price
        // signal, but something is clearly happening and the user should know.
        List<ChangeSignal> signals = detector.detect(quote(100, 5000, 1000, 200, 50), seenAt(100), null);

        assertThat(signals)
                .extracting(ChangeSignal::type)
                .containsExactly(ChangeSignal.Type.VOLUME_SPIKE);
    }

    /**
     * Regression test for a bug that reached a running build: volume was
     * compared as a cumulative total against a fixed daily average, so the
     * ratio climbed all session and every symbol eventually screamed
     * "volume spike". Measured as pace, an ordinary day sits near 1.0 and
     * stays quiet no matter how long the app has been open.
     */
    @Test
    void ordinaryVolumePace_staysQuietHoweverLongTheSessionRuns() {
        // Late in the session: large absolute numbers, but exactly on pace.
        List<ChangeSignal> lateInDay = detector.detect(quote(100, 5_000_000, 5_000_000, 200, 50), seenAt(100), null);

        assertThat(lateInDay)
                .extracting(ChangeSignal::type)
                .doesNotContain(ChangeSignal.Type.VOLUME_SPIKE);
    }

    @Test
    void fiftyTwoWeekHigh_isFlagged() {
        List<ChangeSignal> signals = detector.detect(quote(200, 1000, 1000, 200, 50), seenAt(199), null);

        assertThat(signals)
                .extracting(ChangeSignal::type)
                .contains(ChangeSignal.Type.FIFTY_TWO_WEEK_HIGH);
    }

    @Test
    void staleQuote_isDetectedFromItsOwnTimestamp() {
        Quote old = new Quote("TEST", 100, 0, 0, 1000, 200, 50, Instant.now().minusSeconds(120));

        assertThat(detector.isStale(old, Instant.now())).isTrue();
    }

    @Test
    void zeroExpectedVolume_doesNotDivideByZero() {
        // First tick of a session: nothing expected yet, so no ratio to take.
        Quote atOpen = new Quote("TEST", 100, 0, 0, 0, 200, 50, Instant.now());

        assertThat(atOpen.volumeRatio()).isZero();
    }

    /**
     * A 1:10 split shows up as a ~90% crash against yesterday's anchor.
     * Reporting that as a price move would be worse than saying nothing —
     * the user might act on it.
     */
    @Test
    void implausiblyLargeMove_isReportedAsADiscontinuityNotAPriceMove() {
        // Anchored at 2000, now 200: a split, not a 90% collapse.
        List<ChangeSignal> signals = detector.detect(quote(200, 1000, 1000, 5000, 50), seenAt(2000), null);

        assertThat(signals)
                .extracting(ChangeSignal::type)
                .contains(ChangeSignal.Type.PRICE_DISCONTINUITY)
                .doesNotContain(ChangeSignal.Type.PRICE_MOVE_SINCE_LAST_SEEN);
    }

    @Test
    void anchorOlderThanTheMaxAge_isTreatedAsExpired() {
        assertThat(detector.isAnchorExpired(Instant.now().minus(30, java.time.temporal.ChronoUnit.DAYS))).isTrue();
    }

    @Test
    void recentAnchor_isStillGood() {
        assertThat(detector.isAnchorExpired(Instant.now().minusSeconds(3600))).isFalse();
    }

    @Test
    void zeroLastSeenPrice_doesNotDivideByZero() {
        // Defensive: a corrupt/zero anchor must not produce Infinity or NaN.
        assertThat(detector.changeSinceLastSeenPct(quote(100, 1000, 1000, 200, 50), seenAt(0)))
                .isNull();
    }
}
