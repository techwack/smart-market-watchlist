package in.groww.prep.watchlist;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ViewStateRepository extends JpaRepository<ViewState, Long> {

    Optional<ViewState> findByViewerIdAndSymbol(String viewerId, String symbol);

    /** Batched: one query for the whole watchlist, not one per row. */
    List<ViewState> findByViewerId(String viewerId);
}
