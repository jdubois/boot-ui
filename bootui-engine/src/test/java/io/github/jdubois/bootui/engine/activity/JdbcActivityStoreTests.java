package io.github.jdubois.bootui.engine.activity;

import static io.github.jdubois.bootui.engine.activity.ActivityTestFixtures.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class JdbcActivityStoreTests {

    private static final String INSTANCE = "app-1";
    private static final AtomicInteger DB_COUNTER = new AtomicInteger();

    private DataSource newDataSource() {
        return newDataSource(null);
    }

    private DataSource newDataSource(String h2Mode) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:activity-test-" + DB_COUNTER.incrementAndGet() + ";DB_CLOSE_DELAY=-1"
                + (h2Mode == null ? "" : ";MODE=" + h2Mode));
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private void append(JdbcActivityStore store, String id, String type, long ts, String severity, String summary) {
        store.appendBatch(List.of(new StoredActivityEntry(INSTANCE, ts, entry(id, type, ts, severity, summary))));
    }

    @Test
    void autoCreatesTheTableOnFirstUse() {
        JdbcActivityStore store = new JdbcActivityStore(newDataSource(), "bootui_activity");
        append(store, "1", "REQUEST", 1, "OK", "hello");

        ActivityPage page = store.query(ActivityQuery.firstPage(INSTANCE));
        assertThat(page.entryDtos()).extracting(ActivityEntryDto::id).containsExactly("1");
    }

    @Test
    void queryingAnEmptyTableStillAutoCreatesItAndReturnsEmptyPage() {
        JdbcActivityStore store = new JdbcActivityStore(newDataSource(), "bootui_activity");
        ActivityPage page = store.query(ActivityQuery.firstPage(INSTANCE));
        assertThat(page).isEqualTo(ActivityPage.EMPTY);
    }

    @Test
    void roundTripsAllFieldsThroughInsertAndQuery() {
        DataSource dataSource = newDataSource();
        JdbcActivityStore store = new JdbcActivityStore(dataSource, "bootui_activity");
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
        store.appendBatch(List.of(new StoredActivityEntry(INSTANCE, 1, original)));

        ActivityPage page = store.query(ActivityQuery.firstPage(INSTANCE));
        assertThat(page.entryDtos()).containsExactly(original);
    }

    @Test
    void insertsPreserveNullOptionalFields() {
        JdbcActivityStore store = new JdbcActivityStore(newDataSource(), "bootui_activity");
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
        store.appendBatch(List.of(new StoredActivityEntry(INSTANCE, 1, original)));

        ActivityPage page = store.query(ActivityQuery.firstPage(INSTANCE));
        assertThat(page.entryDtos()).containsExactly(original);
    }

    @Test
    void paginatesWithCursorAcrossTwoPages() {
        JdbcActivityStore store = new JdbcActivityStore(newDataSource(), "bootui_activity");
        for (long i = 1; i <= 5; i++) {
            append(store, String.valueOf(i), "REQUEST", i, "OK", "entry " + i);
        }

        ActivityPage firstPage = store.query(new ActivityQuery(INSTANCE, null, null, null, null, null, null, 2));
        assertThat(firstPage.entryDtos()).extracting(ActivityEntryDto::id).containsExactly("5", "4");
        assertThat(firstPage.hasMore()).isTrue();

        ActivityPage secondPage =
                store.query(new ActivityQuery(INSTANCE, null, null, null, null, null, firstPage.nextCursor(), 2));
        assertThat(secondPage.entryDtos()).extracting(ActivityEntryDto::id).containsExactly("3", "2");
        assertThat(secondPage.hasMore()).isTrue();

        ActivityPage thirdPage =
                store.query(new ActivityQuery(INSTANCE, null, null, null, null, null, secondPage.nextCursor(), 2));
        assertThat(thirdPage.entryDtos()).extracting(ActivityEntryDto::id).containsExactly("1");
        assertThat(thirdPage.hasMore()).isFalse();
    }

    @Test
    void filtersByTypeSeverityTextAndTimeWindow() {
        JdbcActivityStore store = new JdbcActivityStore(newDataSource(), "bootui_activity");
        append(store, "1", "REQUEST", 100, "OK", "GET /users");
        append(store, "2", "SQL", 200, "ERROR", "select from orders");
        append(store, "3", "SQL", 300, "OK", "select from users");

        assertThat(store.query(new ActivityQuery(INSTANCE, "sql", null, null, null, null, null, 200))
                        .entryDtos())
                .extracting(ActivityEntryDto::id)
                .containsExactly("3", "2");
        assertThat(store.query(new ActivityQuery(INSTANCE, null, "error", null, null, null, null, 200))
                        .entryDtos())
                .extracting(ActivityEntryDto::id)
                .containsExactly("2");
        assertThat(store.query(new ActivityQuery(INSTANCE, null, null, "users", null, null, null, 200))
                        .entryDtos())
                .extracting(ActivityEntryDto::id)
                .containsExactly("3", "1");
        assertThat(store.query(new ActivityQuery(INSTANCE, null, null, null, 150L, 250L, null, 200))
                        .entryDtos())
                .extracting(ActivityEntryDto::id)
                .containsExactly("2");
    }

    @Test
    void scopesRowsToTheRequestedInstanceOnlySharingOneTable() {
        DataSource dataSource = newDataSource();
        JdbcActivityStore storeA = new JdbcActivityStore(dataSource, "bootui_activity");
        JdbcActivityStore storeB = new JdbcActivityStore(dataSource, "bootui_activity");
        storeA.appendBatch(List.of(new StoredActivityEntry("app-a", 1, entry("1", "REQUEST", 1, "OK", "from a"))));
        storeB.appendBatch(List.of(new StoredActivityEntry("app-b", 1, entry("2", "REQUEST", 2, "OK", "from b"))));

        assertThat(storeA.query(ActivityQuery.firstPage("app-a")).entryDtos())
                .extracting(ActivityEntryDto::id)
                .containsExactly("1");
        assertThat(storeA.query(ActivityQuery.firstPage("app-b")).entryDtos())
                .extracting(ActivityEntryDto::id)
                .containsExactly("2");
    }

    @Test
    void pruneDeletesOnlyOwnInstanceRowsOlderThanCutoff() {
        JdbcActivityStore store = new JdbcActivityStore(newDataSource(), "bootui_activity");
        store.appendBatch(List.of(
                new StoredActivityEntry("app-a", 1, entry("old-a", "REQUEST", 100, "OK", "old a")),
                new StoredActivityEntry("app-a", 2, entry("new-a", "REQUEST", 500, "OK", "new a")),
                new StoredActivityEntry("app-b", 1, entry("old-b", "REQUEST", 100, "OK", "old b"))));

        store.prune("app-a", 300);

        assertThat(store.query(ActivityQuery.firstPage("app-a")).entryDtos())
                .extracting(ActivityEntryDto::id)
                .containsExactly("new-a");
        // app-b's equally old row is untouched: prune only ever targets the calling instance's own rows.
        assertThat(store.query(ActivityQuery.firstPage("app-b")).entryDtos())
                .extracting(ActivityEntryDto::id)
                .containsExactly("old-b");
    }

    @Test
    void rejectsTableNamesThatAreNotPlainIdentifiers() {
        DataSource dataSource = newDataSource();
        assertThatThrownBy(() -> new JdbcActivityStore(dataSource, "bootui_activity; DROP TABLE users"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JdbcActivityStore(dataSource, "1_starts_with_digit"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JdbcActivityStore(dataSource, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void verifySchemaCreatesTheTableEagerlyWithoutRequiringAWrite() {
        JdbcActivityStore store = new JdbcActivityStore(newDataSource(), "bootui_activity");
        store.verifySchema();

        ActivityPage page = store.query(ActivityQuery.firstPage(INSTANCE));
        assertThat(page).isEqualTo(ActivityPage.EMPTY);
    }

    @Test
    void verifySchemaIsIdempotent() {
        JdbcActivityStore store = new JdbcActivityStore(newDataSource(), "bootui_activity");
        store.verifySchema();
        store.verifySchema();

        append(store, "1", "REQUEST", 1, "OK", "hello");
        assertThat(store.query(ActivityQuery.firstPage(INSTANCE)).entryDtos())
                .extracting(ActivityEntryDto::id)
                .containsExactly("1");
    }

    @Test
    void verifySchemaWrapsFailuresAsActivityStoreException() {
        DataSource broken = (DataSource) java.lang.reflect.Proxy.newProxyInstance(
                DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                    if ("getConnection".equals(method.getName())) {
                        throw new SQLException("simulated connection failure");
                    }
                    throw new UnsupportedOperationException(method.getName());
                });

        JdbcActivityStore store = new JdbcActivityStore(broken, "bootui_activity");
        assertThatThrownBy(store::verifySchema).isInstanceOf(ActivityStoreException.class);
    }

    @Test
    void everyJdbcCallRunsWithCaptureSuppressedAndRestoresAfter() throws SQLException {
        DataSource delegate = newDataSource();
        AtomicReference<Boolean> suppressedDuringGetConnection = new AtomicReference<>();
        DataSource observing = (DataSource) java.lang.reflect.Proxy.newProxyInstance(
                DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                    if ("getConnection".equals(method.getName()) && (args == null || args.length == 0)) {
                        suppressedDuringGetConnection.set(BootUiJdbcCaptureGuard.isSuppressed());
                    }
                    return method.invoke(delegate, args);
                });

        assertThat(BootUiJdbcCaptureGuard.isSuppressed()).isFalse();
        JdbcActivityStore store = new JdbcActivityStore(observing, "bootui_activity");
        append(store, "1", "REQUEST", 1, "OK", "hello");

        assertThat(suppressedDuringGetConnection.get()).isTrue();
        assertThat(BootUiJdbcCaptureGuard.isSuppressed()).isFalse();
    }

    @Test
    void pagesWithLimitWhenTheDatabaseReportsMySql() {
        // MySQL has no OFFSET ... FETCH FIRST row limit in any version (issue #1142).
        RecordingDataSource mysql = new RecordingDataSource(newDataSource("MySQL"), "MySQL", false);
        JdbcActivityStore store = new JdbcActivityStore(mysql.dataSource(), "bootui_activity");
        for (long i = 1; i <= 3; i++) {
            append(store, String.valueOf(i), "REQUEST", i, "OK", "entry " + i);
        }

        ActivityPage first = store.query(new ActivityQuery(INSTANCE, null, null, null, null, null, null, 2));
        ActivityPage second =
                store.query(new ActivityQuery(INSTANCE, null, null, null, null, null, first.nextCursor(), 2));

        assertThat(first.entryDtos()).extracting(ActivityEntryDto::id).containsExactly("3", "2");
        assertThat(second.entryDtos()).extracting(ActivityEntryDto::id).containsExactly("1");
        assertThat(mysql.pageReads())
                .hasSize(2)
                .allSatisfy(sql -> assertThat(sql)
                        .endsWith("ORDER BY occurred_at DESC, seq DESC LIMIT ?")
                        .doesNotContain("FETCH"));
    }

    @Test
    void createsNumberColumnsWhenTheDatabaseReportsOracle() {
        // Oracle has no BIGINT type: CREATE TABLE fails with ORA-00902.
        RecordingDataSource oracle = new RecordingDataSource(newDataSource("Oracle"), "Oracle", false);
        JdbcActivityStore store = new JdbcActivityStore(oracle.dataSource(), "bootui_activity");
        append(store, "1", "REQUEST", 1, "OK", "hello");

        assertThat(store.query(ActivityQuery.firstPage(INSTANCE)).entryDtos())
                .extracting(ActivityEntryDto::id)
                .containsExactly("1");
        assertThat(oracle.sql)
                .filteredOn(sql -> sql.startsWith("CREATE TABLE"))
                .singleElement()
                .satisfies(ddl -> assertThat(ddl)
                        .contains("seq NUMBER(19) NOT NULL")
                        .contains("occurred_at NUMBER(19) NOT NULL")
                        .contains("duration_ms NUMBER(19),")
                        .doesNotContain("BIGINT"));
        assertThat(oracle.pageReads())
                .singleElement()
                .satisfies(sql -> assertThat(sql).endsWith(" OFFSET 0 ROWS FETCH FIRST ? ROWS ONLY"));
    }

    @Test
    void otherDatabasesKeepTheStandardDdlAndRowLimit() {
        RecordingDataSource postgres = new RecordingDataSource(newDataSource("PostgreSQL"), "PostgreSQL", false);
        JdbcActivityStore store = new JdbcActivityStore(postgres.dataSource(), "bootui_activity");
        append(store, "1", "REQUEST", 1, "OK", "hello");
        store.query(ActivityQuery.firstPage(INSTANCE));

        assertThat(postgres.sql)
                .filteredOn(sql -> sql.startsWith("CREATE TABLE"))
                .singleElement()
                .satisfies(
                        ddl -> assertThat(ddl).contains("seq BIGINT NOT NULL").doesNotContain("NUMBER"));
        assertThat(postgres.pageReads())
                .singleElement()
                .satisfies(sql -> assertThat(sql).endsWith(" OFFSET 0 ROWS FETCH FIRST ? ROWS ONLY"));
    }

    @Test
    void detectsTheDialectOncePerStore() {
        RecordingDataSource h2 = new RecordingDataSource(newDataSource(), "H2", false);
        JdbcActivityStore store = new JdbcActivityStore(h2.dataSource(), "bootui_activity");

        store.verifySchema();
        append(store, "1", "REQUEST", 1, "OK", "hello");
        store.query(ActivityQuery.firstPage(INSTANCE));
        store.query(ActivityQuery.firstPage(INSTANCE));
        store.prune(INSTANCE, 0);

        assertThat(h2.productNameCalls).hasValue(1);
    }

    @Test
    void verifySchemaFailsWhenTheDatabaseRejectsTheReadStatement() {
        // Creating the table succeeds, as it did on MySQL before issue #1142: only the read is refused.
        RecordingDataSource rejecting = new RecordingDataSource(newDataSource(), "H2", true);
        JdbcActivityStore store = new JdbcActivityStore(rejecting.dataSource(), "bootui_activity");

        assertThatThrownBy(store::verifySchema)
                .isInstanceOf(ActivityStoreException.class)
                .hasMessageContaining("read")
                .hasRootCauseMessage("synthetic syntax error near the row limit");
        assertThat(rejecting.sql).anySatisfy(sql -> assertThat(sql).startsWith("CREATE TABLE"));
    }
}
