package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.core.dto.RuntimeRunComparisonDto;
import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.insights.RunComparison;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.AggregatesSnapshot;
import io.github.jdubois.bootui.engine.journal.SqlPayload.Provenance;
import io.github.jdubois.bootui.engine.model.NodeType;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.engine.sqltrace.SqlTracingProxies;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class SqlAggregateProvenanceTests {

    private static final RunStart START = new RunStart(
            null,
            List.of(),
            ComparabilityFacts.of(
                    List.of("dev"), Map.of("db", "jdbc:h2:mem:orders"), null, false, JournalSource.all()));

    @Test
    void excludedSqlRemainsInTheJournalButDoesNotManufactureAggregateExecutions() {
        try (Run run = new Run()) {
            run.request(
                    sql("select * from prepared", 0, Provenance.PREPARATION),
                    sql("update unknown_target set n = ?", 1_000_000, Provenance.UNKNOWN),
                    sql("select * from executed", 0, Provenance.EXECUTION));

            var snapshot = run.aggregates.snapshot();
            assertThat(run.journal.entries()).hasSize(4);
            assertThat(snapshot.run().events().getOrDefault(JournalSource.SQL, 0L))
                    .isEqualTo(1);
            assertThat(snapshot.run().nanos().getOrDefault(JournalSource.SQL, 0L))
                    .isZero();
            assertThat(snapshot.statements()).singleElement().satisfies(statement -> {
                assertThat(statement.fingerprint()).contains("executed");
                assertThat(statement.executions()).isEqualTo(1);
            });
            assertThat(snapshot.routes()).singleElement().satisfies(route -> {
                assertThat(route.childCounts().getOrDefault(JournalSource.SQL, 0L))
                        .isEqualTo(1);
                assertThat(route.statements()).hasSize(1);
            });
            assertThat(snapshot.edges())
                    .filteredOn(edge -> edge.edge().toType() == NodeType.TABLE)
                    .singleElement()
                    .satisfies(edge -> assertThat(edge.edge().toKey()).isEqualTo("executed"));
        }
    }

    @Test
    void processingPreparationsDoesNotInventMissesOnRebindAndRealMissesStillCount() {
        try (Run run = new Run()) {
            run.request(sql("select * from prepared", 0, Provenance.PREPARATION));
            run.journal.removeListener(run.aggregates);
            run.journal.addListener(run.aggregates);
            assertThat(run.aggregates.snapshot().overflowed().getOrDefault("journal:missed:sql", 0L))
                    .isZero();

            run.journal.removeListener(run.aggregates);
            run.request(sql("select * from missed", 0, Provenance.EXECUTION));
            run.journal.addListener(run.aggregates);
            assertThat(run.aggregates.snapshot().overflowed().getOrDefault("journal:missed:sql", 0L))
                    .isEqualTo(1);

            run.journal.clear();
            run.request(
                    sql("select * from prepared", 0, Provenance.PREPARATION),
                    sql("select * from executed", 0, Provenance.EXECUTION));
            run.journal.removeListener(run.aggregates);
            run.journal.addListener(run.aggregates);
            var metadata = run.aggregates.snapshot().overflowed();
            assertThat(metadata.getOrDefault("journal:window-missed:sql", 0L)).isZero();
            assertThat(metadata.getOrDefault("journal:missed:sql", 0L)).isEqualTo(1);
            assertThat(run.aggregates.snapshot().run().events().getOrDefault(JournalSource.SQL, 0L))
                    .isEqualTo(1);
        }
    }

    @Test
    void preparationOnlyAgainstExecutedSqlCannotInventRemovalReductionOrNoveltyInEitherDirection() {
        try (Run executed = new Run();
                Run prepared = new Run()) {
            for (int i = 0; i < 3; i++) {
                executed.request(sql("select * from orders", 0, Provenance.EXECUTION));
                prepared.request(sql("select * from orders", 0, Provenance.PREPARATION));
            }

            assertUnqualifiedSql(compare(executed, prepared));
            assertUnqualifiedSql(compare(prepared, executed));
        }
    }

    @Test
    void mixedConfirmedExecutionsRemainUsableWhileUnqualifiedSqlComparisonsDoNotHideOtherDimensions() {
        try (Run before = new Run();
                Run after = new Run()) {
            for (int i = 0; i < 3; i++) {
                before.request(
                        sql("select * from orders", 0, Provenance.EXECUTION),
                        sql("select * from prepared_only", 0, Provenance.PREPARATION),
                        rest(),
                        cache());
                after.request(
                        sql("select * from orders", 0, Provenance.EXECUTION),
                        sql("select * from unverified_only", 1_000_000, Provenance.UNKNOWN),
                        rest(),
                        rest(),
                        cache(),
                        cache());
            }

            assertThat(before.aggregates.snapshot().run().events().getOrDefault(JournalSource.SQL, 0L))
                    .isEqualTo(3);
            assertThat(after.aggregates.snapshot().run().events().getOrDefault(JournalSource.SQL, 0L))
                    .isEqualTo(3);
            RuntimeRunComparisonDto comparison = compare(before, after);
            assertUnqualifiedSql(comparison);
            assertThat(comparison.behavior())
                    .extracting(change -> change.kind())
                    .contains("rest-calls-per-request", "cache-misses-per-request");
        }
    }

    @Test
    void journalCompletenessWithoutExplicitSqlQualificationDoesNotCertifyExecutionCoverage() {
        try (Run before = new Run();
                Run after = new Run()) {
            for (int i = 0; i < 3; i++) {
                before.request(sql("select * from orders", 0, Provenance.EXECUTION));
                after.request(
                        sql("select * from orders", 0, Provenance.EXECUTION),
                        sql("select * from added", 0, Provenance.EXECUTION));
            }
            AggregatesSnapshot current = after.aggregates.snapshot();
            AggregatesSnapshot original = before.aggregates.snapshot();
            Map<String, Long> legacyMetadata = new LinkedHashMap<>(original.overflowed());
            legacyMetadata.keySet().removeIf(key -> key.startsWith("journal:sql-"));
            AggregatesSnapshot legacy = new AggregatesSnapshot(
                    original.routes(),
                    original.statements(),
                    original.exceptionGroups(),
                    original.transactionalMethods(),
                    original.threadFamilies(),
                    original.edges(),
                    original.run(),
                    legacyMetadata,
                    original.executions(),
                    original.executionsRecorded());
            assertThat(legacy.overflowed().get("journal:verified")).isEqualTo(1L);
            assertThat(legacy.overflowed().get("journal:source:sql")).isEqualTo(1L);

            assertUnqualifiedSql(compare(legacy, current));
            assertUnqualifiedSql(compare(current, legacy));
        }
    }

    @Test
    void genuinePreProvenanceV14WriterFixtureRemainsUnknownForSqlInBothDirections() throws Exception {
        byte[] encoded;
        try (var fixture = getClass().getResourceAsStream("/journal/pre-provenance-v14.bin")) {
            assertThat(fixture).isNotNull();
            encoded = fixture.readAllBytes();
        }
        assertThat(java.util.HexFormat.of()
                        .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                                .digest(encoded)))
                .isEqualTo("c2ca7edcb5cde77ef3297fa6c2f04098432098914c3ed8452b39ce331dc58735");
        RunSummary legacy = RunSummaryCodec.decode(encoded);
        assertThat(legacy.aggregates().overflowed()).containsEntry("journal:verified", 1L);
        assertThat(legacy.aggregates().run().events()).containsEntry(JournalSource.SQL, 3L);
        assertThat(legacy.aggregates().overflowed().keySet()).noneMatch(key -> key.startsWith("journal:sql-"));
        try (Run current = new Run()) {
            for (int i = 0; i < 3; i++) {
                current.tracedJdbcRequest("select * from orders", "select * from added");
            }
            AggregatesSnapshot qualified = completeClosedWorldJdbcFixture(current);
            assertUnqualifiedSql(compare(legacy.aggregates(), qualified));
            assertUnqualifiedSql(compare(qualified, legacy.aggregates()));
        }
    }

    @Test
    void boundedSummaryRoundtripPreservesSqlQualificationInsteadOfCertifyingUnverifiedZero() {
        try (Run run = new Run()) {
            for (int i = 0; i < 3; i++) {
                run.request(sql("select * from prepared", 0, Provenance.PREPARATION));
            }
            AggregatesSnapshot original = run.aggregates.snapshot();
            Map<String, Long> qualification = new LinkedHashMap<>();
            original.overflowed().forEach((key, value) -> {
                if (key.startsWith("journal:sql-")) {
                    qualification.put(key, value);
                }
            });
            assertThat(qualification).isNotEmpty();
            RunSummary summary = RunSummary.of(new RunIdentity("run", 1, 1), original, START, 2);
            RunSummary decoded = RunSummaryCodec.decode(RunSummaryCodec.encode(summary, RunHistory.MAX_SUMMARY_BYTES));

            assertThat(decoded.aggregates().overflowed()).containsAllEntriesOf(qualification);
            assertThat(decoded.aggregates().run().events().getOrDefault(JournalSource.SQL, 0L))
                    .isZero();
        }
    }

    @Test
    void a4096ByteSummaryActuallyTrimsEntriesWithoutTrimmingSqlQualification() {
        try (Run run = new Run()) {
            run.request(sql("select * from prepared", 0, Provenance.PREPARATION));
            for (int i = 0; i < 128; i++) {
                run.request(sql(
                        "select a_rather_long_column_name, another_long_column_name from table_" + i + " where id = ?",
                        0,
                        Provenance.EXECUTION));
            }
            AggregatesSnapshot original = run.aggregates.snapshot();
            Map<String, Long> qualification = new LinkedHashMap<>();
            original.overflowed().forEach((key, value) -> {
                if (key.startsWith("journal:sql-")) {
                    qualification.put(key, value);
                }
            });
            assertThat(qualification).isNotEmpty();
            RunSummary summary = RunSummary.of(new RunIdentity("run", 1, 1), original, START, 2);
            byte[] full = RunSummaryCodec.encode(summary, RunHistory.MAX_SUMMARY_BYTES);
            byte[] bounded = RunSummaryCodec.encode(summary, 4096);
            RunSummary decoded = RunSummaryCodec.decode(bounded);

            assertThat(full.length).isGreaterThan(4096);
            assertThat(bounded.length).isLessThanOrEqualTo(4096);
            assertThat(decoded.header().omittedEntries()).isPositive();
            assertThat(decoded.aggregates().statements())
                    .hasSizeLessThan(original.statements().size());
            assertThat(decoded.aggregates().overflowed()).containsAllEntriesOf(qualification);
            assertThat(decoded.aggregates().run()).isEqualTo(original.run());
        }
    }

    @Test
    void confirmedExecutionsOutsideRegisteredScopeStayPositiveButCannotInventComparisons() throws Exception {
        for (String outside : java.util.Arrays.asList("dbB", null, "")) {
            try (Run before = new Run();
                    Run after = new Run()) {
                for (int i = 0; i < 3; i++) {
                    before.tracedJdbcRequest("select * from orders");
                    after.tracedJdbcRequest("select * from orders");
                    before.request(new Child(
                            JournalSource.SQL, 0, new SqlPayload("select * from outside_scope", null, outside, false)));
                    after.request();
                }
                assertThat(before.aggregates.snapshot().run().events()).containsEntry(JournalSource.SQL, 6L);
                assertThat(before.aggregates.snapshot().statements())
                        .anyMatch(statement -> statement.fingerprint().contains("outside_scope"));
                assertUnqualifiedSql(compare(before, after));
                assertUnqualifiedSql(compare(after, before));
            }
        }
    }

    @Test
    void fullyCoveredExecutionOnlyJdbcKeepsRateNoveltyAndRemovalComparisons() throws Exception {
        try (Run before = new Run();
                Run after = new Run()) {
            for (int i = 0; i < 3; i++) {
                before.tracedJdbcRequest("select * from orders");
                after.tracedJdbcRequest("select * from orders", "select * from added");
            }

            AggregatesSnapshot then = completeClosedWorldJdbcFixture(before);
            AggregatesSnapshot now = completeClosedWorldJdbcFixture(after);
            RuntimeRunComparisonDto increased = compare(then, now);
            assertThat(increased.status()).isEqualTo("COMPARED");
            assertThat(increased.behavior())
                    .extracting(change -> change.kind())
                    .contains("new-statement", "statements-per-request");
            assertThat(increased.behavior())
                    .filteredOn(change -> change.kind().equals("statements-per-request"))
                    .singleElement()
                    .satisfies(change -> {
                        assertThat(change.before()).isEqualTo(1.0);
                        assertThat(change.after()).isEqualTo(2.0);
                        assertThat(change.change()).isEqualTo("INCREASED");
                    });
            assertThat(increased.edges())
                    .anyMatch(change ->
                            change.change().equals("ADDED") && change.sentence().contains("table `added`"));

            RuntimeRunComparisonDto reduced = compare(now, then);
            assertThat(reduced.status()).isEqualTo("COMPARED");
            assertThat(reduced.behavior())
                    .extracting(change -> change.kind())
                    .contains("gone-statement", "statements-per-request");
            assertThat(reduced.behavior())
                    .filteredOn(change -> change.kind().equals("statements-per-request"))
                    .singleElement()
                    .satisfies(change -> {
                        assertThat(change.before()).isEqualTo(2.0);
                        assertThat(change.after()).isEqualTo(1.0);
                        assertThat(change.change()).isEqualTo("DECREASED");
                    });
            assertThat(reduced.edges())
                    .anyMatch(change -> change.change().equals("REMOVED")
                            && change.sentence().contains("table `added`"));
        }
    }

    private static AggregatesSnapshot completeClosedWorldJdbcFixture(Run run) {
        AggregatesSnapshot original = run.aggregates.snapshot();
        assertThat(run.journal.entries())
                .filteredOn(entry -> entry.event().payload() instanceof SqlPayload)
                .isNotEmpty()
                .allSatisfy(entry -> assertThat(((SqlPayload) entry.event().payload()).executed())
                        .isTrue());
        assertThat(original.overflowed())
                .containsEntry("journal:sql-provenance", 1L)
                .containsEntry("journal:sql-execution-coverage", 1L);
        assertThat(JournalCompleteness.sqlExecutionScope(original))
                .singleElement()
                .asString()
                .hasSize(64);
        return original;
    }

    private static void assertUnqualifiedSql(RuntimeRunComparisonDto comparison) {
        assertThat(comparison.status()).isEqualTo("PARTIAL");
        assertThat(comparison.behavior())
                .noneMatch(change ->
                        change.kind().contains("statement") || change.kind().contains("sql"));
        assertThat(comparison.edges()).noneMatch(change -> change.sentence().contains("table `"));
        assertThat(comparison.limitations())
                .anyMatch(limit -> limit.toLowerCase(java.util.Locale.ROOT).contains("sql")
                        && limit.toLowerCase(java.util.Locale.ROOT).contains("execution"));
    }

    private static RuntimeRunComparisonDto compare(Run before, Run after) {
        return compare(before.aggregates.snapshot(), after.aggregates.snapshot());
    }

    private static RuntimeRunComparisonDto compare(AggregatesSnapshot before, AggregatesSnapshot after) {
        RunSummary previous = RunSummary.of(new RunIdentity("before", 1, 1), before, START, 2);
        return RunComparison.compare(new RunIdentity("after", 2, 3), after, START, previous, List.of(), null, null);
    }

    private static Child sql(String text, long nanos, Provenance provenance) {
        return new Child(
                JournalSource.SQL, nanos, new SqlPayload(text, null, "db", false, null, null, 5, 0, provenance));
    }

    private static Child rest() {
        return new Child(
                JournalSource.REST_CLIENT,
                1_000_000,
                new RestClientPayload("GET", "inventory:8080", "/items", 200, "RestClient", false));
    }

    private static Child cache() {
        return new Child(JournalSource.CACHE, 0, new CachePayload("prices", "MISS"));
    }

    private record Child(JournalSource source, long nanos, RuntimeEventPayload payload) {}

    private static final class Run implements AutoCloseable {

        private final RuntimeJournal journal = new RuntimeJournal(
                new RuntimeJournalSettings(true, 1_000, 10_000_000, 1_000, 10, 10, JournalSource.all()),
                RunIdentity.start(),
                false);
        private final JournalAggregates aggregates = new JournalAggregates();
        private int requests;

        private Run() {
            journal.addListener(aggregates);
        }

        private void tracedJdbcRequest(String... statements) throws Exception {
            DataSource source = mock(DataSource.class);
            Connection physical = mock(Connection.class);
            when(source.getConnection()).thenReturn(physical);
            for (String sql : statements) {
                PreparedStatement statement = mock(PreparedStatement.class);
                when(physical.prepareStatement(sql)).thenReturn(statement);
                when(statement.executeQuery()).thenReturn(mock(ResultSet.class));
            }
            SqlTraceRecorder recorder = new SqlTraceRecorder(true, true, false, false, 10, 100, 2_000, 200, 5);
            recorder.setRuntimeEventSink(journal);
            DataSource traced = SqlTracingProxies.wrapNamed(source, recorder, "db");
            CorrelationContext context = CorrelationContext.forRequest("r" + (++requests));
            long start = System.currentTimeMillis();
            try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(context);
                    Connection connection = traced.getConnection()) {
                for (String sql : statements) {
                    try (PreparedStatement statement = connection.prepareStatement(sql);
                            ResultSet result = statement.executeQuery()) {
                        assertThat(result).isNotNull();
                    }
                }
            }
            journal.offer(RuntimeEvent.of(
                    JournalSource.HTTP,
                    start,
                    10_000_000,
                    context,
                    "worker",
                    null,
                    false,
                    new HttpPayload("GET", "/orders", "/orders", null, 200)));
            journal.dispatchPending();
        }

        private void request(Child... children) {
            CorrelationContext context = CorrelationContext.forRequest("r" + (++requests));
            long start = System.currentTimeMillis() + 1_000;
            for (Child child : children) {
                journal.offer(RuntimeEvent.of(
                        child.source(), start, child.nanos(), context, "worker", null, false, child.payload()));
            }
            journal.offer(RuntimeEvent.of(
                    JournalSource.HTTP,
                    start,
                    10_000_000,
                    context,
                    "worker",
                    null,
                    false,
                    new HttpPayload("GET", "/orders", "/orders", null, 200)));
            journal.dispatchPending();
        }

        @Override
        public void close() {
            journal.close();
        }
    }
}
