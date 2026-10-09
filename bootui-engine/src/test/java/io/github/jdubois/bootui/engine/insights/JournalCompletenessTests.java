package io.github.jdubois.bootui.engine.insights;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.RuntimeChangeImpactDto;
import io.github.jdubois.bootui.core.dto.RuntimeRunChangeDto;
import io.github.jdubois.bootui.core.dto.RuntimeRunComparisonDto;
import io.github.jdubois.bootui.engine.journal.CachePayload;
import io.github.jdubois.bootui.engine.journal.ComparabilityFacts;
import io.github.jdubois.bootui.engine.journal.ExceptionPayload;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalAggregates;
import io.github.jdubois.bootui.engine.journal.JournalCompleteness;
import io.github.jdubois.bootui.engine.journal.JournalListener;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.MessagingPayload;
import io.github.jdubois.bootui.engine.journal.RestClientPayload;
import io.github.jdubois.bootui.engine.journal.RunStart;
import io.github.jdubois.bootui.engine.journal.RunSummary;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeEventPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import io.github.jdubois.bootui.engine.journal.ScheduledPayload;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.journal.SynchronousJournals;
import io.github.jdubois.bootui.engine.model.RuntimeModelService;
import io.github.jdubois.bootui.engine.model.StructureSnapshot;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.Test;

class JournalCompletenessTests {

    @Test
    void droppedSqlOnEitherSideCannotInventStatementChanges() {
        try (Capture complete = new Capture(index -> false);
                Capture lossy = new Capture(index -> index % 2 == 0)) {
            for (int i = 0; i < 3; i++) {
                complete.request("r" + i, "/orders", 1000, true);
                lossy.request("r" + i, "/orders", 1000, true);
            }
            assertThat(lossy.journal.status().dropped()).containsEntry(JournalSource.SQL, 3L);
            for (RuntimeRunComparisonDto result : List.of(compare(complete, lossy), compare(lossy, complete))) {
                assertThat(result.status()).isEqualTo("PARTIAL");
                assertThat(result.behavior())
                        .extracting(RuntimeRunChangeDto::kind)
                        .doesNotContain("new-statement", "gone-statement", "statements-per-request");
                assertThat(result.edges()).isEmpty();
                assertThat(result.limitations()).anyMatch(limit -> limit.contains("sql") && limit.contains("dropped"));
                assertThat(RuntimeInsightsAgentView.comparison(result).limitations())
                        .containsAll(result.limitations());
            }
        }
    }

    @Test
    void aDroppedHttpCompletionCannotProveADeclaredRouteWasNotExercised() {
        try (Capture capture = new Capture(index -> index == 1)) {
            capture.request("lost", "/orders", 1000, true);
            capture.request("kept", "/orders/{id}", 1000, false);
            RuntimeChangeImpactDto result = capture.impact("Orders");
            assertThat(result.observed()).extracting(row -> row.route()).containsExactly("GET /orders/{id}");
            assertThat(result.notExercised()).isEmpty();
            assertThat(result.notExercisedUndetermined()).isTrue();
            assertThat(result.limitations()).anyMatch(limit -> limit.contains("http") && limit.contains("dropped"));
            assertThat(RuntimeInsightsAgentView.impact(result).notExercisedUndetermined())
                    .isTrue();
        }
    }

    @Test
    void httpCaptureOffIsUnknownButVerifiedZeroTrafficIsNotExercised() {
        try (Capture off = new Capture(index -> false, Set.of(JournalSource.SQL), 100, 1_000_000);
                Capture on = new Capture(index -> false)) {
            RuntimeChangeImpactDto unknown = off.impact("Orders");
            assertThat(unknown.notExercised()).isEmpty();
            assertThat(unknown.notExercisedUndetermined()).isTrue();
            assertThat(unknown.limitations())
                    .anyMatch(limit -> limit.contains("http") && limit.contains("not recorded"));
            RuntimeChangeImpactDto zero = on.impact("Orders");
            assertThat(zero.notExercised()).hasSize(2);
            assertThat(zero.notExercisedUndetermined()).isFalse();
        }
    }

    @Test
    void sqlAdmissionLossDoesNotHideRealRestAndCacheChanges() {
        try (Capture before = new Capture(index -> false);
                Capture after = new Capture(index -> index % 6 == 0)) {
            for (int i = 0; i < 3; i++) {
                before.request("r" + i, "/orders", 1000, true);
                CorrelationContext context = CorrelationContext.forRequest("r" + i);
                after.offer(
                        JournalSource.SQL, 1000, context, new SqlPayload("select * from orders", null, "db", false));
                for (int child = 0; child < 2; child++) {
                    after.offer(
                            JournalSource.REST_CLIENT,
                            1000,
                            context,
                            new RestClientPayload("GET", "orders.local", "/orders", 200, "client", false));
                    after.offer(JournalSource.CACHE, 1000, context, new CachePayload("orders", "MISS", null));
                }
                after.request("r" + i, "/orders", 1000, false);
            }
            assertThat(compare(before, after).behavior())
                    .extracting(RuntimeRunChangeDto::kind)
                    .containsExactly("rest-calls-per-request", "cache-misses-per-request");
        }
    }

    @Test
    void evictionByCountOrBytesDoesNotInvalidateIncrementalEvidence() {
        for (RuntimeJournalSettings bounds : List.of(
                new RuntimeJournalSettings(true, 1, 1_000_000, 100, 0, 0, JournalSource.all()),
                new RuntimeJournalSettings(true, 100, 1, 100, 0, 0, JournalSource.all()))) {
            try (Capture before = new Capture(index -> false);
                    Capture after =
                            new Capture(index -> false, bounds.sources(), bounds.maxEvents(), bounds.maxBytes())) {
                for (int i = 0; i < 3; i++) {
                    before.request("r" + i, "/orders", 1000, false);
                    after.request("r" + i, "/orders", 1000, true);
                }
                assertThat(after.journal.status().evictedByCount()
                                + after.journal.status().evictedByBytes())
                        .isGreaterThan(0);
                assertThat(compare(before, after).behavior())
                        .extracting(RuntimeRunChangeDto::kind)
                        .contains("new-statement", "statements-per-request");
                assertThat(after.impact("Orders").observed())
                        .extracting(row -> row.route())
                        .containsExactly("GET /orders");
                assertThat(after.impact("Orders").notExercisedUndetermined()).isFalse();
            }
        }
    }

    @Test
    void clearCrossingRequestsCannotBecomeZeroSqlButFreshSameRouteRequestsRemainComparable() {
        for (boolean dispatched : List.of(false, true)) {
            try (Capture before = new Capture(index -> false);
                    Capture after = new Capture(index -> false)) {
                for (int i = 0; i < 3; i++) {
                    before.request("r" + i, "/orders", 1000, true);
                    after.offer(
                            JournalSource.SQL,
                            1000,
                            CorrelationContext.forRequest("crossing" + i),
                            new SqlPayload("select * from orders", null, "db", false));
                }
                if (dispatched) {
                    SynchronousJournals.dispatch(after.journal);
                }
                after.journal.clear();
                for (int i = 0; i < 3; i++) {
                    after.request("crossing" + i, "/orders", 1000, false);
                    after.request("fresh" + i, "/orders", after.freshStart(), true);
                }
                RuntimeRunComparisonDto result = compare(before, after);
                assertThat(result.status()).isEqualTo("PARTIAL");
                assertThat(result.behavior()).isEmpty();
                assertThat(result.edges()).isEmpty();
                assertThat(after.aggregates.snapshot().routes())
                        .singleElement()
                        .satisfies(route -> assertThat(route.requests()).isEqualTo(3));
                assertThat(result.limitations()).anyMatch(limit -> limit.contains("clear"));
                RuntimeChangeImpactDto impact = after.impact("BEAN orders");
                assertThat(impact.status()).isEqualTo(ChangeImpactService.RESOLVED);
                assertThat(impact.observed())
                        .singleElement()
                        .satisfies(route -> assertThat(route.requests()).isEqualTo(6));
                assertThat(impact.notExercised()).isEmpty();
                assertThat(impact.notExercisedUndetermined()).isTrue();
            }
        }
    }

    @Test
    void droppingHttpDoesNotInvalidateCompleteScheduledWork() {
        try (Capture before = new Capture(index -> false);
                Capture after = new Capture(index -> index == 0)) {
            after.request("lost", "/orders", 1000, false);
            for (int i = 0; i < 3; i++) {
                before.job("e" + i, 1000, 1);
                after.job("e" + i, 1000, 2);
            }
            assertThat(compare(before, after).behavior())
                    .extracting(RuntimeRunChangeDto::kind)
                    .containsExactly("statements-per-execution");
        }
    }

    @Test
    void freshScheduledWorkAfterClearRecoversFromOldSqlAdmissionLoss() {
        try (Capture before = new Capture(index -> false);
                Capture after = new Capture(index -> index == 0)) {
            after.offer(
                    JournalSource.SQL,
                    1000,
                    CorrelationContext.forExecution("lost"),
                    new SqlPayload("select * from orders", null, "db", false));
            after.journal.clear();
            for (int i = 0; i < 3; i++) {
                before.job("e" + i, 1000, 1);
                after.job("fresh" + i, after.freshStart(), 2);
            }
            assertThat(after.journal.status().dropped()).containsEntry(JournalSource.SQL, 1L);
            RuntimeRunComparisonDto result = compare(before, after);
            assertThat(result.behavior())
                    .extracting(RuntimeRunChangeDto::kind)
                    .containsExactly("statements-per-execution");
            assertThat(result.limitations()).anyMatch(limit -> limit.contains("clear"));
        }
    }

    @Test
    void aClearWithoutNewTrafficDoesNotProveWholeRunAbsence() {
        try (Capture capture = new Capture(index -> false)) {
            capture.request("r", "/orders", 1000, false);
            capture.journal.clear();
            RuntimeChangeImpactDto result = capture.impact("Orders");
            assertThat(result.observed()).isEmpty();
            assertThat(result.notExercised()).isEmpty();
            assertThat(result.notExercisedUndetermined()).isTrue();
            assertThat(result.limitations()).anyMatch(limit -> limit.contains("clear"));
        }
    }

    @Test
    void verifiedZeroLossDoesNotBecomeAnOverflowOrPartialComparison() {
        try (Capture before = new Capture(index -> false);
                Capture after = new Capture(index -> false)) {
            for (int i = 0; i < 3; i++) {
                before.request("r" + i, "/orders", 1000, true);
                after.request("r" + i, "/orders", 1000, true);
            }
            RuntimeRunComparisonDto result = compare(before, after);
            assertThat(result.status()).isEqualTo("COMPARED");
            assertThat(result.behavior()).isEmpty();
            assertThat(result.limitations()).isEmpty();
        }
    }

    @Test
    void aLossDoesNotUpgradeTooFewSamplesIntoAnAdequatePartialComparison() {
        try (Capture before = new Capture(index -> false);
                Capture after = new Capture(index -> index == 0)) {
            before.request("r", "/orders", 1000, true);
            after.request("r", "/orders", 1000, true);
            assertThat(compare(before, after).status()).isEqualTo("INSUFFICIENT");
        }
    }

    @Test
    void anEarlyGcEventBeforeAggregateRegistrationDoesNotDisableReliableHttpOrSql() {
        try (Capture before = new Capture(index -> false);
                Capture after = new Capture(index -> false)) {
            after.journal.removeListener(after.aggregates);
            after.offer(JournalSource.GC, 1000, CorrelationContext.NONE, null);
            SynchronousJournals.dispatch(after.journal);
            after.journal.addListener(after.aggregates);
            for (int i = 0; i < 3; i++) {
                before.request("r" + i, "/orders", 1000, true);
                after.request("r" + i, "/orders", 1000, true);
            }
            var comparison = compare(before, after);
            assertThat(JournalCompleteness.missed(after.aggregates.snapshot(), JournalSource.GC))
                    .isEqualTo(1);
            assertThat(comparison.status()).isEqualTo("COMPARED");
            assertThat(comparison.limitations()).isEmpty();
        }
    }

    @Test
    void anUnrelatedFailingListenerDoesNotInvalidateAggregateComparison() {
        try (Capture before = new Capture(index -> false);
                Capture after = new Capture(index -> false)) {
            after.journal.addListener(entries -> {
                throw new IllegalStateException("An unrelated UI listener failed");
            });
            for (int i = 0; i < 3; i++) {
                before.request("r" + i, "/orders", 1000, true);
                after.request("r" + i, "/orders", 1000, true);
            }
            assertThat(after.journal.status().listenerFailures()).isEqualTo(3);
            var comparison = compare(before, after);
            assertThat(comparison.status()).isEqualTo("COMPARED");
            assertThat(comparison.limitations()).isEmpty();
        }
    }

    @Test
    void missingPreBindingHttpCannotProveAbsenceAfterItsEvidenceIsEvicted() {
        try (Capture capture = new Capture(index -> false, JournalSource.all(), 1, 1_000_000)) {
            capture.journal.removeListener(capture.aggregates);
            capture.request("early", "/orders", 1000, false);
            capture.journal.addListener(capture.aggregates);
            capture.offer(JournalSource.GC, 1000, CorrelationContext.NONE, null);
            SynchronousJournals.dispatch(capture.journal);
            var impact = capture.impact("BEAN orders");
            assertThat(impact.notExercised()).isEmpty();
            assertThat(impact.notExercisedUndetermined()).isTrue();
            assertThat(impact.limitations())
                    .anyMatch(limit -> limit.contains("completeness") && limit.contains("unknown"));
            assertThat(impact.limitations()).noneMatch(limit -> limit.contains("cardinality limit"));
            RuntimeInsightsService insights =
                    new RuntimeInsightsService(capture.journal, null, null, null, null, List.of());
            insights.setDeclaredRoutes(
                    () -> List.of(new io.github.jdubois.bootui.core.dto.MappingDto(
                            "GET", "/orders", "example.Orders#list", null, null)),
                    capture.aggregates::routeLabels);
            assertThat(insights.report().notExercised()).isEmpty();
        }
    }

    @Test
    void anOverflowedExceptionSignatureIsNotNewWhenItAppearsAgainAndReliableCountersRemainUsable() {
        try (Capture before = new Capture(index -> false);
                Capture after = new Capture(index -> false)) {
            for (int i = 0; i <= JournalAggregates.MAX_EXCEPTION_GROUPS; i++) {
                before.offer(
                        JournalSource.EXCEPTION,
                        1000,
                        CorrelationContext.NONE,
                        new ExceptionPayload("group-" + i, "example.Filler", "signature-" + i));
                SynchronousJournals.dispatch(before.journal);
            }
            for (int i = 0; i < 3; i++) {
                CorrelationContext owner = CorrelationContext.forRequest("r" + i);
                ExceptionPayload same = new ExceptionPayload("displaced", "example.Failure", "displaced-signature");
                before.offer(JournalSource.EXCEPTION, 1000, owner, same);
                before.request("r" + i, "/orders", 1000, false);
                after.offer(JournalSource.EXCEPTION, 1000, owner, same);
                for (int child = 0; child < 2; child++) {
                    after.offer(
                            JournalSource.REST_CLIENT,
                            1000,
                            owner,
                            new RestClientPayload("GET", "orders.local", "/orders", 200, "client", false));
                    after.offer(JournalSource.CACHE, 1000, owner, new CachePayload("orders", "MISS", null));
                }
                after.request("r" + i, "/orders", 1000, false);
            }
            assertThat(before.aggregates.snapshot().overflowed().get("exceptionGroups"))
                    .isPositive();
            assertThat(compare(before, after).behavior())
                    .extracting(RuntimeRunChangeDto::kind)
                    .containsExactly("rest-calls-per-request", "cache-misses-per-request");
        }
    }

    @Test
    void aSignatureBeyondThePerOwnerGroupCapIsNotNewWhenTheNextRunAttributesIt() {
        try (Capture before = new Capture(index -> false);
                Capture after = new Capture(index -> false)) {
            for (int request = 0; request < 3; request++) {
                CorrelationContext owner = CorrelationContext.forRequest("r" + request);
                for (int group = 0; group < 17; group++) {
                    before.offer(
                            JournalSource.EXCEPTION,
                            1000,
                            owner,
                            new ExceptionPayload("group-" + group, "example.Failure", "signature-" + group));
                }
                before.offer(
                        JournalSource.EXCEPTION,
                        1000,
                        owner,
                        new ExceptionPayload("group-0", "example.Failure", "signature-0"));
                before.request("r" + request, "/orders", 1000, false);
                after.offer(
                        JournalSource.EXCEPTION,
                        1000,
                        owner,
                        new ExceptionPayload("group-16", "example.Failure", "signature-16"));
                for (int child = 0; child < 2; child++) {
                    after.offer(
                            JournalSource.REST_CLIENT,
                            1000,
                            owner,
                            new RestClientPayload("GET", "orders.local", "/orders", 200, "client", false));
                    after.offer(JournalSource.CACHE, 1000, owner, new CachePayload("orders", "MISS", null));
                }
                after.request("r" + request, "/orders", 1000, false);
            }
            assertThat(before.aggregates.snapshot().overflowed().get("exceptionGroups"))
                    .isZero();
            assertThat(compare(before, after).behavior())
                    .extracting(RuntimeRunChangeDto::kind)
                    .containsExactly("rest-calls-per-request", "cache-misses-per-request");
            assertThat(before.aggregates.snapshot().overflowed()).containsEntry("exceptionGroupAttributions", 3L);
        }
    }

    @Test
    void pendingHttpWorkMakesFingerprintAbsenceUnknownButCompletedRequestCountersRemainUsable() {
        for (boolean pendingBefore : List.of(false, true)) {
            try (Capture before = new Capture(index -> false);
                    Capture after = new Capture(index -> false)) {
                for (int i = 0; i < 3; i++) {
                    before.request("r" + i, "/orders", 1000, !pendingBefore);
                    after.request("r" + i, "/orders", 1000, pendingBefore);
                }
                Capture pending = pendingBefore ? before : after;
                pending.offer(
                        JournalSource.SQL,
                        1000,
                        CorrelationContext.forRequest("unfinished"),
                        new SqlPayload("select * from orders", null, "db", false));
                SynchronousJournals.dispatch(pending.journal);
                var comparison = compare(before, after);
                assertThat(comparison.behavior())
                        .extracting(RuntimeRunChangeDto::kind)
                        .containsExactly("statements-per-request");
                assertThat(comparison.edges()).isEmpty();
                assertThat(comparison.status()).isEqualTo("PARTIAL");
            }
        }
    }

    @Test
    void pendingJobsAndMessagesMakeAbsenceUnknownButCompletedExecutionCountersRemainUsable() {
        for (JournalSource source : List.of(JournalSource.SCHEDULED, JournalSource.MESSAGING)) {
            for (boolean pendingBefore : List.of(false, true)) {
                try (Capture before = new Capture(index -> false);
                        Capture after = new Capture(index -> false)) {
                    for (int i = 0; i < 3; i++) {
                        before.execution(source, "e" + i, 1000, pendingBefore ? 0 : 1);
                        after.execution(source, "e" + i, 1000, pendingBefore ? 1 : 0);
                    }
                    Capture pending = pendingBefore ? before : after;
                    pending.offer(
                            JournalSource.SQL,
                            1000,
                            CorrelationContext.forExecution("unfinished"),
                            new SqlPayload("select * from orders", null, "db", false));
                    SynchronousJournals.dispatch(pending.journal);
                    var comparison = compare(before, after);
                    assertThat(comparison.behavior())
                            .extracting(RuntimeRunChangeDto::kind)
                            .containsExactly("statements-per-execution");
                    assertThat(comparison.edges()).isEmpty();
                    assertThat(comparison.status()).isEqualTo("PARTIAL");
                }
            }
        }
    }

    @Test
    void clearCrossingJobsAndMessagesCannotInventZeroSqlWhileFreshExecutionsRemainUsable() {
        for (JournalSource source : List.of(JournalSource.SCHEDULED, JournalSource.MESSAGING)) {
            for (boolean dispatched : List.of(false, true)) {
                try (Capture before = new Capture(index -> false);
                        Capture after = new Capture(index -> false)) {
                    for (int i = 0; i < 3; i++) {
                        before.execution(source, "before" + i, 1000, 1);
                        after.offer(
                                JournalSource.SQL,
                                1000,
                                CorrelationContext.forExecution("crossing" + i),
                                new SqlPayload("select * from orders", null, "db", false));
                    }
                    if (dispatched) {
                        SynchronousJournals.dispatch(after.journal);
                    }
                    after.journal.clear();
                    for (int i = 0; i < 3; i++) {
                        after.execution(source, "crossing" + i, 1000, 0);
                        after.execution(source, "fresh" + i, after.freshStart(), 1);
                    }
                    RuntimeRunComparisonDto result = compare(before, after);
                    assertThat(result.behavior()).isEmpty();
                    assertThat(after.aggregates.snapshot().executions())
                            .singleElement()
                            .satisfies(
                                    work -> assertThat(work.stats().requests()).isEqualTo(3));
                    assertThat(result.status()).isEqualTo("PARTIAL");
                }
            }
        }
    }

    @Test
    void aNewQueueDropBeforeAggregateResetCannotDisappearAndOffersNeverWaitForClear() throws Exception {
        CountDownLatch replaced = new CountDownLatch(1);
        CountDownLatch reset = new CountDownLatch(1);
        var producer = Executors.newSingleThreadExecutor();
        RuntimeJournalSettings settings =
                new RuntimeJournalSettings(true, 100, 1_000_000, 100, 0, 0, JournalSource.all());
        try (Capture before = new Capture(index -> false);
                RuntimeJournal journal = SynchronousJournals.create(settings, index -> index == 0, queued -> {
                    replaced.countDown();
                    try {
                        assertThat(reset.await(5, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(ex);
                    }
                })) {
            JournalAggregates aggregates = new JournalAggregates();
            journal.addListener(aggregates);
            Thread clearer = new Thread(journal::clear, "test-clear");
            clearer.start();
            try {
                assertThat(replaced.await(5, TimeUnit.SECONDS)).isTrue();
                var offer = producer.submit(() -> journal.offer(RuntimeEvent.of(
                        JournalSource.SQL,
                        System.currentTimeMillis() + 1,
                        1_000_000,
                        CorrelationContext.forRequest("fresh0"),
                        "worker",
                        null,
                        false,
                        new SqlPayload("select * from orders", null, "db", false))));
                assertThat(offer.get(3, TimeUnit.SECONDS)).isFalse();
                assertThat(journal.status().dropped()).containsEntry(JournalSource.SQL, 1L);
            } finally {
                reset.countDown();
                clearer.join(5000);
            }
            assertThat(clearer.isAlive()).isFalse();
            for (int i = 0; i < 3; i++) {
                before.request("r" + i, "/orders", 1000, true);
                journal.offer(RuntimeEvent.of(
                        JournalSource.HTTP,
                        JournalCompleteness.clearAt(aggregates.snapshot()) + 1,
                        1_000_000,
                        CorrelationContext.forRequest("fresh" + i),
                        "worker",
                        null,
                        false,
                        new HttpPayload("GET", "/orders", "/orders", null, 200)));
            }
            SynchronousJournals.dispatch(journal);
            RunSummary previous =
                    RunSummary.of(before.journal.run(), before.aggregates.snapshot(), before.start(), 2000);
            var comparison = RunComparison.compare(
                    journal.run(),
                    aggregates.snapshot(),
                    before.start(),
                    previous,
                    List.of(previous.header()),
                    null,
                    null);
            assertThat(comparison.behavior()).isEmpty();
            assertThat(comparison.status()).isEqualTo("PARTIAL");
            assertThat(comparison.limitations()).anyMatch(limit -> limit.contains("sql") && limit.contains("dropped"));
        } finally {
            reset.countDown();
            producer.shutdownNow();
            assertThat(producer.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void anUnknownDurationMessageAfterClearCannotUseItsEmissionTimestampAsProofOfAFreshStart() {
        try (Capture before = new Capture(index -> false);
                Capture after = new Capture(index -> false)) {
            for (int i = 0; i < 3; i++) {
                before.execution(JournalSource.MESSAGING, "r" + i, 1000, 1);
                after.offer(
                        JournalSource.SQL,
                        1000,
                        CorrelationContext.forExecution("unknown" + i),
                        new SqlPayload("select * from orders", null, "db", false));
            }
            after.journal.clear();
            for (int i = 0; i < 3; i++) {
                after.journal.offer(RuntimeEvent.of(
                        JournalSource.MESSAGING,
                        after.freshStart(),
                        -1,
                        CorrelationContext.forExecution("unknown" + i),
                        "worker",
                        null,
                        false,
                        new MessagingPayload("kafka", false, "orders", false)));
                after.execution(JournalSource.MESSAGING, "fresh" + i, after.freshStart(), 1);
            }
            SynchronousJournals.dispatch(after.journal);
            var comparison = compare(before, after);
            assertThat(comparison.behavior()).isEmpty();
            assertThat(after.aggregates.snapshot().executions())
                    .singleElement()
                    .satisfies(work -> assertThat(work.stats().requests()).isEqualTo(3));
            assertThat(comparison.status()).isEqualTo("PARTIAL");
        }
    }

    @Test
    void aBlockedClearListenerDoesNotBlockProducerOffersOrAggregateSnapshots() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        var producer = Executors.newSingleThreadExecutor();
        try (Capture capture = new Capture(index -> false)) {
            capture.journal.addListener(new JournalListener() {
                @Override
                public void onEntries(List<io.github.jdubois.bootui.engine.journal.JournalEntry> entries) {}

                @Override
                public void onClear() {
                    entered.countDown();
                    try {
                        if (!released.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Test did not release the clear listener");
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(ex);
                    }
                }
            });
            Thread clearer = new Thread(capture.journal::clear, "test-clear-listener");
            clearer.start();
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var offer = producer.submit(() -> capture.journal.offer(RuntimeEvent.of(
                        JournalSource.SQL,
                        System.currentTimeMillis() + 1,
                        1_000_000,
                        CorrelationContext.forRequest("new"),
                        "worker",
                        null,
                        false,
                        new SqlPayload("select * from orders", null, "db", false))));
                assertThat(offer.get(3, TimeUnit.SECONDS)).isTrue();
                assertThat(producer.submit(capture.aggregates::snapshot).get(3, TimeUnit.SECONDS))
                        .isNotNull();
            } finally {
                released.countDown();
                clearer.join(5000);
            }
            assertThat(clearer.isAlive()).isFalse();
        } finally {
            released.countDown();
            producer.shutdownNow();
            assertThat(producer.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static RuntimeRunComparisonDto compare(Capture before, Capture after) {
        RunSummary previous = RunSummary.of(before.journal.run(), before.aggregates.snapshot(), before.start(), 2000);
        return RunComparison.compare(
                after.journal.run(),
                after.aggregates.snapshot(),
                after.start(),
                previous,
                List.of(previous.header()),
                null,
                null);
    }

    private static final class Capture implements AutoCloseable {
        private final RuntimeJournal journal;
        private final JournalAggregates aggregates = new JournalAggregates();

        Capture(IntPredicate drops) {
            this(drops, JournalSource.all(), 100, 1_000_000);
        }

        Capture(IntPredicate drops, Set<JournalSource> sources, int count, long bytes) {
            journal = SynchronousJournals.create(
                    new RuntimeJournalSettings(true, count, bytes, 100, 0, 0, sources), drops);
            journal.addListener(aggregates);
        }

        RunStart start() {
            return new RunStart(
                    null,
                    List.of(),
                    ComparabilityFacts.of(
                            List.of("dev"),
                            Map.of(),
                            null,
                            true,
                            journal.settings().sources()));
        }

        long freshStart() {
            return JournalCompleteness.clearAt(aggregates.snapshot()) + 1;
        }

        void request(String id, String route, long started, boolean sql) {
            CorrelationContext context = CorrelationContext.forRequest(id);
            if (sql) {
                offer(JournalSource.SQL, started, context, new SqlPayload("select * from orders", null, "db", false));
            }
            offer(JournalSource.HTTP, started, context, new HttpPayload("GET", route, route, null, 200));
            SynchronousJournals.dispatch(journal);
        }

        void offer(JournalSource source, long started, CorrelationContext context, RuntimeEventPayload payload) {
            journal.offer(RuntimeEvent.of(source, started, 1_000_000, context, "worker", null, false, payload));
        }

        void job(String id, long started, int statements) {
            execution(JournalSource.SCHEDULED, id, started, statements);
        }

        void execution(JournalSource source, String id, long started, int statements) {
            CorrelationContext context = CorrelationContext.forExecution(id);
            for (int i = 0; i < statements; i++) {
                offer(JournalSource.SQL, started, context, new SqlPayload("select * from orders", null, "db", false));
            }
            offer(
                    source,
                    started,
                    context,
                    source == JournalSource.SCHEDULED
                            ? new ScheduledPayload("Orders.run", null)
                            : new MessagingPayload("kafka", false, "orders", false));
            SynchronousJournals.dispatch(journal);
        }

        RuntimeChangeImpactDto impact(String symbol) {
            StructureSnapshot structure = new StructureSnapshot(
                    null,
                    List.of(
                            new StructureSnapshot.RouteHandler("GET /orders", "example.Orders", "list"),
                            new StructureSnapshot.RouteHandler("GET /orders/{id}", "example.Orders", "one")),
                    List.of(new StructureSnapshot.Bean("orders", "example.Orders", false, List.of())),
                    null);
            return new ChangeImpactService(
                            journal,
                            aggregates,
                            new RuntimeModelService(journal, null, run -> structure),
                            null,
                            panel -> true)
                    .impact(symbol);
        }

        @Override
        public void close() {
            journal.close();
        }
    }
}
