package io.github.jdubois.bootui.engine.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.engine.journal.AiCallEvents;
import io.github.jdubois.bootui.engine.journal.AiPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.junit.jupiter.api.Test;

class TelemetryStoreTests {

    private static NormalizedSpan span(String traceId, String spanId) {
        return new NormalizedSpan(
                traceId,
                spanId,
                null,
                "GET /sample",
                "SERVER",
                "sample",
                "test",
                1L,
                2L,
                "OK",
                null,
                Map.of(),
                List.of());
    }

    @Test
    void theGenAiSpanOfACallTheFrameworkAlreadyReportedIsNotPublishedTwice() {
        TelemetryStore store = new TelemetryStore(TelemetrySettings.of(true, true, 500, 500, 4096));
        List<RuntimeEvent> published = new ArrayList<>();
        store.setRuntimeEventSink(published::add);
        AiCallEvents.publish(
                published::add,
                CorrelationContext.forRequest("r1"),
                "trace-native",
                "span-native",
                5,
                1,
                null,
                new AiPayload("chat", "openai", "gpt-4o", null, null, null, false));
        NormalizedSpan chat = new NormalizedSpan(
                "trace-native",
                "span-native",
                null,
                "chat gpt-4o",
                "CLIENT",
                "sample",
                "spring-ai",
                5_000_000L,
                45_000_000L,
                "OK",
                null,
                Map.of("gen_ai.operation.name", AttributeValue.ofString("chat")),
                List.of());

        store.add(chat);

        assertThat(published).as("only the framework's own event").hasSize(1);
    }

    @Test
    void storedAiSpansArePublishedToTheJournalAsMetadataLinkedByTraceId() {
        TelemetryStore store = new TelemetryStore(TelemetrySettings.of(true, true, 500, 500, 4096));
        List<RuntimeEvent> published = new ArrayList<>();
        store.setRuntimeEventSink(published::add);
        NormalizedSpan chat = new NormalizedSpan(
                "trace-1",
                "span-1",
                null,
                "chat gpt-4o",
                "CLIENT",
                "sample",
                "spring-ai",
                5_000_000L,
                45_000_000L,
                "OK",
                null,
                Map.of(
                        "gen_ai.operation.name", AttributeValue.ofString("chat"),
                        "gen_ai.system", AttributeValue.ofString("openai"),
                        "gen_ai.request.model", AttributeValue.ofString("gpt-4o"),
                        "gen_ai.usage.input_tokens", AttributeValue.ofNumber(1200),
                        "gen_ai.usage.output_tokens", AttributeValue.ofNumber(300),
                        "gen_ai.response.finish_reasons", AttributeValue.ofList(List.of("length")),
                        "gen_ai.prompt", AttributeValue.ofString("never published")),
                List.of());

        store.add(chat);
        store.add(span("trace-2", "span-2"));
        store.add(span("trace-3", "span-3"), true);

        assertThat(published).singleElement().satisfies(event -> {
            assertThat(event.source()).isEqualTo(JournalSource.AI);
            assertThat(event.requestId()).isNull();
            assertThat(event.traceId()).isEqualTo("trace-1");
            assertThat(event.epochMillis()).isEqualTo(5);
            assertThat(event.durationNanos()).isEqualTo(40_000_000L);
            AiPayload ai = (AiPayload) event.payload();
            assertThat(ai).isEqualTo(new AiPayload("chat", "openai", "gpt-4o", 1200L, 300L, "length", false, "span-1"));
            assertThat(ai.lengthLimited()).isTrue();
        });
    }

    @Test
    void storeClampsConfiguredTraceCapacity() {
        TelemetryStore store = new TelemetryStore(TelemetrySettings.of(true, true, 0, 500, 4096));

        store.add(span("trace-a", "span-a"));
        store.add(span("trace-b", "span-b"));

        assertThat(store.capacity()).isEqualTo(1);
        assertThat(store.retainedTraceCount()).isEqualTo(1);
        assertThat(store.findTrace("trace-a")).isNull();
        assertThat(store.findTrace("trace-b")).isNotNull();
    }

    @Test
    void storeClampsConfiguredSpanCapacity() {
        TelemetryStore store = new TelemetryStore(TelemetrySettings.of(true, true, 500, Integer.MAX_VALUE, 4096));

        for (int i = 0; i < TelemetryStore.HARD_MAX_SPANS_PER_TRACE + 5; i++) {
            store.add(span("trace-a", "span-" + i));
        }

        assertThat(store.findTrace("trace-a").spans()).hasSize(TelemetryStore.HARD_MAX_SPANS_PER_TRACE);
    }

    @Test
    void suspendForIdleClearsAndStopsIngestionUntilResumed() {
        TelemetryStore store = new TelemetryStore(TelemetrySettings.of(true, true, 500, 500, 4096));
        assertThat(store.add(span("trace-a", "span-a"), false)).isTrue();
        assertThat(store.retainedTraceCount()).isEqualTo(1);

        store.suspendForIdle();
        assertThat(store.retainedTraceCount()).isZero();
        assertThat(store.add(span("trace-b", "span-b"), false)).isFalse();
        assertThat(store.retainedTraceCount()).isZero();

        store.resumeFromIdle();
        assertThat(store.add(span("trace-c", "span-c"), false)).isTrue();
        assertThat(store.retainedTraceCount()).isEqualTo(1);
    }

    @Test
    void traceReadsReturnIsolatedImmutableSnapshots() {
        TelemetryStore store = new TelemetryStore(TelemetrySettings.of(true, true, 500, 500, 4096));
        store.add(span("trace-a", "span-a"));

        List<TelemetryStore.TraceBucket> recentSnapshot = store.recentTraces(10);
        TelemetryStore.TraceBucket foundSnapshot = store.findTrace("trace-a");
        store.add(span("trace-a", "span-b"));

        assertThat(recentSnapshot)
                .singleElement()
                .satisfies(bucket -> assertThat(bucket.spans())
                        .extracting(NormalizedSpan::spanId)
                        .containsExactly("span-a"));
        assertThat(foundSnapshot.spans()).extracting(NormalizedSpan::spanId).containsExactly("span-a");
        assertThat(store.findTrace("trace-a").spans())
                .extracting(NormalizedSpan::spanId)
                .containsExactly("span-a", "span-b");
        assertThatThrownBy(() -> recentSnapshot.add(foundSnapshot)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> foundSnapshot.spans().add(span("trace-a", "span-c")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void concurrentIngestionAndReadsUseConsistentSnapshots() throws Exception {
        TelemetryStore store = new TelemetryStore(TelemetrySettings.of(true, true, 500, 1000, 4096));
        int writerCount = 4;
        int spansPerWriter = 200;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(writerCount + 1);
        try {
            List<Future<?>> writers = new ArrayList<>();
            for (int writer = 0; writer < writerCount; writer++) {
                int writerId = writer;
                writers.add(executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < spansPerWriter; i++) {
                        store.add(span("trace-a", "span-" + writerId + "-" + i));
                    }
                    return null;
                }));
            }
            Future<?> reader = executor.submit(() -> {
                start.await();
                for (int i = 0; i < 2_000; i++) {
                    for (TelemetryStore.TraceBucket bucket : store.recentTraces(10)) {
                        for (NormalizedSpan storedSpan : bucket.spans()) {
                            assertThat(storedSpan.traceId()).isEqualTo(bucket.traceId());
                        }
                    }
                    TelemetryStore.TraceBucket found = store.findTrace("trace-a");
                    if (found != null) {
                        assertThat(found.spans())
                                .allSatisfy(storedSpan ->
                                        assertThat(storedSpan.traceId()).isEqualTo(found.traceId()));
                    }
                }
                return null;
            });

            start.countDown();
            for (Future<?> writer : writers) {
                writer.get(10, TimeUnit.SECONDS);
            }
            reader.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(store.findTrace("trace-a").spans()).hasSize(writerCount * spansPerWriter);
    }

    @Test
    void addRechecksIdleSuspensionAfterWaitingForWriteLock() throws Exception {
        TelemetryStore store = new TelemetryStore(TelemetrySettings.of(true, true, 500, 500, 4096));
        ReentrantReadWriteLock storeLock = storeLock(store);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicReference<Thread> addThread = new AtomicReference<>();
        storeLock.readLock().lock();
        try {
            Future<Boolean> add = executor.submit(() -> {
                addThread.set(Thread.currentThread());
                return store.add(span("trace-a", "span-a"), false);
            });
            awaitCondition(() -> addThread.get() != null && storeLock.hasQueuedThread(addThread.get()));

            Future<?> suspend = executor.submit(store::suspendForIdle);
            awaitCondition(() -> storeLock.getQueueLength() == 2);

            storeLock.readLock().unlock();
            assertThat(add.get(10, TimeUnit.SECONDS)).isFalse();
            suspend.get(10, TimeUnit.SECONDS);
        } finally {
            if (storeLock.getReadHoldCount() > 0) {
                storeLock.readLock().unlock();
            }
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(store.retainedTraceCount()).isZero();
    }

    private static ReentrantReadWriteLock storeLock(TelemetryStore store) throws Exception {
        Field lockField = TelemetryStore.class.getDeclaredField("lock");
        lockField.setAccessible(true);
        return (ReentrantReadWriteLock) lockField.get(store);
    }

    private static void awaitCondition(Condition condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.evaluate()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Condition was not met before timeout");
            }
            Thread.sleep(1);
        }
    }

    @FunctionalInterface
    private interface Condition {

        boolean evaluate();
    }
}
