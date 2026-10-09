package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.core.dto.RuntimeJournalStatusDto;
import io.github.jdubois.bootui.core.dto.RuntimeRunChangeDto;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.insights.RunComparison;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.AggregatesSnapshot;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.RouteStats;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.StatementStats;
import io.github.jdubois.bootui.engine.model.ObservedEdge;
import io.github.jdubois.bootui.engine.resources.ResourceUsage;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RunSummaryTests {

    // Emitted and round-tripped by the unchanged v13 codec at 32f3a6142, before completeness metadata existed.
    private static final String VERSION_THIRTEEN =
            "QlVSUw0KbGVnYWN5LXYxMwToB9APAwAGAAABAAABA2RldgAEbm9uZQEXF2FnZW50LmNhdWdodC1leGNlcHRpb25zD2FnZW50LmV4ZWN1dG9ycwJhaQlhcHAtZXZlbnQNYXV0aG9yaXphdGlvbgVjYWNoZQpjb25uZWN0aW9uCWV4Y2VwdGlvbg9mYXVsdC10b2xlcmFuY2UCZ2MEaHR0cAlsaWZlY3ljbGUDbG9nBG1haWwJbWVzc2FnaW5nA29ybQlyZXNvdXJjZXMLcmVzdC1jbGllbnQJc2NoZWR1bGVkCHNlY3VyaXR5A3NxbAt0cmFuc2FjdGlvbgl3ZWJzb2NrZXQAFQRodHRwA3NxbAtHRVQgL29yZGVycxRzZWxlY3QgKiBmcm9tIG9yZGVycwVST1VURQVSRUFEUwVUQUJMRQZvcmRlcnMGcm91dGVzCnN0YXRlbWVudHMPZXhjZXB0aW9uR3JvdXBzFHRyYW5zYWN0aW9uYWxNZXRob2RzDnRocmVhZEZhbWlsaWVzHGNvbXBsZXRlZFJlcXVlc3RBdHRyaWJ1dGlvbnMXbGF0ZVJlcXVlc3RBdHRyaWJ1dGlvbnMVYXR0cmlidXRpb25Ub21ic3RvbmVzE3RyYWNlQWlBdHRyaWJ1dGlvbnMOdHJhY2VBaVVub3duZWQFZWRnZXMKZXhlY3V0aW9ucxZ1bmF0dHJpYnV0ZWRFeGVjdXRpb25zAgEDAgMCAYCb7gICwI23AekH6QcDAAAAAQMDAAMAAAAD8C7QDwF/AwECAwECwI23AQEEAwAAAAAAAAAAAAEAAAAAAqAf0A8BfwIAAAAAAAAAAAAAAAAAAAAAAAEEAwADuBfoBwFvAwAAAAABBQMGBwgD6AfoBw0JAAoACwAMAA0ADgAPABAAEQASABMAFAAVAAEAAA==";

    private long sequence;

    @Test
    void genuineVersionThirteenPreservesObservedFactsButCannotProveSqlDisappeared() {
        RunSummary legacy = RunSummaryCodec.decode(java.util.Base64.getDecoder().decode(VERSION_THIRTEEN));
        assertThat(legacy.header().requests()).isEqualTo(3);
        assertThat(legacy.aggregates().routes())
                .singleElement()
                .satisfies(route -> assertThat(route.statements()).containsEntry("select * from orders", 3L));
        assertThat(legacy.aggregates().overflowed().keySet()).noneMatch(key -> key.startsWith("journal:"));
        try (RuntimeJournal journal = SynchronousJournals.create(RuntimeJournalSettings.defaults(), index -> false)) {
            JournalAggregates current = new JournalAggregates();
            journal.addListener(current);
            for (int i = 0; i < 3; i++) {
                journal.offer(http("r" + i, "/orders", 200, 2_000_000));
            }
            SynchronousJournals.dispatch(journal);
            var comparison = RunComparison.compare(
                    journal.run(),
                    current.snapshot(),
                    legacy.header().runStart(),
                    legacy,
                    List.of(legacy.header()),
                    null,
                    null);
            assertThat(comparison.previous().requests()).isEqualTo(3);
            assertThat(comparison.behavior())
                    .extracting(RuntimeRunChangeDto::kind)
                    .doesNotContain("gone-statement", "statements-per-request");
            assertThat(comparison.edges()).isEmpty();
            assertThat(comparison.status()).isEqualTo("PARTIAL");
            assertThat(comparison.limitations())
                    .anyMatch(limit -> limit.contains("completeness") && limit.contains("unknown"));
        }
    }

    @Test
    void completenessPresenceDropsAndClearBoundaryRoundTripAndSurviveSummaryTrimming() {
        for (boolean cleared : List.of(false, true)) {
            for (boolean dropped : List.of(false, true)) {
                try (RuntimeJournal journal =
                        SynchronousJournals.create(RuntimeJournalSettings.defaults(), index -> dropped && index == 0)) {
                    JournalAggregates aggregates = new JournalAggregates();
                    journal.addListener(aggregates);
                    journal.offer(sql("lost", "select * from old_orders", 1_000_000, null));
                    if (cleared) {
                        journal.clear();
                    }
                    long started = journal.clearBoundary().epochMillis() + 1;
                    for (int i = 0; i < 150; i++) {
                        String route = "/orders/" + i + "/" + "long-template".repeat(8);
                        journal.offer(RuntimeEvent.of(
                                JournalSource.HTTP,
                                started,
                                1_000_000,
                                CorrelationContext.forRequest("fresh" + i),
                                "worker",
                                null,
                                false,
                                new HttpPayload("GET", route, route, null, 200)));
                    }
                    SynchronousJournals.dispatch(journal);
                    var full = aggregates.snapshot();
                    assertThat(JournalCompleteness.limited(full))
                            .as("metadata presence, source markers and a nonzero clear timestamp are not overflows")
                            .isFalse();
                    for (int bound : List.of(RunHistory.MAX_SUMMARY_BYTES, 4096)) {
                        RunSummary decoded = RunSummaryCodec.decode(
                                RunSummaryCodec.encode(RunSummary.of(journal.run(), full, 2000), bound));
                        assertThat(JournalCompleteness.verified(decoded.aggregates()))
                                .isTrue();
                        assertThat(JournalCompleteness.limited(decoded.aggregates()))
                                .isFalse();
                        assertThat(JournalCompleteness.dropped(decoded.aggregates(), JournalSource.SQL))
                                .isEqualTo(dropped ? 1 : 0);
                        assertThat(JournalCompleteness.clears(decoded.aggregates()))
                                .isEqualTo(cleared ? 1 : 0);
                        assertThat(JournalCompleteness.clearAt(decoded.aggregates()))
                                .isEqualTo(journal.clearBoundary().epochMillis());
                        assertThat(JournalCompleteness.windowComplete(decoded.aggregates(), JournalSource.SQL))
                                .isEqualTo(!dropped || cleared);
                        if (bound == 4096) {
                            assertThat(decoded.header().omittedEntries()).isPositive();
                        }
                    }
                }
            }
        }
    }

    @Test
    void aggregateFailureIsSourceSpecificPersistsAndAFreshClearRestoresWindowCompleteness() {
        try (RuntimeJournal journal = SynchronousJournals.create(RuntimeJournalSettings.defaults(), ignored -> false)) {
            JournalAggregates aggregates = new JournalAggregates();
            journal.addListener(aggregates);
            RuntimeEvent failed = sql("failed", "select * from orders", 1_000_000, null);
            aggregates.failedEntries(List.of(new JournalEntry(1, failed, failed.estimatedBytes())));
            RunSummary decoded = RunSummaryCodec.decode(RunSummaryCodec.encode(
                    RunSummary.of(journal.run(), aggregates.snapshot(), 2000), RunHistory.MAX_SUMMARY_BYTES));
            assertThat(JournalCompleteness.windowComplete(decoded.aggregates(), JournalSource.SQL))
                    .isFalse();
            assertThat(JournalCompleteness.windowComplete(decoded.aggregates(), JournalSource.HTTP))
                    .isTrue();
            assertThat(JournalCompleteness.failed(decoded.aggregates(), JournalSource.SQL))
                    .isEqualTo(1);
            journal.clear();
            assertThat(JournalCompleteness.windowComplete(aggregates.snapshot(), JournalSource.SQL))
                    .isTrue();
            assertThat(JournalCompleteness.wholeRunComplete(aggregates.snapshot(), JournalSource.SQL))
                    .isFalse();
            assertThat(JournalCompleteness.failed(aggregates.snapshot(), JournalSource.SQL))
                    .isEqualTo(1);
        }
    }

    @Test
    void perOwnerExceptionGroupAttributionLossSurvivesRoundTripAndTrimming() {
        JournalAggregates aggregates = new JournalAggregates();
        for (int group = 0; group < 17; group++) {
            publish(
                    aggregates,
                    event(
                            "owner",
                            JournalSource.EXCEPTION,
                            1_000_000,
                            new ExceptionPayload("group-" + group, "example.Failure", "signature-" + group)));
        }
        publish(aggregates, http("owner", "/orders", 200, 1_000_000));
        for (int route = 0; route < 150; route++) {
            publish(
                    aggregates,
                    http("r" + route, "/orders/" + route + "/" + "long-template".repeat(8), 200, 1_000_000));
        }
        for (int bound : List.of(RunHistory.MAX_SUMMARY_BYTES, 4096)) {
            RunSummary decoded = RunSummaryCodec.decode(
                    RunSummaryCodec.encode(RunSummary.of(RunIdentity.start(), aggregates.snapshot(), 2000), bound));
            assertThat(decoded.aggregates().overflowed()).containsEntry("exceptionGroupAttributions", 1L);
            assertThat(JournalCompleteness.exceptionSignaturesComplete(decoded.aggregates()))
                    .isFalse();
            if (bound == 4096) {
                assertThat(decoded.header().omittedEntries()).isPositive();
            }
        }
        aggregates.clear();
        assertThat(aggregates.snapshot().overflowed()).containsEntry("exceptionGroupAttributions", 0L);
        assertThat(JournalCompleteness.exceptionSignaturesComplete(aggregates.snapshot()))
                .isTrue();
    }

    @Test
    void oldVersionNineSqlLiteralsAreSanitizedOnReadAndNeverWrittenAgain() {
        // Produced by the pre-fix v9 writer with one POST and its MySQL double-quoted literal.
        byte[] encoded = java.util.Base64.getDecoder()
                .decode(
                        "QlVSUwkKbGVnYWN5LXNxbALoB9APAQACAAAAEQRodHRwA3NxbAtQT1NUIC91c2VycyppbnNlcnQgaW50byB1c2VycyhwdykgdmFsdWVzKCJ6enNlY3JldHp6IikPUmVwb3NpdG9yeS5zYXZlBVJPVVRFBldSSVRFUwVUQUJMRQV1c2VycwZyb3V0ZXMKc3RhdGVtZW50cw9leGNlcHRpb25Hcm91cHMUdHJhbnNhY3Rpb25hbE1ldGhvZHMOdGhyZWFkRmFtaWxpZXMFZWRnZXMKZXhlY3V0aW9ucxZ1bmF0dHJpYnV0ZWRFeGVjdXRpb25zAgEBAgECAYCJegLAhD3pB+oHAQAAAAEDAQABAAAAAdAP0A8BfwEBAgEBAsCEPQEEAQAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAQQBAAHoB+gHAW8BAQUBAAAAAQYDBwgJAegH6AcICgALAAwADQAOAA8AEAARAAEA");
        RunSummary decoded = RunSummaryCodec.decode(encoded);

        assertThat(JournalCompleteness.verified(decoded.aggregates())).isFalse();
        assertThat(decoded.aggregates().statements()).singleElement().satisfies(statement -> {
            assertThat(statement.fingerprint()).isEqualTo("insert into users(pw) values(?)");
            assertThat(statement.executions()).isEqualTo(1);
        });
        assertThat(decoded.aggregates().routes())
                .singleElement()
                .satisfies(route -> assertThat(route.statements())
                        .containsExactlyEntriesOf(java.util.Map.of("insert into users(pw) values(?)", 1L)));
        assertThat(new String(RunSummaryCodec.encode(decoded, RunHistory.MAX_SUMMARY_BYTES), StandardCharsets.UTF_8))
                .doesNotContain("zzsecretzz");
    }

    @Test
    void encodedSummariesMergeSafeStatementShapesAcrossRoutesExecutionsAndGlobalCounts() {
        JournalAggregates aggregates = new JournalAggregates();
        for (CorrelationContext context :
                List.of(CorrelationContext.forRequest("r1"), CorrelationContext.forExecution("e1"))) {
            for (String value : List.of("zzsecretzz", "secondSecret")) {
                boolean failed = "secondSecret".equals(value);
                publish(
                        aggregates,
                        RuntimeEvent.of(
                                JournalSource.SQL,
                                1000,
                                1_000_000,
                                context,
                                "worker",
                                null,
                                failed,
                                new SqlPayload(
                                        "insert into users(pw) values(\"" + value + "\")",
                                        failed ? "Repository.other" : "Repository.save",
                                        "db",
                                        failed)));
            }
            publish(
                    aggregates,
                    context.requestId() == null
                            ? RuntimeEvent.of(
                                    JournalSource.SCHEDULED,
                                    1001,
                                    2_000_000,
                                    context,
                                    "worker",
                                    null,
                                    false,
                                    new ScheduledPayload("UserJob.run", null))
                            : http("r1", "/users", 200, 2_000_000));
        }
        byte[] encoded = RunSummaryCodec.encode(
                RunSummary.of(new RunIdentity("sql-shapes", 2, 1000), aggregates.snapshot(), 2000),
                RunHistory.MAX_SUMMARY_BYTES);

        assertThat(encoded[4]).isEqualTo((byte) 14);
        assertThat(new String(encoded, StandardCharsets.UTF_8)).doesNotContain("zzsecretzz", "secondsecret");
        RunSummary decoded = RunSummaryCodec.decode(encoded);
        assertThat(decoded.aggregates().statements()).singleElement().satisfies(statement -> {
            assertThat(statement.fingerprint()).isEqualTo("insert into users(pw) values(?)");
            assertThat(statement.executions()).isEqualTo(4);
            assertThat(statement.failures()).isEqualTo(2);
            assertThat(statement.latency().count()).isEqualTo(4);
            assertThat(statement.callSites())
                    .containsEntry("Repository.save", 2L)
                    .containsEntry("Repository.other", 2L);
        });
        assertThat(decoded.aggregates().routes().get(0).statements())
                .containsExactlyEntriesOf(java.util.Map.of("insert into users(pw) values(?)", 2L));
        assertThat(decoded.aggregates().executions().get(0).stats().statements())
                .containsExactlyEntriesOf(java.util.Map.of("insert into users(pw) values(?)", 2L));
        assertThat(RunSummaryCodec.encode(decoded, RunHistory.MAX_SUMMARY_BYTES))
                .isEqualTo(encoded);
    }

    @Test
    void oldVersionTenTableEdgesAreMarkedIncomparableWithoutLosingOtherEdges() {
        JournalAggregates aggregates = new JournalAggregates();
        CorrelationContext request = CorrelationContext.forRequest("legacy-request");
        publish(
                aggregates,
                RuntimeEvent.of(
                        JournalSource.SQL,
                        1000,
                        1_000_000,
                        request,
                        "worker",
                        null,
                        false,
                        new SqlPayload("insert into products values (1)", null, "db", false)));
        publish(aggregates, http("legacy-request", "/audit", 200, 2_000_000));
        byte[] encoded = RunSummaryCodec.encode(
                RunSummary.of(new RunIdentity("legacy-edges", 1, 1000), aggregates.snapshot(), 2000),
                RunHistory.MAX_SUMMARY_BYTES);
        RunSummary legacy = RunSummaryCodec.decode(LegacyRunSummaries.asVersion(encoded, 10));

        JournalAggregates current = new JournalAggregates();
        publish(current, sql("current-request", "select id from products", 1_000_000, "Products.find"));
        publish(
                current,
                event(
                        "current-request",
                        JournalSource.EXCEPTION,
                        -1,
                        new ExceptionPayload("new-error", "java.lang.IllegalStateException", "s1")));
        publish(current, http("current-request", "/audit", 200, 2_000_000));

        assertThat(legacy.aggregates().overflowed()).containsKey(JournalAggregates.LEGACY_TABLE_EDGES);
        assertThat(legacy.aggregates().edges())
                .anyMatch(edge -> edge.edge().toKey().equals("products")
                        && edge.edge().type() == io.github.jdubois.bootui.engine.model.EdgeType.WRITES);
        assertThat(current.snapshot().edges())
                .anyMatch(edge -> edge.edge().toKey().equals("products")
                        && edge.edge().type() == io.github.jdubois.bootui.engine.model.EdgeType.READS);
        var diff = io.github.jdubois.bootui.engine.model.RunEdgeDiff.compare(legacy, current.snapshot(), null);
        assertThat(diff.added()).singleElement().satisfies(edge -> {
            assertThat(edge.edge().type()).isEqualTo(io.github.jdubois.bootui.engine.model.EdgeType.RAISES);
            assertThat(edge.edge().toKey()).isEqualTo("new-error");
        });
        assertThat(diff.removed()).isEmpty();
        assertThat(diff.limitations()).anyMatch(reason -> reason.contains("table reads and writes cannot be compared"));
    }

    @Test
    void versionEightRouteResourcesRemainReadableWithoutInventingAnAllocationMedian() {
        // Produced by the unchanged v8 aggregates and codec, with one resource-measured GET /old.
        byte[] encoded = java.util.Base64.getDecoder()
                .decode(
                        "QlVSUwgJb2xkLXJvdXRlAugH0A8BAAEAAAAIBGh0dHAIR0VUIC9vbGQGcm91dGVzCnN0YXRlbWVudHMPZXhjZXB0aW9uR3JvdXBzFHRyYW5zYWN0aW9uYWxNZXRob2RzDnRocmVhZEZhbWlsaWVzBWVkZ2VzAQEBAQHAhD3pB+kHAQAAAAECAQABAAAAAegH6AcBbwEAAAAAAQAAAYAgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAGAwAEAAUABgAHAAgA");
        RunSummary decoded = RunSummaryCodec.decode(encoded);
        assertThat(decoded.header().runId()).isEqualTo("old-route");
        assertThat(JournalCompleteness.verified(decoded.aggregates())).isFalse();
        assertThat(decoded.aggregates().executionsRecorded()).isFalse();
        assertThat(decoded.aggregates().routes()).singleElement().satisfies(route -> {
            assertThat(route.route()).isEqualTo("GET /old");
            assertThat(route.requests()).isEqualTo(1);
            assertThat(route.resources().measuredRequests()).isEqualTo(1);
            assertThat(route.resources().allocatedBytes()).isEqualTo(4096);
            assertThat(route.resources().allocation()).isNull();
            assertThat(route.latency().count()).isEqualTo(1);
        });
    }

    @Test
    void versionEightRemainsReadableButDoesNotInventExecutionAggregates() {
        byte[] encoded = RunSummaryCodec.encode(
                RunSummary.of(new RunIdentity("old", 1, 1), new JournalAggregates().snapshot(), 2),
                RunHistory.MAX_SUMMARY_BYTES);
        // The empty v8 layout matches the newer formats, without the header's application, up to their
        // execution-capability flag, empty list, and the absent side effects.
        byte[] withoutApplication = LegacyRunSummaries.asVersion(encoded, 8);
        byte[] old = java.util.Arrays.copyOf(withoutApplication, withoutApplication.length - 3);
        RunSummary decoded = RunSummaryCodec.decode(old);
        assertThat(decoded.header().runId()).isEqualTo("old");
        assertThat(decoded.aggregates().executionsRecorded()).isFalse();
        assertThat(decoded.aggregates().executions()).isEmpty();
        old[4] = 7;
        assertThatThrownBy(() -> RunSummaryCodec.decode(old))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("supported run summary");
    }

    @Test
    void theRunsApplicationRoundTripsAndAVersionTwelveSummaryReadsWithoutOne() {
        RunSummary summary = RunSummary.of(
                new RunIdentity("old", 1, 1), "dev:orders", new JournalAggregates().snapshot(), null, null, 2);
        byte[] encoded = RunSummaryCodec.encode(summary, RunHistory.MAX_SUMMARY_BYTES);
        assertThat(RunSummaryCodec.header(encoded).application()).isEqualTo("dev:orders");
        assertThat(RunSummaryCodec.decode(encoded).header().application()).isEqualTo("dev:orders");

        byte[] v12 = LegacyRunSummaries.asVersion(
                RunSummaryCodec.encode(
                        RunSummary.of(new RunIdentity("old", 1, 1), new JournalAggregates().snapshot(), 2),
                        RunHistory.MAX_SUMMARY_BYTES),
                12);
        RunSummary decoded = RunSummaryCodec.decode(v12);
        assertThat(decoded.header().runId()).isEqualTo("old");
        assertThat(decoded.header().application()).isNull();
        assertThat(decoded.header().comparableWith("dev:orders"))
                .as("a run an earlier BootUI kept is compared as before")
                .isTrue();
        assertThat(summary.header().comparableWith("dev:payments")).isFalse();
        assertThat(summary.header().comparableWith(null)).isTrue();
    }

    @Test
    void anApplicationKeyIsItsModeAndName() {
        assertThat(RunSummary.applicationKey("test", " orders ")).isEqualTo("test:orders");
        assertThat(RunSummary.applicationKey(null, "orders")).isEqualTo("dev:orders");
        assertThat(RunSummary.applicationKey("dev", " ")).isNull();
    }

    @Test
    void executionWorkAndMedianAllocationRoundTripWithTheirBoundedCounts() {
        JournalAggregates aggregates = new JournalAggregates();
        for (int i = 0; i < 3; i++) {
            CorrelationContext context = CorrelationContext.forExecution("job-" + i);
            publish(
                    aggregates,
                    RuntimeEvent.of(
                            JournalSource.SQL,
                            1000,
                            1_000_000,
                            context,
                            "worker",
                            null,
                            false,
                            new SqlPayload("select * from orders", null, "db", false)));
            publish(
                    aggregates,
                    RuntimeEvent.of(
                            JournalSource.SCHEDULED,
                            1000,
                            2_000_000,
                            context,
                            "worker",
                            null,
                            false,
                            new ScheduledPayload("OrderJob.run", null)));
            publish(aggregates, http("r-" + i, "/allocated", 200, 1_000_000));
        }
        RunSummary decoded = RunSummaryCodec.decode(RunSummaryCodec.encode(
                RunSummary.of(RunIdentity.start(), aggregates.snapshot(), 2), RunHistory.MAX_SUMMARY_BYTES));
        assertThat(decoded.aggregates().executionsRecorded()).isTrue();
        assertThat(decoded.aggregates().executions()).singleElement().satisfies(work -> {
            assertThat(work.source()).isEqualTo(JournalSource.SCHEDULED);
            assertThat(work.stats().requests()).isEqualTo(3);
            assertThat(work.stats().statements()).containsEntry("select * from orders", 3L);
        });
        assertThat(decoded.aggregates().routes().get(0).resources().allocation().percentileMicros(50))
                .isBetween(4096L, 4096L + 256);
    }

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
                        new ExceptionPayload("g1", "java.lang.IllegalStateException", "s1")));
        publish(aggregates, event("r1", JournalSource.CONNECTION, 9_000_000, new ConnectionPayload("db", 2_000, 1)));
        publish(
                aggregates,
                event(
                        "r1",
                        JournalSource.ORM,
                        8_000_000,
                        new OrmPayload(null, 2, 3_000_000, 1, 0, 1, 400_000, 2, 600_000, 1, 25, 0, 0, 0)));
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
        assertThat(decoded.header().events()).isEqualTo(9);
        assertThat(decoded.header().omittedEntries()).isZero();
        assertThat(decoded.header().omittedEdges()).isZero();
        assertThat(decoded.header().encodedBytes()).isEqualTo(encoded.length).isLessThan(1_024);
        assertThat(RunSummaryCodec.header(encoded)).isEqualTo(decoded.header());

        AggregatesSnapshot copy = decoded.aggregates();
        assertThat(copy.run()).isEqualTo(original.run());
        assertThat(copy.overflowed()).isEqualTo(original.overflowed());
        assertThat(copy.exceptionGroups()).isEqualTo(original.exceptionGroups());
        assertThat(copy.exceptionGroups().get(0).signature()).isEqualTo("s1");
        assertThat(copy.threadFamilies()).isEqualTo(original.threadFamilies());
        assertThat(original.edges())
                .extracting(edge -> edge.edge().type() + " " + edge.edge().toKey() + " " + edge.count())
                .containsExactlyInAnyOrder("READS orders 2", "RAISES g1 1");
        assertThat(copy.edges()).isEqualTo(original.edges());
        RouteStats route = copy.routes().get(0);
        RouteStats originalRoute = original.routes().get(0);
        assertThat(route.route()).isEqualTo("GET /api/orders/{id}");
        assertThat(route.statusClasses()).isEqualTo(originalRoute.statusClasses());
        assertThat(route.childCounts()).isEqualTo(originalRoute.childCounts());
        assertThat(route.childNanos()).isEqualTo(originalRoute.childNanos());
        assertThat(route.statements()).isEqualTo(originalRoute.statements());
        assertThat(route.connectionWaitNanos()).isEqualTo(2_000);
        assertThat(route.resources()).usingRecursiveComparison().isEqualTo(originalRoute.resources());
        assertSameHistogram(
                route.resources().allocation(), originalRoute.resources().allocation());
        assertThat(route.resources().cpuNanos()).isEqualTo(11_500_000);
        assertSameHistogram(route.latency(), originalRoute.latency());
        assertThat(originalRoute.warmLatency().count())
                .as("the route's first request is its cold one")
                .isEqualTo(1);
        assertSameHistogram(route.warmLatency(), originalRoute.warmLatency());
        assertThat(route.cacheMisses()).isEqualTo(originalRoute.cacheMisses());
        assertThat(route.aiTokens()).isEqualTo(originalRoute.aiTokens());
        assertThat(originalRoute.orm().requests()).isEqualTo(1);
        assertThat(route.orm().flushes()).isEqualTo(1);
        assertThat(route.orm().autoFlushes()).isEqualTo(2);
        assertThat(route.orm().entities()).isEqualTo(25);
        assertSameHistogram(route.orm().time(), originalRoute.orm().time());
        assertThat(route.orm().time().count()).isEqualTo(1);
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
        assertThat(kept.header().omittedEdges())
                .isPositive()
                .isEqualTo(full.edges().size() - kept.aggregates().edges().size());
        long fewestObservationsKept = kept.aggregates().edges().stream()
                .mapToLong(ObservedEdge::count)
                .min()
                .orElseThrow();
        long mostObservationsLeftOut = full.edges().stream()
                .filter(edge -> !kept.aggregates().edges().contains(edge))
                .mapToLong(ObservedEdge::count)
                .max()
                .orElseThrow();
        assertThat(fewestObservationsKept)
                .as("the most observed edges are kept")
                .isGreaterThanOrEqualTo(mostObservationsLeftOut);
    }

    @Test
    void encodedSummariesExcludeTruncatedSqlLiteralValuesAndTableLikeWords() {
        String secret = "sëcrét-table";
        JournalAggregates aggregates = new JournalAggregates();
        publish(aggregates, sql("r1", "select * from orders where note = $$join " + secret, 1_000_000, null));
        publish(aggregates, http("r1", "/api/orders", 200, 2_000_000));

        byte[] encoded = RunSummaryCodec.encode(
                RunSummary.of(RunIdentity.start(), aggregates.snapshot(), 3_000), RunHistory.MAX_SUMMARY_BYTES);
        RunSummary decoded = RunSummaryCodec.decode(encoded);

        assertThat(new String(encoded, StandardCharsets.UTF_8)).doesNotContain(secret);
        assertThat(decoded.aggregates().statements())
                .extracting(StatementStats::fingerprint)
                .containsExactly("select * from orders where note = ?");
        assertThat(decoded.aggregates().edges())
                .extracting(edge -> edge.edge().toKey())
                .containsExactly("orders");
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
                new HttpPayload(
                        "GET",
                        route,
                        route,
                        null,
                        status,
                        new ResourceUsage(nanos / 2, 4_096, 1, 0, null, 1, List.of(), false)));
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
