package io.github.jdubois.bootui.engine.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.correlation.RequestIds;
import io.github.jdubois.bootui.engine.resources.ResourceUsage.Availability;
import io.github.jdubois.bootui.engine.resources.ResourceUsage.Unmeasured;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SegmentMeterTests {

    private final FakeReadings readings = new FakeReadings();
    private final SegmentMeter meter = new SegmentMeter(readings);

    @Test
    void aSegmentAddsItsThreadsCpuAllocationAndCompletedCollections() {
        readings.set(Thread.currentThread(), 1_000, 10_000);
        readings.collections(5, 2);

        meter.begin("r1");
        readings.set(Thread.currentThread(), 4_000, 14_000);
        readings.collections(7, 2);
        meter.switchTo(null);

        ResourceUsage usage = meter.take("r1");
        assertThat(usage.cpuNanos()).isEqualTo(3_000);
        assertThat(usage.allocatedBytes()).isEqualTo(4_000);
        assertThat(usage.segments()).isEqualTo(1);
        assertThat(usage.availability()).isEqualTo(Availability.AVAILABLE);
        assertThat(usage.gcPauses()).isEqualTo(2);
        assertThat(usage.gcPauseRanges()).containsExactly(new GcPauseRange("Young", 5, 7));
        assertThat(meter.openRequests()).isZero();
    }

    @Test
    void aRequestThatHopsThreadsSumsItsSegmentsAndWorkForOtherRequestsIsNotCounted() throws Exception {
        Thread main = Thread.currentThread();
        readings.set(main, 0, 0);
        meter.begin("r1");
        readings.set(main, 100, 1_000);
        meter.switchTo("r2");
        readings.set(main, 900, 9_000);
        meter.switchTo("r1");
        readings.set(main, 1_000, 10_000);
        meter.switchTo(null);

        runOn(worker -> {
            readings.set(worker, 50, 500);
            meter.switchTo("r1");
            readings.set(worker, 250, 2_500);
            meter.switchTo(null);
        });

        ResourceUsage usage = meter.take("r1");
        assertThat(usage.segments()).isEqualTo(3);
        assertThat(usage.cpuNanos()).as("100 + 100 on main, 200 on the worker").isEqualTo(400);
        assertThat(usage.allocatedBytes()).isEqualTo(4_000);
    }

    @Test
    void reenteringTheSameRequestKeepsItsSegmentOpen() {
        readings.set(Thread.currentThread(), 0, 0);
        meter.begin("r1");
        meter.switchTo("r1");
        meter.switchTo("r1");
        readings.set(Thread.currentThread(), 10, 10);

        ResourceUsage usage = meter.take("r1");

        assertThat(usage.segments()).isEqualTo(1);
        assertThat(usage.cpuNanos()).isEqualTo(10);
    }

    @Test
    void takingClosesASegmentLeftOpenOnAnotherThreadWithThatThreadsReadings() throws Exception {
        AtomicReference<Thread> worker = new AtomicReference<>();
        meter.begin("r1");
        meter.switchTo(null);
        runOn(thread -> {
            worker.set(thread);
            readings.set(thread, 1_000, 1_000);
            meter.switchTo("r1");
        });
        readings.set(worker.get(), 3_500, 6_000);

        ResourceUsage usage = meter.take("r1");

        assertThat(usage.segments()).isEqualTo(2);
        assertThat(usage.cpuNanos()).isEqualTo(2_500);
        assertThat(usage.allocatedBytes()).isEqualTo(5_000);
    }

    @Test
    void workAfterTheRequestWasTakenIsNotMeasured() {
        meter.begin("r1");
        meter.take("r1");

        meter.switchTo("r1");
        meter.switchTo(null);

        assertThat(meter.take("r1")).isNull();
        assertThat(meter.openRequests()).isZero();
    }

    @Test
    void anUnmeasurableSegmentIsCountedWithItsReasonNeverAsZero() {
        readings.set(Thread.currentThread(), 0, 0);
        meter.begin("r1");
        readings.set(Thread.currentThread(), 100, 100);
        meter.switchTo(null);
        readings.set(Thread.currentThread(), -1, -1);
        meter.switchTo("r1");
        meter.switchTo(null);

        ResourceUsage usage = meter.take("r1");

        assertThat(usage.availability()).isEqualTo(Availability.PARTIAL);
        assertThat(usage.unmeasuredSegments()).isEqualTo(1);
        assertThat(usage.unmeasuredReason()).isEqualTo(Unmeasured.THREAD_ENDED);
        assertThat(usage.cpuNanos()).isEqualTo(100);

        readings.supported = false;
        meter.begin("r2");
        assertThat(meter.take("r2").unmeasuredReason()).isEqualTo(Unmeasured.UNSUPPORTED);
        assertThat(new ResourceUsage(0, 0, 0, 0, null, 0, null, false).availability())
                .isEqualTo(Availability.UNAVAILABLE);
    }

    @Test
    void overlappingCollectionRangesOfConcurrentSegmentsAreCountedOnce() throws Exception {
        readings.collections(10, 0);
        meter.begin("r1");
        runOn(worker -> meter.switchTo("r1"));
        readings.collections(12, 0);
        meter.switchTo(null);
        readings.collections(13, 0);

        ResourceUsage usage = meter.take("r1");

        assertThat(usage.gcPauseRanges()).containsExactly(new GcPauseRange("Young", 10, 13));
        assertThat(usage.gcPauses()).isEqualTo(3);
    }

    @Test
    void rangesBeyondTheBoundAreDroppedAndFlagged() {
        meter.begin("r1");
        meter.switchTo(null);
        for (int i = 0; i <= SegmentMeter.MAX_GC_RANGES; i++) {
            readings.collections(i * 10, 0);
            meter.switchTo("r1");
            readings.collections(i * 10 + 1, 0);
            meter.switchTo(null);
        }

        ResourceUsage usage = meter.take("r1");

        assertThat(usage.gcPauseRanges()).hasSize(SegmentMeter.MAX_GC_RANGES);
        assertThat(usage.gcPauseRangesTruncated()).isTrue();
    }

    @Test
    @SuppressWarnings("deprecation")
    void theLedgerReadsEachPlatformThreadsCreditedCpuAndForgetsThreadsThatEnded() throws Exception {
        AtomicReference<Thread> worker = new AtomicReference<>();
        meter.begin("r1");
        meter.switchTo(null);
        runOn(thread -> {
            worker.set(thread);
            readings.set(thread, 0, 0);
            meter.switchTo("r1");
            readings.set(thread, 40, 0);
            meter.switchTo(null);
        });
        long id = worker.get().getId();

        assertThat(meter.attributedCpuNanos(id, 999)).isEqualTo(40);
        meter.forgetEndedThreads();
        assertThat(meter.attributedCpuNanos(id, 999)).isZero();
        meter.take("r1");
    }

    @Test
    void anUnbegunRequestIsNeverMeasured() {
        meter.switchTo("never-begun");
        meter.switchTo(null);

        assertThat(meter.take("never-begun")).isNull();
        assertThat(meter.begin(null)).isFalse();
        assertThat(meter.take(null)).isNull();
    }

    @Test
    void correlationScopesDriveTheSharedMeterWithTheJvmsReadings() {
        String requestId = RequestIds.next();
        SegmentMeter shared = SegmentMeter.shared();
        shared.begin(requestId);
        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(CorrelationContext.forRequest(requestId))) {
            burn();
        }
        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(CorrelationContext.forRequest(requestId))) {
            burn();
        }

        ResourceUsage usage = shared.take(requestId);

        assertThat(usage.segments())
                .as("begin, then two scopes; the first scope continues begin's segment")
                .isEqualTo(2);
        assumeThat(ThreadReadings.get().supported()).isTrue();
        assertThat(usage.availability()).isEqualTo(Availability.AVAILABLE);
        assertThat(usage.cpuNanos()).isPositive();
        assertThat(usage.allocatedBytes()).isGreaterThan(1_000_000);
    }

    @Test
    void aVirtualThreadsSegmentIsUnavailableBecauseTheJvmDoesNotMeasureIt() throws Exception {
        Method start;
        try {
            start = Thread.class.getMethod("startVirtualThread", Runnable.class);
        } catch (NoSuchMethodException ex) {
            assumeThat(ex).as("virtual threads need JDK 21").isNull();
            return;
        }
        String requestId = RequestIds.next();
        SegmentMeter shared = SegmentMeter.shared();
        shared.begin(requestId);
        shared.switchTo(null);
        Thread virtual = (Thread) start.invoke(null, (Runnable) () -> {
            try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(CorrelationContext.forRequest(requestId))) {
                burn();
            }
        });
        virtual.join();

        ResourceUsage usage = shared.take(requestId);

        assertThat(usage.segments()).isEqualTo(2);
        assertThat(usage.unmeasuredSegments()).isEqualTo(1);
        assertThat(usage.unmeasuredReason()).isEqualTo(Unmeasured.VIRTUAL_THREAD);
        assertThat(usage.availability()).isEqualTo(Availability.PARTIAL);
    }

    private static void burn() {
        long sum = 0;
        for (int i = 0; i < 20_000; i++) {
            sum += new byte[128].length + Long.toString(i).length();
        }
        assertThat(sum).isPositive();
    }

    private static void runOn(java.util.function.Consumer<Thread> work) throws InterruptedException {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                work.accept(Thread.currentThread());
            } catch (Throwable ex) {
                failure.set(ex);
            }
        });
        thread.start();
        thread.join();
        assertThat(failure.get()).isNull();
    }
}
