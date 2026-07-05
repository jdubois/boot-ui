package io.github.jdubois.bootui.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.test.StepVerifier;

class ReactiveBootUiChangeStreamTests {

    @Test
    void signalIsANoOpWhenNobodyIsListening() {
        ReactiveBootUiChangeStream stream = new ReactiveBootUiChangeStream("test", Duration.ofMillis(10L));
        stream.signal();
        assertThat(stream.subscriberCount()).isZero();
        assertThat(stream.flushCount()).isZero();
    }

    @Test
    void subscribingIncrementsSubscriberCountAndDisposingDecrementsIt() {
        ReactiveBootUiChangeStream stream = new ReactiveBootUiChangeStream("test", Duration.ofMillis(10L));

        Disposable subscription = stream.open().subscribe();
        try {
            assertThat(stream.subscriberCount()).isEqualTo(1);
        } finally {
            subscription.dispose();
        }
        assertThat(stream.subscriberCount()).isZero();
    }

    @Test
    void coalescesMultipleSignalsIntoASingleFlush() {
        ReactiveBootUiChangeStream stream = new ReactiveBootUiChangeStream("test", Duration.ofMillis(60L));
        Disposable subscription = stream.open().subscribe();
        try {
            for (int i = 0; i < 50; i++) {
                stream.signal();
            }
            waitForFlushes(stream, 1);
            assertThat(stream.flushCount()).isEqualTo(1);

            for (int i = 0; i < 10; i++) {
                stream.signal();
            }
            waitForFlushes(stream, 2);
            assertThat(stream.flushCount()).isEqualTo(2);
        } finally {
            subscription.dispose();
        }
    }

    @Test
    void rejectsStreamsBeyondTheConcurrencyLimit() {
        ReactiveBootUiChangeStream stream = new ReactiveBootUiChangeStream("test", Duration.ofMillis(10L));
        CopyOnWriteArrayList<Disposable> subscriptions = new CopyOnWriteArrayList<>();
        try {
            for (int i = 0; i < ReactiveBootUiChangeStream.MAX_CONCURRENT_STREAMS; i++) {
                subscriptions.add(stream.open().subscribe());
            }
            assertThat(stream.subscriberCount()).isEqualTo(ReactiveBootUiChangeStream.MAX_CONCURRENT_STREAMS);

            StepVerifier.create(stream.open())
                    .expectErrorMessage("Too many concurrent BootUI test streams")
                    .verify(Duration.ofSeconds(2));
            // The rejected overflow subscription never reserves a slot.
            assertThat(stream.subscriberCount()).isEqualTo(ReactiveBootUiChangeStream.MAX_CONCURRENT_STREAMS);
        } finally {
            subscriptions.forEach(Disposable::dispose);
        }
    }

    @Test
    void closeCompletesEveryActiveStream() {
        ReactiveBootUiChangeStream stream = new ReactiveBootUiChangeStream("test", Duration.ofMillis(10L));

        StepVerifier.create(stream.open()).then(stream::close).expectComplete().verify(Duration.ofSeconds(2));
    }

    @Test
    void emittedEventsCarryTheUpdateNameAndATimestamp() {
        ReactiveBootUiChangeStream stream = new ReactiveBootUiChangeStream("test", Duration.ofMillis(10L));

        StepVerifier.create(stream.open())
                .then(stream::signal)
                .assertNext(event -> {
                    assertThat(event.event()).isEqualTo("update");
                    Map<String, Object> data = event.data();
                    assertThat(data).containsKey("ts");
                    assertThat(data.get("ts")).isInstanceOf(Long.class);
                })
                .then(stream::close)
                .expectComplete()
                .verify(Duration.ofSeconds(2));
    }

    private static void waitForFlushes(ReactiveBootUiChangeStream stream, int expected) {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (stream.flushCount() < expected && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
