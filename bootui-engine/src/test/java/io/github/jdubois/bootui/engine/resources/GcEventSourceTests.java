package io.github.jdubois.bootui.engine.resources;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.GcPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeEventSink;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class GcEventSourceTests {

    @Test
    void aCollectionBecomesOneGcEventKeyedByCollectorAndId() throws Exception {
        List<RuntimeEvent> events = new CopyOnWriteArrayList<>();
        RuntimeEventSink sink = event -> events.add(event);
        try (GcEventSource source = GcEventSource.start(sink)) {
            assertThat(source.collectors()).isPositive();

            System.gc();
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (events.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
        }

        assertThat(events).isNotEmpty().allSatisfy(event -> {
            assertThat(event.source()).isEqualTo(JournalSource.GC);
            assertThat(event.requestId()).isNull();
            assertThat(event.epochMillis()).isPositive();
            GcPayload gc = (GcPayload) event.payload();
            assertThat(gc.collector()).isNotBlank();
            assertThat(gc.gcId()).isPositive();
            assertThat(gc.heapAfterBytes()).isNotNegative();
        });
    }

    @Test
    void aClosedSourcePublishesNothingMore() throws Exception {
        List<RuntimeEvent> events = new CopyOnWriteArrayList<>();
        GcEventSource source = GcEventSource.start(event -> events.add(event));
        source.close();

        System.gc();
        Thread.sleep(200);

        assertThat(events).isEmpty();
        assertThat(source.collectors()).isZero();
    }

    @Test
    void theOldGenerationIsRecognizedByItsPoolNameInEveryGenerationalCollector() {
        assertThat(List.of("G1 Old Gen", "PS Old Gen", "Tenured Gen", "ZGC Old Generation"))
                .allMatch(GcEventSource::isOldGeneration);
        assertThat(List.of("G1 Eden Space", "G1 Survivor Space", "ZGC Young Generation", "Shenandoah", "ZHeap"))
                .noneMatch(GcEventSource::isOldGeneration);
        GcPayload full = (GcPayload)
                GcEventSource.event("G1 Old Generation", 1, "end of major GC", "System.gc()", 0, 30, 90, 40, 80, 35)
                        .payload();
        assertThat(full.reclaimedOldGeneration()).isTrue();
        GcPayload young = (GcPayload) GcEventSource.event(
                        "G1 Young Generation", 2, "end of minor GC", "G1 Evacuation Pause", 0, 3, 90, 50, 35, 38)
                .payload();
        assertThat(young.reclaimedOldGeneration())
                .as("a young collection only promotes")
                .isFalse();
    }

    @Test
    void aConcurrentCycleIsNeverAPause() {
        GcPayload cycle =
                (GcPayload) GcEventSource.event("ZGC Major Cycles", 3, "end of GC cycle", "Proactive", 0, 40, 9, 8)
                        .payload();
        GcPayload pause =
                (GcPayload) GcEventSource.event("ZGC Major Pauses", 6, "end of GC pause", "Proactive", 0, 1, 9, 8)
                        .payload();

        assertThat(cycle.pause()).isFalse();
        assertThat(pause.pause()).isTrue();
        assertThat(GcEventSource.event("G1 Young Generation", 1, "a", "c", 0, 7, 0, 0)
                        .durationNanos())
                .as("the JVM reports whole milliseconds")
                .isEqualTo(7_000_000);
    }

    /**
     * §5.11 on every collector BootUI supports: each GarbageCollectorMXBean the JDK registers for G1, Parallel, Serial,
     * ZGC (generational or not), and Shenandoah is a pause collector or a concurrent cycle, and a concurrent cycle is
     * never counted as a pause.
     */
    @org.junit.jupiter.params.ParameterizedTest(name = "{0}: {1}")
    @org.junit.jupiter.params.provider.CsvSource({
        "G1, G1 Young Generation, true",
        "G1, G1 Old Generation, true",
        "G1, G1 Concurrent GC, true",
        "Parallel, PS Scavenge, true",
        "Parallel, PS MarkSweep, true",
        "Serial, Copy, true",
        "Serial, MarkSweepCompact, true",
        "ZGC, ZGC Pauses, true",
        "ZGC, ZGC Cycles, false",
        "ZGC, ZGC Minor Pauses, true",
        "ZGC, ZGC Minor Cycles, false",
        "ZGC, ZGC Major Pauses, true",
        "ZGC, ZGC Major Cycles, false",
        "Shenandoah, Shenandoah Pauses, true",
        "Shenandoah, Shenandoah Cycles, false"
    })
    void everyCollectorsBeansAreClassifiedAndItsConcurrentCyclesAreNeverPauses(
            String collector, String bean, boolean pause) {
        GcPayload gc = (GcPayload) GcEventSource.event(bean, 4, "end of GC", "Allocation Failure", 0, 12, 90, 40)
                .payload();

        assertThat(gc.pause()).as(collector + "'s " + bean).isEqualTo(pause);
        assertThat(gc.collector()).isEqualTo(bean);
        assertThat(gc.gcId()).isEqualTo(4);
    }

    @Test
    void theJournalStartsItsGcSourceOnlyWhenItRecordsGcAndStopsItWhenTheRunEnds() {
        RuntimeJournalSettings withoutGc = RuntimeJournalSettings.of(true, 1_000, null, 100, "http,sql");
        try (RuntimeJournal journal = new RuntimeJournal(withoutGc, RunIdentity.start())) {
            assertThat(journal.startGcSource()).isFalse();
        }

        RuntimeJournal journal = new RuntimeJournal(RuntimeJournalSettings.defaults(), RunIdentity.start());
        assertThat(journal.startGcSource()).isTrue();
        assertThat(journal.startGcSource()).as("idempotent").isTrue();
        journal.close();
        assertThat(journal.startGcSource()).isFalse();
    }
}
