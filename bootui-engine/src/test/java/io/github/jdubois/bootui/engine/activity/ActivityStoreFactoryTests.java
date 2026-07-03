package io.github.jdubois.bootui.engine.activity;

import static io.github.jdubois.bootui.engine.activity.ActivityTestFixtures.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
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
        try (ActivityStore store = ActivityStoreFactory.create(disabledSettings(), () -> null)) {
            assertThat(store).isInstanceOf(InMemoryActivityStore.class);
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

        try (ActivityStore store = ActivityStoreFactory.create(settings, () -> null)) {
            assertThat(store).isInstanceOf(BufferedActivityStore.class);
            BufferedActivityStore buffered = (BufferedActivityStore) store;

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

        try (ActivityStore store = ActivityStoreFactory.create(settings, () -> shared)) {
            BufferedActivityStore buffered = (BufferedActivityStore) store;
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
}
