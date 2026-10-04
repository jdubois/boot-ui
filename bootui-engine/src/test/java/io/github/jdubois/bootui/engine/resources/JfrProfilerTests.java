package io.github.jdubois.bootui.engine.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.correlation.RequestIds;
import io.github.jdubois.bootui.engine.resources.JfrProfiler.RequestSamples;
import io.github.jdubois.bootui.engine.resources.JfrProfiler.Snapshot;
import io.github.jdubois.bootui.engine.resources.JfrProfiler.State;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

/** §5.11's acceptance: a real JFR recording joins samples to the right request, including on a virtual thread. */
class JfrProfilerTests {

    private static volatile long sink;

    private final JfrProfiler profiler =
            new JfrProfiler(type -> type.startsWith("java.") || type.startsWith("jdk.") || type.startsWith("sun."));

    @Test
    void aSessionJoinsEachRequestsSamplesToItsSegmentsOnPlatformAndVirtualThreads() throws Exception {
        assumeThat(JfrProfiler.unavailableReason()).as("JFR is available").isNull();
        Method startVirtual = virtualThreads();
        assertThat(profiler.snapshot().state()).as("never started on its own").isEqualTo(State.IDLE);

        Snapshot running = profiler.start(Duration.ofMinutes(1));
        assertThat(running.state()).isEqualTo(State.RUNNING);
        assertThat(running.sampler()).isIn(JfrProfiler.EXECUTION_SAMPLER, JfrProfiler.CPU_TIME_SAMPLER);
        assertThat(profiler.start(Duration.ofMinutes(1)).startedAt())
                .as("a second start joins the running session")
                .isEqualTo(running.startedAt());

        String platform = RequestIds.next();
        Thread worker = new Thread(() -> work(platform));
        worker.start();
        worker.join();
        String virtual = null;
        if (startVirtual != null) {
            String id = RequestIds.next();
            virtual = id;
            ((Thread) startVirtual.invoke(null, (Runnable) () -> work(id))).join();
        }

        Snapshot done = profiler.stop();

        assertThat(done.state()).isEqualTo(State.COMPLETED);
        assertThat(done.finishedAt()).isNotNull();
        assertThat(JfrSegments.active()).as("segments are no longer events").isFalse();
        assertThat(done.analysis().cpuSamples()).isPositive();
        RequestSamples onPlatform = done.analysis().requests().get(platform);
        assertThat(onPlatform).isNotNull();
        assertThat(onPlatform.cpuSamples()).isPositive();
        assertThat(onPlatform.virtualThread()).isFalse();
        assertThat(onPlatform.frames().keySet()).anyMatch(frame -> frame.contains("JfrProfilerTests.burn"));
        if (virtual != null) {
            RequestSamples onVirtual = done.analysis().requests().get(virtual);
            assertThat(onVirtual)
                    .as("a virtual thread's samples join its request")
                    .isNotNull();
            assertThat(onVirtual.cpuSamples()).isPositive();
            assertThat(onVirtual.virtualThread()).isTrue();
        }
        assertThat(done.analysis().requests().values().stream()
                        .mapToLong(RequestSamples::allocatedBytes)
                        .sum())
                .isPositive();
        assertThat(profiler.stop().state()).as("stopping twice is harmless").isEqualTo(State.COMPLETED);
    }

    @Test
    void aThreadThatLeftARequestStopsContributingSamplesToIt() throws Exception {
        assumeThat(JfrProfiler.unavailableReason()).as("JFR is available").isNull();
        profiler.start(Duration.ofMinutes(1));
        String id = RequestIds.next();
        SegmentMeter meter = SegmentMeter.shared();
        CountDownLatch worked = new CountDownLatch(1);
        CountDownLatch taken = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            meter.begin(id);
            burn(600);
            meter.switchTo(null); // the request's chain is done with this thread
            burnBackInThePool(600);
            worked.countDown();
            try {
                taken.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        worker.start();
        worked.await();
        meter.take(id);
        taken.countDown();
        worker.join();

        Snapshot done = profiler.stop();

        RequestSamples samples = done.analysis().requests().get(id);
        assertThat(samples).isNotNull();
        assertThat(samples.frames().keySet())
                .as("the request keeps the samples of the work the thread did for it")
                .anyMatch(frame -> frame.contains("JfrProfilerTests.burn")
                        && !frame.contains("JfrProfilerTests.burnBackInThePool"));
        assertThat(samples.frames().keySet())
                .as("what the thread does back in its pool, before the request is taken, is not the request's")
                .noneMatch(frame -> frame.contains("JfrProfilerTests.burnBackInThePool"));
    }

    @Test
    void aSegmentTakenByAnotherThreadStillJoinsItsOwnThreadsSamples() throws Exception {
        assumeThat(JfrProfiler.unavailableReason()).as("JFR is available").isNull();
        profiler.start(Duration.ofMinutes(1));
        String id = RequestIds.next();
        CountDownLatch worked = new CountDownLatch(1);
        CountDownLatch taken = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            SegmentMeter.shared().begin(id);
            burn(600);
            worked.countDown();
            try {
                taken.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        worker.start();
        worked.await();
        SegmentMeter.shared().take(id);
        taken.countDown();
        worker.join();

        Snapshot done = profiler.stop();

        RequestSamples joined = done.analysis().requests().get(id);
        assertThat(joined)
                .as("samples of the worker's segment join its request")
                .isNotNull();
        assertThat(joined.cpuSamples()).isPositive();
        assertThat(joined.frames().keySet()).anyMatch(frame -> frame.contains("JfrProfilerTests.burn"));
    }

    @Test
    void withoutASessionASegmentCreatesNoEvent() {
        assertThat(JfrSegments.active()).isFalse();
        assertThat(JfrSegments.begin("r-1")).isNull();
        JfrSegments.end(null);
    }

    private static void work(String requestId) {
        SegmentMeter meter = SegmentMeter.shared();
        meter.begin(requestId);
        meter.switchTo(null);
        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(CorrelationContext.forRequest(requestId))) {
            burn(600);
        }
        meter.take(requestId);
    }

    private static void burn(long millis) {
        long end = System.nanoTime() + millis * 1_000_000;
        long sum = 0;
        while (System.nanoTime() < end) {
            sum += new byte[256].length + Long.toString(sum).length();
        }
        sink = sum;
    }

    private static void burnBackInThePool(long millis) {
        long end = System.nanoTime() + millis * 1_000_000;
        long sum = 0;
        while (System.nanoTime() < end) {
            sum += new byte[256].length + Long.toString(sum).length();
        }
        sink = sum;
    }

    private static Method virtualThreads() {
        try {
            return Thread.class.getMethod("startVirtualThread", Runnable.class);
        } catch (NoSuchMethodException ex) {
            return null;
        }
    }
}
