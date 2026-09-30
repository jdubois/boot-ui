package io.github.jdubois.bootui.autoconfigure.activity;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.core.dto.ActivitySwitchRequest;
import io.github.jdubois.bootui.engine.activity.ActivityPage;
import io.github.jdubois.bootui.engine.activity.ActivityPersistenceSettings;
import io.github.jdubois.bootui.engine.activity.ActivityQuery;
import io.github.jdubois.bootui.engine.activity.ActivitySwitchResponse;
import io.github.jdubois.bootui.engine.activity.ActivitySwitchService;
import io.github.jdubois.bootui.engine.activity.InMemoryActivityStore;
import io.github.jdubois.bootui.engine.activity.JdbcActivityStore;
import io.github.jdubois.bootui.engine.activity.StoredActivityEntry;
import io.github.jdubois.bootui.engine.activity.SwitchableActivityStore;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Live coverage of the Live Activity durable store ({@link JdbcActivityStore}) against a real database server.
 *
 * <p>The store's table DDL and paged read are the two places real databases disagree: MySQL has no
 * {@code OFFSET ... FETCH FIRST} row-limit clause and Oracle has no {@code BIGINT} type. H2, which the engine's own
 * unit tests use, accepts both, so only a real server can prove the store works there. Every scenario runs on each
 * subclass's database, against its own freshly created table.</p>
 */
abstract class AbstractJdbcActivityStoreLiveTests {

    private static final String INSTANCE = "app-1";
    private static final AtomicInteger TABLES = new AtomicInteger();

    /** A connection source for the live database under test. */
    protected abstract DataSource dataSource();

    /** The product name the database's JDBC driver reports, proving which dialect path ran. */
    protected abstract String expectedProductName();

    protected static String newTableName() {
        return "bootui_activity_live_" + TABLES.incrementAndGet();
    }

    protected static ActivityEntryDto entry(String id, String type, long timestamp, String severity, String summary) {
        return new ActivityEntryDto(
                id, type, timestamp, severity, summary, null, null, null, null, null, null, null, false, null, null,
                false);
    }

    protected static StoredActivityEntry stored(String instanceId, long seq, ActivityEntryDto entry) {
        return new StoredActivityEntry(instanceId, seq, entry);
    }

    private JdbcActivityStore newStore() {
        return new JdbcActivityStore(dataSource(), newTableName());
    }

    private static List<String> ids(ActivityPage page) {
        return page.entryDtos().stream().map(ActivityEntryDto::id).toList();
    }

    private static ActivityQuery pageAfter(ActivityPage page, int pageSize) {
        return new ActivityQuery(INSTANCE, null, null, null, null, null, page.nextCursor(), pageSize);
    }

    @Test
    void runsAgainstTheExpectedDatabase() throws SQLException {
        try (Connection connection = dataSource().getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo(expectedProductName());
        }
    }

    @Test
    void createsTheTableOnFirstUseAndRoundTripsEveryField() {
        JdbcActivityStore store = newStore();
        ActivityEntryDto complete = new ActivityEntryDto(
                "req-1",
                "REQUEST",
                12_345L,
                "SLOW",
                "GET /api/foo -> 200",
                "as alice",
                250L,
                "trace-abc",
                "GET",
                "/api/foo",
                200,
                "http-nio-1",
                true,
                "parent-1",
                "alice",
                true);
        ActivityEntryDto sparse = new ActivityEntryDto(
                "sql-1",
                "SQL",
                12_000L,
                "OK",
                "select 1",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                false);

        store.appendBatch(List.of(stored(INSTANCE, 1, sparse), stored(INSTANCE, 2, complete)));

        assertThat(store.query(ActivityQuery.firstPage(INSTANCE)).entryDtos()).containsExactly(complete, sparse);
    }

    @Test
    void pagesNewestFirstWithAKeysetCursorIncludingTimestampTies() {
        JdbcActivityStore store = newStore();
        // Entries 3 and 4 share a timestamp, so the cursor must fall back to seq to page without gaps or repeats.
        store.appendBatch(List.of(
                stored(INSTANCE, 1, entry("1", "REQUEST", 100, "OK", "entry 1")),
                stored(INSTANCE, 2, entry("2", "REQUEST", 200, "OK", "entry 2")),
                stored(INSTANCE, 3, entry("3", "REQUEST", 300, "OK", "entry 3")),
                stored(INSTANCE, 4, entry("4", "REQUEST", 300, "OK", "entry 4")),
                stored(INSTANCE, 5, entry("5", "REQUEST", 500, "OK", "entry 5"))));

        ActivityPage first = store.query(new ActivityQuery(INSTANCE, null, null, null, null, null, null, 2));
        assertThat(ids(first)).containsExactly("5", "4");
        assertThat(first.hasMore()).isTrue();

        ActivityPage second = store.query(pageAfter(first, 2));
        assertThat(ids(second)).containsExactly("3", "2");
        assertThat(second.hasMore()).isTrue();

        ActivityPage third = store.query(pageAfter(second, 2));
        assertThat(ids(third)).containsExactly("1");
        assertThat(third.hasMore()).isFalse();
        assertThat(third.nextCursor()).isNull();
    }

    @Test
    void filtersByTypeSeverityTextAndTimeWindow() {
        JdbcActivityStore store = newStore();
        store.appendBatch(List.of(
                stored(INSTANCE, 1, entry("1", "REQUEST", 100, "OK", "GET /users")),
                stored(INSTANCE, 2, entry("2", "SQL", 200, "ERROR", "select from orders")),
                stored(INSTANCE, 3, entry("3", "SQL", 300, "OK", "select from users"))));

        assertThat(ids(store.query(new ActivityQuery(INSTANCE, "sql", null, null, null, null, null, 50))))
                .containsExactly("3", "2");
        assertThat(ids(store.query(new ActivityQuery(INSTANCE, null, "error", null, null, null, null, 50))))
                .containsExactly("2");
        assertThat(ids(store.query(new ActivityQuery(INSTANCE, null, null, "USERS", null, null, null, 50))))
                .containsExactly("3", "1");
        assertThat(ids(store.query(new ActivityQuery(INSTANCE, null, null, null, 150L, 250L, null, 50))))
                .containsExactly("2");
    }

    @Test
    void readsAndPrunesOnlyTheOwningInstanceRows() {
        JdbcActivityStore store = newStore();
        store.appendBatch(List.of(
                stored("app-a", 1, entry("old-a", "REQUEST", 100, "OK", "old a")),
                stored("app-a", 2, entry("new-a", "REQUEST", 500, "OK", "new a")),
                stored("app-b", 1, entry("old-b", "REQUEST", 100, "OK", "old b"))));

        store.prune("app-a", 300);

        assertThat(ids(store.query(ActivityQuery.firstPage("app-a")))).containsExactly("new-a");
        assertThat(ids(store.query(ActivityQuery.firstPage("app-b")))).containsExactly("old-b");
    }

    @Test
    void verifySchemaIsIdempotentAndLeavesAReadableTable() {
        JdbcActivityStore store = newStore();

        store.verifySchema();
        store.verifySchema();

        assertThat(store.query(ActivityQuery.firstPage(INSTANCE))).isEqualTo(ActivityPage.EMPTY);
        store.appendBatch(List.of(stored(INSTANCE, 1, entry("1", "REQUEST", 1, "OK", "hello"))));
        assertThat(ids(store.query(ActivityQuery.firstPage(INSTANCE)))).containsExactly("1");
    }

    @Test
    void useTheExistingDataSourceSwitchPersistsEntriesThatReadBack() {
        String table = newTableName();
        // A long flush interval leaves close() as the only flush, so the durable read below is deterministic.
        ActivityPersistenceSettings settings = new ActivityPersistenceSettings(
                false,
                ActivityPersistenceSettings.DataSourceMode.SHARED,
                null,
                null,
                null,
                null,
                table,
                Duration.ofMinutes(10),
                200,
                Duration.ofDays(7),
                INSTANCE,
                Duration.ofSeconds(1));
        SwitchableActivityStore store = new SwitchableActivityStore(new InMemoryActivityStore(200));
        try {
            ActivitySwitchResponse response = new ActivitySwitchService()
                    .useExistingDataSource(store, settings, dataSource(), new ActivitySwitchRequest(true));
            assertThat(response.status()).as(response.body().message()).isEqualTo(200);
            assertThat(response.body().status()).isEqualTo("success");
            assertThat(store.persistent()).isTrue();

            store.appendBatch(List.of(
                    stored(INSTANCE, 1, entry("1", "REQUEST", 100, "OK", "first")),
                    stored(INSTANCE, 2, entry("2", "REQUEST", 200, "OK", "second"))));

            // The read GET /bootui/api/activity issues on every request once persistence is active.
            assertThat(ids(store.query(ActivityQuery.firstPage(INSTANCE)))).containsExactly("2", "1");
        } finally {
            store.close();
        }

        assertThat(ids(new JdbcActivityStore(dataSource(), table).query(ActivityQuery.firstPage(INSTANCE))))
                .containsExactly("2", "1");
    }
}
