package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.core.dto.RuntimeJournalStatusDto;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.AggregatesSnapshot;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.RouteStats;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.StatementStats;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RunSummaryTests {

    private long sequence;

    @Test
    void aSummaryRoundTripsEveryAggregateWithItsHistograms() {
        JournalAggregates aggregates = new JournalAggregates();
        publish(aggregates, sql("r1", "select * from orders where id = ?", 2_000_000, "OrderRepository.find:42"));
        publish(aggregates, sql("r1", "select * from orders where id = ?", 7_000_000, "OrderRepository.find:42"));
        publish(
                aggregates,
                event(
                        "r1",
                        JournalSource.EXCEPTION,
                        -1,
                        new ExceptionPayload("g1", "java.lang.IllegalStateException")));
        publish(aggregates, event("r1", JournalSource.CONNECTION, 9_000_000, new ConnectionPayload("db", 2_000, 1)));
        publish(aggregates, http("r1", "/api/orders/{id}", 500, 20_000_000));
        publish(aggregates, http("r2", "/api/orders/{id}", 200, 3_000_000));
        publish(aggregates, event(null, JournalSource.SCHEDULED, 5_000_000, null));
        publish(
                aggregates,
                event(null, JournalSource.TRANSACTION, 4_000_000, new TransactionPayload("OrderService.pay", true)));
        AggregatesSnapshot original = aggregates.snapshot();
        RunIdentity run = RunIdentity.start();

        byte[] encoded = RunSummaryCodec.encode(RunSummary.of(run, original, 5_000), RunHistory.MAX_SUMMARY_BYTES);
        RunSummary decoded = RunSummaryCodec.decode(encoded);

        assertThat(decoded.header().runId()).isEqualTo(run.id());
        assertThat(decoded.header().ordinal()).isEqualTo(run.ordinal());
        assertThat(decoded.header().startedAtEpochMillis()).isEqualTo(run.startedAtEpochMillis());
        assertThat(decoded.header().endedAtEpochMillis()).isEqualTo(5_000);
        assertThat(decoded.header().requests()).isEqualTo(2);
        assertThat(decoded.header().failedRequests()).isEqualTo(1);
        assertThat(decoded.header().events()).isEqualTo(8);
        assertThat(decoded.header().omittedEntries()).isZero();
        assertThat(decoded.header().encodedBytes()).isEqualTo(encoded.length).isLessThan(1_024);
        assertThat(RunSummaryCodec.header(encoded)).isEqualTo(decoded.header());

        AggregatesSnapshot copy = decoded.aggregates();
        assertThat(copy.run()).isEqualTo(original.run());
        assertThat(copy.overflowed()).isEqualTo(original.overflowed());
        assertThat(copy.exceptionGroups()).isEqualTo(original.exceptionGroups());
        assertThat(copy.threadFamilies()).isEqualTo(original.threadFamilies());
        RouteStats route = copy.routes().get(0);
        RouteStats originalRoute = original.routes().get(0);
        assertThat(route.route()).isEqualTo("GET /api/orders/{id}");
        assertThat(route.statusClasses()).isEqualTo(originalRoute.statusClasses());
        assertThat(route.childCounts()).isEqualTo(originalRoute.childCounts());
        assertThat(route.childNanos()).isEqualTo(originalRoute.childNanos());
        assertThat(route.statements()).isEqualTo(originalRoute.statements());
        assertThat(route.connectionWaitNanos()).isEqualTo(2_000);
        assertSameHistogram(route.latency(), originalRoute.latency());
        StatementStats statement = copy.statements().get(0);
        assertThat(statement.callSites()).containsEntry("OrderRepository.find:42", 2L);
        assertSameHistogram(statement.latency(), original.statements().get(0).latency());
        assertThat(copy.transactionalMethods()).singleElement().satisfies(method -> {
            assertThat(method.method()).isEqualTo("OrderService.pay");
            assertThat(method.rollbacks()).isEqualTo(1);
            assertSameHistogram(
                    method.latency(), original.transactionalMethods().get(0).latency());
        });
    }

    @Test
    void aSummaryBeyondItsBoundKeepsTheMostUsedEntriesAndCountsWhatItLeftOut() {
        JournalAggregates aggregates = new JournalAggregates();
        for (int route = 0; route < JournalAggregates.MAX_ROUTES; route++) {
            for (int request = 0; request <= route % 7; request++) {
                String requestId = "r" + route + "-" + request;
                for (int statement = 0; statement < 4; statement++) {
                    publish(
                            aggregates,
                            sql(
                                    requestId,
                                    "select a_rather_long_column_name, another_long_column_name from table_" + route
                                            + "_" + statement + " where id = ?",
                                    1_000L * (request + 1) * (statement + 1),
                                    "com.example.repository.Repository" + route + ".find:" + statement));
                }
                publish(aggregates, http(requestId, "/api/resource-" + route + "/{id}", 200, 1_000_000L * route));
            }
        }
        AggregatesSnapshot full = aggregates.snapshot();
        int bound = 64 * 1024;

        byte[] encoded = RunSummaryCodec.encode(RunSummary.of(RunIdentity.start(), full, 1), bound);
        RunSummary kept = RunSummaryCodec.decode(encoded);

        assertThat(encoded.length).isLessThanOrEqualTo(bound);
        assertThat(kept.header().omittedEntries()).isPositive();
        assertThat(kept.aggregates().routes())
                .isNotEmpty()
                .hasSizeLessThan(full.routes().size());
        long fewestKept = kept.aggregates().routes().stream()
                .mapToLong(RouteStats::requests)
                .min()
                .orElseThrow();
        long mostLeftOut = full.routes().stream()
                .filter(route -> kept.aggregates().routes().stream()
                        .noneMatch(candidate -> candidate.route().equals(route.route())))
                .mapToLong(RouteStats::requests)
                .max()
                .orElseThrow();
        assertThat(fewestKept).as("the most used routes are kept").isGreaterThanOrEqualTo(mostLeftOut);
        assertThat(kept.aggregates().run()).as("run totals are never trimmed").isEqualTo(full.run());
    }

    @Test
    void theHistoryKeepsTheNewestRunsAndReplacesARunRecordedAgain() {
        RunHistory history = new RunHistory(2, RunHistory.MAX_SUMMARY_BYTES, null);
        RunIdentity first = RunIdentity.start();
        RunIdentity second = RunIdentity.start();
        RunIdentity third = RunIdentity.start();
        AggregatesSnapshot empty = new JournalAggregates().snapshot();

        history.record(RunSummary.of(first, empty, 1));
        history.record(RunSummary.of(second, empty, 2));
        history.record(RunSummary.of(second, empty, 3));
        assertThat(history.headers()).extracting(RunSummary.Header::runId).containsExactly(second.id(), first.id());
        assertThat(history.headers().get(0).endedAtEpochMillis()).isEqualTo(3);

        history.record(RunSummary.of(third, empty, 4));
        assertThat(history.headers()).extracting(RunSummary.Header::runId).containsExactly(third.id(), second.id());
        assertThat(history.summaries()).hasSize(2);
        assertThat(history.bytes()).isPositive();
    }

    @Test
    void aSummaryThatCannotFitIsSkippedWithoutFailingTheRunsEnd() {
        RunHistory history = new RunHistory(5, 8, null);

        history.record(RunSummary.of(RunIdentity.start(), new JournalAggregates().snapshot(), 1));

        assertThat(history.headers()).isEmpty();
        assertThatThrownBy(() -> RunSummaryCodec.decode(new byte[] {1, 2, 3, 4, 5}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void closingTheJournalProcessesItsQueuedEventsThenRecordsTheRunOnce() {
        RunIdentity run = RunIdentity.start();
        RuntimeJournal journal = new RuntimeJournal(
                new RuntimeJournalSettings(true, 100, 1_000_000, 100, 10, 10, JournalSource.all()), run, false);
        JournalAggregates aggregates = new JournalAggregates();
        RunHistory history = new RunHistory(5, RunHistory.MAX_SUMMARY_BYTES, null);
        aggregates.recordRunIn(history, run);
        journal.addListener(aggregates);
        AtomicInteger closes = new AtomicInteger();
        journal.addListener(new JournalListener() {
            @Override
            public void onEntries(List<JournalEntry> entries) {}

            @Override
            public void onClose() {
                closes.incrementAndGet();
            }
        });
        journal.offer(sql("r1", "select 1", 1_000_000, null));
        journal.offer(http("r1", "/api/orders", 200, 2_000_000));

        journal.close();
        journal.close();

        assertThat(closes).hasValue(1);
        assertThat(history.headers()).singleElement().satisfies(header -> {
            assertThat(header.runId()).isEqualTo(run.id());
            assertThat(header.requests())
                    .as("the queued request was processed before the summary")
                    .isEqualTo(1);
            assertThat(header.events()).isEqualTo(2);
        });
    }

    @Test
    void aDisabledJournalKeepsNoSummary() {
        RunIdentity run = RunIdentity.start();
        RuntimeJournal journal = new RuntimeJournal(RuntimeJournalSettings.disabled(), run, false);
        JournalAggregates aggregates = new JournalAggregates();
        RunHistory history = new RunHistory(5, RunHistory.MAX_SUMMARY_BYTES, null);
        aggregates.recordRunIn(history, run);
        journal.addListener(aggregates);

        journal.close();

        assertThat(history.headers()).isEmpty();
    }

    @Test
    void theStatusListsThePreviousRunsButNeverTheCurrentOne() {
        RunIdentity previous = RunIdentity.start();
        RunIdentity current = RunIdentity.start();
        RuntimeJournal journal = new RuntimeJournal(
                new RuntimeJournalSettings(true, 100, 1_000_000, 100, 10, 10, JournalSource.all()), current, false);
        RunHistory history = new RunHistory(5, RunHistory.MAX_SUMMARY_BYTES, null);
        JournalAggregates ended = new JournalAggregates();
        publish(ended, http("r1", "/api/orders", 503, 1_000_000));
        history.record(RunSummary.of(previous, ended.snapshot(), 42));
        history.record(RunSummary.of(current, new JournalAggregates().snapshot(), 43));

        RuntimeJournalStatusDto status = new RuntimeJournalService(journal, new JournalAggregates(), history).status();

        assertThat(status.previousRunsUnavailable()).isNull();
        assertThat(status.previousRuns()).singleElement().satisfies(run -> {
            assertThat(run.runId()).isEqualTo(previous.id());
            assertThat(run.ordinal()).isEqualTo(previous.ordinal());
            assertThat(run.endedAt()).isEqualTo(42);
            assertThat(run.requests()).isEqualTo(1);
            assertThat(run.failedRequests()).isEqualTo(1);
            assertThat(run.summaryBytes()).isPositive();
        });
        journal.close();
    }

    @Test
    void aHistoryLoadedByAReloadableClassLoaderSaysItKeepsNoPreviousRun() {
        assertThat(RunHistory.reloadableReason(RunHistory.SPRING_RESTART_CLASS_LOADER, "restart"))
                .contains("spring.devtools.restart.include");
        assertThat(RunHistory.reloadableReason(
                        RunHistory.QUARKUS_CLASS_LOADER, "Quarkus Runtime ClassLoader: DEV restart no:3"))
                .contains("quarkus.class-loading.reloadable-artifacts");
        assertThat(RunHistory.reloadableReason(
                        RunHistory.QUARKUS_CLASS_LOADER, "Quarkus Base Runtime ClassLoader: DEV"))
                .isNull();
        assertThat(RunHistory.reloadableReason("jdk.internal.loader.ClassLoaders$AppClassLoader", "app"))
                .isNull();
        assertThat(RunHistory.shared().unavailableReason())
                .as("the engine's tests load it with the application class loader")
                .isNull();

        RuntimeJournal journal = new RuntimeJournal(RuntimeJournalSettings.disabled(), RunIdentity.start(), false);
        RuntimeJournalStatusDto status = new RuntimeJournalService(
                        journal, null, new RunHistory(5, 1_024, "BootUI reloads with the application."))
                .status();
        assertThat(status.previousRunsUnavailable()).isEqualTo("BootUI reloads with the application.");
    }

    private static void assertSameHistogram(LatencyHistogram actual, LatencyHistogram expected) {
        assertThat(actual.count()).isEqualTo(expected.count());
        assertThat(actual.totalMicros()).isEqualTo(expected.totalMicros());
        assertThat(actual.maxMicros()).isEqualTo(expected.maxMicros());
        for (int bucket = 0; bucket < LatencyHistogram.BUCKETS; bucket++) {
            assertThat(actual.bucketCount(bucket)).isEqualTo(expected.bucketCount(bucket));
        }
    }

    private void publish(JournalAggregates aggregates, RuntimeEvent event) {
        List<JournalEntry> batch = new ArrayList<>();
        batch.add(new JournalEntry(++sequence, event, event.estimatedBytes()));
        aggregates.onEntries(batch);
    }

    private static RuntimeEvent http(String requestId, String route, int status, long nanos) {
        return RuntimeEvent.of(
                JournalSource.HTTP,
                1_000,
                nanos,
                CorrelationContext.forRequest(requestId),
                "http-nio-8080-exec-1",
                null,
                status >= 500,
                new HttpPayload("GET", route, route, null, status));
    }

    private static RuntimeEvent sql(String requestId, String fingerprint, long nanos, String callSite) {
        return event(requestId, JournalSource.SQL, nanos, new SqlPayload(fingerprint, callSite, "dataSource", false));
    }

    private static RuntimeEvent event(String requestId, JournalSource source, long nanos, RuntimeEventPayload payload) {
        return RuntimeEvent.of(
                source,
                1_000,
                nanos,
                requestId == null ? CorrelationContext.NONE : CorrelationContext.forRequest(requestId),
                requestId == null ? "scheduling-1" : "http-nio-8080-exec-1",
                null,
                false,
                payload);
    }
}
