package in.groww.prep;

import in.groww.prep.watchlist.ViewState;
import in.groww.prep.watchlist.ViewStateRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.RollbackException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The concurrent-acknowledgement claim, actually exercised.
 *
 * Scenario: the same person has the watchlist open on a phone and a laptop.
 * Both read the row, both hit "mark seen". Without a version check the
 * second write silently overwrites the first, and the acknowledgement anchor
 * ends up pointing at whichever request happened to land last — including a
 * stale one, which would then mis-report "change since you last looked".
 *
 * @Version on ViewState makes the losing writer fail loudly instead. These
 * tests use two independent persistence contexts, which is what two devices
 * actually look like to the database, rather than trying to race threads and
 * hoping the timing lands.
 */
@SpringBootTest
class ConcurrentAcknowledgementTest {

    @Autowired
    private ViewStateRepository viewStateRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void twoDevicesAcknowledging_theStaleWriterIsRejectedRatherThanClobbering() {
        String viewer = UUID.randomUUID().toString();
        Long id = viewStateRepository.save(new ViewState(viewer, "TCS", 100.0)).getId();

        // Two devices load the same row independently.
        EntityManager phone = entityManagerFactory.createEntityManager();
        EntityManager laptop = entityManagerFactory.createEntityManager();
        try {
            ViewState onPhone = phone.find(ViewState.class, id);
            ViewState onLaptop = laptop.find(ViewState.class, id);

            // The phone acknowledges first and wins; version moves on.
            phone.getTransaction().begin();
            onPhone.acknowledge(110.0);
            phone.merge(onPhone);
            phone.getTransaction().commit();

            // The laptop is still holding the version it read before that.
            laptop.getTransaction().begin();
            onLaptop.acknowledge(120.0);
            assertThatThrownBy(() -> {
                laptop.merge(onLaptop);
                laptop.getTransaction().commit();
            }).isInstanceOfAny(OptimisticLockException.class, RollbackException.class);
        } finally {
            if (laptop.getTransaction().isActive()) {
                laptop.getTransaction().rollback();
            }
            phone.close();
            laptop.close();
        }

        // The winning write survived intact — no silent corruption.
        assertThat(viewStateRepository.findById(id))
                .get()
                .extracting(ViewState::getLastSeenPrice)
                .isEqualTo(110.0);
    }

    /**
     * The guard must not punish ordinary repeated use — someone marking the
     * same row seen twice in a row is not a conflict.
     *
     * Note the reassignment on every save: outside a transaction the entity
     * is detached, and save() returns a *new* merged instance carrying the
     * incremented version. Reusing the original stale reference would stage
     * the very conflict the test above stages deliberately. Production code
     * doesn't hit this because WatchlistService.acknowledge is @Transactional,
     * so the entity stays managed and Hibernate dirty-checks it.
     */
    @Test
    void sequentialAcknowledgementsFromOneDevice_bothSucceed() {
        String viewer = UUID.randomUUID().toString();
        ViewState state = viewStateRepository.save(new ViewState(viewer, "INFY", 100.0));

        state.acknowledge(105.0);
        state = viewStateRepository.save(state);
        state.acknowledge(112.0);
        viewStateRepository.save(state);

        assertThat(viewStateRepository.findByViewerIdAndSymbol(viewer, "INFY"))
                .get()
                .extracting(ViewState::getLastSeenPrice)
                .isEqualTo(112.0);
    }
}
