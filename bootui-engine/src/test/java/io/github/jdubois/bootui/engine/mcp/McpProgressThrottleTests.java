package io.github.jdubois.bootui.engine.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.progress.ProgressEvent;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class McpProgressThrottleTests {

    private final AtomicLong now = new AtomicLong(1_000);
    private final McpProgressThrottle throttle = new McpProgressThrottle(now::get);

    @Test
    void aBurstIsSentImmediatelyAndOnlyEventsOverTheLimitAreCoalesced() {
        for (int i = 1; i <= McpProgressThrottle.BURST; i++) {
            assertThat(throttle.offer(event(i))).isEqualTo(event(i));
        }
        assertThat(throttle.offer(event(9))).isNull();
        assertThat(throttle.offer(event(10)))
                .as("the newer event replaces the held one")
                .isNull();
        assertThat(throttle.poll()).isNull();
        assertThat(throttle.nanosUntilNextToken()).isEqualTo(TimeUnit.MILLISECONDS.toNanos(250));

        advance(249);
        assertThat(throttle.poll()).isNull();
        advance(1);
        assertThat(throttle.nanosUntilNextToken()).isZero();
        assertThat(throttle.poll()).isEqualTo(event(10));
        assertThat(throttle.poll()).as("nothing pending").isNull();
    }

    @Test
    void aNewerEventThatFindsATokenIsSentAndSupersedesTheHeldOne() {
        for (int i = 1; i <= McpProgressThrottle.BURST; i++) {
            throttle.offer(event(i));
        }
        throttle.offer(event(9));
        advance(250);
        assertThat(throttle.offer(event(10)))
                .as("no timer is needed: the next report sends itself")
                .isEqualTo(event(10));
        assertThat(throttle.poll()).as("the older held event is dropped").isNull();
        assertThat(throttle.drainPending()).isNull();
    }

    @Test
    void thePendingEventIsFlushedBeforeTheFinalResponseEvenWithoutATokenAndTheBurstRefills() {
        for (int i = 1; i <= McpProgressThrottle.BURST + 1; i++) {
            throttle.offer(event(i));
        }
        assertThat(throttle.drainPending()).isEqualTo(event(9));
        assertThat(throttle.drainPending()).isNull();

        advance(10_000);
        for (int i = 10; i < 10 + McpProgressThrottle.BURST; i++) {
            assertThat(throttle.offer(event(i)))
                    .as("the bucket refills to the burst, not beyond")
                    .isNotNull();
        }
        assertThat(throttle.offer(event(100))).isNull();
    }

    @Test
    void aThirtySecondFloodIsBounded() {
        int sent = 0;
        for (int tick = 0; tick < 30_000; tick++) {
            if (throttle.offer(event(tick + 1)) != null) {
                sent++;
            }
            if (throttle.poll() != null) {
                sent++;
            }
            advance(1);
        }
        assertThat(sent).isLessThanOrEqualTo(McpProgressThrottle.BURST + 30_000 / 250);
    }

    private void advance(long millis) {
        now.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));
    }

    private static ProgressEvent event(double progress) {
        return new ProgressEvent(progress, null, "Working");
    }
}
