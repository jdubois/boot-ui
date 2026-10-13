package io.github.jdubois.bootui.engine.memory;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.cache.CacheActivityRecorder;
import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder;
import io.github.jdubois.bootui.engine.scheduled.ScheduledTaskRunStore;
import io.github.jdubois.bootui.spi.LowFootprintSuspendable;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The recorders low-footprint mode pauses drop what they would record, then record again once resumed. */
class LowFootprintRecorderPauseTests {

    @Test
    void kafkaStopsRecordingWhilePausedAndKeepsNothing() {
        KafkaActivityRecorder recorder = new KafkaActivityRecorder(true, false, 10, 16);
        recorder.recordProduce("t", 0, null, null, true, null);
        assertThat(recorder.recent()).hasSize(1);

        recorder.suspendForLowFootprint();
        assertThat(recorder.recent()).isEmpty();
        recorder.recordProduce("t", 0, null, null, true, null);
        recorder.recordConsume("t", 0, 1L, null, null, true, null, "g", "l");
        assertThat(recorder.recent()).isEmpty();

        recorder.resumeFromLowFootprint();
        recorder.recordProduce("t", 0, null, null, true, null);
        assertThat(recorder.recent()).hasSize(1);
    }

    @Test
    void scheduledRunsAreSkippedWhilePaused() {
        ScheduledTaskRunStore store = new ScheduledTaskRunStore(10);
        store.suspendForLowFootprint();
        store.record("job", 1L, 1L, true, null, null, "main");
        assertThat(store.runs()).isEmpty();

        store.resumeFromLowFootprint();
        store.record("job", 1L, 1L, true, null, null, "main");
        assertThat(store.runs()).hasSize(1);
    }

    @Test
    void cacheEventsAreSkippedWhilePaused() {
        CacheActivityRecorder recorder = new CacheActivityRecorder(true, 10);
        recorder.suspendForLowFootprint();
        recorder.recordClear("m", "c");
        assertThat(recorder.offloadRetainedData()).isZero();

        recorder.resumeFromLowFootprint();
        recorder.recordClear("m", "c");
        assertThat(recorder.offloadRetainedData()).isEqualTo(1);
    }

    @Test
    void everyPausableRecorderDeclaresAStableIdAndLabel() {
        List<LowFootprintSuspendable> parts = List.of(
                new KafkaActivityRecorder(true, false, 10, 16),
                new ScheduledTaskRunStore(10),
                new CacheActivityRecorder(true, 10),
                new ExceptionStore(10, 5, 10));
        assertThat(parts).allSatisfy(part -> {
            assertThat(part.suspendId()).isNotBlank();
            assertThat(part.suspendLabel()).isNotBlank();
        });
    }
}
