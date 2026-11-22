package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.RequestJournalProfileDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightsReportDto;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.insights.InsightsStack;
import io.github.jdubois.bootui.engine.insights.SafeMethodDml;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import io.github.jdubois.bootui.spi.ThreadKind;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DurableJournalArchiveServiceTests {

    private static final AtomicInteger DATABASES = new AtomicInteger();
    private static final long DAY = 24L * 60 * 60 * 1_000;
    private static final ExposurePolicy MASKED = new ExposurePolicy() {
        @Override
        public ValueExposure valueExposure() {
            return ValueExposure.MASKED;
        }

        @Override
        public boolean maskSecrets() {
            return true;
        }
    };

    @Test
    void projectsTypedHistoryProfilesAndBoundedMultiRunInsightsAcrossAuthorizedScopes() {
        Fixture fixture = fixture();
        DurableJournalScope previous = new DurableJournalScope("alpha", "orders", "run-day-1");
        DurableJournalScope interrupted = new DurableJournalScope("alpha", "orders", "run-day-2");
        DurableJournalScope billing = new DurableJournalScope("alpha", "billing", "run-day-2");
        DurableJournalScope otherNamespace = new DurableJournalScope("beta", "orders", "run-day-1");

        List<JournalEntry> interruptedEntries = List.of(
                http(1, "request-2", 2 * DAY, "/orders/42", "/orders/{id}", 500),
                sql(2, "request-2", 2 * DAY + 2, "delete from orders where id = 42"),
                exception(3, "request-2", 2 * DAY + 3, null));
        persist(fixture, previous, 1 * DAY, 1, DurableJournalRunMetadata.Phase.END, List.of());
        persist(fixture, interrupted, 2 * DAY, 2, DurableJournalRunMetadata.Phase.CHECKPOINT, interruptedEntries);
        persist(
                fixture,
                billing,
                2 * DAY + 1,
                3,
                DurableJournalRunMetadata.Phase.END,
                List.of(http(1, "billing-1", 2 * DAY + 1, "/billing", "/billing", 200)));
        persist(
                fixture,
                otherNamespace,
                DAY,
                4,
                DurableJournalRunMetadata.Phase.END,
                List.of(http(1, "beta-1", DAY, "/orders", "/orders", 200)));
        fixture.store()
                .upsertDetail(DurableJournalDetail.request(
                        interrupted, 1, "GET", "/orders/{id}", "OrderController.get", 500));
        fixture.store()
                .upsertDetail(DurableJournalDetail.exception(
                        interrupted,
                        3,
                        "group-2",
                        "java.lang.IllegalStateException",
                        "signature-2",
                        List.of("java.lang.IllegalStateException", "java.lang.RuntimeException"),
                        List.of("example.OrderService.get(OrderService.java:42)"),
                        List.of("java.lang.RuntimeException"),
                        1));
        fixture.store()
                .upsertDetail(
                        DurableJournalDetail.request(otherNamespace, 1, "GET", "/orders", "OrderController.list", 200));

        DurableJournalArchiveService alpha =
                DurableJournalArchiveService.forNamespace(fixture.store(), MASKED, "alpha");
        assertThat(alpha.listRuns("alpha", "orders", 10))
                .extracting(run -> run.scope().run())
                .containsExactly("run-day-2", "run-day-1");
        assertThat(alpha.queryFeed(DurableJournalFeedQuery.globalFirstPage()).entries())
                .extracting(item -> item.origin().scope().namespace())
                .containsOnly("alpha");
        assertThatThrownBy(() -> alpha.queryFeed(DurableJournalFeedQuery.firstPage(otherNamespace)))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> alpha.listRuns("beta", "orders")).isInstanceOf(SecurityException.class);

        RequestJournalProfileDto profile = alpha.profile(interrupted, "request-2");
        assertThat(profile.available()).isTrue();
        assertThat(profile.route()).startsWith("GET /orders/{id}");
        assertThat(profile.timeline())
                .extracting(item -> item.label())
                .anyMatch(label -> label.contains("IllegalStateException"));
        assertThat(profile.timeline()).extracting(item -> item.source()).contains("sql", "exception");
        assertThat(profile.notes()).anyMatch(note -> note.contains("end snapshot"));
        assertThat(profile.toString()).doesNotContain("secret-message");

        RuntimeInsightsReportDto report = alpha.insights(interrupted);
        assertThat(report.checks())
                .filteredOn(check -> SafeMethodDml.KIND.equals(check.kind()))
                .singleElement()
                .extracting(check -> check.eligibleRequests())
                .isEqualTo(1L);
        assertThat(report.limitations())
                .anyMatch(limit -> limit.contains("interrupted"))
                .anyMatch(limit -> limit.contains("current BootUI agent"));

        assertThatThrownBy(() -> alpha.profile(otherNamespace, "beta-1"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("outside authorized namespace alpha");
        assertThatThrownBy(() -> alpha.multiRunInsights(List.of(interrupted, otherNamespace)))
                .isInstanceOf(SecurityException.class);

        DurableJournalArchiveService cross = DurableJournalArchiveService.crossNamespaceReader(fixture.store(), MASKED);
        DurableJournalFeedPage global = cross.queryFeed(DurableJournalFeedQuery.globalFirstPage());
        assertThat(global.entries())
                .extracting(item -> item.origin().scope())
                .contains(interrupted, billing, otherNamespace)
                .doesNotContain(previous);
        DurableJournalArchiveReport multi = cross.multiRunInsights(List.of(interrupted, otherNamespace));
        assertThat(multi.runs()).extracting(run -> run.origin().scope()).containsExactly(interrupted, otherNamespace);
        assertThat(multi.runs().get(0).completeness().metadataPhase())
                .isEqualTo(DurableJournalRunMetadata.Phase.CHECKPOINT);
        assertThat(multi.runs().get(0).completeness().complete()).isFalse();
        assertThat(multi.runs().get(1).insights().available()).isTrue();
        assertThat(multi.runs().get(1).completeness().complete()).isTrue();
        assertThat(cross.profile(otherNamespace, "beta-1").available()).isTrue();

        DurableJournalArchiveService defaultReader = new DurableJournalArchiveService(fixture.store(), MASKED);
        assertThat(defaultReader.profile(interrupted, "request-2").available()).isTrue();
        assertThatThrownBy(() -> defaultReader.profile(otherNamespace, "beta-1"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("outside authorized namespace alpha");
    }

    @Test
    void readsDetailsOncePerHistoricalProfileAndInsightsReport() throws SQLException {
        Fixture fixture = fixture();
        DurableJournalScope scope = new DurableJournalScope("local", "orders", "run-details");
        persist(
                fixture,
                scope,
                3 * DAY,
                5,
                DurableJournalRunMetadata.Phase.END,
                List.of(http(1, "request-3", 3 * DAY, "/orders/42", null, 200)));
        fixture.store()
                .upsertDetail(
                        DurableJournalDetail.request(scope, 1, "POST", "/orders/{id}", "OtherController.get", 500));
        DurableJournalArchiveService service =
                DurableJournalArchiveService.forNamespace(fixture.store(), MASKED, "local");
        try (var connection = fixture.dataSource().getConnection();
                var statement = connection.createStatement()) {
            statement.execute("SET QUERY_STATISTICS TRUE");
        }

        RequestJournalProfileDto profile = service.profile(scope, "request-3");
        long profileSelects = detailSelects(fixture);
        RuntimeInsightsReportDto report = service.insights(scope);
        long insightSelects = detailSelects(fixture) - profileSelects;

        assertThat(profile.available()).isTrue();
        assertThat(profile.route()).isEqualTo("GET /orders/{id} (OrderController.get)");
        assertThat(profile.status()).isEqualTo(200);
        assertThat(profile.notes()).noneMatch(reason -> reason.contains("detail rows are absent"));
        assertThat(report.available()).isTrue();
        assertThat(report.limitations()).noneMatch(reason -> reason.contains("detail rows are absent"));
        assertThat(List.of(profileSelects, insightSelects))
                .as("detail SELECTs for historical profile and Insights, respectively")
                .containsExactly(1L, 1L);
    }

    @ParameterizedTest
    @EnumSource(DurableJournalDetailKind.class)
    void warnsWhenEitherKindOfSafeDetailIsMissing(DurableJournalDetailKind missing) {
        Fixture fixture = fixture();
        DurableJournalScope scope = new DurableJournalScope("local", "orders", "missing-details");
        persist(
                fixture,
                scope,
                3 * DAY,
                5,
                DurableJournalRunMetadata.Phase.END,
                List.of(
                        http(1, "request-3", 3 * DAY, "/orders", "/orders", 500),
                        exception(2, "request-3", 3 * DAY, "java.lang.IllegalStateException")));
        if (missing == DurableJournalDetailKind.REQUEST) {
            fixture.store()
                    .upsertDetail(DurableJournalDetail.exception(
                            scope,
                            2,
                            "group-2",
                            "java.lang.IllegalStateException",
                            null,
                            List.of(),
                            List.of(),
                            List.of(),
                            1));
        } else {
            fixture.store().upsertDetail(DurableJournalDetail.request(scope, 1, "GET", "/orders", null, 500));
        }
        DurableJournalArchiveService service =
                DurableJournalArchiveService.forNamespace(fixture.store(), MASKED, "local");

        RequestJournalProfileDto profile = service.profile(scope, "request-3");
        assertThat(profile.available()).isTrue();
        assertThat(profile.notes()).anyMatch(reason -> reason.contains("detail rows are absent"));
        DurableJournalArchiveReport report = service.multiRunInsights(List.of(scope));
        assertThat(report.runs()).singleElement().satisfies(run -> {
            assertThat(run.completeness().complete()).isFalse();
            assertThat(run.completeness().limitations()).anyMatch(reason -> reason.contains("detail rows are absent"));
            assertThat(run.insights().available()).isTrue();
            assertThat(run.insights().limitations()).anyMatch(reason -> reason.contains("detail rows are absent"));
        });
    }

    @Test
    void missingAndCorruptMetadataFailClearly() {
        Fixture fixture = fixture();
        DurableJournalScope missing = new DurableJournalScope("local", "orders", "missing-metadata");
        fixture.store().upsertRun(new DurableJournalRun(missing, 10, 11, "STOPPED", null, 0, "instance"));
        DurableJournalArchiveService service =
                DurableJournalArchiveService.forNamespace(fixture.store(), MASKED, "local");
        assertThatThrownBy(() -> service.insights(missing))
                .isInstanceOf(DurableJournalUnavailableException.class)
                .hasMessageContaining("metadata is missing");

        DurableJournalScope corrupt = new DurableJournalScope("local", "orders", "corrupt-metadata");
        persist(fixture, corrupt, 4 * DAY, 6, DurableJournalRunMetadata.Phase.END, List.of());
        try (var connection = fixture.dataSource().getConnection();
                var statement =
                        connection.prepareStatement("UPDATE " + fixture.store().runsTableName()
                                + " SET metadata_payload = ?"
                                + " WHERE namespace_id = ? AND application_id = ? AND run_id = ?")) {
            statement.setBytes(1, new byte[] {1, 2, 3});
            statement.setString(2, corrupt.namespace());
            statement.setString(3, corrupt.application());
            statement.setString(4, corrupt.run());
            statement.executeUpdate();
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
        assertThatThrownBy(() -> service.insights(corrupt))
                .isInstanceOf(DurableJournalStoreException.class)
                .hasMessageContaining("metadata is malformed")
                .hasMessageContaining("corrupt-metadata");
    }

    @Test
    void durableCaptureLossMakesACompletedRunIncomplete() {
        Fixture fixture = fixture();
        DurableJournalScope scope = new DurableJournalScope("alpha", "orders", "run-loss");
        persist(
                fixture,
                scope,
                5 * DAY,
                7,
                DurableJournalRunMetadata.Phase.END,
                List.of(http(1, "request-loss", 5 * DAY, "/orders", "/orders", 200)));
        DurableJournalRun run = fixture.store().findRun(scope);
        DurableJournalRunMetadata metadata =
                fixture.store().findRunMetadata(scope).withCaptureLoss(1, 2, 0);
        fixture.store()
                .upsertRun(new DurableJournalRun(
                        run.scope(),
                        run.startedAtEpochMillis(),
                        run.updatedAtEpochMillis(),
                        run.status(),
                        run.metadata(),
                        run.checkpoint(),
                        run.instanceId(),
                        metadata));

        DurableJournalArchiveReport report = DurableJournalArchiveService.forNamespace(fixture.store(), MASKED, "alpha")
                .multiRunInsights(List.of(scope));

        assertThat(report.runs()).singleElement().satisfies(result -> {
            assertThat(result.completeness().complete()).isFalse();
            assertThat(result.insights().limitations())
                    .anyMatch(reason -> reason.contains("dropped 1") && reason.contains("quarantined 2"));
        });
    }

    private static long detailSelects(Fixture fixture) throws SQLException {
        try (var connection = fixture.dataSource().getConnection();
                var statement = connection.prepareStatement(
                        "SELECT COALESCE(SUM(EXECUTION_COUNT), 0) FROM INFORMATION_SCHEMA.QUERY_STATISTICS"
                                + " WHERE LOWER(SQL_STATEMENT) LIKE ?")) {
            statement.setString(1, "select % from archive_details %");
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static void persist(
            Fixture fixture,
            DurableJournalScope scope,
            long startedAt,
            int ordinal,
            DurableJournalRunMetadata.Phase phase,
            List<JournalEntry> entries) {
        RuntimeJournalSettings settings =
                new RuntimeJournalSettings(true, 1_000, 1_000_000, 100, 10, 10, JournalSource.all());
        JournalStatus status = new JournalStatus(
                true,
                "instance-" + ordinal,
                scope.run(),
                entries.size(),
                entries.size(),
                entries.stream().mapToLong(JournalEntry::estimatedBytes).sum(),
                0,
                0,
                settings.maxEvents(),
                settings.maxBytes(),
                0,
                0,
                0,
                0,
                null,
                entries.isEmpty()
                        ? null
                        : entries.stream()
                                .mapToLong(entry -> entry.event().epochMillis())
                                .min()
                                .orElseThrow(),
                0,
                settings.queueCapacity(),
                Map.of(),
                Map.of(),
                0,
                0);
        JournalAggregates aggregates = new JournalAggregates();
        aggregates.onEntries(entries);
        RunSummary summary = RunSummary.of(
                new RunIdentity(scope.run(), ordinal, startedAt),
                "dev:" + scope.application(),
                aggregates.snapshot(),
                null,
                null,
                startedAt + 10);
        DurableJournalRunMetadata.SqlCaptureQualification sql =
                entries.stream().anyMatch(entry -> entry.event().payload() instanceof SqlPayload)
                        ? new DurableJournalRunMetadata.SqlCaptureQualification(1, 0, false, "a".repeat(64), null)
                        : new DurableJournalRunMetadata.SqlCaptureQualification(0, 0, false, null, null);
        DurableJournalRunMetadata metadata = new DurableJournalRunMetadata(
                phase,
                startedAt + 10,
                "instance-" + ordinal,
                "process-" + ordinal,
                InsightsStack.SPRING_MVC,
                settings,
                status,
                null,
                null,
                List.of(),
                List.of(),
                Map.of(),
                sql,
                Map.of("orders", 10),
                new DurableJournalRunMetadata.DeclaredRoutes(2, false, null),
                phase == DurableJournalRunMetadata.Phase.END ? summary : null);
        fixture.store()
                .upsertRun(new DurableJournalRun(
                        scope,
                        startedAt,
                        startedAt + 10,
                        phase == DurableJournalRunMetadata.Phase.END ? "STOPPED" : "INTERRUPTED",
                        null,
                        entries.size(),
                        "instance-" + ordinal,
                        metadata));
        fixture.store().appendBatch(scope, entries);
    }

    private static JournalEntry http(
            long sequence, String requestId, long timestamp, String path, String route, int status) {
        RuntimeEvent event = new RuntimeEvent(
                JournalSource.HTTP,
                timestamp,
                5_000_000,
                requestId,
                null,
                "trace-" + requestId,
                "http-nio-1",
                ThreadKind.WORKER,
                status >= 400,
                new HttpPayload("GET", path, route, "OrderController.get", status));
        return new JournalEntry(sequence, event, event.estimatedBytes());
    }

    private static JournalEntry sql(long sequence, String requestId, long timestamp, String sql) {
        RuntimeEvent event = new RuntimeEvent(
                JournalSource.SQL,
                timestamp,
                1_000_000,
                requestId,
                null,
                "trace-" + requestId,
                "worker",
                ThreadKind.WORKER,
                false,
                new SqlPayload(sql, "OrderRepository.change", "orders", false));
        return new JournalEntry(sequence, event, event.estimatedBytes());
    }

    private static JournalEntry exception(long sequence, String requestId, long timestamp, String exceptionClass) {
        RuntimeEvent event = new RuntimeEvent(
                JournalSource.EXCEPTION,
                timestamp,
                -1,
                requestId,
                null,
                "trace-" + requestId,
                "http-nio-1",
                ThreadKind.WORKER,
                true,
                new ExceptionPayload("group-2", exceptionClass, null, List.of()));
        return new JournalEntry(sequence, event, event.estimatedBytes());
    }

    private static Fixture fixture() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:durable-archive-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1");
        JdbcDurableJournalStore store = new JdbcDurableJournalStore(dataSource, "archive");
        store.verifySchema();
        return new Fixture(dataSource, store);
    }

    private record Fixture(JdbcDataSource dataSource, JdbcDurableJournalStore store) {}
}
