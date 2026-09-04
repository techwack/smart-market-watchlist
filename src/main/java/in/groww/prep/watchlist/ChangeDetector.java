package in.groww.prep.watchlist;

import in.groww.prep.marketdata.Quote;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The definition of "meaningfully changed" lives here and nowhere else.
 *
 * The interpretation this build commits to: a change is meaningful when it
 * would change what the user does, not when a number simply moved. A 0.4%
 * drift on a stock they've never flagged is noise; the same drift crossing
 * the price they paid is a decision point. So the detector produces typed,
 * ranked signals rather than a single boolean, and the UI groups by them.
 *
 * Rules, in order of how loudly they should shout:
 *   - fell below / recovered above the user's own cost basis   (URGENT)
 *   - broke the 52-week high or low                            (URGENT / NOTABLE)
 *   - volume spike vs the symbol's own norm                    (NOTABLE)
 *   - price move past threshold since last acknowledgement     (NOTABLE / INFO)
 *
 * Each is a small independent method, so adding "crossed its 50-day moving
 * average" later means one more method and one more line in detect().
 */
@Component
public class ChangeDetector {

    private final double thresholdPct;
    private final double urgentThresholdPct;
    private final double volumeSpikeRatio;
    private final double implausibleMovePct;
    private final Duration staleAfter;
    private final Duration anchorMaxAge;

    public ChangeDetector(
            @Value("${watchlist.change-threshold-pct:1.0}") double thresholdPct,
            @Value("${watchlist.urgent-threshold-pct:3.0}") double urgentThresholdPct,
            @Value("${watchlist.volume-spike-ratio:3.0}") double volumeSpikeRatio,
            @Value("${watchlist.implausible-move-pct:40.0}") double implausibleMovePct,
            @Value("${watchlist.stale-after-seconds:30}") long staleAfterSeconds,
            @Value("${watchlist.anchor-max-age-days:7}") long anchorMaxAgeDays
    ) {
        this.thresholdPct = thresholdPct;
        this.urgentThresholdPct = urgentThresholdPct;
        this.volumeSpikeRatio = volumeSpikeRatio;
        this.implausibleMovePct = implausibleMovePct;
        this.staleAfter = Duration.ofSeconds(staleAfterSeconds);
        this.anchorMaxAge = Duration.ofDays(anchorMaxAgeDays);
    }

    public List<ChangeSignal> detect(Quote quote, ViewState lastSeen, Double costBasis) {
        List<ChangeSignal> signals = new ArrayList<>();
        addCostBasisSignal(signals, quote, lastSeen, costBasis);
        addFiftyTwoWeekSignal(signals, quote);
        addVolumeSignal(signals, quote);
        addPriceMoveSignal(signals, quote, lastSeen);
        return signals;
    }

    /**
     * The most personal signal available: the user's own entry price. Only
     * fires on a *crossing* — comparing against where the price sat when they
     * last looked — so a position that has been underwater for a week doesn't
     * scream every single visit.
     */
    private void addCostBasisSignal(List<ChangeSignal> signals, Quote quote, ViewState lastSeen, Double costBasis) {
        if (costBasis == null || costBasis <= 0 || lastSeen == null) {
            return;
        }
        boolean wasAbove = lastSeen.getLastSeenPrice() >= costBasis;
        boolean isAbove = quote.price() >= costBasis;

        if (wasAbove && !isAbove) {
            signals.add(new ChangeSignal(
                    ChangeSignal.Type.FELL_BELOW_COST,
                    ChangeSignal.Severity.URGENT,
                    String.format("Fell below your average cost of %.2f", costBasis)
            ));
        } else if (!wasAbove && isAbove) {
            signals.add(new ChangeSignal(
                    ChangeSignal.Type.RECOVERED_ABOVE_COST,
                    ChangeSignal.Severity.NOTABLE,
                    String.format("Back above your average cost of %.2f", costBasis)
            ));
        }
    }

    private void addFiftyTwoWeekSignal(List<ChangeSignal> signals, Quote quote) {
        if (quote.price() >= quote.fiftyTwoWeekHigh()) {
            signals.add(new ChangeSignal(
                    ChangeSignal.Type.FIFTY_TWO_WEEK_HIGH,
                    ChangeSignal.Severity.URGENT,
                    "At a 52-week high"
            ));
        } else if (quote.price() <= quote.fiftyTwoWeekLow()) {
            signals.add(new ChangeSignal(
                    ChangeSignal.Type.FIFTY_TWO_WEEK_LOW,
                    ChangeSignal.Severity.URGENT,
                    "At a 52-week low"
            ));
        }
    }

    /**
     * Compared against the symbol's own average, not a global constant — a
     * quiet mid-cap doubling its usual volume is a bigger story than a
     * heavily traded large-cap doing the same.
     */
    private void addVolumeSignal(List<ChangeSignal> signals, Quote quote) {
        double ratio = quote.volumeRatio();
        if (ratio >= volumeSpikeRatio) {
            signals.add(new ChangeSignal(
                    ChangeSignal.Type.VOLUME_SPIKE,
                    ChangeSignal.Severity.NOTABLE,
                    String.format("Trading at %.1fx its normal volume", ratio)
            ));
        }
    }

    /**
     * Two guards run before any price move is reported, because a percentage
     * is only meaningful if the thing it's measured against still is.
     *
     * 1. An anchor older than anchorMaxAge stops being a useful reference —
     *    "+40% since you last looked" is noise if you last looked in March.
     * 2. A move too large to be an ordinary one almost certainly isn't one:
     *    a 1:10 split shows up as a 90% crash, a bonus issue as a jump.
     *    Reporting either as a price move would be worse than saying nothing.
     *
     * Both cases replace the price signal rather than sitting alongside it,
     * and both say what actually happened instead of showing a number the
     * user might act on. Found by leaving the app running across a restart
     * and seeing "+774.61% since you last looked" — see NOTES.md.
     */
    private void addPriceMoveSignal(List<ChangeSignal> signals, Quote quote, ViewState lastSeen) {
        Double change = changeSinceLastSeenPct(quote, lastSeen);
        if (change == null) {
            return;
        }

        if (isAnchorExpired(lastSeen)) {
            signals.add(new ChangeSignal(
                    ChangeSignal.Type.ANCHOR_EXPIRED,
                    ChangeSignal.Severity.INFO,
                    "First look in a while — showing today's move instead"
            ));
            return;
        }

        if (Math.abs(change) >= implausibleMovePct) {
            signals.add(new ChangeSignal(
                    ChangeSignal.Type.PRICE_DISCONTINUITY,
                    ChangeSignal.Severity.NOTABLE,
                    "Price reference looks out of date — possibly a split or corporate action"
            ));
            return;
        }

        if (Math.abs(change) < thresholdPct) {
            return;
        }
        ChangeSignal.Severity severity = Math.abs(change) >= urgentThresholdPct
                ? ChangeSignal.Severity.NOTABLE
                : ChangeSignal.Severity.INFO;
        signals.add(new ChangeSignal(
                ChangeSignal.Type.PRICE_MOVE_SINCE_LAST_SEEN,
                severity,
                String.format("%s%.2f%% since you last looked", change >= 0 ? "+" : "", change)
        ));
    }

    public boolean isAnchorExpired(ViewState lastSeen) {
        return lastSeen != null && isAnchorExpired(lastSeen.getLastSeenAt());
    }

    /** Split out from the entity so the rule can be tested against any instant. */
    public boolean isAnchorExpired(Instant lastSeenAt) {
        return Duration.between(lastSeenAt, Instant.now()).compareTo(anchorMaxAge) > 0;
    }

    public Double changeSinceLastSeenPct(Quote quote, ViewState lastSeen) {
        if (lastSeen == null || lastSeen.getLastSeenPrice() <= 0) {
            return null;
        }
        return round2(((quote.price() - lastSeen.getLastSeenPrice()) / lastSeen.getLastSeenPrice()) * 100.0);
    }

    public boolean isStale(Quote quote, Instant now) {
        return quote.isStaleAsOf(now, staleAfter);
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
