package io.github.jdubois.bootui.console.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.engine.activity.ActivityPage;
import io.github.jdubois.bootui.engine.activity.ActivityQuery;
import io.github.jdubois.bootui.engine.activity.ActivityStoreException;
import io.github.jdubois.bootui.engine.activity.StoredActivityEntry;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryMetadata;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.binding.BindMarkersFactory;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class R2dbcActivityStoreTests {

    private static final String INSTANCE = "app-1";
    private static final AtomicInteger DB_COUNTER = new AtomicInteger();
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private DatabaseClient newDatabaseClient() {
        ConnectionFactory connectionFactory = ConnectionFactories.get(
                "r2dbc:h2:mem:///activity-console-test-" + DB_COUNTER.incrementAndGet() + "?options=DB_CLOSE_DELAY=-1");
        return DatabaseClient.create(connectionFactory);
    }

    private static ActivityEntryDto entry(String id, String type, long ts, String severity, String summary) {
        return new ActivityEntryDto(
                id, type, ts, severity, summary, null, null, null, null, null, null, null, false, null, null, false);
    }

    private static ActivityEntryDto entryWithCorrelation(
            String id, String type, long ts, String severity, String summary, String correlationId) {
        return new ActivityEntryDto(
                id,
                type,
                ts,
                severity,
                summary,
                null,
                null,
                correlationId,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                false);
    }

    private void append(R2dbcActivityStore store, String id, String type, long ts, String severity, String summary) {
        store.appendBatch(List.of(new StoredActivityEntry(INSTANCE, ts, entry(id, type, ts, severity, summary))))
                .block(TIMEOUT);
    }

    @Test
    void autoCreatesTheTableOnFirstUse() {
        R2dbcActivityStore store = new R2dbcActivityStore(newDatabaseClient(), "bootui_activity");
        append(store, "1", "REQUEST", 1, "OK", "hello");

        ActivityPage page = store.query(ActivityQuery.firstPage(INSTANCE)).block(TIMEOUT);
        assertThat(page.entryDtos()).extracting(ActivityEntryDto::id).containsExactly("1");
    }

    @Test
    void queryingAnEmptyTableStillAutoCreatesItAndReturnsEmptyPage() {
        R2dbcActivityStore store = new R2dbcActivityStore(newDatabaseClient(), "bootui_activity");
        StepVerifier.create(store.query(ActivityQuery.firstPage(INSTANCE)))
                .expectNext(ActivityPage.EMPTY)
                .verifyComplete();
    }

    @Test
    void appendBatchOnNullOrEmptyListCompletesWithoutTouchingStorage() {
        R2dbcActivityStore store = new R2dbcActivityStore(newDatabaseClient(), "bootui_activity");
        StepVerifier.create(store.appendBatch(null)).verifyComplete();
        StepVerifier.create(store.appendBatch(List.of())).verifyComplete();
    }

    @Test
    void roundTripsAllFieldsThroughInsertAndQuery() {
        DatabaseClient client = newDatabaseClient();
        R2dbcActivityStore store = new R2dbcActivityStore(client, "bootui_activity");
        ActivityEntryDto original = new ActivityEntryDto(
                "req-1",
                "REQUEST",
                12345L,
                "SLOW",
                "GET /api/foo → 200",
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
        store.appendBatch(List.of(new StoredActivityEntry(INSTANCE, 1, original)))
                .block(TIMEOUT);

        ActivityPage page = store.query(ActivityQuery.firstPage(INSTANCE)).block(TIMEOUT);
        assertThat(page.entryDtos()).containsExactly(original);
    }

    @Test
    void insertsPreserveNullOptionalFields() {
        R2dbcActivityStore store = new R2dbcActivityStore(newDatabaseClient(), "bootui_activity");
        ActivityEntryDto original = new ActivityEntryDto(
                "sql-1",
                "SQL",
                1L,
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
        store.appendBatch(List.of(new StoredActivityEntry(INSTANCE, 1, original)))
                .block(TIMEOUT);

        ActivityPage page = store.query(ActivityQuery.firstPage(INSTANCE)).block(TIMEOUT);
        assertThat(page.entryDtos()).containsExactly(original);
    }

    @Test
    void paginatesWithCursorAcrossTwoPages() {
        R2dbcActivityStore store = new R2dbcActivityStore(newDatabaseClient(), "bootui_activity");
        for (long i = 1; i <= 5; i++) {
            append(store, String.valueOf(i), "REQUEST", i, "OK", "entry " + i);
        }

        ActivityPage firstPage = store.query(new ActivityQuery(INSTANCE, null, null, null, null, null, null, 2))
                .block(TIMEOUT);
        assertThat(firstPage.entryDtos()).extracting(ActivityEntryDto::id).containsExactly("5", "4");
        assertThat(firstPage.hasMore()).isTrue();

        ActivityPage secondPage = store.query(
                        new ActivityQuery(INSTANCE, null, null, null, null, null, firstPage.nextCursor(), 2))
                .block(TIMEOUT);
        assertThat(secondPage.entryDtos()).extracting(ActivityEntryDto::id).containsExactly("3", "2");
        assertThat(secondPage.hasMore()).isTrue();

        ActivityPage thirdPage = store.query(
                        new ActivityQuery(INSTANCE, null, null, null, null, null, secondPage.nextCursor(), 2))
                .block(TIMEOUT);
        assertThat(thirdPage.entryDtos()).extracting(ActivityEntryDto::id).containsExactly("1");
        assertThat(thirdPage.hasMore()).isFalse();
    }

    @Test
    void filtersByTypeSeverityTextAndTimeWindow() {
        R2dbcActivityStore store = new R2dbcActivityStore(newDatabaseClient(), "bootui_activity");
        append(store, "1", "REQUEST", 100, "OK", "GET /users");
        append(store, "2", "SQL", 200, "ERROR", "select from orders");
        append(store, "3", "SQL", 300, "OK", "select from users");

        assertThat(store.query(new ActivityQuery(INSTANCE, "sql", null, null, null, null, null, 200))
                        .block(TIMEOUT)
                        .entryDtos())
                .extracting(ActivityEntryDto::id)
                .containsExactly("3", "2");
        assertThat(store.query(new ActivityQuery(INSTANCE, null, "error", null, null, null, null, 200))
                        .block(TIMEOUT)
                        .entryDtos())
                .extracting(ActivityEntryDto::id)
                .containsExactly("2");
        assertThat(store.query(new ActivityQuery(INSTANCE, null, null, "users", null, null, null, 200))
                        .block(TIMEOUT)
                        .entryDtos())
                .extracting(ActivityEntryDto::id)
                .containsExactly("3", "1");
        assertThat(store.query(new ActivityQuery(INSTANCE, null, null, null, 150L, 250L, null, 200))
                        .block(TIMEOUT)
                        .entryDtos())
                .extracting(ActivityEntryDto::id)
                .containsExactly("2");
    }

    @Test
    void scopesRowsToTheRequestedInstanceOnlySharingOneTable() {
        DatabaseClient client = newDatabaseClient();
        R2dbcActivityStore storeA = new R2dbcActivityStore(client, "bootui_activity");
        R2dbcActivityStore storeB = new R2dbcActivityStore(client, "bootui_activity");
        storeA.appendBatch(List.of(new StoredActivityEntry("app-a", 1, entry("1", "REQUEST", 1, "OK", "from a"))))
                .block(TIMEOUT);
        storeB.appendBatch(List.of(new StoredActivityEntry("app-b", 1, entry("2", "REQUEST", 2, "OK", "from b"))))
                .block(TIMEOUT);

        assertThat(storeA.query(ActivityQuery.firstPage("app-a")).block(TIMEOUT).entryDtos())
                .extracting(ActivityEntryDto::id)
                .containsExactly("1");
        assertThat(storeA.query(ActivityQuery.firstPage("app-b")).block(TIMEOUT).entryDtos())
                .extracting(ActivityEntryDto::id)
                .containsExactly("2");
    }

    @Test
    void queryByCorrelationIdMatchesAcrossInstancesSharingOneTable() {
        // The crux of cross-instance Live Activity correlation: two stores standing in for two separate
        // BootUI-instrumented processes, pointed at the same physical table, and queryByCorrelationId
        // (unlike query()) deliberately has no instance_id predicate.
        DatabaseClient client = newDatabaseClient();
        R2dbcActivityStore storeA = new R2dbcActivityStore(client, "bootui_activity");
        R2dbcActivityStore storeB = new R2dbcActivityStore(client, "bootui_activity");
        storeA.appendBatch(List.of(new StoredActivityEntry(
                        "app-a", 1, entryWithCorrelation("1", "REQUEST", 100, "OK", "from a", "trace-shared"))))
                .block(TIMEOUT);
        storeB.appendBatch(List.of(new StoredActivityEntry(
                        "app-b", 1, entryWithCorrelation("2", "SQL", 200, "OK", "from b", "trace-shared"))))
                .block(TIMEOUT);
        storeA.appendBatch(List.of(new StoredActivityEntry(
                        "app-a", 2, entryWithCorrelation("3", "REQUEST", 300, "OK", "unrelated", "trace-other"))))
                .block(TIMEOUT);

        List<StoredActivityEntry> fromA =
                storeA.queryByCorrelationId("trace-shared", 10).block(TIMEOUT);
        assertThat(fromA).extracting(s -> s.entry().id()).containsExactly("2", "1");
        assertThat(fromA).extracting(StoredActivityEntry::instanceId).containsExactlyInAnyOrder("app-a", "app-b");

        List<StoredActivityEntry> fromB =
                storeB.queryByCorrelationId("trace-shared", 10).block(TIMEOUT);
        assertThat(fromB).extracting(s -> s.entry().id()).containsExactly("2", "1");
    }

    @Test
    void queryByCorrelationIdRespectsLimit() {
        R2dbcActivityStore store = new R2dbcActivityStore(newDatabaseClient(), "bootui_activity");
        store.appendBatch(List.of(
                        new StoredActivityEntry(
                                "app-a", 1, entryWithCorrelation("1", "REQUEST", 100, "OK", "a", "trace-a")),
                        new StoredActivityEntry(
                                "app-a", 2, entryWithCorrelation("2", "REQUEST", 200, "OK", "b", "trace-a")),
                        new StoredActivityEntry(
                                "app-a", 3, entryWithCorrelation("3", "REQUEST", 300, "OK", "c", "trace-a"))))
                .block(TIMEOUT);

        List<StoredActivityEntry> matches =
                store.queryByCorrelationId("trace-a", 2).block(TIMEOUT);
        assertThat(matches).extracting(s -> s.entry().id()).containsExactly("3", "2");
    }

    @Test
    void queryByCorrelationIdReturnsEmptyForBlankOrMissingIdWithoutTouchingStorage() {
        R2dbcActivityStore store = new R2dbcActivityStore(newDatabaseClient(), "bootui_activity");

        StepVerifier.create(store.queryByCorrelationId(null, 10))
                .expectNext(List.of())
                .verifyComplete();
        StepVerifier.create(store.queryByCorrelationId("", 10))
                .expectNext(List.of())
                .verifyComplete();

        store.appendBatch(List.of(new StoredActivityEntry(
                        "app-a", 1, entryWithCorrelation("1", "REQUEST", 100, "OK", "a", "trace-a"))))
                .block(TIMEOUT);
        assertThat(store.queryByCorrelationId("no-such-trace", 10).block(TIMEOUT))
                .isEmpty();
    }

    @Test
    void pruneDeletesOnlyOwnInstanceRowsOlderThanCutoff() {
        R2dbcActivityStore store = new R2dbcActivityStore(newDatabaseClient(), "bootui_activity");
        store.appendBatch(List.of(
                        new StoredActivityEntry("app-a", 1, entry("old-a", "REQUEST", 100, "OK", "old a")),
                        new StoredActivityEntry("app-a", 2, entry("new-a", "REQUEST", 500, "OK", "new a")),
                        new StoredActivityEntry("app-b", 1, entry("old-b", "REQUEST", 100, "OK", "old b"))))
                .block(TIMEOUT);

        store.prune("app-a", 300).block(TIMEOUT);

        assertThat(store.query(ActivityQuery.firstPage("app-a")).block(TIMEOUT).entryDtos())
                .extracting(ActivityEntryDto::id)
                .containsExactly("new-a");
        // app-b's equally old row is untouched: prune only ever targets the calling instance's own rows.
        assertThat(store.query(ActivityQuery.firstPage("app-b")).block(TIMEOUT).entryDtos())
                .extracting(ActivityEntryDto::id)
                .containsExactly("old-b");
    }

    @Test
    void rejectsTableNamesThatAreNotPlainIdentifiers() {
        DatabaseClient client = newDatabaseClient();
        assertThatThrownBy(() -> new R2dbcActivityStore(client, "bootui_activity; DROP TABLE users"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new R2dbcActivityStore(client, "1_starts_with_digit"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new R2dbcActivityStore(client, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void verifySchemaCreatesTheTableEagerlyWithoutRequiringAWrite() {
        R2dbcActivityStore store = new R2dbcActivityStore(newDatabaseClient(), "bootui_activity");
        store.verifySchema().block(TIMEOUT);

        ActivityPage page = store.query(ActivityQuery.firstPage(INSTANCE)).block(TIMEOUT);
        assertThat(page).isEqualTo(ActivityPage.EMPTY);
    }

    @Test
    void verifySchemaIsIdempotent() {
        R2dbcActivityStore store = new R2dbcActivityStore(newDatabaseClient(), "bootui_activity");
        store.verifySchema().block(TIMEOUT);
        store.verifySchema().block(TIMEOUT);

        append(store, "1", "REQUEST", 1, "OK", "hello");
        assertThat(store.query(ActivityQuery.firstPage(INSTANCE)).block(TIMEOUT).entryDtos())
                .extracting(ActivityEntryDto::id)
                .containsExactly("1");
    }

    @Test
    void verifySchemaWrapsFailuresAsActivityStoreException() {
        // getMetadata() is irrelevant here: an explicit bindMarkers(...) bypasses
        // BindMarkersFactoryResolver's driver-name auto-detection entirely, so only create() (the
        // actual connection attempt) needs to fail for this test.
        ConnectionFactory broken = new ConnectionFactory() {
            @Override
            public Publisher<? extends Connection> create() {
                return Mono.<Connection>error(new RuntimeException("simulated connection failure"));
            }

            @Override
            public ConnectionFactoryMetadata getMetadata() {
                throw new UnsupportedOperationException("not used: bindMarkers(...) is set explicitly");
            }
        };
        DatabaseClient brokenClient = DatabaseClient.builder()
                .connectionFactory(broken)
                .bindMarkers(BindMarkersFactory.named(":", ":", 32))
                .build();
        R2dbcActivityStore store = new R2dbcActivityStore(brokenClient, "bootui_activity");

        StepVerifier.create(store.verifySchema())
                .expectError(ActivityStoreException.class)
                .verify(TIMEOUT);
    }
}
