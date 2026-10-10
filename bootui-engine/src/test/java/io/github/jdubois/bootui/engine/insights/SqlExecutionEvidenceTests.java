package io.github.jdubois.bootui.engine.insights;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalEntry;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.JournalStatus;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.journal.SqlPayload.Provenance;
import io.github.jdubois.bootui.engine.model.ObservedEdges;
import io.github.jdubois.bootui.engine.sqltrace.RouteTemplateResolver;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.github.jdubois.bootui.spi.ThreadKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SqlExecutionEvidenceTests {

    @Test
    void mixedFeedersCannotTurnPreparationIntoBlockingOrZeroDurationExecutionIntoPreparation() {
        for (InsightsStack stack : List.of(InsightsStack.SPRING_WEBFLUX, InsightsStack.QUARKUS)) {
            InsightsSnapshot snapshot = snapshot(
                    stack,
                    sql("select * from orders", 0, Provenance.PREPARATION),
                    sql("select * from orders", 0, Provenance.EXECUTION),
                    sql("select * from orders", 1_000_000, Provenance.EXECUTION),
                    sql("select * from orders", 1_000_000, Provenance.UNKNOWN));

            Observation.Evaluation blocking = new EventLoopBlocking().evaluate(snapshot);

            assertThat(blocking.findings()).singleElement().satisfies(finding -> {
                assertThat(finding.sentence()).contains("2 JDBC statements");
                assertThat(finding.rows()).hasSize(2);
            });
            assertThat(blocking.uncounted()).contains("2 SQL captures", "did not establish JDBC execution");
        }
    }

    @Test
    void zeroDurationExecutedDmlIsNotAnOrmPreparationOnAnyStack() {
        for (InsightsStack stack : InsightsStack.values()) {
            Observation.Evaluation writes = new SafeMethodDml()
                    .evaluate(snapshot(stack, sql("insert into audit values (?)", 0, Provenance.EXECUTION)));

            assertThat(writes.findings()).singleElement().satisfies(finding -> {
                assertThat(finding.sentence()).contains(" executed ").doesNotContain(" prepared ");
                assertThat(finding.columns()).contains("Executions");
            });
        }
    }

    @Test
    void preparationAndUnknownProvenanceEstablishNeitherRepeatedExecutionsNorTableAccess() {
        for (Provenance provenance : List.of(Provenance.PREPARATION, Provenance.UNKNOWN)) {
            List<RuntimeEvent> events = new ArrayList<>();
            events.add(sql("select * from orders", 0, provenance));
            for (int i = 0; i < 6; i++) {
                events.add(sql("select * from lines where order_id = ?", 0, provenance));
            }
            InsightsSnapshot snapshot = snapshot(InsightsStack.QUARKUS, events.toArray(RuntimeEvent[]::new));

            assertThat(new EventLoopBlocking().evaluate(snapshot).findings()).isEmpty();
            assertThat(new RepeatedSelects().evaluate(snapshot).findings()).isEmpty();
            assertThat(ObservedEdges.targets(sql("select * from orders", 1_000, provenance)))
                    .isEmpty();
            assertThat(ObservedEdges.targets(sql("update orders set n = ?", 1_000, provenance)))
                    .isEmpty();
        }
        assertThat(ObservedEdges.targets(sql("select * from orders", 0, Provenance.EXECUTION)))
                .hasSize(1);
        assertThat(ObservedEdges.targets(sql("update orders set n = ?", 0, Provenance.EXECUTION)))
                .hasSize(1);
    }

    private static RuntimeEvent sql(String text, long duration, Provenance provenance) {
        return RuntimeEvent.of(
                JournalSource.SQL,
                1_000,
                duration,
                CorrelationContext.forRequest("r1"),
                null,
                "event-loop",
                ThreadKind.EVENT_LOOP,
                false,
                new SqlPayload(text, "Repo.run:1", "db", false, null, null, 5, 0, provenance));
    }

    private static InsightsSnapshot snapshot(InsightsStack stack, RuntimeEvent... sql) {
        List<JournalEntry> entries = new ArrayList<>();
        for (RuntimeEvent event : sql) {
            entries.add(new JournalEntry(entries.size() + 1, event, event.estimatedBytes()));
        }
        RuntimeEvent http = RuntimeEvent.of(
                JournalSource.HTTP,
                1_000,
                5_000_000,
                CorrelationContext.forRequest("r1"),
                "event-loop",
                null,
                false,
                new HttpPayload("GET", "/orders", "/orders", null, 200));
        entries.add(new JournalEntry(entries.size() + 1, http, http.estimatedBytes()));
        JournalStatus status = new JournalStatus(
                true,
                "instance",
                "run",
                entries.size(),
                entries.size(),
                0,
                0,
                0,
                100,
                10_000_000,
                0,
                0,
                0,
                0,
                null,
                1_000L,
                0,
                100,
                Map.of(),
                Map.of(),
                0,
                0);
        return InsightsSnapshot.of(
                entries, status, RouteTemplateResolver.empty(), source -> true, source -> true, stack, null);
    }
}
