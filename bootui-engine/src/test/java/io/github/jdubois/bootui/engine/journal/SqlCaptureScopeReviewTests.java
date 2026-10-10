package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SqlCaptureScopeReviewTests {

    @Test
    void manyRegistrationsAreBoundedInBothRegistriesAndRemainExplicitlyUnqualified() throws Exception {
        boundedRegistrations(false);
    }

    @Test
    void oversizedRegistrationsAreNotRetainedOrTruncatedIntoComparableScope() throws Exception {
        boundedRegistrations(true);
    }

    @Test
    void registrationBeforeSinkReplaysItsActualScopeWithoutNeedingAnExecution() {
        try (RuntimeJournal journal = journal()) {
            SqlTraceRecorder recorder = recorder();
            recorder.registerCaptureSource("dbA", SqlPayload.Provenance.EXECUTION);
            recorder.setRuntimeEventSink(journal);
            JournalAggregates aggregates = new JournalAggregates();
            journal.addListener(aggregates);
            assertThat(JournalCompleteness.sqlExecutionQualified(aggregates.snapshot()))
                    .isTrue();
            assertThat(journal.entries()).isEmpty();
            recorder.registerCaptureSource("orm", SqlPayload.Provenance.PREPARATION);
            assertThat(JournalCompleteness.sqlExecutionQualified(aggregates.snapshot()))
                    .isFalse();
        }
    }

    private static void boundedRegistrations(boolean oversized) throws Exception {
        try (RuntimeJournal journal = journal()) {
            SqlTraceRecorder recorder = recorder();
            for (int i = 0; i < 300; i++) {
                String name = "db-" + i + (oversized ? "x".repeat(10_000) : "");
                recorder.registerCaptureSource(name, SqlPayload.Provenance.EXECUTION);
                journal.registerSqlCapture(name, SqlPayload.Provenance.EXECUTION);
                recorder.registerCaptureSource("orm-" + name, SqlPayload.Provenance.PREPARATION);
                journal.registerSqlCapture("orm-" + name, SqlPayload.Provenance.PREPARATION);
            }
            assertBoundedRegistry(recorder);
            assertBoundedRegistry(journal);
            recorder.setRuntimeEventSink(journal);
            JournalAggregates aggregates = new JournalAggregates();
            journal.addListener(aggregates);
            var snapshot = aggregates.snapshot();
            assertThat(JournalCompleteness.sqlExecutionQualified(snapshot)).isFalse();
            assertThat(snapshot.overflowed()).containsEntry("journal:sql-scope-incomplete", 1L);
            byte[] encoded =
                    RunSummaryCodec.encode(RunSummary.of(new RunIdentity("scope-bounds", 1, 1), snapshot, 2), 4096);
            assertThat(encoded.length).isLessThanOrEqualTo(4096);
            var decoded = RunSummaryCodec.decode(encoded).aggregates();
            assertThat(decoded.overflowed()).containsEntry("journal:sql-scope-incomplete", 1L);
            assertThat(JournalCompleteness.sqlExecutionQualified(decoded)).isFalse();
        }
    }

    private static void assertBoundedRegistry(Object registry) throws Exception {
        List<String> names = new ArrayList<>();
        for (Field field : registry.getClass().getDeclaredFields()) {
            if (List.of("executionSources", "preparationSources", "sqlExecutionSources", "sqlPreparationSources")
                    .contains(field.getName())) {
                field.setAccessible(true);
                for (Object name : (Collection<?>) field.get(registry)) {
                    names.add((String) name);
                }
            } else if (field.getName().equals("sqlCaptureScopes")) {
                field.setAccessible(true);
                Object scopes = field.get(registry);
                Field sources = scopes.getClass().getDeclaredField("sources");
                sources.setAccessible(true);
                for (Object name : ((Map<?, ?>) sources.get(scopes)).keySet()) {
                    names.add((String) name);
                }
            }
        }
        assertThat(names.size()).isLessThanOrEqualTo(64);
        assertThat(names).allSatisfy(name -> assertThat(name.length()).isLessThanOrEqualTo(256));
        assertThat(names.stream().mapToLong(String::length).sum()).isLessThanOrEqualTo(64L * 256);
    }

    private static RuntimeJournal journal() {
        return new RuntimeJournal(
                new RuntimeJournalSettings(true, 100, 1_000_000, 100, 10, 10, JournalSource.all()),
                RunIdentity.start(),
                false);
    }

    private static SqlTraceRecorder recorder() {
        return new SqlTraceRecorder(true, true, false, false, 10, 100, 2_000, 200, 5);
    }
}
