package in.groww.prep.watchlist;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * One symbol on one viewer's watchlist.
 *
 * The unique constraint on (viewerId, symbol) is the idempotency guard for
 * "add to watchlist" — a duplicate/retried add doesn't create a second row
 * or blow up; the service layer catches the constraint violation and treats
 * it as a no-op success. Cheaper and more robust than a Redis lock for this
 * particular operation (see NOTES.md for where a lock-based approach is
 * still the right tool).
 */
@Entity
@Table(name = "watchlist_item", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"viewer_id", "symbol"})
})
public class WatchlistItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "viewer_id", nullable = false)
    private String viewerId;

    @Column(nullable = false)
    private String symbol;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt = Instant.now();

    /**
     * What the user actually paid, if they chose to tell us. Nullable on
     * purpose: a watchlist is not a portfolio, and most tracked symbols are
     * ones the user doesn't own. When present it unlocks the strongest
     * signal the system has (see ChangeDetector cost-basis rules).
     */
    @Column(name = "cost_basis")
    private Double costBasis;

    protected WatchlistItem() {
        // JPA
    }

    public WatchlistItem(String viewerId, String symbol, Double costBasis) {
        this.viewerId = viewerId;
        this.symbol = symbol.toUpperCase();
        this.addedAt = Instant.now();
        this.costBasis = costBasis;
    }

    public Double getCostBasis() {
        return costBasis;
    }

    public void setCostBasis(Double costBasis) {
        this.costBasis = costBasis;
    }

    public Long getId() {
        return id;
    }

    public String getViewerId() {
        return viewerId;
    }

    public String getSymbol() {
        return symbol;
    }

    public Instant getAddedAt() {
        return addedAt;
    }
}
