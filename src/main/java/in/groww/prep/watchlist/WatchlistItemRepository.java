package in.groww.prep.watchlist;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WatchlistItemRepository extends JpaRepository<WatchlistItem, Long> {

    List<WatchlistItem> findByViewerIdOrderByAddedAtDesc(String viewerId);

    Optional<WatchlistItem> findByViewerIdAndSymbol(String viewerId, String symbol);

    void deleteByViewerIdAndSymbol(String viewerId, String symbol);
}
