package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.LogLineDto;
import io.github.jdubois.bootui.core.dto.LowFootprintStatus;
import io.github.jdubois.bootui.core.dto.MemoryOffloadStoreDto;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import io.github.jdubois.bootui.engine.logtail.LogTailBuffer;
import io.github.jdubois.bootui.engine.memory.LowFootprintService;
import io.github.jdubois.bootui.engine.memory.MemoryOffloadService;
import io.github.jdubois.bootui.engine.resources.ResourceSettings;
import io.github.jdubois.bootui.engine.resources.ResourceTrack;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.github.jdubois.bootui.spi.LowFootprintSuspendable;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Low-footprint mode against the real journal, exception store, and log tail. */
class LowFootprintJournalTests {

    private final RuntimeJournal journal = new RuntimeJournal(
            new RuntimeJournalSettings(true, 10_000, 50_000_000, 10_000, 10, 10, JournalSource.all()),
            RunIdentity.start());

    @AfterEach
    void close() {
        journal.close();
    }

    @Test
    void aPausedJournalRefusesEventsWithoutCountingThemAsDroppedAndRecordsAgainOnResume() throws Exception {
        assertThat(journal.offer(sql())).isTrue();
        assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();

        journal.suspendForLowFootprint();
        assertThat(journal.paused()).isTrue();
        assertThat(journal.offer(sql())).isFalse();
        assertThat(journal.offerImported(ai())).isFalse();
        assertThat(journal.offerAgentRecord(sql())).isFalse();
        assertThat(journal.status().accepted().values().stream()
                        .mapToLong(Long::longValue)
                        .sum())
                .isEqualTo(1);
        assertThat(journal.status().dropped()).isEmpty();

        journal.resumeFromLowFootprint();
        assertThat(journal.offer(sql())).isTrue();
        assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();
        assertThat(journal.entries()).hasSize(2);
        assertThat(journal.lostMillis(JournalSource.SQL))
                .as("the pause is a gap the readers of evidence must know")
                .isNotNull();
    }

    @Test
    void aControlMarkerIsStillRecordedWhilePausedSoThePauseIsExplicitInTheHistory() throws Exception {
        journal.suspendForLowFootprint();

        assertThat(ControlMarkers.action(journal, "live-memory", "POST", "/live-memory/footprint"))
                .isTrue();
        assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();

        assertThat(journal.entries()).hasSize(1);
    }

    @Test
    void theDispatcherParksWhilePausedYetStillProcessesAMarkerAtOnce() throws Exception {
        journal.suspendForLowFootprint();
        Thread.sleep(600); // lets the dispatcher enter its long wait

        ControlMarkers.action(journal, "live-memory", "POST", "/live-memory/footprint");

        assertThat(journal.awaitDrained(Duration.ofSeconds(2))).isTrue();
        assertThat(journal.entries()).hasSize(1);
    }

    @Test
    void aClosedPausedJournalStillShutsDownPromptly() {
        journal.suspendForLowFootprint();
        long start = System.nanoTime();

        journal.close();

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void gcSourceAndResourceSamplerAreStoppedThenRestartedWithAFreshBaseline() {
        ResourceTrack track = new ResourceTrack();
        assertThat(journal.startGcSource()).isTrue();
        assertThat(journal.startResourceSampler(new ResourceSettings(Duration.ofMillis(100), 100, null), track))
                .isTrue();
        assertThat(journal.resourceSamplerRunning()).isTrue();
        List<LowFootprintSuspendable> parts = journal.suspendableParts();
        assertThat(parts)
                .extracting(LowFootprintSuspendable::suspendId)
                .containsExactly("runtime-journal", "gc-events", "resource-sampler");
        Set<String> before = threadNames();
        assertThat(before).contains("bootui-resources");

        parts.forEach(LowFootprintSuspendable::suspendForLowFootprint);

        assertThat(journal.resourceSamplerRunning()).isFalse();
        assertThat(threadNames()).doesNotContain("bootui-resources");
        assertThat(journal.startGcSource())
                .as("a start request during the pause does not bring the listener back")
                .isFalse();

        parts.forEach(LowFootprintSuspendable::resumeFromLowFootprint);

        assertThat(journal.resourceSamplerRunning()).isTrue();
        assertThat(threadNames()).contains("bootui-resources");
        assertThat(journal.startGcSource()).isTrue();
    }

    @Test
    void exceptionStoreAndLogTailPauseAndResume() {
        ExceptionStore store = new ExceptionStore(100, 25, 50);
        LogTailBuffer tail = new LogTailBuffer();
        tail.add(new LogLineDto(1, "INFO", "app", "before", "main", false));

        store.suspendForLowFootprint();
        tail.suspendForLowFootprint();
        store.record(new IllegalStateException("paused"), "main", null, null, null, "log");
        tail.add(new LogLineDto(2, "INFO", "app", "while paused", "main", false));

        assertThat(store.totalExceptions()).isZero();
        assertThat(tail.recent())
                .as("retained lines are dropped and none is added")
                .isEmpty();

        store.resumeFromLowFootprint();
        tail.resumeFromLowFootprint();
        store.record(new IllegalStateException("resumed"), "main", null, null, null, "log");
        tail.add(new LogLineDto(3, "INFO", "app", "after", "main", false));

        assertThat(store.totalExceptions()).isEqualTo(1);
        assertThat(tail.recent()).extracting(LogLineDto::message).containsExactly("after");
    }

    @Test
    void theServiceDrivesTheRealComponentsAndTheIdleHoldCoversTheWholeMode() throws Exception {
        ExceptionStore store = new ExceptionStore(100, 25, 50);
        List<Object> candidates = List.of(journal, store);
        LowFootprintService service =
                new LowFootprintService(() -> candidates, new MemoryOffloadService(() -> candidates));
        assertThat(journal.offer(sql())).isTrue();
        assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();

        LowFootprintStatus on = service.enable();

        assertThat(on.components()).allMatch(c -> c.suspended() && c.failure() == null);
        assertThat(on.components().stream().map(c -> c.id()).collect(Collectors.toList()))
                .contains("runtime-journal", "exceptions");
        assertThat(on.offload().stores()).extracting(MemoryOffloadStoreDto::id).contains("runtime-journal");
        assertThat(journal.entries()).as("the offload emptied the journal").isEmpty();
        assertThat(journal.offer(sql())).isFalse();

        service.disable();

        assertThat(journal.offer(sql())).isTrue();
    }

    private static Set<String> threadNames() {
        return Thread.getAllStackTraces().keySet().stream().map(Thread::getName).collect(Collectors.toSet());
    }

    private static RuntimeEvent sql() {
        return RuntimeEvent.of(
                JournalSource.SQL,
                1,
                1,
                CorrelationContext.NONE,
                "worker",
                null,
                false,
                new SqlPayload("select 1", null, "db", false));
    }

    private static RuntimeEvent ai() {
        return RuntimeEvent.of(
                JournalSource.AI,
                1,
                1,
                CorrelationContext.NONE,
                "worker",
                null,
                false,
                new SqlPayload("x", null, "db", false));
    }
}
