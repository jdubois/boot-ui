package io.github.jdubois.bootui.autoconfigure.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
    void coalescesMultipleSignalsIntoASingleFlush() {
        try (BootUiChangeStream stream = new BootUiChangeStream("test", 60L)) {
            List<Runnable> tasks = scheduler(stream);
            stream.open();

            for (int i = 0; i < 50; i++) {
                stream.signal();
            }
            assertThat(tasks).hasSize(1);
            tasks.get(0).run();
            assertThat(stream.flushCount()).isEqualTo(1);

            for (int i = 0; i < 10; i++) {
                stream.signal();
            }
            assertThat(tasks).hasSize(2);
            tasks.get(1).run();
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
        stream.open();
        stream.signal();
        assertThat(stream.subscriberCount()).isZero();
        assertThat(stream.hasScheduler()).isFalse();
    }

    @Test
    void lastDisconnectBeforeFlushAllowsANewGenerationToSignal() {
        try (BootUiChangeStream stream = new BootUiChangeStream("test", 60_000L)) {
            List<Runnable> oldTasks = scheduler(stream);
            SseEmitter old = stream.open();
            stream.signal();
            assertThat(oldTasks).hasSize(1);
            complete(old);
            assertThat(stream.hasScheduler()).isFalse();
            List<Runnable> newTasks = scheduler(stream);
            stream.open();
            stream.signal();
            assertThat(newTasks).hasSize(1);
            newTasks.get(0).run();
            assertThat(stream.flushCount()).isEqualTo(1);
        }
    }

    @Test
    void cancelledOldCallbackCannotFlushOrClearTheNewGenerationsPendingSignal() {
        try (BootUiChangeStream stream = new BootUiChangeStream("test", 60_000L)) {
            List<Runnable> oldTasks = scheduler(stream);
            SseEmitter old = stream.open();
            stream.signal();
            complete(old);
            List<Runnable> newTasks = scheduler(stream);
            stream.open();
            stream.signal();
            oldTasks.get(0).run();
            assertThat(stream.flushCount()).isZero();
            stream.signal();
            assertThat(newTasks).hasSize(1);
            newTasks.get(0).run();
            assertThat(stream.flushCount()).isEqualTo(1);
        }
    }

    @Test
    void inFlightOldFlushCannotConsumeOrRemoveTheNewGenerationsSignal() throws Exception {
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Runnable> completions = new ArrayList<>();
        try (var construction = mockConstruction(SseEmitter.class, (emitter, context) -> {
                    doAnswer(invocation -> {
                                completions.add(invocation.getArgument(0));
                                return null;
                            })
                            .when(emitter)
                            .onCompletion(any(Runnable.class));
                    if (context.getCount() == 1) {
                        doAnswer(invocation -> {
                                    sending.countDown();
                                    if (!release.await(5, TimeUnit.SECONDS)) {
                                        throw new IllegalStateException("test did not release old send");
                                    }
                                    throw new java.io.IOException("old generation disconnected");
                                })
                                .when(emitter)
                                .send(any(SseEmitter.SseEventBuilder.class));
                    }
                });
                BootUiChangeStream stream = new BootUiChangeStream("test", 60_000L)) {
            List<Runnable> oldTasks = scheduler(stream);
            stream.open();
            stream.signal();
            Thread oldFlush = new Thread(oldTasks.get(0), "old-flush-test");
            oldFlush.start();
            try {
                assertThat(sending.await(5, TimeUnit.SECONDS)).isTrue();
                completions.get(0).run();
                List<Runnable> newTasks = scheduler(stream);
                stream.open();
                stream.signal();
                release.countDown();
                oldFlush.join(5_000);
                assertThat(oldFlush.isAlive()).isFalse();
                assertThat(stream.subscriberCount()).isEqualTo(1);
                stream.signal();
                assertThat(newTasks).hasSize(1);
                newTasks.get(0).run();
                assertThat(stream.flushCount()).isEqualTo(2);
            } finally {
                release.countDown();
                oldFlush.join(5_000);
            }
        }
    }

    @Test
    void closeCancelsPendingFlushAndAStaleCallbackDoesNothing() {
        BootUiChangeStream stream = new BootUiChangeStream("test", 60_000L);
        List<Runnable> tasks = scheduler(stream);
        stream.open();
        stream.signal();
        stream.close();
        tasks.get(0).run();
        stream.signal();
        assertThat(stream.flushCount()).isZero();
        assertThat(stream.subscriberCount()).isZero();
        assertThat(stream.hasScheduler()).isFalse();
    }

    @Test
    void anOldCompletionCallbackDoesNotRemoveTheNewSubscriber() {
        try (BootUiChangeStream stream = new BootUiChangeStream("test", 60_000L)) {
            scheduler(stream);
            SseEmitter old = stream.open();
            complete(old);
            List<Runnable> tasks = scheduler(stream);
            stream.open();
            complete(old);
            assertThat(stream.subscriberCount()).isEqualTo(1);
            assertThat(stream.hasScheduler()).isTrue();
            stream.signal();
            assertThat(tasks).hasSize(1);
        }
    }

    @Test
    void sendFailureRemovesTheSubscriberAndStopsTheScheduler() throws Exception {
        try (BootUiChangeStream stream = new BootUiChangeStream("test", 60_000L)) {
            List<Runnable> tasks = scheduler(stream);
            SseEmitter emitter = mock(SseEmitter.class);
            doThrow(new java.io.IOException("disconnected")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
            @SuppressWarnings("unchecked")
            List<SseEmitter> emitters = (List<SseEmitter>) ReflectionTestUtils.getField(stream, "emitters");
            emitters.add(emitter);
            stream.signal();
            tasks.get(0).run();
            assertThat(stream.subscriberCount()).isZero();
            assertThat(stream.hasScheduler()).isFalse();
            verify(emitter).completeWithError(any(java.io.IOException.class));
        }
    }

    @Test
    void rejectedSchedulingDoesNotLeaveThePendingFlagStuck() {
        try (BootUiChangeStream stream = new BootUiChangeStream("test", 60_000L)) {
            List<Runnable> tasks = scheduler(stream);
            ScheduledExecutorService scheduler =
                    (ScheduledExecutorService) ReflectionTestUtils.getField(stream, "scheduler");
            when(scheduler.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS)))
                    .thenThrow(new RejectedExecutionException("closed"))
                    .thenAnswer(invocation -> {
                        tasks.add(invocation.getArgument(0));
                        return mock(ScheduledFuture.class);
                    });
            stream.open();
            stream.signal();
            stream.signal();
            assertThat(tasks).hasSize(1);
            tasks.get(0).run();
            assertThat(stream.flushCount()).isEqualTo(1);
        }
    }

    private static List<Runnable> scheduler(BootUiChangeStream stream) {
        List<Runnable> tasks = new ArrayList<>();
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenAnswer(invocation -> {
                    tasks.add(invocation.getArgument(0));
                    return mock(ScheduledFuture.class);
                });
        ReflectionTestUtils.setField(stream, "scheduler", scheduler);
        return tasks;
    }

    private static void complete(SseEmitter emitter) {
        ((Runnable) ReflectionTestUtils.getField(emitter, "completionCallback")).run();
    }
}
