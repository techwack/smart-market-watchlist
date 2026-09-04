package in.groww.prep.web;

import in.groww.prep.marketdata.SimulatedMarketDataProvider;
import in.groww.prep.watchlist.WatchlistService;
import in.groww.prep.watchlist.WatchlistView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Viewer identity: a client-generated UUID sent as X-Viewer-Id. No login —
 * a documented scope cut, not an oversight. The id is stable per browser
 * (persisted in localStorage) and can be pasted into another device to move
 * the same watchlist there, which is what makes "state survives sessions and
 * devices" true without building an account system in 72 hours.
 *
 * Swapping in real auth means resolving viewerId from a JWT here. Nothing
 * downstream changes, because every layer below treats it as an opaque key.
 */
@RestController
@RequestMapping("/watchlist")
public class WatchlistController {

    private static final String VIEWER_HEADER = "X-Viewer-Id";

    private final WatchlistService watchlistService;
    private final SimulatedMarketDataProvider simulatedProvider;

    public WatchlistController(WatchlistService watchlistService, SimulatedMarketDataProvider simulatedProvider) {
        this.watchlistService = watchlistService;
        this.simulatedProvider = simulatedProvider;
    }

    @GetMapping
    public WatchlistView view(@RequestHeader(VIEWER_HEADER) String viewerId) {
        return watchlistService.view(viewerId);
    }

    @PostMapping
    public ResponseEntity<Void> add(
            @RequestHeader(VIEWER_HEADER) String viewerId,
            @Valid @RequestBody AddRequest request
    ) {
        watchlistService.add(viewerId, request.symbol(), request.costBasis());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{symbol}")
    public ResponseEntity<Void> remove(
            @RequestHeader(VIEWER_HEADER) String viewerId,
            @PathVariable String symbol
    ) {
        watchlistService.remove(viewerId, symbol);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{symbol}/ack")
    public ResponseEntity<Void> acknowledge(
            @RequestHeader(VIEWER_HEADER) String viewerId,
            @PathVariable String symbol
    ) {
        watchlistService.acknowledge(viewerId, symbol);
        return ResponseEntity.noContent().build();
    }

    /** Convenience for a client's first-ever load: mint a viewer id. */
    @PostMapping("/viewers")
    public Map<String, String> newViewer() {
        return Map.of("viewerId", UUID.randomUUID().toString());
    }

    /** Lets the client offer a picker instead of free-typed symbols. */
    @GetMapping("/instruments")
    public List<String> supportedSymbols() {
        return watchlistService.supportedSymbols();
    }

    /**
     * Demo hook: flips the simulated upstream feed into failure so the
     * degraded path can be shown live rather than described. Would be behind
     * an admin role (or absent) in production — called out in the README
     * rather than left looking like a security hole.
     */
    @PostMapping("/debug/feed-outage")
    public Map<String, Boolean> setFeedOutage(@RequestParam boolean enabled) {
        simulatedProvider.setFailing(enabled);
        return Map.of("upstreamFailing", simulatedProvider.isFailing());
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> handleUnknown(NoSuchElementException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    /**
     * Two devices acknowledging the same symbol concurrently: the loser of
     * the version check gets a 409 rather than silently clobbering the newer
     * acknowledgement. The client's correct response is to re-read and retry.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, String>> handleConflict(ObjectOptimisticLockingFailureException ex) {
        return ResponseEntity.status(409)
                .body(Map.of("error", "This was updated on another device. Reload and try again."));
    }

    public record AddRequest(
            @NotBlank String symbol,
            @Positive Double costBasis   // optional
    ) {
    }
}
