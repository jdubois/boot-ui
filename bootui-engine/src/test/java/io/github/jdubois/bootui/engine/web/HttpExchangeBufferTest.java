package io.github.jdubois.bootui.engine.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Pins the SSE change-notification hook on {@link HttpExchangeBuffer}: the Quarkus Live Activity stream
 * fans every {@code subscribe(Runnable)} listener on each recorded exchange, so the shared Vue panel's
 * auto-refresh toggle works at parity with Spring.
 */
class HttpExchangeBufferTest {

    private static CapturedHttpExchange exchange() {
        return new CapturedHttpExchange(
                Instant.now(), "GET", URI.create("/api/widgets"), 200, 3L, "127.0.0.1", null, null, Map.of(), Map.of());
    }

    @Test
    void notifiesListenersOnRecord() {
        HttpExchangeBuffer buffer = new HttpExchangeBuffer(10);
        AtomicInteger ticks = new AtomicInteger();
        buffer.subscribe(ticks::incrementAndGet);

        buffer.record(exchange());
        buffer.record(exchange());

        assertThat(ticks).hasValue(2);
    }

    @Test
    void unsubscribeStopsNotifications() {
        HttpExchangeBuffer buffer = new HttpExchangeBuffer(10);
        AtomicInteger ticks = new AtomicInteger();
        Runnable unsubscribe = buffer.subscribe(ticks::incrementAndGet);

        buffer.record(exchange());
        unsubscribe.run();
        buffer.record(exchange());

        assertThat(ticks).hasValue(1);
    }

    @Test
    void doesNotNotifyWhileSuspended() {
        HttpExchangeBuffer buffer = new HttpExchangeBuffer(10);
        AtomicInteger ticks = new AtomicInteger();
        buffer.subscribe(ticks::incrementAndGet);

        buffer.suspendForIdle();
        buffer.record(exchange());

        assertThat(ticks).hasValue(0);
    }

    @Test
    void doesNotNotifyForNullExchange() {
        HttpExchangeBuffer buffer = new HttpExchangeBuffer(10);
        AtomicInteger ticks = new AtomicInteger();
        buffer.subscribe(ticks::incrementAndGet);

        buffer.record(null);

        assertThat(ticks).hasValue(0);
    }

    @Test
    void isolatesListenerFailures() {
        HttpExchangeBuffer buffer = new HttpExchangeBuffer(10);
        AtomicInteger ticks = new AtomicInteger();
        buffer.subscribe(() -> {
            throw new RuntimeException("boom");
        });
        buffer.subscribe(ticks::incrementAndGet);

        assertThatCode(() -> buffer.record(exchange())).doesNotThrowAnyException();
        assertThat(ticks).hasValue(1);
        assertThat(buffer.snapshot()).hasSize(1);
    }
}
