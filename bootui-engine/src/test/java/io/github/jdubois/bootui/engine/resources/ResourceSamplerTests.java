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
            if (point.processCpuNanos() >= 0) {
                assertThat(point.requestCpuNanos() + point.familiesCpuNanos() + point.internalCpuNanos())
                        .isEqualTo(point.processCpuNanos());
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

    /** Threads, process CPU, and heap the test sets. */
    private static final class FakeProbe implements ResourceSampler.Probe {

        private final Map<Long, ThreadReading> threads = new LinkedHashMap<>();
        private long processCpu;

        void thread(long id, String name, long cpu) {
            threads.put(id, new ThreadReading(id, name, cpu, 0));
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
