package io.github.jdubois.bootui.engine.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Pins the SSE change-notification hook on {@link SecurityEventBuffer}: the Quarkus security-logs stream
 * fans every {@code subscribe(Runnable)} listener on each recorded event, so the shared Vue panel's
 * auto-refresh toggle works at parity with Spring.
 */
class SecurityEventBufferTest {

    private static CapturedSecurityEvent event() {
        return new CapturedSecurityEvent(Instant.now(), "alice", "AUTHENTICATION_SUCCESS", Map.of(), null);
    }

    @Test
    void notifiesListenersOnRecord() {
        SecurityEventBuffer buffer = new SecurityEventBuffer(10);
        AtomicInteger ticks = new AtomicInteger();
        buffer.subscribe(ticks::incrementAndGet);

        buffer.record(event());
        buffer.record(event());

        assertThat(ticks).hasValue(2);
    }

    @Test
    void unsubscribeStopsNotifications() {
        SecurityEventBuffer buffer = new SecurityEventBuffer(10);
        AtomicInteger ticks = new AtomicInteger();
        Runnable unsubscribe = buffer.subscribe(ticks::incrementAndGet);

        buffer.record(event());
        unsubscribe.run();
        buffer.record(event());

        assertThat(ticks).hasValue(1);
    }

    @Test
    void doesNotNotifyWhileSuspended() {
        SecurityEventBuffer buffer = new SecurityEventBuffer(10);
        AtomicInteger ticks = new AtomicInteger();
        buffer.subscribe(ticks::incrementAndGet);

        buffer.suspendForIdle();
        buffer.record(event());

        assertThat(ticks).hasValue(0);
    }

    @Test
    void doesNotNotifyForNullEvent() {
        SecurityEventBuffer buffer = new SecurityEventBuffer(10);
        AtomicInteger ticks = new AtomicInteger();
        buffer.subscribe(ticks::incrementAndGet);

        buffer.record(null);

        assertThat(ticks).hasValue(0);
    }

    @Test
    void isolatesListenerFailures() {
        SecurityEventBuffer buffer = new SecurityEventBuffer(10);
        AtomicInteger ticks = new AtomicInteger();
        buffer.subscribe(() -> {
            throw new RuntimeException("boom");
        });
        buffer.subscribe(ticks::incrementAndGet);

        assertThatCode(() -> buffer.record(event())).doesNotThrowAnyException();
        assertThat(ticks).hasValue(1);
        assertThat(buffer.snapshot()).hasSize(1);
    }
}
