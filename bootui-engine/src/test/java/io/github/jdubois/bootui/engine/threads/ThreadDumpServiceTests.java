package io.github.jdubois.bootui.engine.threads;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.ThreadDumpReport;
import io.github.jdubois.bootui.core.dto.ThreadInfoDto;
import io.github.jdubois.bootui.engine.action.ActionBusyException;
import io.github.jdubois.bootui.engine.support.BootUiThreads;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.AdditionalAnswers;

/**
 * Slice tests for {@link ThreadDumpService}. The service reads the live JVM via
 * {@code ThreadMXBean}, so the running test thread itself provides deterministic data. The
 * exposure decision is supplied through a fixed {@link ExposurePolicy} double so the test stays
 * framework-neutral (no Spring environment binding).
 */
class ThreadDumpServiceTests {

    private final ExposurePolicy masked = new FixedExposure(ValueExposure.MASKED, true);

    @Test
    void bootUiVisibilityOnlyFiltersLivePagesAndNeverRawDumps() {
        Thread current = Thread.currentThread();
        String original = current.getName();
        try {
            current.setName("bootui-visibility-test");
            ThreadDumpService service = new ThreadDumpService(masked);
            var filtered = service.report(null, null, 0, 1, false);
            assertThat(filtered.hiddenBootUiThreads()).isPositive();
            assertThat(filtered.threads())
                    .hasSize(1)
                    .noneMatch(row -> row.name().startsWith("bootui-"));
            assertThat(filtered.page().matched()).isEqualTo(filtered.totalThreads() - filtered.hiddenBootUiThreads());
            assertThat(service.report("bootui-visibility-test", null, 0, 25).threads())
                    .anyMatch(row -> row.id() == current.getId());
            assertThat(service.rawDump()).contains("bootui-visibility-test");
            var metadata = new ThreadDumpService(new FixedExposure(ValueExposure.METADATA_ONLY, true))
                    .report("bootui-visibility-test", null, 0, 25, false);
            assertThat(metadata.threads()).isEmpty();
            assertThat(metadata.hiddenBootUiThreads()).isPositive();
        } finally {
            current.setName(original);
        }
    }

    @Test
    void reportCapturesLiveThreadsWithSummary() {
        ThreadDumpService service = new ThreadDumpService(masked);

        ThreadDumpReport report = service.report(null, null, null, null);

        assertThat(report.available()).isTrue();
        assertThat(report.unavailableReason()).isNull();
        assertThat(report.capturedAt()).isNotNull();
        assertThat(report.totalThreads()).isPositive();
        assertThat(report.threads()).isNotEmpty();
        assertThat(report.stateCounts()).isNotEmpty();
        assertThat(report.stateCounts().stream().mapToInt(c -> c.count()).sum()).isEqualTo(report.totalThreads());
        assertThat(report.page().total()).isEqualTo(report.totalThreads());
    }

    @Test
    void reportFiltersByQueryAndState() {
        ThreadDumpService service = new ThreadDumpService(masked);
        Thread current = Thread.currentThread();

        ThreadDumpReport byName = service.report(current.getName(), null, null, null);
        assertThat(byName.threads()).anyMatch(t -> t.name().equals(current.getName()));

        ThreadDumpReport byState = service.report(null, "runnable", null, null);
        assertThat(byState.threads()).allMatch(t -> t.state().equals("RUNNABLE"));
    }

    @Test
    void reportPagesResults() {
        ThreadDumpService service = new ThreadDumpService(masked);

        ThreadDumpReport firstPage = service.report(null, null, 0, 1);

        assertThat(firstPage.threads()).hasSize(1);
        assertThat(firstPage.page().limit()).isEqualTo(1);
        assertThat(firstPage.page().total()).isGreaterThan(1);
        assertThat(firstPage.page().hasMore()).isTrue();
    }

    @Test
    void metadataOnlyModeHidesStackTraces() {
        ThreadDumpService service = new ThreadDumpService(new FixedExposure(ValueExposure.METADATA_ONLY, true));

        ThreadDumpReport report = service.report(null, null, null, null);

        assertThat(report.threads()).allMatch(t -> t.stackTrace().isEmpty());
        assertThat(report.threads()).allMatch(t -> t.lockName() == null);
    }

    @Test
    void defaultModeIncludesStackTraces() {
        ThreadDumpService service = new ThreadDumpService(masked);

        ThreadDumpReport report = service.report(Thread.currentThread().getName(), null, null, null);

        assertThat(report.threads())
                .filteredOn(t -> t.name().equals(Thread.currentThread().getName()))
                .anySatisfy(t -> assertThat(t.stackTrace()).isNotEmpty());
    }

    @Test
    void rawDumpRendersThreadNames() {
        ThreadDumpService service = new ThreadDumpService(masked);

        String dump = service.rawDump();

        assertThat(dump).isNotNull();
        assertThat(dump).contains("BootUI thread dump");
        assertThat(dump).contains(Thread.currentThread().getName());
    }

    @Test
    void unavailableWhenThreadMxBeanMissing() {
        ThreadDumpService service = new ThreadDumpService(null, masked);

        assertThat(service.available()).isFalse();
        ThreadDumpReport report = service.report(null, null, null, null);
        assertThat(report.available()).isFalse();
        assertThat(report.unavailableReason()).isNotNull();
        assertThat(report.threads()).isEmpty();
        assertThat(service.rawDump()).isNull();
    }

    @Test
    void capturedThreadsExposeBasicMetadata() {
        ThreadDumpService service = new ThreadDumpService(masked);

        ThreadDumpReport report = service.report(null, null, null, null);

        ThreadInfoDto first = report.threads().get(0);
        assertThat(first.id()).isPositive();
        assertThat(first.name()).isNotBlank();
        assertThat(first.state()).isNotBlank();
    }

    @Test
    void peakThreadsIsNeverBelowTheLiveThreadsOfTheSameSnapshot() {
        ThreadMXBean real = ManagementFactory.getThreadMXBean();
        // The peak counter is read separately from the dump; a stale or reset counter must not undercut it.
        ThreadMXBean stalePeak = mock(ThreadMXBean.class, AdditionalAnswers.delegatesTo(real));
        doReturn(1).when(stalePeak).getPeakThreadCount();

        ThreadDumpReport report = new ThreadDumpService(stalePeak, masked).report(null, null, null, null);

        assertThat(report.totalThreads()).isGreaterThan(1);
        assertThat(report.peakThreads()).isEqualTo(report.totalThreads());
        ThreadDumpReport live = new ThreadDumpService(masked).report(null, null, null, null);
        assertThat(live.peakThreads()).isGreaterThanOrEqualTo(live.totalThreads());
    }

    @Test
    void acquisitionBoundsBeforeReadingAndReportsOmissionsSeparatelyFromPaging() {
        ThreadMXBean bean = fakeBean(new long[] {3, 1, 2}, info(1, 2));
        ThreadDumpReport report = service(bean, settings(1, 2)).report(null, null, 0, 1000);

        assertThat(report.quality().threadKinds()).containsExactly("PLATFORM");
        assertThat(report.quality().enumeratedThreads()).isEqualTo(3);
        assertThat(report.quality().omittedThreads()).isEqualTo(2);
        assertThat(report.quality().populationComplete()).isFalse();
        assertThat(report.page().hasMore()).isFalse();
        assertThat(report.threads()).allMatch(row -> !row.virtual());
        verify(bean).getThreadInfo(eq(new long[] {1}), eq(true), eq(false), eq(3));
        verify(bean, never()).dumpAllThreads(anyBoolean(), anyBoolean());
    }

    @Test
    void anExtraFrameEstablishesTruncationWithoutAnUnboundedRead() {
        ThreadMXBean bean = fakeBean(new long[] {1}, info(1, 3));
        ThreadSnapshot snapshot = service(bean, settings(10, 2)).acquire();

        assertThat(snapshot.threads().get(0).stackTruncated()).isTrue();
        assertThat(snapshot.threads().get(0).thread().stackTrace()).hasSize(2);
        assertThat(snapshot.threads().get(0).ownershipComplete()).isFalse();
        assertThat(snapshot.report().quality().stacksComplete()).isFalse();
    }

    @Test
    void unsupportedBoundedOwnershipFallsBackOnlyToBoundedStacks() {
        ThreadMXBean bean = fakeBean(new long[] {1}, info(1, 2));
        when(bean.getThreadInfo(any(long[].class), anyBoolean(), anyBoolean(), anyInt()))
                .thenThrow(new UnsupportedOperationException());
        ThreadInfo fallback = info(1, 2);
        when(bean.getThreadInfo(any(long[].class), eq(3))).thenReturn(new ThreadInfo[] {fallback});

        ThreadDumpReport report = service(bean, settings(10, 2)).report(null, null, 0, 200);

        assertThat(report.available()).isTrue();
        assertThat(report.quality().monitorsRead()).isFalse();
        verify(bean, never()).getThreadInfo(any(long[].class), anyBoolean(), anyBoolean());
    }

    @Test
    void deadlineLimitedAcquisitionIsPartialEvenWithAnEmptyPage() {
        ThreadMXBean bean = fakeBean(new long[] {1, 2}, info(1, 2), info(2, 2));
        var clock = new java.util.concurrent.atomic.AtomicLong();
        ThreadInfo first = info(1, 2);
        ThreadInfo second = info(2, 2);
        when(bean.getThreadInfo(any(long[].class), anyBoolean(), anyBoolean(), anyInt()))
                .thenAnswer(call -> {
                    clock.set(3_000_000_000L);
                    return new ThreadInfo[] {first, second};
                });
        ThreadDumpService service = new ThreadDumpService(bean, masked, settings(10, 2), () -> 1000L, clock::get);

        ThreadDumpReport report = service.report(null, null, 0, 1000);

        assertThat(report.threads()).isEmpty();
        assertThat(report.quality().populationComplete()).isFalse();
        assertThat(report.quality().omittedThreads()).isEqualTo(2);
        assertThat(report.quality().limitations()).anyMatch(text -> text.contains("deadline"));
    }

    @Test
    void expiredPhasesNeverStartAnotherNativeAcquisitionOrFallback() {
        for (String phase : java.util.List.of("deadlocks", "enumeration", "ownership", "deadlock-fallback")) {
            ThreadMXBean bean = fakeBean(new long[] {1}, info(1, 2));
            var clock = new java.util.concurrent.atomic.AtomicLong();
            if (phase.equals("deadlocks")) {
                when(bean.findMonitorDeadlockedThreads()).thenAnswer(call -> {
                    clock.set(3_000_000_000L);
                    return null;
                });
            } else if (phase.equals("enumeration")) {
                when(bean.getAllThreadIds()).thenAnswer(call -> {
                    clock.set(3_000_000_000L);
                    return new long[] {1};
                });
            } else if (phase.equals("ownership")) {
                when(bean.getThreadInfo(any(long[].class), anyBoolean(), anyBoolean(), anyInt()))
                        .thenAnswer(call -> {
                            clock.set(3_000_000_000L);
                            throw new UnsupportedOperationException();
                        });
            } else {
                when(bean.isSynchronizerUsageSupported()).thenReturn(true);
                when(bean.findDeadlockedThreads()).thenAnswer(call -> {
                    clock.set(3_000_000_000L);
                    throw new UnsupportedOperationException();
                });
            }
            var service = new ThreadDumpService(bean, masked, settings(10, 2), () -> 1000L, clock::get);
            assertThatThrownBy(service::acquire)
                    .isInstanceOf(ThreadAnalysisException.class)
                    .hasMessageContaining("deadline");
            verify(bean, never()).getThreadInfo(any(long[].class), anyInt());
            if (phase.equals("deadlocks") || phase.equals("deadlock-fallback"))
                verify(bean, never()).getAllThreadIds();
            if (phase.equals("enumeration"))
                verify(bean, never()).getThreadInfo(any(long[].class), anyBoolean(), anyBoolean(), anyInt());
            if (phase.equals("deadlock-fallback")) verify(bean, never()).findMonitorDeadlockedThreads();
        }
    }

    @Test
    void distinctMaskedLockNamesRetainOnlyOpaqueCorrelationKeys() {
        ThreadInfo first = info(1, 2);
        ThreadInfo second = info(2, 2);
        when(first.getLockName()).thenReturn("password.Lock@1");
        when(second.getLockName()).thenReturn("password.Lock@2");
        var snapshot = service(fakeBean(new long[] {1, 2}, first, second), settings(10, 2))
                .acquire();
        assertThat(snapshot.threads().get(0).thread().lockName())
                .isEqualTo(snapshot.threads().get(1).thread().lockName());
        assertThat(snapshot.waitingLocks()).containsEntry(1L, "lock-0").containsEntry(2L, "lock-1");
        assertThat(snapshot.waitingLocks().toString()).doesNotContain("password");
    }

    @Test
    void maskedFrameTextIsDisclosedAsIncompleteEvidence() {
        ThreadInfo info = info(1, 1);
        when(info.getStackTrace()).thenReturn(new StackTraceElement[] {
            new StackTraceElement("example.Authentication", "authenticate", "Authentication.java", 1)
        });
        var snapshot = service(fakeBean(new long[] {1}, info), settings(10, 2)).acquire();
        assertThat(snapshot.report().quality().stacksComplete()).isFalse();
        assertThat(snapshot.report().quality().limitations()).anyMatch(text -> text.contains("frame text was masked"));
        assertThat(ThreadSnapshot.hasComparableStack(snapshot.threads().get(0))).isFalse();
    }

    @Test
    void aNonBusyPlatformFailurePreservesTheLegacyUnavailableDownload() {
        ThreadMXBean bean = fakeBean(new long[] {1}, info(1, 1));
        when(bean.getAllThreadIds()).thenThrow(new SecurityException("Not allowed"));
        var service = service(bean, settings(10, 2));
        assertThat(service.rawDump()).isNull();
        assertThat(service.report(null, null, 0, 25).available()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "true,true"})
    void realLockWaitsKeepTheirOwnerWithoutInventingSharedEdges(boolean readWrite, boolean shared) throws Exception {
        var readWriteLock = new java.util.concurrent.locks.ReentrantReadWriteLock();
        java.util.concurrent.locks.Lock ownerLock =
                readWrite ? readWriteLock.writeLock() : new java.util.concurrent.locks.ReentrantLock();
        java.util.concurrent.locks.Lock waiterLock = shared ? readWriteLock.readLock() : ownerLock;
        Thread waiter = BootUiThreads.daemonFactory("bootui-thread-rwlock-test-", BootUiThreads.ENGINE_LOADER)
                .newThread(() -> OrderSelector.select(waiterLock));
        ownerLock.lock();
        try {
            waiter.start();
            long ownerId = Thread.currentThread().getId();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            ThreadMXBean bean = ManagementFactory.getThreadMXBean();
            boolean waiting = false;
            while (System.nanoTime() < deadline) {
                ThreadInfo info = bean.getThreadInfo(waiter.getId(), 1);
                if (info != null && info.getLockOwnerId() == ownerId) {
                    waiting = true;
                    break;
                }
                java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
            }
            assertThat(waiting)
                    .as("waiter is parked behind the exclusive owner")
                    .isTrue();
            var policy = mock(io.github.jdubois.bootui.spi.McpPanelPolicy.class);
            when(policy.isEnabled("threads")).thenReturn(true);
            try (var analysis = new ThreadAnalysisService(
                    new ThreadDumpService(bean, masked),
                    ThreadCaptureSettings.defaults(),
                    io.github.jdubois.bootui.engine.correlation.RunIdentity.start(),
                    masked,
                    policy,
                    () -> false)) {
                String id = analysis.capture().id();
                var captured = analysis.thread(id + ":" + waiter.getId());
                assertThat(captured.activity())
                        .isEqualTo(shared ? "WAITING_UNCLASSIFIED" : "EXCLUSIVE_SYNCHRONIZER_ACQUISITION");
                assertThat(captured.thread().lockOwnerId()).isEqualTo(ownerId);
                var report = analysis.analysis(id, 0, 1000);
                if (shared) {
                    assertThat(report.rootBlockers())
                            .flatExtracting(root -> root.blockedObservationIds())
                            .doesNotContain(captured.observationId());
                    assertThat(report.unresolvedWaits()).anySatisfy(wait -> {
                        assertThat(wait.waiterId()).isEqualTo(captured.observationId());
                        assertThat(wait.ownerId()).isEqualTo(id + ":" + ownerId);
                        assertThat(wait.kind()).isEqualTo("WAITING_UNCLASSIFIED");
                    });
                } else {
                    assertThat(report.rootBlockers()).anySatisfy(root -> {
                        assertThat(root.threadId()).isEqualTo(ownerId);
                        assertThat(root.blockedObservationIds()).contains(captured.observationId());
                    });
                }
            }
        } finally {
            ownerLock.unlock();
            waiter.join(5000);
        }
        assertThat(waiter.isAlive()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
        "WriteLock,lock,true",
        "WriteLock,lockInterruptibly,true",
        "WriteLock,tryLock,true",
        "ReadLock,lock,false",
        "ReadLock,lockInterruptibly,false",
        "ReadLock,tryLock,false",
        "WriteLock,unknown,false"
    })
    void readWriteWaitsRequireAVisibleExclusiveAcquisition(String entry, String method, boolean exclusive) {
        ThreadInfo info = info(1, 1);
        when(info.getThreadState()).thenReturn(Thread.State.WAITING);
        when(info.getLockOwnerId()).thenReturn(2L);
        when(info.getLockName()).thenReturn("java.util.concurrent.locks.ReentrantReadWriteLock$NonfairSync@123");
        when(info.getStackTrace()).thenReturn(new StackTraceElement[] {
            new StackTraceElement(
                    null,
                    "java.base",
                    "26",
                    "java.util.concurrent.locks.ReentrantReadWriteLock$" + entry,
                    method,
                    "ReentrantReadWriteLock.java",
                    1)
        });
        var captured = service(fakeBean(new long[] {1}, info), settings(10, 2))
                .acquire()
                .threads()
                .get(0);
        assertThat(captured.activity())
                .isEqualTo(exclusive ? "EXCLUSIVE_SYNCHRONIZER_ACQUISITION" : "WAITING_UNCLASSIFIED");
        assertThat(ThreadContentionAnalysis.supported(captured)).isEqualTo(exclusive);
    }

    @Test
    void absentMaskedOrAmbiguousReadWriteFramesNeverInventExclusiveEvidence() {
        for (StackTraceElement[] frames : java.util.List.of(
                new StackTraceElement[0],
                new StackTraceElement[] {
                    new StackTraceElement("example.Authentication", "authenticate", "Authentication.java", 1)
                },
                new StackTraceElement[] {
                    new StackTraceElement(
                            "java.util.concurrent.locks.AbstractQueuedSynchronizer", "acquire", "AQS.java", 1)
                },
                new StackTraceElement[] {
                    new StackTraceElement(
                            "example.ReentrantReadWriteLock$WriteLock", "lock", "ReentrantReadWriteLock.java", 1)
                })) {
            ThreadInfo info = info(1, 1);
            when(info.getThreadState()).thenReturn(Thread.State.WAITING);
            when(info.getLockOwnerId()).thenReturn(2L);
            when(info.getLockName()).thenReturn("java.util.concurrent.locks.ReentrantReadWriteLock$NonfairSync@123");
            when(info.getStackTrace()).thenReturn(frames);
            var captured = service(fakeBean(new long[] {1}, info), settings(10, 2))
                    .acquire()
                    .threads()
                    .get(0);
            assertThat(captured.activity()).isEqualTo("WAITING_UNCLASSIFIED");
            assertThat(ThreadContentionAnalysis.supported(captured)).isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "java.base@26/java.lang.Object.wait0(Native Method)",
                "java.lang.Object.wait(Object.java:1)",
                "java.base/java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject.await(AQS.java:1)"
            })
    void actualJdkConditionWaitsRemainNonExclusive(String frame) {
        ThreadInfo info = info(1, 1);
        when(info.getThreadState()).thenReturn(Thread.State.WAITING);
        when(info.getLockOwnerId()).thenReturn(2L);
        when(info.getLockName()).thenReturn("java.util.concurrent.locks.ReentrantLock$NonfairSync@123");
        when(info.getStackTrace()).thenReturn(new StackTraceElement[] {
            new StackTraceElement("com.example.OrderSelector", "select", "OrderSelector.java", 1)
        });
        var row = service(fakeBean(new long[] {1}, info), settings(10, 2))
                .acquire()
                .threads()
                .get(0);
        var thread = row.thread();
        var condition = new ThreadInfoDto(
                thread.id(),
                thread.name(),
                thread.state(),
                thread.priority(),
                thread.daemon(),
                thread.virtual(),
                thread.cpuTimeMillis(),
                thread.userTimeMillis(),
                thread.blockedCount(),
                thread.waitedCount(),
                thread.inNative(),
                thread.suspended(),
                thread.deadlocked(),
                thread.lockName(),
                thread.lockOwnerId(),
                thread.lockOwnerName(),
                java.util.List.of(frame));
        assertThat(ThreadDumpService.activity(condition)).isEqualTo("CONDITION_OR_EVENT_WAIT");
        assertThat(row.activity()).isEqualTo("EXCLUSIVE_SYNCHRONIZER_ACQUISITION");
    }

    private static final class OrderSelector {
        static void select(java.util.concurrent.locks.Lock lock) {
            lock.lock();
            lock.unlock();
        }
    }

    @Test
    void metadataOnlyDoesNotRetainOwnershipOrStackDerivedClassification() {
        ThreadInfo info = info(1, 2);
        when(info.getLockOwnerId()).thenReturn(2L);
        when(info.getLockName()).thenReturn("example.Lock@123");
        ThreadSnapshot snapshot = new ThreadDumpService(
                        fakeBean(new long[] {1}, info),
                        new FixedExposure(ValueExposure.METADATA_ONLY, true),
                        settings(10, 2),
                        () -> 1000L,
                        () -> 0L)
                .acquire();

        assertThat(snapshot.threads().get(0).ownedLocks()).isEmpty();
        assertThat(snapshot.threads().get(0).thread().lockOwnerId()).isNull();
        assertThat(snapshot.threads().get(0).thread().stackTrace()).isEmpty();
    }

    @Test
    void concurrentLiveReadFailsFastInsteadOfWaitingOrBecomingUnavailable() throws Exception {
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ThreadMXBean bean = fakeBean(new long[] {1}, info(1, 2));
        when(bean.getAllThreadIds()).thenAnswer(call -> {
            reading.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test capture was not released");
            }
            return new long[] {1};
        });
        ThreadDumpService service = service(bean, settings(10, 2));
        var executor = Executors.newSingleThreadExecutor(
                BootUiThreads.daemonFactory("bootui-thread-test-", BootUiThreads.ENGINE_LOADER));
        try {
            var first = executor.submit(() -> service.report(null, null, 0, 200));
            assertThat(reading.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> service.report(null, null, 0, 200)).isInstanceOf(ActionBusyException.class);
            assertThatThrownBy(service::rawDump).isInstanceOf(ActionBusyException.class);
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).available()).isTrue();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private ThreadDumpService service(ThreadMXBean bean, ThreadCaptureSettings settings) {
        return new ThreadDumpService(bean, masked, settings, () -> 1000L, () -> 0L);
    }

    private static ThreadCaptureSettings settings(int threads, int depth) {
        ThreadCaptureSettings defaults = ThreadCaptureSettings.defaults();
        return new ThreadCaptureSettings(
                threads,
                depth,
                defaults.maxOwnedLocks(),
                defaults.captureDeadlineMillis(),
                false,
                defaults.maxCaptures(),
                defaults.maxRetainedBytes(),
                defaults.maxCaptureBytes(),
                defaults.ttlMillis(),
                defaults.observationSamples(),
                defaults.observationIntervalMillis());
    }

    private static ThreadMXBean fakeBean(long[] ids, ThreadInfo... infos) {
        ThreadMXBean bean = mock(ThreadMXBean.class);
        when(bean.getAllThreadIds()).thenReturn(ids);
        when(bean.isObjectMonitorUsageSupported()).thenReturn(true);
        when(bean.getThreadInfo(any(long[].class), anyBoolean(), anyBoolean(), anyInt()))
                .thenReturn(infos);
        return bean;
    }

    private static ThreadInfo info(long id, int frames) {
        ThreadInfo info = mock(ThreadInfo.class);
        when(info.getThreadId()).thenReturn(id);
        when(info.getThreadName()).thenReturn("worker-" + id);
        when(info.getThreadState()).thenReturn(Thread.State.RUNNABLE);
        when(info.getPriority()).thenReturn(Thread.NORM_PRIORITY);
        when(info.getLockOwnerId()).thenReturn(-1L);
        when(info.getLockedMonitors()).thenReturn(new MonitorInfo[0]);
        when(info.getLockedSynchronizers()).thenReturn(new LockInfo[0]);
        StackTraceElement[] stack = new StackTraceElement[frames];
        for (int i = 0; i < frames; i++) {
            stack[i] = new StackTraceElement("example.Worker", "run", "Worker.java", i + 1);
        }
        when(info.getStackTrace()).thenReturn(stack);
        return info;
    }

    /**
     * Minimal {@link ExposurePolicy} test double; the record accessors satisfy the interface.
     */
    private record FixedExposure(ValueExposure valueExposure, boolean maskSecrets) implements ExposurePolicy {}
}
