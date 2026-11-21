package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class JdbcDurableJournalProjectionTests {

    private static final AtomicInteger DATABASES = new AtomicInteger();

    @Test
    void globalFeedIsMonotonicAcrossWritersAndReconnectsByGlobalCursor() {
        DataSource dataSource = dataSource();
        JdbcDurableJournalStore firstWriter = new JdbcDurableJournalStore(dataSource, "durable");
        JdbcDurableJournalStore secondWriter = new JdbcDurableJournalStore(dataSource, "durable");
        DurableJournalScope local = new DurableJournalScope("local", "orders", "run-1");
        DurableJournalScope team = new DurableJournalScope("team", "billing", "run-2");
        firstWriter.upsertRun(run(local, "instance-a"));
        secondWriter.upsertRun(run(team, "instance-b"));

        firstWriter.append(local, event(1, 100, "request-a", null), 10);
        secondWriter.append(team, activity(event(1, 50, "request-b", null)), 20);
        firstWriter.append(local, activity(event(2, 25, "request-c", null)), 30);
        firstWriter.append(local, event(3, 5, "unprojected", null), 40);

        DurableJournalFeedPage first = firstWriter.queryFeed(DurableJournalFeedQuery.globalFirstPage(2));
        assertThat(first.entries())
                .extracting(DurableJournalFeedItem::globalSequence)
                .containsExactly(4L, 3L);
        assertThat(first.entries().get(0).projectionAvailable()).isFalse();
        assertThat(first.entries().get(1).activity().id()).isEqualTo("request-c");
        assertThat(first.hasMore()).isTrue();

        JdbcDurableJournalStore reconnect = new JdbcDurableJournalStore(dataSource, "durable", true);
        DurableJournalFeedPage second =
                reconnect.queryFeed(DurableJournalFeedQuery.globalFirstPage(2).withCursor(first.nextCursor()));
        assertThat(second.entries())
                .extracting(DurableJournalFeedItem::globalSequence)
                .containsExactly(2L, 1L);
        assertThat(second.entries()).extracting(item -> item.origin().scope()).containsExactly(team, local);
        assertThat(second.entries().get(0).origin().instanceId()).isEqualTo("instance-b");
        assertThat(second.entries().get(1).projectionAvailable()).isFalse();
        assertThat(second.hasMore()).isFalse();
    }

    @Test
    void filtersAndOriginScopesUseProjectedColumnsWithoutDecodingThePayload() throws Exception {
        DataSource dataSource = dataSource();
        JdbcDurableJournalStore store = new JdbcDurableJournalStore(dataSource, "durable");
        DurableJournalScope first = new DurableJournalScope("local", "orders", "run-1");
        DurableJournalScope other = new DurableJournalScope("team", "orders", "run-2");
        store.upsertRun(run(first, "orders-a"));
        store.upsertRun(run(other, "orders-b"));

        JournalEntry request = http(1, "request-1", "/orders/42;token=secret", "/orders/{id}", 503, 30);
        JournalEntry sql = new JournalEntry(
                2,
                new RuntimeEvent(
                        JournalSource.SQL,
                        20,
                        1_000_000,
                        "request-1",
                        null,
                        "trace-1",
                        "worker",
                        null,
                        false,
                        new SqlPayload("SELECT * FROM orders WHERE email = 'alice@example.com'", "site", "db", false)),
                1);
        JournalEntry exception = new JournalEntry(
                3,
                new RuntimeEvent(
                        JournalSource.EXCEPTION,
                        30,
                        0,
                        "request-1",
                        null,
                        "trace-1",
                        "worker",
                        null,
                        true,
                        new ExceptionPayload("group-7", "java.lang.IllegalStateException")),
                1);
        JournalActivityFeed feed = new JournalActivityFeed(20, 2, null);
        List<DurableJournalEvent> projected = feed.durableEvents(
                List.of(request, sql, exception),
                entry -> "event-" + entry.sequence(),
                new HashMap<>(),
                new HashMap<>(),
                count -> {});

        store.appendEvents(first, projected);
        store.append(other, event(1, 40, "other", null));

        DurableJournalFeedQuery query = new DurableJournalFeedQuery(
                "local",
                "orders",
                "run-1",
                "orders-a",
                "sql",
                "SQL",
                "OK",
                "SELECT * FROM orders",
                "/orders/{id}",
                "request-1",
                null,
                20);
        DurableJournalFeedPage filtered = store.queryFeed(query);
        assertThat(filtered.entries()).singleElement().satisfies(item -> {
            assertThat(item.origin()).isEqualTo(new DurableJournalOrigin(first, "orders-a"));
            assertThat(item.activity().id()).isEqualTo("event-2");
            assertThat(item.activity().parentId()).isEqualTo("request-1");
        });

        ActivityEntryDto failedRequest = store.queryFeed(new DurableJournalFeedQuery(
                        "local", "orders", "run-1", null, null, "REQUEST", "ERROR", null, null, "request-1", null, 20))
                .entries()
                .get(0)
                .activity();
        assertThat(failedRequest.id()).isEqualTo("request-1");
        assertThat(failedRequest.path()).doesNotContain("secret").contains("token=******");
        assertThat(failedRequest.profileable()).isTrue();

        ActivityEntryDto persistedException = store.queryFeed(new DurableJournalFeedQuery(
                        "local", "orders", "run-1", null, null, "EXCEPTION", null, null, null, null, null, 20))
                .entries()
                .get(0)
                .activity();
        assertThat(persistedException.exceptionGroupId()).isEqualTo("group-7");
        assertThat(persistedException.path()).isNull();

        try (Connection connection = dataSource.getConnection();
                PreparedStatement corrupt = connection.prepareStatement(
                        "UPDATE durable_events SET event_payload = ? WHERE namespace_id = 'team'")) {
            corrupt.setBytes(1, new byte[] {1, 2, 3});
            corrupt.executeUpdate();
        }
        assertThat(store.queryFeed(new DurableJournalFeedQuery("team", "orders", "run-2", 20)))
                .extracting(DurableJournalFeedPage::entries)
                .as("feed paging reads projection columns, not typed payload bytes")
                .isNotNull();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE durable_events ADD COLUMN IF NOT EXISTS ignored_marker INTEGER");
        }
    }

    @Test
    void derivesMaskedProjectionAndNPlusOneThroughTheExactActivityFeedLogic() {
        JdbcDurableJournalStore store = new JdbcDurableJournalStore(dataSource(), "durable");
        DurableJournalScope scope = new DurableJournalScope("local", "orders", "run-1");
        store.upsertRun(run(scope, "instance"));
        JournalEntry firstSql = new JournalEntry(
                1, sqlEvent("request-1", "SELECT * FROM accounts WHERE email = 'alice@example.com'"), 1);
        JournalEntry secondSql =
                new JournalEntry(2, sqlEvent("request-1", "SELECT * FROM accounts WHERE email = 'bob@example.com'"), 1);
        JournalEntry request = http(3, "request-1", "/orders/1", "/orders/{id}", 200, 10);
        JournalActivityFeed feed = new JournalActivityFeed(100, 2, null);
        List<DurableJournalEvent> durable = feed.durableEvents(
                List.of(firstSql, secondSql, request),
                entry -> "run-" + entry.sequence(),
                new HashMap<>(),
                new HashMap<>(),
                count -> {});

        store.appendEvents(scope, durable);

        List<DurableJournalFeedItem> rows =
                store.queryFeed(DurableJournalFeedQuery.firstPage(scope)).entries();
        ActivityEntryDto persistedRequest = rows.stream()
                .filter(row -> "REQUEST".equals(row.activity().type()))
                .findFirst()
                .orElseThrow()
                .activity();
        assertThat(persistedRequest.sqlNPlusOneSuspected()).isTrue();
        assertThat(persistedRequest.profileable()).isTrue();
        assertThat(rows)
                .filteredOn(row -> "SQL".equals(row.activity().type()))
                .allSatisfy(row -> assertThat(row.activity().summary())
                        .doesNotContain("alice", "bob")
                        .contains("?"));
    }

    @Test
    void corruptProjectionAndPayloadFailHistoricalReadsExplicitly() throws Exception {
        DataSource dataSource = dataSource();
        JdbcDurableJournalStore store = new JdbcDurableJournalStore(dataSource, "durable");
        DurableJournalScope scope = new DurableJournalScope("local", "orders", "run-1");
        store.upsertRun(run(scope, "instance"));
        store.append(scope, activity(event(1, 10, "request-1", null)));

        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE durable_events SET summary = NULL WHERE journal_sequence = 1");
        }
        assertThatThrownBy(() -> store.queryFeed(DurableJournalFeedQuery.firstPage(scope)))
                .isInstanceOf(DurableJournalUnavailableException.class)
                .hasMessageContaining("history is unavailable");

        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE durable_events SET summary = 'restored', event_payload = X'01020304'"
                    + " WHERE journal_sequence = 1");
        }
        assertThatThrownBy(() -> store.query(scope)).isInstanceOf(DurableJournalUnavailableException.class);
    }

    private static DurableJournalRun run(DurableJournalScope scope, String instanceId) {
        return new DurableJournalRun(scope, 1, 1, "RUNNING", null, 0, instanceId);
    }

    private static DurableJournalEvent activity(JournalEntry entry) {
        ActivityEntryDto activity = new ActivityEntryDto(
                entry.event().requestId(),
                "REQUEST",
                entry.event().epochMillis(),
                "OK",
                "GET /orders",
                "/orders/{id}",
                1L,
                entry.event().traceId(),
                "GET",
                "/orders",
                200,
                entry.event().thread(),
                true,
                null,
                null,
                false,
                List.of(),
                null);
        return new DurableJournalEvent(entry, activity);
    }

    private static JournalEntry event(long sequence, long timestamp, String requestId, Object ignored) {
        return new JournalEntry(
                sequence,
                new RuntimeEvent(
                        JournalSource.HTTP,
                        timestamp,
                        1_000_000,
                        requestId,
                        null,
                        "trace",
                        "thread",
                        null,
                        false,
                        new HttpPayload("GET", "/orders", "/orders", null, 200)),
                1);
    }

    private static JournalEntry http(
            long sequence, String requestId, String path, String route, int status, long durationMs) {
        return new JournalEntry(
                sequence,
                new RuntimeEvent(
                        JournalSource.HTTP,
                        sequence * 10,
                        durationMs * 1_000_000,
                        requestId,
                        null,
                        "trace-1",
                        "http",
                        null,
                        false,
                        new HttpPayload("GET", path, route, null, status)),
                1);
    }

    private static RuntimeEvent sqlEvent(String requestId, String sql) {
        return new RuntimeEvent(
                JournalSource.SQL,
                1,
                1_000_000,
                requestId,
                null,
                "trace-1",
                "worker",
                null,
                false,
                new SqlPayload(sql, "site", "db", false));
    }

    private static DataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:durable-projection-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }
}
