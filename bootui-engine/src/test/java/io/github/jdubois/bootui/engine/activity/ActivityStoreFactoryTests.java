package io.github.jdubois.bootui.engine.activity;

import static io.github.jdubois.bootui.engine.activity.ActivityTestFixtures.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class ActivityStoreFactoryTests {

    private static final AtomicInteger DB_COUNTER = new AtomicInteger();

    private static DataSource newDataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:factory-test-" + DB_COUNTER.incrementAndGet() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private static ActivityPersistenceSettings disabledSettings() {
        return new ActivityPersistenceSettings(
                false,
                ActivityPersistenceSettings.DataSourceMode.SHARED,
                null,
                null,
                null,
                null,
                "bootui_activity",
                Duration.ofSeconds(5),
                200,
                null,
                "app-1",
                Duration.ofSeconds(1));
    }

    @Test
    void disabledSettingsReturnAPlainInMemoryStore() {
        try (SwitchableActivityStore store = ActivityStoreFactory.create(disabledSettings(), () -> null)) {
            assertThat(store.delegate()).isInstanceOf(InMemoryActivityStore.class);
        }
    }

    @Test
    void enabledWithDedicatedDataSourceBuildsAFunctioningBufferedJdbcStore() {
        ActivityPersistenceSettings settings = new ActivityPersistenceSettings(
                true,
                ActivityPersistenceSettings.DataSourceMode.DEDICATED,
                "jdbc:h2:mem:factory-dedicated-" + DB_COUNTER.incrementAndGet() + ";DB_CLOSE_DELAY=-1",
                "sa",
                "",
                null,
                "bootui_activity",
                Duration.ofSeconds(5),
                200,
                null,
                "app-1",
                Duration.ofSeconds(1));

        try (SwitchableActivityStore store = ActivityStoreFactory.create(settings, () -> null)) {
            assertThat(store.delegate()).isInstanceOf(BufferedActivityStore.class);
            BufferedActivityStore buffered = (BufferedActivityStore) store.delegate();

            store.append(new StoredActivityEntry("app-1", 1, entry("1", "REQUEST", 1, "OK", "hello")));
            buffered.flushNow();

            assertThat(store.query(ActivityQuery.firstPage("app-1")).entryDtos())
                    .extracting(ActivityEntryDto::id)
                    .containsExactly("1");
        }
    }

    @Test
    void enabledWithSharedDataSourceUsesTheSuppliedDataSource() {
        DataSource shared = newDataSource();
        ActivityPersistenceSettings settings = new ActivityPersistenceSettings(
                true,
                ActivityPersistenceSettings.DataSourceMode.SHARED,
                null,
                null,
                null,
                null,
                "bootui_activity",
                Duration.ofSeconds(5),
                200,
                null,
                "app-1",
                Duration.ofSeconds(1));

        try (SwitchableActivityStore store = ActivityStoreFactory.create(settings, () -> shared)) {
            BufferedActivityStore buffered = (BufferedActivityStore) store.delegate();
            store.append(new StoredActivityEntry("app-1", 1, entry("1", "REQUEST", 1, "OK", "hello")));
            buffered.flushNow();

            // Querying the shared DataSource directly through a second, independent JdbcActivityStore
            // proves the factory really wrote through the supplied DataSource rather than a private one.
            JdbcActivityStore direct = new JdbcActivityStore(shared, "bootui_activity");
            assertThat(direct.query(ActivityQuery.firstPage("app-1")).entryDtos())
                    .extracting(ActivityEntryDto::id)
                    .containsExactly("1");
        }
    }

    @Test
    void enabledWithSharedDataSourceButNoSupplierThrows() {
        ActivityPersistenceSettings settings = new ActivityPersistenceSettings(
                true,
                ActivityPersistenceSettings.DataSourceMode.SHARED,
                null,
                null,
                null,
                null,
                "bootui_activity",
                Duration.ofSeconds(5),
                200,
                null,
                "app-1",
                Duration.ofSeconds(1));

        assertThatThrownBy(() -> ActivityStoreFactory.create(settings, () -> null))
                .isInstanceOf(ActivityStoreException.class);
        assertThatThrownBy(() -> ActivityStoreFactory.create(settings, null))
                .isInstanceOf(ActivityStoreException.class);
    }

    // ── three-arg overload: persistence + forwarding composition ─────────────

    private static ActivityForwardingSettings disabledForwardingSettings() {
        return new ActivityForwardingSettings(
                false,
                null,
                null,
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                500,
                "app-1",
                Duration.ofSeconds(1));
    }

    private static ActivityForwardingSettings enabledForwardingSettings(String peerBaseUrl) {
        return new ActivityForwardingSettings(
                true,
                peerBaseUrl,
                null,
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                500,
                "app-1",
                Duration.ofSeconds(1));
    }

    @Test
    void threeArgOverloadFallsBackToPersistenceOnlyBehaviorWhenForwardingSettingsIsNull() {
        try (SwitchableActivityStore store = ActivityStoreFactory.create(disabledSettings(), null, () -> null)) {
            assertThat(store.delegate()).isInstanceOf(InMemoryActivityStore.class);
        }
    }

    @Test
    void threeArgOverloadFallsBackToPersistenceOnlyBehaviorWhenForwardingIsDisabled() {
        DataSource shared = newDataSource();
        ActivityPersistenceSettings persistence = new ActivityPersistenceSettings(
                true,
                ActivityPersistenceSettings.DataSourceMode.SHARED,
                null,
                null,
                null,
                null,
                "bootui_activity",
                Duration.ofSeconds(5),
                200,
                null,
                "app-1",
                Duration.ofSeconds(1));

        try (SwitchableActivityStore store =
                ActivityStoreFactory.create(persistence, disabledForwardingSettings(), () -> shared)) {
            assertThat(store.delegate()).isInstanceOf(BufferedActivityStore.class);
        }
    }

    @Test
    void forwardingEnabledBuildsABufferedStoreWrappingAnHttpActivityStoreWithoutTouchingAnyDataSource() {
        ActivityForwardingSettings forwarding = enabledForwardingSettings("http://localhost:1");

        try (SwitchableActivityStore store = ActivityStoreFactory.create(disabledSettings(), forwarding, () -> {
            throw new AssertionError("forwarding must never resolve a DataSource");
        })) {
            assertThat(store.delegate()).isInstanceOf(BufferedActivityStore.class);
        }
    }

    @Test
    void forwardingEnabledActuallyForwardsAppendedEntriesToThePeer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        AtomicInteger received = new AtomicInteger();
        server.createContext(ActivityForwardService.FORWARD_PATH, exchange -> {
            exchange.getRequestBody().readAllBytes();
            received.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            ActivityForwardingSettings forwarding = enabledForwardingSettings(
                    "http://localhost:" + server.getAddress().getPort());
            try (SwitchableActivityStore store =
                    ActivityStoreFactory.create(disabledSettings(), forwarding, () -> null)) {
                store.append(new StoredActivityEntry("app-1", 1, entry("1", "REQUEST", 1, "OK", "hello")));
                BufferedActivityStore buffered = (BufferedActivityStore) store.delegate();
                buffered.flushNow();

                assertThat(received.get()).isEqualTo(1);
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void bothPersistenceAndForwardingEnabledThrowsAtConstructionTime() {
        ActivityPersistenceSettings persistence = new ActivityPersistenceSettings(
                true,
                ActivityPersistenceSettings.DataSourceMode.SHARED,
                null,
                null,
                null,
                null,
                "bootui_activity",
                Duration.ofSeconds(5),
                200,
                null,
                "app-1",
                Duration.ofSeconds(1));
        ActivityForwardingSettings forwarding = enabledForwardingSettings("http://localhost:1");

        assertThatThrownBy(() -> ActivityStoreFactory.create(persistence, forwarding, () -> newDataSource()))
                .isInstanceOf(ActivityStoreException.class)
                .hasMessageContaining("cannot both be");
    }
}
