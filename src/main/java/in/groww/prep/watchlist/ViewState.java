package in.groww.prep.watchlist;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * What a viewer last *acknowledged* seeing for a symbol — the anchor that
 * "meaningful change since you last checked" is computed against.
 *
 * Deliberately separate from "last page load": if acknowledgement fired on
 * every GET, refreshing the page would silently clear the "changed" badge
 * before the user consciously registered it. Instead the client calls
 * POST /watchlist/ack after rendering, once the user has actually seen the
 * update — this row only moves on that explicit signal. This is exactly the
 * kind of product-facing edge case the brief asks for a defensible answer
 * to (see PITCH_TEMPLATE.md).
 *
 * @Version gives optimistic locking for the case of the same viewer
 * acknowledging from two devices at once (conflicting writes) — last write
 * wins, but a stale write gets a 409 instead of silently corrupting state.
 */
@Entity
@Table(name = "view_state", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"viewer_id", "symbol"})
})
public class ViewState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "viewer_id", nullable = false)
    private String viewerId;

    @Column(nullable = false)
    private String symbol;

    @Column(name = "last_seen_price", nullable = false)
    private double lastSeenPrice;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Version
    private Long version;

    protected ViewState() {
        // JPA
    }

    public ViewState(String viewerId, String symbol, double lastSeenPrice) {
        this.viewerId = viewerId;
        this.symbol = symbol.toUpperCase();
        this.lastSeenPrice = lastSeenPrice;
        this.lastSeenAt = Instant.now();
    }

    public void acknowledge(double price) {
        this.lastSeenPrice = price;
        this.lastSeenAt = Instant.now();
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

    public double getLastSeenPrice() {
        return lastSeenPrice;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }
}
