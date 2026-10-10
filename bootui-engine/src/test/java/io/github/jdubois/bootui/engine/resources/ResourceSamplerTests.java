package io.github.jdubois.bootui.engine.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import io.github.jdubois.bootui.engine.resources.ResourceSampler.Sweep;
import io.github.jdubois.bootui.engine.resources.ResourceSampler.ThreadReading;
import io.github.jdubois.bootui.engine.resources.ResourceTrack.Point;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResourceSamplerTests {

    private final FakeReadings readings = new FakeReadings();
    private final SegmentMeter meter = new SegmentMeter(readings);
    private final FakeProbe probe = new FakeProbe();
    private final ResourceTrack track = new ResourceTrack();

    @Test
    @SuppressWarnings("deprecation")
    void requestsThreadFamiliesAndTheJvmsOwnWorkSumToTheProcessCpu() {
        long main = Thread.currentThread().getId();
        ResourceSampler sampler = sampler(500);
        probe.thread(main, "http-nio-8080-exec-1", 0);
        probe.thread(1_002, "bootui-journal-dispatch", 0);
        probe.thread(1_003, "pool-1-thread-7", 0);
        probe.processCpu = 1_000;
        sampler.sweep(1_000, 0);
        assertThat(track.points()).as("the first sweep is the baseline").isEmpty();

        readings.set(Thread.currentThread(), 0, 0);
        meter.begin("r1");
        readings.set(Thread.currentThread(), 60, 0);
        meter.switchTo(null);
        probe.thread(main, "http-nio-8080-exec-1", 100);
        probe.thread(1_002, "bootui-journal-dispatch", 50);
        probe.thread(1_003, "pool-1-thread-7", 70);
        probe.processCpu = 1_300;
        sampler.sweep(2_000, 1_000_000_000L);

        Point point = track.points().get(0);
        assertThat(point.intervalNanos()).isEqualTo(1_000_000_000L);
        assertThat(point.sequence()).isEqualTo(42);
        assertThat(point.processCpuNanos()).isEqualTo(300);
        assertThat(point.requestCpuNanos()).isEqualTo(60);
        assertThat(point.internalCpuNanos()).isEqualTo(80);
        assertThat(byFamily(point))
                .containsEntry("http-nio-N-exec-N", 40L)
                .containsEntry(ResourceTrack.BOOTUI_FAMILY, 50L)
                .containsEntry("pool-N-thread-N", 70L);
        assertThat(point.requestCpuNanos() + point.familiesCpuNanos() + point.internalCpuNanos())
                .isEqualTo(point.processCpuNanos());
        assertThat(point.heapUsedBytes()).isEqualTo(64);
        assertThat(point.liveThreads()).isEqualTo(3);
    }

    @Test
    @SuppressWarnings("deprecation")
    void anOpenSegmentsProgressIsAlreadyCreditedToRequests() {
        long main = Thread.currentThread().getId();
        ResourceSampler sampler = sampler(500);
        probe.thread(main, "http-nio-8080-exec-1", 1_000);
        sampler.sweep(0, 0);

        readings.set(Thread.currentThread(), 1_000, 0);
        meter.begin("r1");
        probe.thread(main, "http-nio-8080-exec-1", 1_500);
        probe.processCpu = 500;
        sampler.sweep(0, 1);

        Point point = track.points().get(0);
        assertThat(point.requestCpuNanos()).isEqualTo(500);
        assertThat(point.familiesCpuNanos()).isZero();
        meter.take("r1");
    }

    @Test
    void threadsBeyondTheCapCountAsTheJvmsOwnWork() {
        ResourceSampler sampler = sampler(2);
        probe.thread(1, "a-1", 0);
        probe.thread(2, "b-1", 0);
        probe.thread(3, "c-1", 0);
        sampler.sweep(0, 0);
        probe.thread(1, "a-1", 10);
        probe.thread(2, "b-1", 20);
        probe.thread(3, "c-1", 30);
        probe.processCpu = 100;
        sampler.sweep(0, 1);

        Point point = track.points().get(0);
        assertThat(point.unreadThreads()).isEqualTo(1);
        assertThat(point.familiesCpuNanos()).isEqualTo(30);
        assertThat(point.internalCpuNanos()).isEqualTo(70);
    }

    @Test
    void aJvmWithoutProcessCpuReportsTheJvmsShareAsUnknown() {
        ResourceSampler sampler = sampler(500);
        probe.processCpu = -1;
        probe.thread(1, "a-1", 0);
        sampler.sweep(0, 0);
        probe.thread(1, "a-1", 10);
        sampler.sweep(0, 1);

        Point point = track.points().get(0);
        assertThat(point.processCpuNanos()).isEqualTo(-1);
        assertThat(point.internalCpuNanos()).isEqualTo(-1);
        assertThat(point.familiesCpuNanos()).isEqualTo(10);
    }

    @Test
    void intervalPromotedCappedThreadDoesNotChargeItsLifetimeCounters() {
        ResourceSampler sampler = sampler(2);
        probe.thread(1, "worker-1", 100_000_000, 1_000);
        probe.thread(2, "ended-1", 200_000_000, 2_000);
        probe.thread(3, "old-unread-1", 1_000_000_000, 2_000_000);
        probe.processCpu = 2_000_000_000;
        sampler.sweep(0, 0);

        probe.threads.remove(2L);
        probe.thread(1, "worker-1", 115_000_000, 7_000);
        probe.processCpu += 20_000_000;
        sampler.sweep(1_000, 1_000_000_000);

        Point promoted = track.points().get(0);
        assertThat(promoted.processCpuNanos()).isEqualTo(20_000_000);
        assertThat(promoted.familiesCpuNanos()).isEqualTo(15_000_000);
        assertThat(promoted.internalCpuNanos()).isEqualTo(5_000_000);
        assertThat(promoted.allocatedBytes()).isEqualTo(-1);
        assertBalanced(promoted);

        probe.thread(1, "worker-1", 120_000_000, 8_000);
        probe.thread(3, "old-unread-1", 1_010_000_000, 2_002_000);
        probe.processCpu += 20_000_000;
        sampler.sweep(2_000, 2_000_000_000);

        Point measured = track.points().get(1);
        assertThat(measured.processCpuNanos()).isEqualTo(20_000_000);
        assertThat(measured.familiesCpuNanos()).isEqualTo(15_000_000);
        assertThat(measured.internalCpuNanos()).isEqualTo(5_000_000);
        assertThat(measured.allocatedBytes()).isEqualTo(3_000);
        assertBalanced(measured);
    }

    @Test
    void intervalFirstSeenThreadLeavesItsUnbaselinedWorkInTheRemainder() {
        ResourceSampler sampler = sampler(2);
        probe.thread(1, "worker-1", 100, 1_000);
        sampler.sweep(0, 0);

        probe.thread(1, "worker-1", 110, 1_200);
        probe.thread(2, "new-worker-1", 1_000, 20_000);
        probe.processCpu = 20;
        sampler.sweep(0, 1);

        Point point = track.points().get(0);
        assertThat(point.processCpuNanos()).isEqualTo(20);
        assertThat(point.familiesCpuNanos()).isEqualTo(10);
        assertThat(point.internalCpuNanos()).isEqualTo(10);
        assertThat(point.allocatedBytes()).isEqualTo(-1);
        assertBalanced(point);
    }

    @Test
    void intervalCapChurnRebaselinesReappearingThreadIdentities() {
        ResourceSampler sampler = sampler(1);
        for (int i = 0; i < 10; i++) {
            probe.threads.clear();
            long id = 1 + i % 2;
            probe.thread(id, "worker-" + id, 1_000 + i * 10, 10_000 + i * 100);
            probe.thread(3, "unread-1", 10_000, 100_000);
            probe.processCpu = i * 20;
            sampler.sweep(i, i);
        }

        assertThat(track.points()).hasSize(9).allSatisfy(point -> {
            assertThat(point.processCpuNanos()).isEqualTo(20);
            assertThat(point.familiesCpuNanos()).isZero();
            assertThat(point.internalCpuNanos()).isEqualTo(20);
            assertThat(point.allocatedBytes()).isEqualTo(-1);
            assertThat(point.unreadThreads()).isEqualTo(1);
            assertBalanced(point);
        });
        assertThat(track.totals().processCpuNanos()).isEqualTo(180);
        assertThat(track.families()).hasSizeLessThanOrEqualTo(ResourceTrack.MAX_FAMILIES + 1);
    }

    @Test
    void intervalUnavailableCpuDoesNotSuppressKnownAllocation() {
        ResourceSampler sampler = sampler(1);
        probe.thread(1, "worker-1", -1, 1_000);
        sampler.sweep(0, 0);
        probe.thread(1, "worker-1", -1, 1_600);
        probe.processCpu = 20;
        sampler.sweep(0, 1);

        Point point = track.points().get(0);
        assertThat(point.allocatedBytes()).isEqualTo(600);
        assertThat(point.familiesCpuNanos()).isZero();
        assertThat(point.internalCpuNanos()).isEqualTo(20);
        assertBalanced(point);
    }

    @Test
    void intervalRecoveredCpuRequiresAFreshBaseline() {
        ResourceSampler sampler = sampler(1);
        probe.thread(1, "worker-1", 1_000, 100);
        sampler.sweep(0, 0);
        probe.thread(1, "worker-1", -1, 200);
        probe.processCpu = 10;
        sampler.sweep(0, 1);
        probe.thread(1, "worker-1", 2_000, 300);
        probe.processCpu = 20;
        sampler.sweep(0, 2);

        Point recovered = track.points().get(1);
        assertThat(recovered.processCpuNanos()).isEqualTo(10);
        assertThat(recovered.familiesCpuNanos()).isZero();
        assertThat(recovered.internalCpuNanos()).isEqualTo(10);
        assertThat(recovered.allocatedBytes()).isEqualTo(100);
        assertBalanced(recovered);

        probe.thread(1, "worker-1", 2_020, 400);
        probe.processCpu = 50;
        sampler.sweep(0, 3);
        Point measured = track.points().get(2);
        assertThat(measured.familiesCpuNanos()).isEqualTo(20);
        assertThat(measured.internalCpuNanos()).isEqualTo(10);
        assertThat(measured.allocatedBytes()).isEqualTo(100);
        assertBalanced(measured);
    }

    @Test
    void intervalBackwardsThreadCpuRebaselinesWithoutSuppressingAllocation() {
        ResourceSampler sampler = sampler(1);
        probe.thread(1, "worker-1", 1_000, 100);
        sampler.sweep(0, 0);
        probe.thread(1, "worker-1", 500, 200);
        probe.processCpu = 20;
        sampler.sweep(0, 1);
        probe.thread(1, "worker-1", 510, 300);
        probe.processCpu = 40;
        sampler.sweep(0, 2);

        Point reset = track.points().get(0);
        assertThat(reset.processCpuNanos()).isEqualTo(20);
        assertThat(reset.familiesCpuNanos()).isZero();
        assertThat(reset.internalCpuNanos()).isEqualTo(20);
        assertThat(reset.allocatedBytes()).isEqualTo(100);
        assertBalanced(reset);
        Point measured = track.points().get(1);
        assertThat(measured.familiesCpuNanos()).isEqualTo(10);
        assertThat(measured.internalCpuNanos()).isEqualTo(10);
        assertThat(measured.allocatedBytes()).isEqualTo(100);
        assertBalanced(measured);
    }

    @Test
    void intervalUnavailableAllocationIsNotReportedAsHealthyZero() {
        ResourceSampler sampler = sampler(1);
        probe.thread(1, "worker-1", -1, -1);
        sampler.sweep(0, 0);
        probe.processCpu = 20;
        sampler.sweep(0, 1);

        Point point = track.points().get(0);
        assertThat(point.allocatedBytes()).isEqualTo(-1);
        assertThat(point.internalCpuNanos()).isEqualTo(20);
        assertBalanced(point);
    }

    @Test
    void intervalRecoveredAllocationRequiresAFreshBaselineWhileCpuStaysKnown() {
        ResourceSampler sampler = sampler(1);
        probe.thread(1, "worker-1", 100, 1_000);
        sampler.sweep(0, 0);
        probe.thread(1, "worker-1", 110, -1);
        probe.processCpu = 20;
        sampler.sweep(0, 1);
        probe.thread(1, "worker-1", 120, 5_000);
        probe.processCpu = 40;
        sampler.sweep(0, 2);
        probe.thread(1, "worker-1", 130, 5_100);
        probe.processCpu = 60;
        sampler.sweep(0, 3);

        assertThat(track.points().get(0).allocatedBytes()).isEqualTo(-1);
        assertThat(track.points().get(1).allocatedBytes()).isEqualTo(-1);
        assertThat(track.points().get(2).allocatedBytes()).isEqualTo(100);
        assertThat(track.points()).allSatisfy(point -> {
            assertThat(point.familiesCpuNanos()).isEqualTo(10);
            assertThat(point.internalCpuNanos()).isEqualTo(10);
            assertBalanced(point);
        });
    }

    @Test
    void intervalBackwardsAllocationIsUnknownAndRebaselinesTheCounter() {
        ResourceSampler sampler = sampler(1);
        probe.thread(1, "worker-1", 100, 1_000);
        sampler.sweep(0, 0);
        probe.thread(1, "worker-1", 110, 500);
        probe.processCpu = 20;
        sampler.sweep(0, 1);
        probe.thread(1, "worker-1", 120, 700);
        probe.processCpu = 40;
        sampler.sweep(0, 2);

        assertThat(track.points().get(0).allocatedBytes()).isEqualTo(-1);
        assertThat(track.points().get(1).allocatedBytes()).isEqualTo(200);
        assertThat(track.points()).allSatisfy(ResourceSamplerTests::assertBalanced);
    }

    @Test
    void intervalBackwardsProcessCpuIsUnknownWithoutLosingThreadMeasurements() {
        ResourceSampler sampler = sampler(1);
        probe.thread(1, "worker-1", 100);
        probe.processCpu = 1_000;
        sampler.sweep(0, 0);
        probe.thread(1, "worker-1", 110);
        probe.processCpu = 500;
        sampler.sweep(0, 1);
        probe.thread(1, "worker-1", 120);
        probe.processCpu = 520;
        sampler.sweep(0, 2);

        Point reset = track.points().get(0);
        assertThat(reset.processCpuNanos()).isEqualTo(-1);
        assertThat(reset.internalCpuNanos()).isEqualTo(-1);
        assertThat(reset.familiesCpuNanos()).isEqualTo(10);
        Point measured = track.points().get(1);
        assertThat(measured.processCpuNanos()).isEqualTo(20);
        assertThat(measured.familiesCpuNanos()).isEqualTo(10);
        assertThat(measured.internalCpuNanos()).isEqualTo(10);
        assertBalanced(measured);
    }

    @Test
    void intervalRecoveredProcessCpuRequiresTwoKnownObservations() {
        ResourceSampler sampler = sampler(1);
        probe.thread(1, "worker-1", 100);
        probe.processCpu = -1;
        sampler.sweep(0, 0);
        probe.thread(1, "worker-1", 110);
        probe.processCpu = 1_000;
        sampler.sweep(0, 1);
        probe.thread(1, "worker-1", 120);
        probe.processCpu = 1_020;
        sampler.sweep(0, 2);

        Point recovered = track.points().get(0);
        assertThat(recovered.processCpuNanos()).isEqualTo(-1);
        assertThat(recovered.internalCpuNanos()).isEqualTo(-1);
        assertThat(recovered.familiesCpuNanos()).isEqualTo(10);
        assertBalanced(track.points().get(1));
    }

    @Test
    void intervalThreadCounterSkewPreservesMeasurementsAndMakesTheRemainderUnknown() {
        ResourceSampler sampler = sampler(1);
        probe.thread(1, "worker-1", 100, 1_000);
        sampler.sweep(0, 0);
        probe.thread(1, "worker-1", 130, 1_100);
        probe.processCpu = 20;
        sampler.sweep(0, 1);

        Point skewed = track.points().get(0);
        assertThat(skewed.processCpuNanos()).isEqualTo(20);
        assertThat(skewed.familiesCpuNanos()).isEqualTo(30);
        assertThat(skewed.internalCpuNanos()).isEqualTo(-1);
        assertThat(skewed.allocatedBytes()).isEqualTo(100);
        assertThat(track.totals().processCpuNanos()).isEqualTo(20);
        assertThat(track.totals().familyCpuNanos()).containsEntry("worker-N", 30L);
        assertThat(track.totals().internalCpuNanos()).isEqualTo(-1);

        probe.thread(1, "worker-1", 135, 1_200);
        probe.processCpu = 40;
        sampler.sweep(0, 2);
        assertBalanced(track.points().get(1));
        assertThat(track.totals().processCpuNanos()).isEqualTo(40);
        assertThat(track.totals().familyCpuNanos()).containsEntry("worker-N", 35L);
        assertThat(track.totals().internalCpuNanos()).isEqualTo(-1);

        track.clear();
        assertThat(track.totals().internalCpuNanos()).isZero();
        probe.thread(1, "worker-1", 140, 1_300);
        probe.processCpu = 60;
        sampler.sweep(0, 3);
        assertThat(track.totals().processCpuNanos()).isEqualTo(20);
        assertThat(track.totals().internalCpuNanos()).isEqualTo(15);
        assertThat(track.totals().familyCpuNanos()).containsEntry("worker-N", 5L);
    }

    @Test
    void intervalUnknownProcessMakesTheRunLedgerIncompleteEvenAfterRecovery() {
        ResourceSampler sampler = sampler(1);
        probe.thread(1, "worker-1", 100);
        probe.processCpu = -1;
        sampler.sweep(0, 0);
        probe.thread(1, "worker-1", 110);
        sampler.sweep(0, 1);
        assertThat(track.totals().processCpuNanos()).isZero();
        assertThat(track.totals().familyCpuNanos()).containsEntry("worker-N", 10L);
        assertThat(track.totals().internalCpuNanos()).isEqualTo(-1);

        probe.thread(1, "worker-1", 120);
        probe.processCpu = 1_000;
        sampler.sweep(0, 2);
        probe.thread(1, "worker-1", 130);
        probe.processCpu = 1_020;
        sampler.sweep(0, 3);

        assertBalanced(track.points().get(2));
        assertThat(track.totals().processCpuNanos()).isEqualTo(20);
        assertThat(track.totals().familyCpuNanos()).containsEntry("worker-N", 30L);
        assertThat(track.totals().internalCpuNanos()).isEqualTo(-1);
    }

    @Test
    void intervalZeroMeasuredProcessCpuIsNotInflatedByPositiveThreadCpu() {
        ResourceSampler sampler = sampler(1);
        probe.thread(1, "worker-1", 100);
        sampler.sweep(0, 0);
        probe.thread(1, "worker-1", 110);
        sampler.sweep(0, 1);

        Point point = track.points().get(0);
        assertThat(point.processCpuNanos()).isZero();
        assertThat(point.familiesCpuNanos()).isEqualTo(10);
        assertThat(point.internalCpuNanos()).isEqualTo(-1);
        assertThat(track.totals().processCpuNanos()).isZero();
        assertThat(track.totals().internalCpuNanos()).isEqualTo(-1);
    }

    @Test
    void intervalIncompleteRemainderStaysUnknownAfterItsPointLeavesTheRing() {
        track.add(new Point(0, 0, 1, 20, 0, -1, new long[] {30}, 0, 0, 0, 0, 0, 0, 0));
        for (int i = 1; i <= ResourceTrack.CAPACITY; i++) {
            track.add(new Point(i, i, 1, 20, 0, 10, new long[] {10}, 0, 0, 0, 0, 0, 0, 0));
        }

        assertThat(track.points()).hasSize(ResourceTrack.CAPACITY).allSatisfy(ResourceSamplerTests::assertBalanced);
        assertThat(track.totals().processCpuNanos()).isEqualTo(20L * (ResourceTrack.CAPACITY + 1));
        assertThat(track.totals().internalCpuNanos()).isEqualTo(-1);
    }

    @Test
    @SuppressWarnings("deprecation")
    void intervalFirstRequestObservationBaselinesAnOpenSegmentsProgress() {
        long main = Thread.currentThread().getId();
        ResourceSampler sampler = sampler(1);
        probe.thread(1_002, "old-worker-1", 1_000);
        sampler.sweep(0, 0);

        readings.set(Thread.currentThread(), 100, 0);
        assertThat(meter.begin("r1")).isTrue();
        try {
            probe.threads.clear();
            probe.thread(main, "http-nio-8080-exec-1", 1_000);
            probe.processCpu = 20;
            sampler.sweep(0, 1);
            Point firstSeen = track.points().get(0);
            assertThat(firstSeen.processCpuNanos()).isEqualTo(20);
            assertThat(firstSeen.requestCpuNanos()).isZero();
            assertThat(firstSeen.familiesCpuNanos()).isZero();
            assertThat(firstSeen.internalCpuNanos()).isEqualTo(20);
            assertBalanced(firstSeen);

            probe.thread(main, "http-nio-8080-exec-1", 1_010);
            probe.processCpu = 40;
            sampler.sweep(0, 2);
            Point measured = track.points().get(1);
            assertThat(measured.requestCpuNanos()).isEqualTo(10);
            assertThat(measured.familiesCpuNanos()).isZero();
            assertThat(measured.internalCpuNanos()).isEqualTo(10);
            assertBalanced(measured);
        } finally {
            meter.take("r1");
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    void intervalRecoveredRequestCpuDoesNotCreditWorkDuringTheUnknownInterval() {
        long main = Thread.currentThread().getId();
        ResourceSampler sampler = sampler(1);
        probe.thread(main, "http-nio-8080-exec-1", 100, 0);
        readings.set(Thread.currentThread(), 100, 0);
        assertThat(meter.begin("r1")).isTrue();
        try {
            sampler.sweep(0, 0);
            probe.thread(main, "http-nio-8080-exec-1", -1, 100);
            probe.processCpu = 20;
            sampler.sweep(0, 1);
            probe.thread(main, "http-nio-8080-exec-1", 1_000, 200);
            probe.processCpu = 40;
            sampler.sweep(0, 2);

            Point recovered = track.points().get(1);
            assertThat(recovered.processCpuNanos()).isEqualTo(20);
            assertThat(recovered.requestCpuNanos()).isZero();
            assertThat(recovered.familiesCpuNanos()).isZero();
            assertThat(recovered.internalCpuNanos()).isEqualTo(20);
            assertBalanced(recovered);

            readings.set(Thread.currentThread(), 1_010, 300);
            meter.switchTo(null);
            probe.thread(main, "http-nio-8080-exec-1", 1_020, 400);
            probe.processCpu = 70;
            sampler.sweep(0, 3);
            Point measured = track.points().get(2);
            assertThat(measured.requestCpuNanos()).isEqualTo(10);
            assertThat(measured.familiesCpuNanos()).isEqualTo(10);
            assertThat(measured.internalCpuNanos()).isEqualTo(10);
            assertBalanced(measured);
        } finally {
            meter.take("r1");
        }
    }

    @Test
    void theTrackKeepsItsMostRecentPointsAndTheRunsTotals() {
        track.family("pool-N-thread-N");
        for (int i = 0; i < ResourceTrack.CAPACITY + 5; i++) {
            track.add(new Point(i, i, 1, 10, 3, 2, new long[] {5}, 0, 0, 0, 0, 0, 0, 0));
        }

        assertThat(track.points()).hasSize(ResourceTrack.CAPACITY);
        assertThat(track.points().get(0).epochMillis()).isEqualTo(5);
        ResourceTrack.Totals totals = track.totals();
        assertThat(totals.sweeps()).isEqualTo(ResourceTrack.CAPACITY + 5);
        assertThat(totals.processCpuNanos()).isEqualTo(10L * (ResourceTrack.CAPACITY + 5));

        track.clear();
        assertThat(track.points()).isEmpty();
        assertThat(track.totals().sweeps()).isZero();
    }

    @Test
    void familiesBeyondTheCapShareTheOtherFamily() {
        for (int i = 0; i < ResourceTrack.MAX_FAMILIES; i++) {
            assertThat(track.family("family-" + (char) ('a' + i))).isEqualTo(i);
        }

        assertThat(track.family("one-too-many")).isEqualTo(ResourceTrack.MAX_FAMILIES);
        assertThat(track.family("another")).isEqualTo(ResourceTrack.MAX_FAMILIES);
        assertThat(track.families()).last().isEqualTo(ResourceTrack.OTHER_FAMILY);
    }

    @Test
    void theJvmSamplerRecordsBalancedPointsOnItsOwnThread() throws Exception {
        ResourceTrack jvmTrack = new ResourceTrack();
        try (ResourceSampler ignored =
                ResourceSampler.start(new ResourceSettings(Duration.ofMillis(100), 500), jvmTrack, () -> 7)) {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (jvmTrack.points().size() < 3 && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
        }

        assertThat(jvmTrack.points()).hasSizeGreaterThanOrEqualTo(3).allSatisfy(point -> {
            assertThat(point.sequence()).isEqualTo(7);
            assertThat(point.heapUsedBytes()).isPositive();
            assertThat(point.liveThreads()).isPositive();
            long measuredThreadCpu = point.requestCpuNanos() + point.familiesCpuNanos();
            assertThat(point.requestCpuNanos()).isNotNegative();
            assertThat(point.familiesCpuNanos()).isNotNegative();
            if (point.internalCpuNanos() >= 0) {
                assertThat(point.processCpuNanos()).isNotNegative();
                assertThat(measuredThreadCpu + point.internalCpuNanos()).isEqualTo(point.processCpuNanos());
            } else {
                assertThat(point.internalCpuNanos()).isEqualTo(-1);
                if (point.processCpuNanos() >= 0) {
                    assertThat(measuredThreadCpu).isGreaterThan(point.processCpuNanos());
                } else {
                    assertThat(point.processCpuNanos()).isEqualTo(-1);
                }
            }
        });
        assertThat(jvmTrack.families()).contains(ResourceTrack.BOOTUI_FAMILY);
    }

    @Test
    void theJournalStartsItsSamplerOnlyWhenItRecordsResources() {
        ResourceTrack journalTrack = new ResourceTrack();
        RuntimeJournalSettings withoutResources = RuntimeJournalSettings.of(true, 1_000, null, 100, "http,gc");
        try (RuntimeJournal journal = new RuntimeJournal(withoutResources, RunIdentity.start())) {
            assertThat(journal.startResourceSampler(ResourceSettings.defaults(), journalTrack))
                    .isFalse();
        }

        RuntimeJournal journal = new RuntimeJournal(RuntimeJournalSettings.defaults(), RunIdentity.start());
        assertThat(journal.startResourceSampler(ResourceSettings.defaults(), journalTrack))
                .isTrue();
        journal.close();
        assertThat(journal.startResourceSampler(ResourceSettings.defaults(), journalTrack))
                .isFalse();
    }

    @Test
    void settingsRejectAnIntervalBelow100MsAndANonPositiveCap() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ResourceSettings(Duration.ofMillis(10), 500))
                .withMessageContaining("bootui.resources.sample-interval");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ResourceSettings(Duration.ofSeconds(1), 0))
                .withMessageContaining("bootui.resources.max-threads");
    }

    private ResourceSampler sampler(int maxThreads) {
        return new ResourceSampler(
                new ResourceSettings(Duration.ofSeconds(1), maxThreads), track, meter, () -> 42, probe);
    }

    private Map<String, Long> byFamily(Point point) {
        Map<String, Long> families = new LinkedHashMap<>();
        long[] parts = point.familyCpuNanos();
        for (int i = 0; i < parts.length; i++) {
            if (parts[i] != 0) {
                families.put(track.families().get(i), parts[i]);
            }
        }
        return families;
    }

    private static void assertBalanced(Point point) {
        assertThat(point.requestCpuNanos() + point.familiesCpuNanos() + point.internalCpuNanos())
                .isEqualTo(point.processCpuNanos());
    }

    /** Threads, process CPU, and heap the test sets. */
    private static final class FakeProbe implements ResourceSampler.Probe {

        private final Map<Long, ThreadReading> threads = new LinkedHashMap<>();
        private long processCpu;

        void thread(long id, String name, long cpu) {
            thread(id, name, cpu, 0);
        }

        void thread(long id, String name, long cpu, long allocated) {
            threads.put(id, new ThreadReading(id, name, cpu, allocated));
        }

        @Override
        public Sweep threads(int maxThreads) {
            List<ThreadReading> read = new ArrayList<>(threads.values());
            int unread = Math.max(0, read.size() - maxThreads);
            return new Sweep(read.subList(0, read.size() - unread), unread);
        }

        @Override
        public long processCpuNanos() {
            return processCpu;
        }

        @Override
        public long[] heap() {
            return new long[] {64, 128, 32};
        }

        @Override
        public int[] threadCounts() {
            return new int[] {threads.size(), 1};
        }
    }
}
