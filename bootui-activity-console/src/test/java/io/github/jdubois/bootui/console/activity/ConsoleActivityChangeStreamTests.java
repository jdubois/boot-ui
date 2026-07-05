package io.github.jdubois.bootui.console.activity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.test.StepVerifier;

/**
 * Tests for {@link ConsoleActivityChangeStream}: the console's SSE broadcaster. Mirrors {@code
 * ReactiveBootUiChangeStreamTests} (the host-adapter design template this class was built from)
 * test-for-test, adapted to this class's slightly smaller surface (no {@code flushCount()} hook, a
 * single fixed event name).
 */
class ConsoleActivityChangeStreamTests {

    @Test
    void signalIsANoOpWhenNobodyIsListening() {
        ConsoleActivityChangeStream stream = new ConsoleActivityChangeStream(Duration.ofMillis(10L));
        stream.signal();
        assertThat(stream.subscriberCount()).isZero();
    }

    @Test
    void subscribingIncrementsSubscriberCountAndDisposingDecrementsIt() {
        ConsoleActivityChangeStream stream = new ConsoleActivityChangeStream(Duration.ofMillis(10L));

        Disposable subscription = stream.open().subscribe();
        try {
            assertThat(stream.subscriberCount()).isEqualTo(1);
        } finally {
            subscription.dispose();
        }
        assertThat(stream.subscriberCount()).isZero();
    }

    @Test
    void emittedEventsCarryTheUpdateNameAndATimestamp() {
        ConsoleActivityChangeStream stream = new ConsoleActivityChangeStream(Duration.ofMillis(10L));

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

    @Test
    void coalescesMultipleSignalsIntoASingleFlush() {
        ConsoleActivityChangeStream stream = new ConsoleActivityChangeStream(Duration.ofMillis(60L));

        StepVerifier.create(stream.open().take(1).timeout(Duration.ofSeconds(2)))
                .then(() -> {
                    for (int i = 0; i < 20; i++) {
                        stream.signal();
                    }
                })
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    void rejectsStreamsBeyondTheConcurrencyLimit() {
        ConsoleActivityChangeStream stream = new ConsoleActivityChangeStream(Duration.ofMillis(10L));
        CopyOnWriteArrayList<Disposable> subscriptions = new CopyOnWriteArrayList<>();
        try {
            for (int i = 0; i < ConsoleActivityChangeStream.MAX_CONCURRENT_STREAMS; i++) {
                subscriptions.add(stream.open().subscribe());
            }
            assertThat(stream.subscriberCount()).isEqualTo(ConsoleActivityChangeStream.MAX_CONCURRENT_STREAMS);

            StepVerifier.create(stream.open())
                    .expectErrorMessage("Too many concurrent BootUI Activity Console streams")
                    .verify(Duration.ofSeconds(2));
            // The rejected overflow subscription never reserves a slot.
            assertThat(stream.subscriberCount()).isEqualTo(ConsoleActivityChangeStream.MAX_CONCURRENT_STREAMS);
        } finally {
            subscriptions.forEach(Disposable::dispose);
        }
    }

    @Test
    void closeCompletesEveryActiveStream() {
        ConsoleActivityChangeStream stream = new ConsoleActivityChangeStream(Duration.ofMillis(10L));

        StepVerifier.create(stream.open()).then(stream::close).expectComplete().verify(Duration.ofSeconds(2));
    }
}
