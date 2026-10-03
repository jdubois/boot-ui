package io.github.jdubois.bootui.engine.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.github.jdubois.bootui.core.dto.CaptureRetentionDto;
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
        return exchange("/api/widgets", 200, 3L);
    }

    private static CapturedHttpExchange exchange(String path, int status, Long durationMs) {
        return new CapturedHttpExchange(
                Instant.now(),
                "GET",
                URI.create(path),
                status,
                durationMs,
                "127.0.0.1",
                null,
                null,
                Map.of(),
                Map.of(),
                null);
    }

    @Test
    void offloadDropsRetainedExchangesButKeepsRecording() {
        HttpExchangeBuffer buffer = new HttpExchangeBuffer(10);
        buffer.record(exchange());
        buffer.record(exchange());

        assertThat(buffer.offloadId()).isEqualTo("http-exchanges");
        assertThat(buffer.offloadRetainedData()).isEqualTo(2);
        assertThat(buffer.snapshot()).isEmpty();

        buffer.record(exchange());
        assertThat(buffer.snapshot()).hasSize(1);
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

    @Test
    void floodOfSuccessesKeepsRecentServerErrorsAndSlowExchangesUpToTheReservedShare() {
        HttpExchangeBuffer buffer = new HttpExchangeBuffer(6, 50, 1_000L);
        buffer.record(exchange("/server-error", 500, 5L));
        buffer.record(exchange("/client-error", 404, 5L));
        buffer.record(exchange("/slow", 200, 1_000L));
        buffer.record(exchange("/unknown-duration", 200, null));
        buffer.record(exchange("/unavailable", 503, 5L));
        for (int i = 0; i < 50; i++) {
            buffer.record(exchange("/ok-" + i, 200, 999L));
        }

        assertThat(buffer.snapshot())
                .extracting(exchange -> exchange.uri().getPath())
                .containsExactly("/ok-49", "/ok-48", "/ok-47", "/unavailable", "/slow", "/server-error");
        CaptureRetentionDto retention = buffer.retention();
        assertThat(retention).isEqualTo(new CaptureRetentionDto(false, 6, 3, 6, 3, 49L, 1_000L));
    }

    @Test
    void zeroSlowThresholdReservesOnlyServerErrors() {
        HttpExchangeBuffer buffer = new HttpExchangeBuffer(4, 50, 0L);
        buffer.record(exchange("/server-error", 500, 5L));
        buffer.record(exchange("/very-slow", 200, 60_000L));
        for (int i = 0; i < 5; i++) {
            buffer.record(exchange("/ok-" + i, 200, 1L));
        }

        assertThat(buffer.snapshot())
                .extracting(exchange -> exchange.uri().getPath())
                .containsExactly("/ok-4", "/ok-3", "/ok-2", "/server-error");
        assertThat(buffer.slowThresholdMillis()).isZero();
        assertThat(buffer.retention().slowThresholdMillis()).isZero();
    }

    @Test
    void defaultsReserveAQuarterAndUseTheSharedRequestSlowThreshold() {
        HttpExchangeBuffer buffer = new HttpExchangeBuffer(200);

        assertThat(buffer.capacity()).isEqualTo(200);
        assertThat(buffer.slowThresholdMillis()).isEqualTo(RequestSlowThreshold.DEFAULT_MILLIS);
        assertThat(buffer.retention()).isEqualTo(new CaptureRetentionDto(false, 200, 50, 0, 0, 0L, 1_000L));
    }

    @Test
    void suspendForIdleClearsButKeepsTheEvictionCount() {
        HttpExchangeBuffer buffer = new HttpExchangeBuffer(1, 0, 1_000L);
        buffer.record(exchange());
        buffer.record(exchange());

        buffer.suspendForIdle();

        assertThat(buffer.snapshot()).isEmpty();
        assertThat(buffer.retention().retained()).isZero();
        assertThat(buffer.retention().evicted()).isEqualTo(1L);
    }
}
