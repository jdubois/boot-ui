package io.github.jdubois.bootui.engine.threads;

import static io.github.jdubois.bootui.engine.threads.ThreadAnalysisFixtures.row;
import static io.github.jdubois.bootui.engine.threads.ThreadAnalysisFixtures.snapshot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.CapturedThreadDto;
import io.github.jdubois.bootui.engine.action.ActionBusyException;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import io.github.jdubois.bootui.spi.McpPanelPolicy;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ThreadAnalysisServiceTests {
    @Test
    void hidingBootUiFiltersBeforePagingWithoutRemovingOwnerEvidenceOrExports() {
        try (Harness h = new Harness()) {
            h.sources.add(snapshot(
                    1000,
                    row(1, "bootui-owner", "RUNNABLE", null, "app.Owner"),
                    row(2, "application", "BLOCKED", 1L, "app.Wait"),
                    row(3, "application-2", "RUNNABLE", null, "app.Work")));
            var capture = h.service.capture();
            var first = h.service.threads(capture.id(), null, null, "id", "asc", 0, 1, false);
            assertThat(first.hiddenBootUiThreads()).isEqualTo(1);
            assertThat(first.page().total()).isEqualTo(3);
            assertThat(first.page().matched()).isEqualTo(2);
            assertThat(first.page().hasMore()).isTrue();
            assertThat(first.threads()).extracting(row -> row.thread().id()).containsExactly(2L);
            assertThat(h.service
                            .threads(capture.id(), null, null, "id", "asc", 1, 1, false)
                            .threads())
                    .extracting(row -> row.thread().id())
                    .containsExactly(3L);
            assertThat(h.service
                            .threads(capture.id(), null, null, "id", "asc", 0, 25)
                            .threads())
                    .hasSize(3);
            assertThat(h.service.export(capture.id()).threads()).hasSize(3);
            assertThat(h.service.analysis(capture.id(), 0, 25).rootBlockers())
                    .singleElement()
                    .satisfies(root -> assertThat(root.threadId()).isEqualTo(1));
            assertThat(h.service.thread(capture.id() + ":1").thread().name()).isEqualTo("bootui-owner");
        }
    }

    @Test
    void comparisonFilteringDoesNotChangeSamplesOrCpuEvidence() {
        try (Harness h = new Harness()) {
            h.sources.add(snapshot(
                    1000,
                    row(1, "bootui-observer", "RUNNABLE", null, "bootui.Observe"),
                    row(2, "application", "RUNNABLE", null, "app.Work")));
            h.sources.add(snapshot(
                    6000,
                    row(1, "bootui-observer", "RUNNABLE", null, "bootui.Observe"),
                    row(2, "application", "RUNNABLE", null, "app.Work")));
            var started = h.service.observe(2, 5000L, false);
            h.tick(0);
            h.tick(5_000_000_000L);
            var filtered = h.service.observation(started.id(), 0, 1, false);
            var full = h.service.observation(started.id(), 0, 25);
            assertThat(filtered.hiddenBootUiThreads()).isEqualTo(1);
            assertThat(filtered.page().matched()).isEqualTo(1);
            assertThat(filtered.threads()).singleElement().satisfies(row -> {
                assertThat(row.name()).isEqualTo("application");
                assertThat(row.singleCoreCpuPercent()).isEqualTo(10.0);
            });
            assertThat(filtered.completedSamples()).isEqualTo(2);
            assertThat(full.threads()).hasSize(2);
            assertThat(full.hiddenBootUiThreads()).isZero();
            assertThat(h.service.export(filtered.captureIds().get(0)).threads()).hasSize(2);
        }
    }

    @Test
    void passiveReadsNeverAcquireAndPagingKeepsTheOriginalCapture() {
        try (Harness h = new Harness()) {
            assertThat(h.service.list(10).captures()).isEmpty();
            h.sources.add(snapshot(
                    1000,
                    row(2, "worker-2", "RUNNABLE", null, "app.B"),
                    row(1, "worker-1", "RUNNABLE", null, "app.A")));
            var capture = h.service.capture();
            var first = h.service.threads(capture.id(), null, null, "id", "asc", 0, 1);
            var second = h.service.threads(capture.id(), null, null, "id", "asc", 1, 1);
            assertThat(first.threads().get(0).thread().id()).isEqualTo(1);
            assertThat(second.threads().get(0).thread().id()).isEqualTo(2);
            assertThat(first.captureId()).isEqualTo(second.captureId());
            assertThat(h.service.thread(first.threads().get(0).observationId()))
                    .isEqualTo(first.threads().get(0));
            assertThat(h.service.export(capture.id()).schemaVersion()).isEqualTo(1);
            assertThat(h.service.analysis(capture.id(), 0, 25).capture().id()).isEqualTo(capture.id());
            verify(h.reader, org.mockito.Mockito.times(1)).acquire(any(Runnable.class));
        }
    }

    @Test
    void rootImpactUsesTheWholeCaptureNotTheFilteredPage() {
        try (Harness h = new Harness()) {
            var rows = new java.util.ArrayList<CapturedThreadDto>();
            rows.add(row(1, "root", "RUNNABLE", null, "app.Root.work"));
            rows.add(row(2, "middle", "BLOCKED", 1L, "app.Middle.work"));
            for (int id = 3; id <= 8; id++) {
                rows.add(row(id, "waiter-" + id, "BLOCKED", 2L, "app.Waiter.work"));
            }
            rows.add(row(9, "condition", "WAITING", 1L, "java.lang.Object.wait"));
            h.sources.add(snapshot(1000, rows.toArray(CapturedThreadDto[]::new)));
            var capture = h.service.capture();
            assertThat(h.service
                            .threads(capture.id(), "root", null, "id", "asc", 0, 1)
                            .threads())
                    .hasSize(1);
            var analysis = h.service.analysis(capture.id(), 0, 25);
            assertThat(analysis.rootBlockers()).singleElement().satisfies(root -> {
                assertThat(root.threadId()).isEqualTo(1);
                assertThat(root.directBlocked()).isEqualTo(1);
                assertThat(root.transitiveBlocked()).isEqualTo(7);
                assertThat(root.maxDepth()).isEqualTo(2);
            });
            assertThat(analysis.unresolvedWaits()).hasSize(1);
        }
    }

    @Test
    void cycleTailsAreRetainedWithoutCreatingAnAcyclicRoot() {
        try (Harness h = new Harness()) {
            h.sources.add(snapshot(
                    1000,
                    row(1, "a", "BLOCKED", 2L, "app.A"),
                    row(2, "b", "BLOCKED", 1L, "app.B"),
                    row(3, "tail", "BLOCKED", 2L, "app.C"),
                    row(4, "tail-2", "BLOCKED", 3L, "app.C")));
            var analysis = h.service.analysis(h.service.capture().id(), 0, 1);
            assertThat(analysis.rootBlockers()).isEmpty();
            assertThat(analysis.cycles()).singleElement().satisfies(cycle -> {
                assertThat(cycle.participantCount()).isEqualTo(2);
                assertThat(cycle.participantIds()).hasSize(1);
                assertThat(cycle.participantListTruncated()).isTrue();
                assertThat(cycle.affectedCount()).isEqualTo(2);
                assertThat(cycle.affectedObservationIds()).hasSize(1);
            });
        }
    }

    @Test
    void unknownOwnerAndSharedWaitAreNotFabricatedDependencies() {
        try (Harness h = new Harness()) {
            h.sources.add(snapshot(
                    1000,
                    row(1, "missing", "BLOCKED", 99L, "app.Work"),
                    row(2, "shared", "WAITING", 1L, "java.util.concurrent.Semaphore.acquire")));
            var analysis = h.service.analysis(h.service.capture().id(), 0, 25);
            assertThat(analysis.rootBlockers()).isEmpty();
            assertThat(analysis.cycles()).isEmpty();
            assertThat(analysis.unresolvedWaits()).hasSize(2);
        }
    }

    @Test
    void unresolvedAcquisitionsPrecedeIdleWaitsBeforePaging() {
        try (Harness h = new Harness()) {
            var rows = new java.util.ArrayList<CapturedThreadDto>();
            for (int id = 1; id <= 30; id++) {
                rows.add(row(id, "idle-" + id, "WAITING", 999L, "java.lang.Object.wait(Object.java:1)"));
            }
            rows.add(row(31, "blocked", "BLOCKED", 999L, "app.Lock.acquire(Lock.java:1)"));
            h.sources.add(snapshot(1000, rows.toArray(CapturedThreadDto[]::new)));
            String id = h.service.capture().id();
            var analysis = h.service.analysis(id, 0, 25);
            assertThat(analysis.unresolvedPage().hasMore()).isTrue();
            assertThat(analysis.unresolvedWaits().get(0)).satisfies(wait -> {
                assertThat(wait.waiterId()).isEqualTo(id + ":31");
                assertThat(wait.kind()).isEqualTo("MONITOR_ACQUISITION");
                assertThat(wait.explanation()).contains("Owner was not captured");
            });
            assertThat(h.service.analysis(id, 25, 25).unresolvedWaits()).hasSize(6);
        }
    }

    @Test
    void exactVisibleStackGroupsPreserveDifferentLockContexts() {
        try (Harness h = new Harness()) {
            h.sources.add(
                    snapshot(1000, row(1, "a", "BLOCKED", 99L, "app.Work"), row(2, "b", "RUNNABLE", null, "app.Work")));
            var analysis = h.service.analysis(h.service.capture().id(), 0, 25);
            assertThat(analysis.stackGroups()).singleElement().satisfies(group -> {
                assertThat(group.count()).isEqualTo(2);
                assertThat(group.states()).hasSize(2);
                assertThat(group.memberIds()).hasSize(2);
            });
        }
    }

    @Test
    void maskedStackGroupsRemainUnknownRatherThanBecomingIdenticalStacks() {
        try (Harness h = new Harness()) {
            h.sources.add(snapshot(
                    1000,
                    row(1, "a", "RUNNABLE", null, io.github.jdubois.bootui.core.SecretMasker.MASKED_VALUE),
                    row(2, "b", "RUNNABLE", null, io.github.jdubois.bootui.core.SecretMasker.MASKED_VALUE)));
            assertThat(h.service.analysis(h.service.capture().id(), 0, 25).stackGroups())
                    .isEmpty();
        }
    }

    @Test
    void finiteWindowsLeaveTimeForTheLastCaptureAndTolerateNormalSchedulerLatency() {
        for (int samples : java.util.List.of(2, 3)) {
            try (Harness h = new Harness()) {
                assertThatThrownBy(() -> h.service.observe(samples, 30_000L / (samples - 1)))
                        .isInstanceOf(ThreadAnalysisException.class)
                        .hasMessageContaining("final capture");
                long interval = 28_000L / (samples - 1);
                for (int i = 0; i < samples; i++)
                    h.sources.add(snapshot(1000 + i * interval, row(1, "a", "RUNNABLE", null, "app.Work")));
                h.afterRead = () -> h.nanos.addAndGet(100_000_000L);
                var session = h.service.observe(samples, interval);
                for (int i = 0; i < samples; i++) h.tick(i * interval * 1_000_000L + 100_000_000L);
                assertThat(h.service.observation(session.id(), 0, 25).status()).isEqualTo("COMPLETED");
            }
        }
    }

    @Test
    void anUnresolvedUpstreamWaitMakesTheVisibleRootPartial() {
        try (Harness h = new Harness()) {
            h.sources.add(snapshot(
                    1000,
                    row(1, "intermediate", "BLOCKED", 99L, "app.Work"),
                    row(2, "waiter", "BLOCKED", 1L, "app.Work")));
            var report = h.service.analysis(h.service.capture().id(), 0, 25);
            assertThat(report.rootBlockers()).singleElement().satisfies(root -> {
                assertThat(root.threadId()).isEqualTo(1);
                assertThat(root.partial()).isTrue();
            });
            assertThat(report.unresolvedWaits()).hasSize(1);
        }
    }

    @Test
    void lockCorrelationUsesOpaqueCaptureLocalKeysRatherThanMaskedDisplayNames() {
        try (Harness h = new Harness()) {
            var source = snapshot(
                    1000,
                    row(1, "a", "BLOCKED", 10L, "app.Work"),
                    row(2, "b", "BLOCKED", 10L, "app.Work"),
                    row(3, "c", "BLOCKED", 10L, "app.Work"));
            h.sources.add(new ThreadSnapshot(
                    source.report(),
                    source.threads(),
                    source.cpu(),
                    java.util.Map.of(1L, "lock-0", 2L, "lock-1", 3L, "lock-0"),
                    source.completedAt(),
                    source.durationNanos(),
                    source.retainedBytes()));
            String id = h.service.capture().id();
            assertThat(h.service.lock(id, id + ":1", 0, 25).waiters())
                    .extracting(wait -> wait.waiterId())
                    .containsExactly(id + ":1", id + ":3");
        }
    }

    @Test
    void expiryNeverRecapturesOrFallsBackFromAnExplicitId() {
        try (Harness h = new Harness()) {
            h.sources.add(snapshot(1000, row(1, "a", "RUNNABLE", null, "app.Work")));
            var capture = h.service.capture();
            h.nanos.set(301_000_000_000L);
            assertThat(h.service.list(10).captures()).isEmpty();
            assertThatThrownBy(() -> h.service.analysis(capture.id(), 0, 25))
                    .isInstanceOfSatisfying(
                            ThreadAnalysisException.class,
                            error -> assertThat(error.status()).isEqualTo(409));
            verify(h.reader, org.mockito.Mockito.times(1)).acquire(any(Runnable.class));
        }
    }

    @Test
    void offloadDuringAcquisitionPreventsLatePublication() {
        try (Harness h = new Harness()) {
            h.sources.add(snapshot(1000, row(1, "a", "RUNNABLE", null, "app.Work")));
            h.afterRead = h.service::offloadRetainedData;
            assertThatThrownBy(h.service::capture).isInstanceOf(ThreadAnalysisException.class);
            assertThat(h.service.list(10).captures()).isEmpty();
        }
    }

    @Test
    void stricterExposureClearsEvidenceAndLooseningDoesNotRestoreIt() {
        try (Harness h = new Harness()) {
            h.sources.add(snapshot(1000, row(1, "a", "RUNNABLE", null, "app.Work")));
            h.service.capture();
            h.exposure.value = ValueExposure.METADATA_ONLY;
            assertThat(h.service.list(10).captures()).isEmpty();
            h.exposure.value = ValueExposure.FULL;
            assertThat(h.service.list(10).captures()).isEmpty();
        }
    }

    @Test
    void aLateCreatedComponentAllowsExplicitCapturesAndFiniteObservationsUnderTheGlobalHold() {
        try (Harness h = new Harness()) {
            h.hold.set(true);
            h.sources.add(snapshot(1000, row(1, "a", "RUNNABLE", null, "app.Work")));
            h.sources.add(snapshot(1000, row(1, "a", "RUNNABLE", null, "app.Work")));
            h.sources.add(snapshot(6000, row(1, "a", "RUNNABLE", null, "app.Work")));
            assertThat(h.service.list(10).lowFootprint()).isTrue();
            var captured = h.service.capture();
            assertThat(h.service.export(captured.id()).threads()).hasSize(1);
            var started = h.service.observe(2, 5000L);
            h.tick(0);
            h.tick(5_000_000_000L);
            assertThat(h.service.observation(started.id(), 0, 25).status()).isEqualTo("COMPLETED");
            assertThat(h.service.list(10).captures()).hasSize(3);
            assertThat(h.tasks).isEmpty();
            verify(h.scheduler).shutdown();
            h.nanos.set(306_000_000_000L);
            assertThat(h.service.list(10).captures()).isEmpty();
            h.readOnly.set(true);
            assertThatThrownBy(h.service::capture).isInstanceOf(ThreadAnalysisException.class);
            assertThatThrownBy(h.service::observe).isInstanceOf(ThreadAnalysisException.class);
            verify(h.reader, org.mockito.Mockito.times(3)).acquire(any(Runnable.class));
        }
    }

    @Test
    void finiteObservationComparesUnionAndStopsWithoutAnIdleWorker() {
        try (Harness h = new Harness()) {
            h.sources.add(snapshot(1000, row(1, "original", "RUNNABLE", null, "app.A")));
            h.sources.add(snapshot(
                    6000, row(1, "renamed", "RUNNABLE", null, "app.B"), row(2, "late", "WAITING", null, "app.Wait")));
            h.sources.add(snapshot(
                    11000, row(1, "renamed", "RUNNABLE", null, "app.A"), row(2, "late", "RUNNABLE", null, "app.Wait")));
            var started = h.service.observe();
            h.tick(0);
            h.tick(5_000_000_000L);
            h.tick(10_000_000_000L);
            var result = h.service.observation(started.id(), 0, 25);
            assertThat(result.status()).isEqualTo("COMPLETED");
            assertThat(result.completedSamples()).isEqualTo(3);
            assertThat(result.threads()).hasSize(2);
            assertThat(result.threads())
                    .filteredOn(row -> row.threadId() == 1)
                    .singleElement()
                    .satisfies(row -> {
                        assertThat(row.observedSamples()).isEqualTo(3);
                        assertThat(row.matchingStackOccurrences()).isEqualTo(2);
                        assertThat(row.consecutiveStackStreak()).isEqualTo(1);
                        assertThat(row.elapsedNanos()).isEqualTo(10_000_000_000L);
                        assertThat(row.singleCoreCpuPercent()).isEqualTo(10.0);
                    });
            assertThat(h.tasks).isEmpty();
            verify(h.scheduler).shutdown();
        }
    }

    @Test
    void cancellationAndReadOnlyChangesStopFutureSamples() {
        try (Harness h = new Harness()) {
            h.sources.add(snapshot(1000, row(1, "a", "RUNNABLE", null, "app.Work")));
            var started = h.service.observe();
            h.tick(0);
            h.readOnly.set(true);
            h.tick(5_000_000_000L);
            assertThat(h.service.observation(started.id(), 0, 25).status()).isEqualTo("POLICY_STOPPED");
            assertThat(h.tasks).isEmpty();
            verify(h.reader, org.mockito.Mockito.times(1)).acquire(any(Runnable.class));
        }
        try (Harness h = new Harness()) {
            var started = h.service.observe();
            assertThat(h.service.cancel(started.id()).status()).isEqualTo("CANCELLED");
            assertThat(h.tasks).isEmpty();
        }
    }

    @Test
    void oneShotOffloadAllowsOnlyRemainingSamplesAndReportsLostEvidence() {
        try (Harness h = new Harness()) {
            h.sources.add(snapshot(1000, row(1, "a", "RUNNABLE", null, "app.Work")));
            h.sources.add(snapshot(6000, row(1, "a", "RUNNABLE", null, "app.Work")));
            h.sources.add(snapshot(11000, row(1, "a", "RUNNABLE", null, "app.Work")));
            var started = h.service.observe();
            h.tick(0);
            assertThat(h.service.offloadRetainedData()).isEqualTo(1);
            h.tick(5_000_000_000L);
            h.tick(10_000_000_000L);
            var report = h.service.observation(started.id(), 0, 25);
            assertThat(report.completedSamples()).isEqualTo(3);
            assertThat(report.clearedSamples()).isEqualTo(1);
            assertThat(report.captureIds()).hasSize(2);
        }
    }

    @Test
    void lowFootprintStopsExistingWorkButPermitsNewExplicitWorkBeforeResuming() {
        try (Harness h = new Harness()) {
            h.sources.add(snapshot(1000, row(1, "a", "RUNNABLE", null, "app.Work")));
            h.sources.add(snapshot(1000, row(1, "a", "RUNNABLE", null, "app.Work")));
            h.sources.add(snapshot(6000, row(1, "a", "RUNNABLE", null, "app.Work")));
            var started = h.service.observe();
            h.tick(0);
            h.service.suspendForLowFootprint();
            assertThat(h.service.observation(started.id(), 0, 25).status()).isEqualTo("POLICY_STOPPED");
            assertThat(h.service.list(10).captures()).isEmpty();
            assertThat(h.tasks).isEmpty();
            assertThat(h.service.list(10).lowFootprint()).isTrue();
            var next = h.service.observe(2, 5000L);
            h.tick(0);
            assertThat(h.service.clear()).isEqualTo(1);
            h.tick(5_000_000_000L);
            var completed = h.service.observation(next.id(), 0, 25);
            assertThat(completed.status()).isEqualTo("COMPLETED");
            assertThat(completed.clearedSamples()).isEqualTo(1);
            assertThat(h.service.list(10).captures()).hasSize(1);
            assertThat(h.tasks).isEmpty();
            h.service.resumeFromLowFootprint();
            assertThat(h.service.observation(next.id(), 0, 25).status()).isEqualTo("COMPLETED");
            assertThat(h.tasks).isEmpty();
        }
    }

    @Test
    void enteringLowFootprintInvalidatesAnInFlightCaptureButNotTheNextExplicitCapture() {
        try (Harness h = new Harness()) {
            h.sources.add(snapshot(1000, row(1, "a", "RUNNABLE", null, "app.Work")));
            h.sources.add(snapshot(1000, row(1, "a", "RUNNABLE", null, "app.Work")));
            h.afterRead = h.service::suspendForLowFootprint;
            assertThatThrownBy(h.service::capture)
                    .isInstanceOf(ThreadAnalysisException.class)
                    .hasMessageContaining("invalidated");
            assertThat(h.service.list(10).captures()).isEmpty();
            h.afterRead = () -> {};
            assertThat(h.service.capture()).isNotNull();
            assertThat(h.service.list(10).captures()).hasSize(1);
        }
    }

    @Test
    void invalidSettingsSortsAndIdsFailExplicitly() {
        try (Harness h = new Harness()) {
            assertThatThrownBy(() -> h.service.analysis("", 0, 25)).isInstanceOf(ThreadAnalysisException.class);
            assertThatThrownBy(() -> h.service.thread("../secrets")).isInstanceOf(ThreadAnalysisException.class);
            assertThatThrownBy(() -> h.service.list(1001)).isInstanceOf(ThreadAnalysisException.class);
        }
    }

    @Test
    void impossibleCustomObservationNeverEvictsExistingEvidence() {
        try (Harness h = new Harness()) {
            h.sources.add(snapshot(1000, row(1, "kept", "RUNNABLE", null, "app.Work")));
            var capture = h.service.capture();
            assertThatThrownBy(() -> h.service.observe(5, 1000L))
                    .isInstanceOf(ThreadAnalysisException.class)
                    .hasMessageContaining("reservation");
            assertThat(h.service.list(10).captures()).extracting(c -> c.id()).containsExactly(capture.id());
            assertThat(h.tasks).isEmpty();
        }
    }

    @Test
    void inventoryExposesTheEffectiveReservationCeilingAndRejectsFourDefaultSamples() {
        try (Harness h = new Harness()) {
            assertThat(h.service.list(10).maxObservationSamples()).isEqualTo(3);
            assertThatThrownBy(() -> h.service.observe(4, 1000L)).hasMessageContaining("reservation");
            assertThat(h.tasks).isEmpty();
        }
        assertThat(new ThreadCaptureSettings(
                                2048, 128, 256, 2000, false, 2, 24 * 1024 * 1024L, 4 * 1024 * 1024L, 300_000, 2, 1000)
                        .maxObservationSamples())
                .isEqualTo(2);
        assertThat(new ThreadCaptureSettings(
                                2048, 128, 256, 29_000, false, 6, 24 * 1024 * 1024L, 4 * 1024 * 1024L, 300_000, 2, 1000)
                        .maxObservationSamples())
                .isEqualTo(2);
    }

    @Test
    void largerReservationBudgetsPermitFiveCompleteSamples() {
        var settings = new ThreadCaptureSettings(
                2048, 128, 256, 2000, false, 6, 24 * 1024 * 1024L, 4 * 1024 * 1024L, 300_000, 3, 5000);
        try (Harness h = new Harness(settings)) {
            assertThat(h.service.list(10).maxObservationSamples()).isEqualTo(5);
            for (int i = 0; i < 5; i++) {
                h.sources.add(snapshot(1000 + i * 1000, row(1, "worker", "RUNNABLE", null, "app.Work")));
            }
            var started = h.service.observe(5, 1000L);
            for (int i = 0; i < 5; i++) h.tick(i * 1_000_000_000L);
            var completed = h.service.observation(started.id(), 0, 25);
            assertThat(completed.status()).isEqualTo("COMPLETED");
            assertThat(completed.captureIds()).hasSize(5);
            assertThat(h.tasks).isEmpty();
        }
    }

    @Test
    void busyObservationTickFailsVisiblyWithoutRetryingAndKeepsEarlierEvidence() {
        try (Harness h = new Harness()) {
            h.sources.add(snapshot(1000, row(1, "worker", "RUNNABLE", null, "app.Work")));
            var started = h.service.observe();
            h.tick(0);
            doThrow(mock(ActionBusyException.class)).when(h.reader).acquire(any(Runnable.class));
            h.tick(5_000_000_000L);
            var failed = h.service.observation(started.id(), 0, 25);
            assertThat(failed.status()).isEqualTo("FAILED");
            assertThat(failed.message()).contains("busy", "no catch-up");
            assertThat(failed.captureIds()).hasSize(1);
            assertThat(h.service.list(10).captures()).hasSize(1);
            assertThat(h.tasks).isEmpty();
        }
    }

    @Test
    void missedObservationTickFailsVisiblyWithoutStartingACatchUpBurst() {
        try (Harness h = new Harness()) {
            h.sources.add(snapshot(1000, row(1, "worker", "RUNNABLE", null, "app.Work")));
            var started = h.service.observe();
            h.afterRead = () -> h.nanos.set(6_000_000_000L);
            h.tick(0);
            var failed = h.service.observation(started.id(), 0, 25);
            assertThat(failed.status()).isEqualTo("FAILED");
            assertThat(failed.message()).contains("missed", "no catch-up");
            assertThat(failed.captureIds()).hasSize(1);
            assertThat(h.tasks).isEmpty();
            verify(h.reader, org.mockito.Mockito.times(1)).acquire(any(Runnable.class));
        }
    }

    @Test
    void anInFlightReservationCannotCauseAFailedObservationToEvictEvidence() {
        try (Harness h = new Harness()) {
            h.sources.add(snapshot(1000, row(1, "kept", "RUNNABLE", null, "app.Work")));
            String kept = h.service.capture().id();
            h.sources.add(snapshot(2000, row(1, "next", "RUNNABLE", null, "app.Work")));
            h.afterRead = () -> {
                assertThatThrownBy(h.service::observe).isInstanceOf(ThreadAnalysisException.class);
                assertThat(h.service.list(10).captures())
                        .extracting(c -> c.id())
                        .containsExactly(kept);
            };
            h.service.capture();
            assertThat(h.service.list(10).captures()).hasSize(2);
            assertThat(h.tasks).isEmpty();
        }
    }

    @Test
    void explicitClearKeepsTheFiniteSessionAndOnlyCollectsRemainingSamples() {
        try (Harness h = new Harness()) {
            for (int i = 0; i < 3; i++)
                h.sources.add(snapshot(1000 + i * 5000, row(1, "a", "RUNNABLE", null, "app.Work")));
            var started = h.service.observe();
            assertThat(h.service.list(10).observationId()).isEqualTo(started.id());
            h.tick(0);
            assertThat(h.service.clear()).isEqualTo(1);
            assertThat(h.service.observation(started.id(), 0, 25).status()).isEqualTo("RUNNING");
            h.tick(5_000_000_000L);
            h.tick(10_000_000_000L);
            var completed = h.service.observation(started.id(), 0, 25);
            assertThat(completed.status()).isEqualTo("COMPLETED");
            assertThat(completed.completedSamples()).isEqualTo(3);
            assertThat(completed.clearedSamples()).isEqualTo(1);
            assertThat(completed.captureIds()).hasSize(2);
        }
    }

    @Test
    void repeatedCapturesPlateauWithinCountAndByteBudgets() {
        try (Harness h = new Harness()) {
            String first = null;
            for (int i = 0; i < 50; i++) {
                h.sources.add(snapshot(1000 + i, row(1, "bounded", "RUNNABLE", null, "app.Work")));
                String id = h.service.capture().id();
                if (first == null) first = id;
                var inventory = h.service.list(20);
                assertThat(inventory.captures()).hasSizeLessThanOrEqualTo(inventory.captureLimit());
                assertThat(inventory.retainedBytes()).isLessThanOrEqualTo(inventory.retainedByteLimit());
            }
            assertThat(h.service.list(20).captures()).hasSize(6);
            String evicted = first;
            assertThatThrownBy(() -> h.service.analysis(evicted, 0, 25)).hasMessageContaining("not retained");
        }
    }

    private static final class MutableExposure implements ExposurePolicy {
        private ValueExposure value = ValueExposure.MASKED;

        public ValueExposure valueExposure() {
            return value;
        }

        public boolean maskSecrets() {
            return true;
        }
    }

    private static final class Harness implements AutoCloseable {
        final ThreadDumpService reader = mock(ThreadDumpService.class);
        final AtomicLong nanos = new AtomicLong();
        final AtomicBoolean hold = new AtomicBoolean();
        final AtomicBoolean readOnly = new AtomicBoolean();
        final MutableExposure exposure = new MutableExposure();
        final Deque<ThreadSnapshot> sources = new ArrayDeque<>();
        final Deque<Runnable> tasks = new ArrayDeque<>();
        final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        final ThreadAnalysisService service;
        Runnable afterRead = () -> {};

        Harness() {
            this(ThreadCaptureSettings.defaults());
        }

        Harness(ThreadCaptureSettings settings) {
            when(reader.available()).thenReturn(true);
            McpPanelPolicy policy = mock(McpPanelPolicy.class);
            when(policy.isEnabled("threads")).thenReturn(true);
            when(policy.isReadOnly("threads")).thenAnswer(call -> readOnly.get());
            when(policy.readOnlyReason("threads")).thenReturn("Threads are read-only.");
            when(reader.acquire(any(Runnable.class))).thenAnswer(call -> {
                call.getArgument(0, Runnable.class).run();
                ThreadSnapshot result = sources.removeFirst();
                afterRead.run();
                return result;
            });
            when(scheduler.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.NANOSECONDS)))
                    .thenAnswer(call -> {
                        Runnable task = call.getArgument(0);
                        tasks.add(task);
                        ScheduledFuture<?> future = mock(ScheduledFuture.class);
                        when(future.cancel(false)).thenAnswer(cancel -> tasks.remove(task));
                        return future;
                    });
            service = new ThreadAnalysisService(
                    reader,
                    settings,
                    new RunIdentity("1234abcd", 1, 1000),
                    exposure,
                    policy,
                    hold::get,
                    () -> 1000 + nanos.get() / 1_000_000L,
                    nanos::get,
                    () -> scheduler);
        }

        void tick(long time) {
            nanos.set(time);
            tasks.removeFirst().run();
        }

        public void close() {
            service.close();
        }
    }
}
