package io.github.jdubois.bootui.autoconfigure.idle;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ConsoleActivityTrackerTests {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private ConsoleActivityTracker tracker(AtomicLong clock, IdleReclaimable... buffers) {
        return new ConsoleActivityTracker(TIMEOUT, List.of(buffers), false, clock::get);
    }

    @Test
    void suspendsBuffersOnceTheTimeoutElapses() {
        AtomicLong clock = new AtomicLong(0);
        RecordingBuffer buffer = new RecordingBuffer();
        ConsoleActivityTracker tracker = tracker(clock, buffer);

        clock.set(11_000);
        tracker.reclaimIfIdle();

        assertThat(tracker.isIdle()).isTrue();
        assertThat(buffer.suspends).isEqualTo(1);
        assertThat(buffer.resumes).isZero();
    }

    @Test
    void doesNotSuspendBeforeTheTimeoutElapses() {
        AtomicLong clock = new AtomicLong(0);
        RecordingBuffer buffer = new RecordingBuffer();
        ConsoleActivityTracker tracker = tracker(clock, buffer);

        clock.set(9_999);
        tracker.reclaimIfIdle();

        assertThat(tracker.isIdle()).isFalse();
        assertThat(buffer.suspends).isZero();
    }

    @Test
    void markActiveResumesBuffersAfterIdle() {
        AtomicLong clock = new AtomicLong(0);
        RecordingBuffer buffer = new RecordingBuffer();
        ConsoleActivityTracker tracker = tracker(clock, buffer);

        clock.set(11_000);
        tracker.reclaimIfIdle();
        assertThat(tracker.isIdle()).isTrue();

        clock.set(12_000);
        tracker.markActive();

        assertThat(tracker.isIdle()).isFalse();
        assertThat(buffer.resumes).isEqualTo(1);
    }

    @Test
    void activityResetsTheIdleTimer() {
        AtomicLong clock = new AtomicLong(0);
        RecordingBuffer buffer = new RecordingBuffer();
        ConsoleActivityTracker tracker = tracker(clock, buffer);

        clock.set(9_000);
        tracker.markActive();
        clock.set(11_000);
        tracker.reclaimIfIdle();

        assertThat(tracker.isIdle()).isFalse();
        assertThat(buffer.suspends).isZero();
    }

    @Test
    void markActiveWhileActiveDoesNotRepeatedlyResume() {
        AtomicLong clock = new AtomicLong(0);
        RecordingBuffer buffer = new RecordingBuffer();
        ConsoleActivityTracker tracker = tracker(clock, buffer);

        tracker.markActive();
        tracker.markActive();

        assertThat(buffer.resumes).isZero();
    }

    @Test
    void reclaimIsIdempotentWhileIdle() {
        AtomicLong clock = new AtomicLong(0);
        RecordingBuffer buffer = new RecordingBuffer();
        ConsoleActivityTracker tracker = tracker(clock, buffer);

        clock.set(11_000);
        tracker.reclaimIfIdle();
        tracker.reclaimIfIdle();

        assertThat(buffer.suspends).isEqualTo(1);
    }

    @Test
    void aMisbehavingBufferDoesNotStopTheOthers() {
        AtomicLong clock = new AtomicLong(0);
        RecordingBuffer healthy = new RecordingBuffer();
        ConsoleActivityTracker tracker = tracker(clock, new ThrowingBuffer(), healthy);

        clock.set(11_000);
        tracker.reclaimIfIdle();

        assertThat(tracker.isIdle()).isTrue();
        assertThat(healthy.suspends).isEqualTo(1);
    }

    private static final class RecordingBuffer implements IdleReclaimable {
        int suspends;
        int resumes;

        @Override
        public void suspendForIdle() {
            suspends++;
        }

        @Override
        public void resumeFromIdle() {
            resumes++;
        }
    }

    private static final class ThrowingBuffer implements IdleReclaimable {
        @Override
        public void suspendForIdle() {
            throw new IllegalStateException("boom");
        }

        @Override
        public void resumeFromIdle() {
            throw new IllegalStateException("boom");
        }
    }
}
