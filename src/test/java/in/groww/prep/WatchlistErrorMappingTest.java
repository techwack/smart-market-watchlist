package in.groww.prep;

import in.groww.prep.marketdata.SimulatedMarketDataProvider;
import in.groww.prep.watchlist.WatchlistService;
import in.groww.prep.web.WatchlistController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;

import java.util.NoSuchElementException;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * How failures reach the client.
 *
 * ConcurrentAcknowledgementTest proves the optimistic lock fires; this proves
 * the resulting exception becomes a 409 with an actionable message rather
 * than a 500 and a stack trace. Both halves matter: a lock that works but
 * surfaces as "Internal Server Error" tells the user nothing about what to do.
 */
@WebMvcTest(WatchlistController.class)
class WatchlistErrorMappingTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WatchlistService watchlistService;

    @MockBean
    private SimulatedMarketDataProvider simulatedMarketDataProvider;

    @Test
    void aLostOptimisticLockBecomesA409TellingTheClientToReload() throws Exception {
        willThrow(new ObjectOptimisticLockingFailureException("ViewState", 1L))
                .given(watchlistService).acknowledge(anyString(), anyString());

        mockMvc.perform(post("/watchlist/TCS/ack").header("X-Viewer-Id", "viewer-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("This was updated on another device. Reload and try again."));
    }

    @Test
    void anUnknownSymbolBecomesA400NotA500() throws Exception {
        willThrow(new NoSuchElementException("Unknown symbol: NOTREAL"))
                .given(watchlistService).acknowledge(anyString(), anyString());

        mockMvc.perform(post("/watchlist/NOTREAL/ack").header("X-Viewer-Id", "viewer-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Unknown symbol: NOTREAL"));
    }
}
