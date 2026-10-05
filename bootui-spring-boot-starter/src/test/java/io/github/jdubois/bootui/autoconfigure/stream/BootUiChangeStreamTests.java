package io.github.jdubois.bootui.autoconfigure.stream;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BootUiChangeStreamTests {

    @Test
    void signalIsANoOpWhenNobodyIsListening() {
        try (BootUiChangeStream stream = new BootUiChangeStream("test", 10L)) {
            stream.signal();
            assertThat(stream.subscriberCount()).isZero();
            assertThat(stream.hasScheduler()).isFalse();
            assertThat(stream.flushCount()).isZero();
        }
    }

    @Test
    void openStartsTheSchedulerAndTracksSubscribers() {
        try (BootUiChangeStream stream = new BootUiChangeStream("test", 10L)) {
            stream.open();
            assertThat(stream.subscriberCount()).isEqualTo(1);
            assertThat(stream.hasScheduler()).isTrue();
        }
    }

    @Test
    void coalescesMultipleSignalsIntoASingleFlush() throws Exception {
        try (BootUiChangeStream stream = new BootUiChangeStream("test", 60L)) {
            stream.open();

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
        }
    }

    @Test
    void rejectsStreamsBeyondTheConcurrencyLimit() {
        try (BootUiChangeStream stream = new BootUiChangeStream("test", 10L)) {
            for (int i = 0; i < BootUiChangeStream.MAX_CONCURRENT_STREAMS; i++) {
                stream.open();
            }
            assertThat(stream.subscriberCount()).isEqualTo(BootUiChangeStream.MAX_CONCURRENT_STREAMS);

            stream.open();
            // The overflow emitter is completed with an error and never retained.
            assertThat(stream.subscriberCount()).isEqualTo(BootUiChangeStream.MAX_CONCURRENT_STREAMS);
        }
    }

    @Test
    void schedulerShutsDownWhenTheStreamIsClosed() {
        BootUiChangeStream stream = new BootUiChangeStream("test", 10L);
        stream.open();
        assertThat(stream.hasScheduler()).isTrue();

        stream.close();
        assertThat(stream.subscriberCount()).isZero();
        assertThat(stream.hasScheduler()).isFalse();
    }

    private static void waitForFlushes(BootUiChangeStream stream, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (stream.flushCount() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
    }
}
