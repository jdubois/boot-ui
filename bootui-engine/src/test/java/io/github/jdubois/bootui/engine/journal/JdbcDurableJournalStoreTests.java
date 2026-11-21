package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.engine.activity.BootUiJdbcCaptureGuard;
import io.github.jdubois.bootui.engine.insights.InsightsStack;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class JdbcDurableJournalStoreTests {

    private static final AtomicInteger DATABASES = new AtomicInteger();

    @Test
    void commitsStoreOwnedWritesFromConnectionsInManualCommitMode() throws SQLException {
        DataSource physical = dataSource();
        DataSource manualCommit = mock(DataSource.class);
        when(manualCommit.getConnection()).thenAnswer(invocation -> {
            Connection connection = physical.getConnection();
            connection.setAutoCommit(false);
            return connection;
        });
        JdbcDurableJournalStore store = new JdbcDurableJournalStore(manualCommit, "manual_commit");
        DurableJournalScope scope = new DurableJournalScope("local", "orders", "run-1");

        store.upsertRun(run(scope, 1, 0));
        store.appendBatch(scope, List.of(entry(1, 10, "request-1"), entry(2, 20, "request-2")));

        assertThat(new JdbcDurableJournalStore(physical, "manual_commit")
                        .query(scope)
                        .entries())
                .extracting(JournalEntry::sequence)
                .containsExactly(1L, 2L);
    }

    @Test
    void createsNewTablesWithoutChangingTheLegacyActivityTable() throws SQLException {
        DataSource dataSource = dataSource();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE bootui_activity"
                    + " (instance_id VARCHAR(64), seq BIGINT, PRIMARY KEY (instance_id, seq))");
            statement.executeUpdate("INSERT INTO bootui_activity (instance_id, seq) VALUES ('legacy', 7)");
        }
        JdbcDurableJournalStore store = new JdbcDurableJournalStore(dataSource, "bootui_journal");
        DurableJournalScope scope = new DurableJournalScope("local", "orders", "run-1");

        store.upsertRun(run(scope, 10, 0));
        store.append(scope, entry(41, 100, "request-41"));

        assertThat(store.query(scope).entries())
                .singleElement()
                .extracting(JournalEntry::sequence)
                .isEqualTo(41L);
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                var legacy = statement.executeQuery(
                        "SELECT instance_id, seq FROM bootui_activity WHERE instance_id = 'legacy'")) {
            assertThat(legacy.next()).isTrue();
            assertThat(legacy.getLong("seq")).isEqualTo(7);
            assertThat(legacy.next()).isFalse();
        }
        try (Connection connection = dataSource.getConnection();
                var columns = connection.getMetaData().getColumns(null, null, "BOOTUI_ACTIVITY", null)) {
            assertThat(columns).isNotNull();
            List<String> names = new java.util.ArrayList<>();
            while (columns.next()) {
                names.add(columns.getString("COLUMN_NAME").toLowerCase(java.util.Locale.ROOT));
            }
            assertThat(names).containsExactlyInAnyOrder("instance_id", "seq");
        }
    }

    @Test
    void persistsNullRunMetadataWithoutDecodingIt() throws SQLException {
        DataSource dataSource = dataSource();
        JdbcDurableJournalStore store = new JdbcDurableJournalStore(dataSource, "durable");
        DurableJournalScope scope = new DurableJournalScope("local", "orders", "run-1");

        store.upsertRun(run(scope, 1, 0));

        assertThat(store.findRun(scope).metadata()).isNull();
        assertThat(store.findRun(scope).metadataPayload()).isNull();
        assertThat(store.findRunMetadata(scope)).isNull();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                var result = statement.executeQuery("SELECT metadata, metadata_payload FROM "
                        + store.runsTableName()
                        + " WHERE namespace_id = 'local'")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString("metadata")).isNull();
            assertThat(result.getBytes("metadata_payload")).isNull();
        }
    }

    @Test
    void roundTripsCodecMetadataAlongsideTheLegacyStringMetadata() {
        JdbcDurableJournalStore store = new JdbcDurableJournalStore(dataSource(), "durable");
        DurableJournalScope scope = new DurableJournalScope("local", "orders", "run-1");
        DurableJournalRunMetadata metadata = runMetadata();
        DurableJournalRun input =
                new DurableJournalRun(scope, 10, 20, "RUNNING", "opaque metadata", 3, "instance-1", metadata);

        DurableJournalRun stored = store.upsertRun(input);

        assertThat(stored).isEqualTo(input);
        assertThat(stored.metadata()).isEqualTo("opaque metadata");
        assertThat(store.findRun(scope).metadataPayload())
                .containsExactly(DurableJournalRunMetadataCodec.encode(metadata));
        assertThat(store.findRunMetadata(scope)).isEqualTo(metadata);
        assertThat(store.listRuns("local", "orders")).containsExactly(stored);
    }

    @Test
    void defensivelyCopiesRunMetadataPayloadOnConstructionAccessAndJdbcRoundTrip() {
        JdbcDurableJournalStore store = new JdbcDurableJournalStore(dataSource(), "durable");
        DurableJournalScope scope = new DurableJournalScope("local", "orders", "run-1");
        byte[] input = DurableJournalRunMetadataCodec.encode(runMetadata());
        byte[] expected = input.clone();
        DurableJournalRun run = run(scope, 1, 0).withMetadataPayload(input);

        input[0] ^= 1;
        assertThat(run.metadataPayload()).containsExactly(expected);
        store.upsertRun(run);
        byte[] exposed = store.findRun(scope).metadataPayload();
        exposed[0] ^= 1;

        assertThat(store.findRun(scope).metadataPayload()).containsExactly(expected);
    }

    @Test
    void fullScopeSeparatesNamespacesApplicationsAndNewRuns() {
        JdbcDurableJournalStore store = new JdbcDurableJournalStore(dataSource(), "durable");
        DurableJournalScope localOrders = new DurableJournalScope("local", "orders", "run-1");
        DurableJournalScope localBilling = new DurableJournalScope("local", "billing", "run-1");
        DurableJournalScope teamOrders = new DurableJournalScope("team", "orders", "run-1");
        DurableJournalScope localOrdersSecond = new DurableJournalScope("local", "orders", "run-2");

        prepare(store, localOrders, localBilling, teamOrders, localOrdersSecond);
        store.append(localOrders, entry(1, 10, "local-orders"));
        store.append(localBilling, entry(1, 20, "local-billing"));
        store.append(teamOrders, entry(1, 30, "team-orders"));
        store.append(localOrdersSecond, entry(1, 40, "local-orders-new"));

        assertThat(store.query(localOrders).entries())
                .extracting(JournalEntry::event)
                .extracting(RuntimeEvent::requestId)
                .containsExactly("local-orders");
        assertThat(store.query(localBilling).entries())
                .extracting(JournalEntry::event)
                .extracting(RuntimeEvent::requestId)
                .containsExactly("local-billing");
        assertThat(store.query(teamOrders).entries())
                .extracting(JournalEntry::event)
                .extracting(RuntimeEvent::requestId)
                .containsExactly("team-orders");
        assertThat(store.query(localOrdersSecond).entries())
                .extracting(JournalEntry::event)
                .extracting(RuntimeEvent::requestId)
                .containsExactly("local-orders-new");
    }

    @Test
    void usesTheOriginalSequenceAsThePrimaryKey() {
        JdbcDurableJournalStore store = new JdbcDurableJournalStore(dataSource(), "durable");
        DurableJournalScope scope = new DurableJournalScope("local", "orders", "run-1");

        store.upsertRun(run(scope, 1, 0));
        store.appendBatch(scope, List.of(entry(9, 30, "nine"), entry(3, 10, "three"), entry(6, 20, "six")));

        assertThat(store.query(scope).entries())
                .extracting(JournalEntry::sequence)
                .containsExactly(3L, 6L, 9L);
        assertThat(JournalEntryCodec.encode(store.query(scope).entries().get(1)))
                .containsExactly(JournalEntryCodec.encode(entry(6, 20, "six")));
    }

    @Test
    void identicalFullKeyRetryIsAcceptedAndDifferentBytesConflict() {
        JdbcDurableJournalStore store = new JdbcDurableJournalStore(dataSource(), "durable");
        DurableJournalScope scope = new DurableJournalScope("local", "orders", "run-1");
        JournalEntry original = entry(1, 10, "request-1");

        store.upsertRun(run(scope, 1, 0));
        assertThat(store.append(scope, original)).isTrue();
        assertThat(store.append(scope, original)).isFalse();
        assertThat(store.query(scope).entries()).containsExactly(original);

        assertThatThrownBy(() -> store.append(scope, entry(1, 11, "request-1")))
                .isInstanceOf(DurableJournalConflictException.class)
                .hasMessageContaining("sequence 1");
        assertThat(store.query(scope).entries()).containsExactly(original);
    }

    @Test
    void appliesTheDiskMaskBeforePersistingTypedJournalPayloads() {
        JdbcDurableJournalStore store = new JdbcDurableJournalStore(dataSource(), "durable");
        DurableJournalScope scope = new DurableJournalScope("local", "orders", "run-1");
        store.upsertRun(run(scope, 1, 0));
        JournalEntry raw = new JournalEntry(
                1,
                new RuntimeEvent(
                        JournalSource.SQL,
                        10,
                        1,
                        "request-1",
                        "execution-1",
                        "trace-1",
                        "worker",
                        null,
                        false,
                        new SqlPayload(
                                "SELECT * FROM orders WHERE email = 'alice@example.com'",
                                "OrderRepository.find(OrderRepository.java:42)",
                                "primary",
                                false)),
                100);

        store.append(scope, raw);

        SqlPayload persisted =
                (SqlPayload) store.query(scope).entries().get(0).event().payload();
        assertThat(persisted.sql()).isEqualTo("SELECT * FROM orders WHERE email = ?");
        assertThat(((SqlPayload) raw.event().payload()).sql()).contains("alice@example.com");
    }

    @Test
    void batchInsertIsTransactionalWhenALaterKeyConflicts() {
        JdbcDurableJournalStore store = new JdbcDurableJournalStore(dataSource(), "durable");
        DurableJournalScope scope = new DurableJournalScope("local", "orders", "run-1");
        store.upsertRun(run(scope, 1, 0));
        store.append(scope, entry(2, 20, "existing"));

        assertThatThrownBy(() ->
                        store.appendBatch(scope, List.of(entry(1, 10, "rolled-back"), entry(2, 21, "conflicting"))))
                .isInstanceOf(DurableJournalConflictException.class);

        assertThat(store.query(scope).entries())
                .extracting(JournalEntry::sequence)
                .containsExactly(2L);
    }

    @Test
    void rejectsOverlongScopeRunAndIndexedProjectionFields() {
        assertThatThrownBy(() -> new DurableJournalScope("n".repeat(129), "orders", "run"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("namespace");

        DurableJournalScope scope = new DurableJournalScope("local", "orders", "run-1");
        JdbcDurableJournalStore store = new JdbcDurableJournalStore(dataSource(), "durable");
        store.upsertRun(run(scope, 1, 0));
        assertThatThrownBy(() -> store.upsertRun(new DurableJournalRun(
                        scope, 1, 1, "s".repeat(DurableJournalRun.MAX_STATUS_LENGTH + 1), null, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("status");
        assertThatThrownBy(() -> store.upsertRun(new DurableJournalRun(
                        scope, 1, 1, "RUNNING", "m".repeat(DurableJournalRun.MAX_METADATA_LENGTH + 1), 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("metadata");
        assertThatThrownBy(() -> store.upsertRun(run(scope, 1, 0)
                        .withMetadataPayload(new byte[DurableJournalRun.MAX_METADATA_PAYLOAD_LENGTH + 1])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("metadataPayload")
                .hasMessageContaining(String.valueOf(DurableJournalRun.MAX_METADATA_PAYLOAD_LENGTH));
        assertThatThrownBy(() -> store.append(scope, entry(1, 1, "r".repeat(129))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestId");

        JournalEntry maximum = entry(1, 1, "r".repeat(JdbcDurableJournalStore.MAX_PROJECTION_LENGTH));
        assertThat(store.append(scope, maximum)).isTrue();
    }

    @Test
    void upsertsListsAndAdvancesRunCheckpoints() {
        JdbcDurableJournalStore store = new JdbcDurableJournalStore(dataSource(), "durable");
        DurableJournalScope older = new DurableJournalScope("local", "orders", "run-old");
        DurableJournalScope newer = new DurableJournalScope("local", "orders", "run-new");
        DurableJournalScope other = new DurableJournalScope("local", "billing", "run-other");

        store.upsertRun(run(older, 10, 0));
        store.upsertRun(run(newer, 20, 4));
        store.upsertRun(run(other, 30, 0));
        DurableJournalRun replacement = new DurableJournalRun(older, 10, 25, "STOPPED", "opaque metadata", 2);
        store.upsertRun(replacement);

        assertThat(store.listRuns("local", "orders"))
                .extracting(run -> run.scope().run())
                .containsExactly("run-new", "run-old");
        assertThat(store.findRun(older)).isEqualTo(replacement);
        assertThat(store.listRuns("local", "billing"))
                .extracting(run -> run.scope().run())
                .containsExactly("run-other");

        assertThat(store.checkpoint(older, 1).checkpoint()).isEqualTo(2);
        assertThat(store.checkpoint(older, 7).checkpoint()).isEqualTo(7);
        assertThat(store.findRun(older).checkpoint()).isEqualTo(7);
    }

    @Test
    void queryFiltersAndPagesInOriginalSequenceOrder() {
        JdbcDurableJournalStore store = new JdbcDurableJournalStore(dataSource(), "durable");
        DurableJournalScope scope = new DurableJournalScope("local", "orders", "run-1");
        store.upsertRun(run(scope, 1, 0));
        store.appendBatch(
                scope,
                List.of(
                        eventEntry(3, 300, "request-a", false),
                        eventEntry(1, 100, "request-a", false),
                        eventEntry(4, 400, "request-b", true),
                        eventEntry(2, 200, "request-a", true)));

        DurableJournalPage first = store.query(scope, (DurableJournalCursor) null, 2);
        assertThat(first.entries()).extracting(JournalEntry::sequence).containsExactly(1L, 2L);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.nextCursor()).isEqualTo(new DurableJournalCursor(2));
        assertThat(store.query(scope, first.nextCursorToken(), 2).entries())
                .extracting(JournalEntry::sequence)
                .containsExactly(3L, 4L);

        DurableJournalQuery failed =
                new DurableJournalQuery(scope, JournalSource.SQL, true, 100L, 400L, null, null, "trace", null, 50);
        assertThat(store.query(failed).entries())
                .extracting(JournalEntry::sequence)
                .containsExactly(2L, 4L);
    }

    @Test
    void readOnlyModeReportsMissingTablesAndNeverRunsDdl() {
        RecordingDataSource recording = new RecordingDataSource(dataSource());
        JdbcDurableJournalStore store = new JdbcDurableJournalStore(recording, "durable", true);

        assertThatThrownBy(() -> store.query(new DurableJournalScope("local", "orders", "run-1")))
                .isInstanceOf(DurableJournalStoreException.class)
                .hasMessageContaining("schema creation is disabled");
        assertThat(recording.sql).noneMatch(sql -> sql.startsWith("CREATE"));
        assertThat(recording.sql).anyMatch(sql -> sql.startsWith("SELECT"));
        assertThatThrownBy(() -> store.upsertRun(run(new DurableJournalScope("local", "orders", "run-1"), 1, 0)))
                .isInstanceOf(DurableJournalStoreException.class)
                .hasMessageContaining("read-only");
    }

    @Test
    void readOnlyModeCanReadPrecreatedTables() {
        DataSource dataSource = dataSource();
        DurableJournalScope scope = new DurableJournalScope("local", "orders", "run-1");
        JdbcDurableJournalStore writer = new JdbcDurableJournalStore(dataSource, "durable");
        DurableJournalRun input = new DurableJournalRun(scope, 1, 1, "RUNNING", null, 0, "instance-1", runMetadata());
        writer.upsertRun(input);
        writer.append(scope, entry(1, 10, "request-1"));

        JdbcDurableJournalStore reader = new JdbcDurableJournalStore(dataSource, "durable", true);
        assertThat(reader.query(scope).entries()).hasSize(1);
        assertThat(reader.findRun(scope)).isEqualTo(input);
        assertThat(reader.findRunMetadata(scope)).isEqualTo(runMetadata());
    }

    @Test
    void rejectsCorruptRunMetadataOnlyWhenTheCodecHelperIsUsed() throws SQLException {
        DataSource dataSource = dataSource();
        JdbcDurableJournalStore store = new JdbcDurableJournalStore(dataSource, "durable");
        DurableJournalScope scope = new DurableJournalScope("local", "orders", "run-1");
        store.upsertRun(run(scope, 1, 0).withMetadataPayload(DurableJournalRunMetadataCodec.encode(runMetadata())));
        try (Connection connection = dataSource.getConnection();
                var statement = connection.prepareStatement("UPDATE "
                        + store.runsTableName()
                        + " SET metadata_payload = ?"
                        + " WHERE namespace_id = ? AND application_id = ? AND run_id = ?")) {
            statement.setBytes(1, new byte[] {1});
            statement.setString(2, scope.namespace());
            statement.setString(3, scope.application());
            statement.setString(4, scope.run());
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }

        assertThat(store.findRun(scope).metadataPayload()).containsExactly((byte) 1);
        assertThatThrownBy(() -> store.findRunMetadata(scope))
                .isInstanceOf(DurableJournalStoreException.class)
                .hasMessageContaining("malformed")
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allJdbcCallsAreCaptureSuppressedAndRestoreTheGuard() {
        DataSource delegate = dataSource();
        AtomicReference<Boolean> suppressed = new AtomicReference<>();
        DataSource observing = (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                    if ("getConnection".equals(method.getName())) {
                        suppressed.set(BootUiJdbcCaptureGuard.isSuppressed());
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException ex) {
                        throw ex.getCause();
                    }
                });

        JdbcDurableJournalStore store = new JdbcDurableJournalStore(observing, "durable");
        DurableJournalScope scope = new DurableJournalScope("local", "orders", "run-1");
        store.upsertRun(run(scope, 1, 0));
        store.append(scope, entry(1, 10, "request-1"));
        store.query(scope);

        assertThat(suppressed).hasValue(true);
        assertThat(BootUiJdbcCaptureGuard.isSuppressed()).isFalse();
    }

    @Test
    void runMustExistBeforeEventsAndMaskedDetailsAreListableByScope() {
        JdbcDurableJournalStore store = new JdbcDurableJournalStore(dataSource(), "durable");
        DurableJournalScope scope = new DurableJournalScope("local", "orders", "run-1");
        JournalEntry event = entry(1, 10, "request-1");

        assertThatThrownBy(() -> store.append(scope, event))
                .isInstanceOf(DurableJournalStoreException.class)
                .hasMessageContaining("run does not exist");

        store.upsertRun(run(scope, 1, 0));
        store.append(scope, event, 500);
        DurableJournalDetail request = DurableJournalDetail.request(scope, 1, "GET", "/orders/{id}", "readOrder", 200);
        DurableJournalDetail exception = DurableJournalDetail.exception(
                scope,
                1,
                "group-1",
                "java.lang.IllegalStateException",
                "signature",
                List.of("java.lang.RuntimeException", "java.lang.IllegalStateException"),
                List.of("com.example.OrderService.read(OrderService.java:42)"),
                List.of("java.io.IOException"),
                2);

        assertThat(store.upsertDetail(request)).isTrue();
        assertThat(store.upsertDetail(request)).isFalse();
        assertThat(store.upsertDetail(exception)).isTrue();
        assertThat(store.listDetails(scope)).containsExactly(exception, request);
    }

    @Test
    void conflictingMaskedDetailIsExplicit() {
        JdbcDurableJournalStore store = new JdbcDurableJournalStore(dataSource(), "durable");
        DurableJournalScope scope = new DurableJournalScope("local", "orders", "run-1");
        store.upsertRun(run(scope, 1, 0));
        store.append(scope, entry(1, 10, "request-1"));
        store.upsertDetail(DurableJournalDetail.request(scope, 1, "GET", "/orders/{id}", "readOrder", 200));

        assertThatThrownBy(() ->
                        store.upsertDetail(DurableJournalDetail.request(scope, 1, "POST", "/orders", "saveOrder", 201)))
                .isInstanceOf(DurableJournalConflictException.class);
    }

    @Test
    void clearRunThroughDeletesEventsAndDetailsAndLeavesTheFutureIntact() {
        JdbcDurableJournalStore store = new JdbcDurableJournalStore(dataSource(), "durable");
        DurableJournalScope scope = new DurableJournalScope("local", "orders", "run-1");
        store.upsertRun(run(scope, 1, 0));
        store.appendBatch(
                scope, List.of(entry(1, 10, "request-1"), entry(2, 20, "request-2"), entry(3, 30, "request-3")), 50);
        store.upsertDetail(DurableJournalDetail.request(scope, 1, "GET", "/one", null, 200));
        store.upsertDetail(DurableJournalDetail.request(scope, 2, "GET", "/two", null, 200));
        store.upsertDetail(DurableJournalDetail.request(scope, 3, "GET", "/three", null, 200));

        assertThat(store.clearRunThrough(scope, 2)).isEqualTo(new DurableJournalDeletionCounts(2, 2, 0));

        assertThat(store.query(scope).entries())
                .extracting(JournalEntry::sequence)
                .containsExactly(3L);
        assertThat(store.listDetails(scope))
                .extracting(DurableJournalDetail::journalSequence)
                .containsExactly(3L);
        assertThat(store.append(scope, entry(4, 40, "request-4"), 60)).isTrue();
    }

    @Test
    void pruneRemovesExpiredRowsAcrossCurrentAndOldRunsWithoutCrossingTheOwnerPartition() {
        JdbcDurableJournalStore store = new JdbcDurableJournalStore(dataSource(), "durable");
        DurableJournalScope oldRun = new DurableJournalScope("local", "orders", "run-old");
        DurableJournalScope currentRun = new DurableJournalScope("local", "orders", "run-current");
        DurableJournalScope orphanRun = new DurableJournalScope("local", "orders", "run-orphan");
        DurableJournalScope otherOwner = new DurableJournalScope("team", "orders", "run-old");
        DurableJournalScope otherApplication = new DurableJournalScope("local", "billing", "run-old");
        store.upsertRun(run(oldRun, 10, 0));
        store.upsertRun(run(currentRun, 1_000, 0));
        store.upsertRun(run(orphanRun, 10, 0));
        store.upsertRun(run(otherOwner, 10, 0));
        store.upsertRun(run(otherApplication, 10, 0));
        store.append(oldRun, entry(1, 1, "old-run"), 5);
        store.append(currentRun, entry(1, 1, "current-old"), 5);
        store.append(currentRun, entry(2, 2, "current-fresh"), 150);
        store.append(otherOwner, entry(1, 1, "other-owner"), 5);
        store.append(otherApplication, entry(1, 1, "other-application"), 5);
        store.upsertDetail(DurableJournalDetail.request(oldRun, 1, "GET", "/old", null, 200));
        store.upsertDetail(DurableJournalDetail.request(currentRun, 1, "GET", "/current-old", null, 200));
        store.upsertDetail(DurableJournalDetail.request(currentRun, 2, "GET", "/current-fresh", null, 200));
        store.upsertDetail(DurableJournalDetail.request(otherOwner, 1, "GET", "/owner", null, 200));
        store.upsertDetail(DurableJournalDetail.request(otherApplication, 1, "GET", "/app", null, 200));

        assertThat(store.prune(currentRun, 100)).isEqualTo(new DurableJournalDeletionCounts(2, 2, 2));

        assertThat(store.findRun(oldRun)).isNull();
        assertThat(store.findRun(orphanRun)).isNull();
        assertThat(store.findRun(currentRun)).isNotNull();
        assertThat(store.query(currentRun).entries())
                .extracting(JournalEntry::sequence)
                .containsExactly(2L);
        assertThat(store.listDetails(currentRun))
                .extracting(DurableJournalDetail::journalSequence)
                .containsExactly(2L);
        assertThat(store.query(otherOwner).entries()).hasSize(1);
        assertThat(store.query(otherApplication).entries()).hasSize(1);
    }

    @Test
    void globalCursorRoundTripsItsCompleteOrderingTuple() {
        DurableJournalGlobalCursor cursor = new DurableJournalGlobalCursor(123);

        assertThat(DurableJournalGlobalCursor.decode(cursor.encode())).isEqualTo(cursor);
        assertThat(DurableJournalGlobalCursor.decode("not-a-cursor")).isNull();
    }

    @Test
    void globalCursorsAreMonotonicAcrossConcurrentScopes() {
        JdbcDurableJournalStore store = new JdbcDurableJournalStore(dataSource(), "durable");
        DurableJournalScope first = new DurableJournalScope("local", "orders", "run-1");
        DurableJournalScope second = new DurableJournalScope("team", "billing", "run-2");
        prepare(store, first, second);

        store.append(first, entry(5, 50, "first"), 500);
        store.append(second, entry(9, 10, "second"), 100);

        assertThat(store.globalCursor(first, 5)).isEqualTo(new DurableJournalGlobalCursor(1));
        assertThat(store.globalCursor(second, 9)).isEqualTo(new DurableJournalGlobalCursor(2));
    }

    private static void prepare(JdbcDurableJournalStore store, DurableJournalScope... scopes) {
        for (DurableJournalScope scope : scopes) {
            store.upsertRun(run(scope, 1, 0));
        }
    }

    private static DurableJournalRun run(DurableJournalScope scope, long startedAt, long checkpoint) {
        return new DurableJournalRun(scope, startedAt, startedAt, "RUNNING", null, checkpoint);
    }

    private static DurableJournalRunMetadata runMetadata() {
        JournalStatus status = new JournalStatus(
                true,
                "instance-1",
                "run-1",
                3,
                1,
                100,
                2,
                50,
                100,
                10_000,
                0,
                10,
                0,
                0,
                null,
                null,
                0,
                10,
                Map.of(JournalSource.HTTP, 1L),
                Map.of(),
                0,
                0);
        return new DurableJournalRunMetadata(
                DurableJournalRunMetadata.Phase.CHECKPOINT,
                100,
                "instance-1",
                "123",
                InsightsStack.SPRING_MVC,
                new RuntimeJournalSettings(true, 100, 10_000, 10, 10, 10, JournalSource.all()),
                status,
                null,
                null,
                List.of(),
                List.of(),
                Map.of(),
                null,
                Map.of(),
                null,
                null);
    }

    private static JournalEntry entry(long sequence, long epochMillis, String requestId) {
        return eventEntry(sequence, epochMillis, requestId, false);
    }

    private static JournalEntry eventEntry(long sequence, long epochMillis, String requestId, boolean failedOrSlow) {
        return new JournalEntry(
                sequence,
                new RuntimeEvent(
                        JournalSource.SQL,
                        epochMillis,
                        1,
                        requestId,
                        "execution",
                        "trace",
                        "thread",
                        null,
                        failedOrSlow,
                        null),
                100);
    }

    private static DataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:durable-journal-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private static final class RecordingDataSource implements DataSource {

        private final DataSource delegate;
        private final List<String> sql = new CopyOnWriteArrayList<>();

        private RecordingDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return recordingConnection(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return recordingConnection(delegate.getConnection(username, password));
        }

        private Connection recordingConnection(Connection connection) {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                        Object result = invoke(method, connection, args);
                        if (!"createStatement".equals(method.getName())
                                && !"prepareStatement".equals(method.getName())) {
                            return result;
                        }
                        if ("prepareStatement".equals(method.getName()) && args.length > 0) {
                            sql.add(String.valueOf(args[0]));
                        }
                        return Proxy.newProxyInstance(
                                method.getReturnType().getClassLoader(),
                                new Class<?>[] {method.getReturnType()},
                                (statementProxy, statementMethod, statementArgs) -> {
                                    if (statementArgs != null
                                            && ("execute".equals(statementMethod.getName())
                                                    || "executeUpdate".equals(statementMethod.getName())
                                                    || "executeQuery".equals(statementMethod.getName()))
                                            && statementArgs.length > 0) {
                                        sql.add(String.valueOf(statementArgs[0]));
                                    }
                                    return invoke(statementMethod, result, statementArgs);
                                });
                    });
        }

        private static Object invoke(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException ex) {
                throw ex.getCause();
            }
        }

        @Override
        public java.io.PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(java.io.PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public java.util.logging.Logger getParentLogger() {
            try {
                return delegate.getParentLogger();
            } catch (java.sql.SQLFeatureNotSupportedException ex) {
                return java.util.logging.Logger.getLogger(java.util.logging.Logger.GLOBAL_LOGGER_NAME);
            }
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return delegate.isWrapperFor(iface);
        }
    }
}
