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
