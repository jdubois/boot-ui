package io.github.jdubois.bootui.autoconfigure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.core.dto.LogLineDto;
import io.github.jdubois.bootui.engine.logtail.LogTailBuffer;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class LogTailSseSessionTests {

    @Test
    void blockedEmitterDoesNotHoldPublisherAndEventsRemainOrdered() throws Exception {
        LogTailBuffer buffer = new LogTailBuffer();
        BootUiLogAppender appender = freshAppender(buffer);
        TestEmitter emitter = new TestEmitter();
        CountDownLatch firstSendStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstSend = new CountDownLatch(1);
        List<String> sent = new CopyOnWriteArrayList<>();
        AtomicInteger sendCount = new AtomicInteger();
        LogTailController controller = new LogTailController(appender, () -> emitter, 4, (ignored, line) -> {
            sent.add(line.message());
            if (sendCount.incrementAndGet() == 1) {
                firstSendStarted.countDown();
                awaitLatch(releaseFirstSend);
            }
        });
        ExecutorService publisher = Executors.newSingleThreadExecutor();

        try {
            controller.stream();
            Future<?> firstPublication = publisher.submit(() -> buffer.add(line("first")));
            firstPublication.get(1, TimeUnit.SECONDS);
            assertThat(firstSendStarted.await(1, TimeUnit.SECONDS)).isTrue();

            Future<?> secondPublication = publisher.submit(() -> buffer.add(line("second")));
            secondPublication.get(1, TimeUnit.SECONDS);

            releaseFirstSend.countDown();
            await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> assertThat(sent).containsExactly("first", "second"));
        } finally {
            releaseFirstSend.countDown();
            emitter.fireCompletion();
            controller.shutdown();
            publisher.shutdownNow();
        }
    }

    @Test
    void sendFailureUnsubscribesAndStopsFurtherDelivery() {
        LogTailBuffer buffer = new LogTailBuffer();
        BootUiLogAppender appender = freshAppender(buffer);
        TestEmitter emitter = new TestEmitter();
        AtomicInteger attempts = new AtomicInteger();
        IOException failure = new IOException("client disconnected");
        LogTailController controller = new LogTailController(appender, () -> emitter, 4, (ignored, line) -> {
            attempts.incrementAndGet();
            throw failure;
        });

        try {
            controller.stream();
            buffer.add(line("first"));

            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
                assertThat(controller.activeStreamCount()).isZero();
                assertThat(emitter.completedError.get()).isSameAs(failure);
            });

            buffer.add(line("second"));
            assertThat(attempts).hasValue(1);
        } finally {
            controller.shutdown();
        }
    }

    @Test
    void completionAndTimeoutReleaseTheirSubscriptions() {
        LogTailBuffer buffer = new LogTailBuffer();
        BootUiLogAppender appender = freshAppender(buffer);
        TestEmitter completed = new TestEmitter();
        TestEmitter timedOut = new TestEmitter();
        List<TestEmitter> emitters = new CopyOnWriteArrayList<>(List.of(completed, timedOut));
        AtomicInteger sends = new AtomicInteger();
        LogTailController controller = new LogTailController(
                appender, () -> emitters.remove(0), 4, (ignored, line) -> sends.incrementAndGet());

        try {
            controller.stream();
            controller.stream();
            assertThat(controller.activeStreamCount()).isEqualTo(2);

            completed.fireCompletion();
            timedOut.fireTimeout();

            await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(
                            () -> assertThat(controller.activeStreamCount()).isZero());
            buffer.add(line("after-cleanup"));
            assertThat(sends).hasValue(0);
        } finally {
            controller.shutdown();
        }
    }

    @Test
    void queueOverflowDisconnectsSlowSubscriberWithoutBlockingPublisher() throws Exception {
        LogTailBuffer buffer = new LogTailBuffer();
        BootUiLogAppender appender = freshAppender(buffer);
        TestEmitter emitter = new TestEmitter();
        CountDownLatch firstSendStarted = new CountDownLatch(1);
        CountDownLatch holdSender = new CountDownLatch(1);
        CountDownLatch completionStarted = new CountDownLatch(1);
        CountDownLatch releaseCompletion = new CountDownLatch(1);
        emitter.blockErrorCompletion(completionStarted, releaseCompletion);
        AtomicReference<Thread> worker = new AtomicReference<>();
        LogTailController controller = new LogTailController(appender, () -> emitter, 2, (ignored, line) -> {
            worker.set(Thread.currentThread());
            firstSendStarted.countDown();
            awaitLatch(holdSender);
        });
        ExecutorService publisher = Executors.newSingleThreadExecutor();

        try {
            controller.stream();
            publisher.submit(() -> buffer.add(line("sending"))).get(1, TimeUnit.SECONDS);
            assertThat(firstSendStarted.await(1, TimeUnit.SECONDS)).isTrue();

            Future<?> burst = publisher.submit(() -> {
                buffer.add(line("queued-1"));
                buffer.add(line("queued-2"));
                buffer.add(line("overflow"));
            });
            burst.get(1, TimeUnit.SECONDS);
            assertThat(completionStarted.await(1, TimeUnit.SECONDS)).isTrue();
            releaseCompletion.countDown();

            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
                assertThat(controller.activeStreamCount()).isZero();
                assertThat(emitter.completedError.get())
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("pending event queue is full");
                assertThat(worker.get().isAlive()).isFalse();
            });
        } finally {
            holdSender.countDown();
            releaseCompletion.countDown();
            controller.shutdown();
            publisher.shutdownNow();
        }
    }

    @Test
    void shutdownCompletesEmitterAndInterruptsWorker() throws Exception {
        LogTailBuffer buffer = new LogTailBuffer();
        BootUiLogAppender appender = freshAppender(buffer);
        TestEmitter emitter = new TestEmitter();
        AtomicReference<Thread> worker = new AtomicReference<>();
        CountDownLatch sendStarted = new CountDownLatch(1);
        CountDownLatch holdSender = new CountDownLatch(1);
        CountDownLatch completionStarted = new CountDownLatch(1);
        CountDownLatch releaseCompletion = new CountDownLatch(1);
        emitter.blockCompletion(completionStarted, releaseCompletion);
        LogTailController controller = new LogTailController(appender, () -> emitter, 4, (ignored, line) -> {
            worker.set(Thread.currentThread());
            sendStarted.countDown();
            awaitLatch(holdSender);
        });

        controller.stream();
        buffer.add(line("blocked"));
        await().atMost(Duration.ofSeconds(2)).until(() -> sendStarted.getCount() == 0);

        ExecutorService shutdownExecutor = Executors.newSingleThreadExecutor();
        Future<?> shutdown = shutdownExecutor.submit(controller::shutdown);

        try {
            shutdown.get(1, TimeUnit.SECONDS);
            assertThat(completionStarted.await(1, TimeUnit.SECONDS)).isTrue();
            releaseCompletion.countDown();
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
                assertThat(controller.activeStreamCount()).isZero();
                assertThat(emitter.completions).hasValue(1);
                assertThat(worker.get().isAlive()).isFalse();
                assertThat(appender.isStarted()).isFalse();
            });
        } finally {
            releaseCompletion.countDown();
            shutdownExecutor.shutdownNow();
        }
    }

    @Test
    void sendFailureAfterContainerErrorStillReleasesTheSession() {
        LogTailBuffer buffer = new LogTailBuffer();
        BootUiLogAppender appender = freshAppender(buffer);
        TestEmitter emitter = new TestEmitter();
        emitter.rejectErrorCompletion();
        AtomicInteger attempts = new AtomicInteger();
        LogTailController controller = new LogTailController(appender, () -> emitter, 4, (ignored, line) -> {
            attempts.incrementAndGet();
            throw new IOException("client disconnected");
        });

        try {
            controller.stream();
            buffer.add(line("first"));

            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
                assertThat(controller.activeStreamCount()).isZero();
                assertThat(emitter.completedError.get()).isInstanceOf(IOException.class);
            });

            buffer.add(line("second"));
            assertThat(attempts).hasValue(1);
        } finally {
            controller.shutdown();
        }
    }

    @Test
    void containerErrorDropsThePendingTerminalAction() throws Exception {
        LogTailBuffer buffer = new LogTailBuffer();
        BootUiLogAppender appender = freshAppender(buffer);
        TestEmitter emitter = new TestEmitter();
        CountDownLatch sendStarted = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        LogTailController controller = new LogTailController(appender, () -> emitter, 4, (ignored, line) -> {
            sendStarted.countDown();
            awaitUninterruptibly(releaseSend);
        });

        try {
            controller.stream();
            buffer.add(line("blocked"));
            assertThat(sendStarted.await(1, TimeUnit.SECONDS)).isTrue();

            controller.shutdown();
            emitter.fireError(new IllegalStateException("async request already failed"));
            releaseSend.countDown();

            await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(
                            () -> assertThat(controller.activeStreamCount()).isZero());
            assertThat(emitter.completions).hasValue(0);
            assertThat(emitter.completedError.get()).isNull();
        } finally {
            releaseSend.countDown();
            controller.shutdown();
        }
    }

    @Test
    void streamsBacklogAndLiveLinesUnderTheExposurePolicyInForceWhenEachIsSent() throws Exception {
        LogTailBuffer buffer = new LogTailBuffer();
        buffer.add(line("backlog password=hunter2"));
        BootUiLogAppender appender = freshAppender(buffer);
        TestEmitter emitter = new TestEmitter();
        MockEnvironment environment = new MockEnvironment();
        List<LogLineDto> sent = new CopyOnWriteArrayList<>();
        List<String> sendThreads = new CopyOnWriteArrayList<>();
        LogTailController controller = new LogTailController(
                appender,
                new BootUiExposure(environment, new BootUiProperties()),
                () -> emitter,
                8,
                (ignored, line) -> {
                    sendThreads.add(Thread.currentThread().getName());
                    sent.add(line);
                });

        try {
            controller.stream();
            await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> assertThat(sent).singleElement().satisfies(line -> {
                        assertThat(line.message()).isEqualTo("backlog password=******");
                        assertThat(line.messageOmitted()).isFalse();
                    }));

            buffer.add(line("live token: tok-1\nsecond line api_key=ak-2"));
            await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> assertThat(sent).hasSize(2));
            assertThat(sent.get(1).message()).isEqualTo("live token: ******\nsecond line api_key=******");

            environment.setProperty("bootui.expose-values", "METADATA_ONLY");
            buffer.add(line("omitted password=hunter2"));
            await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> assertThat(sent).hasSize(3));
            assertThat(sent.get(2)).isEqualTo(new LogLineDto(0L, "INFO", "test", null, "publisher", true));

            environment.setProperty("bootui.expose-values", "FULL");
            buffer.add(line("verbatim password=hunter2"));
            await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> assertThat(sent).hasSize(4));
            assertThat(sent.get(3).message()).isEqualTo("verbatim password=hunter2");

            environment.setProperty("bootui.expose-values", "MASKED");
            environment.setProperty("bootui.mask-secrets", "false");
            buffer.add(line("unmasked password=hunter2"));
            await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> assertThat(sent).hasSize(5));
            assertThat(sent.get(4).message()).isEqualTo("unmasked password=hunter2");
            assertThat(sendThreads)
                    .as("lines are exposed and sent on the stream worker, never on the logging thread")
                    .allMatch(name -> name.startsWith("bootui-log-tail-stream-"));
            assertThat(buffer.recent())
                    .as("the buffer keeps captured lines; exposure happens on read")
                    .extracting(LogLineDto::message)
                    .contains("backlog password=hunter2", "omitted password=hunter2");
        } finally {
            emitter.fireCompletion();
            controller.shutdown();
        }
    }

    @Test
    void linesLoggedWhileSendingAreNotStreamedBack() throws Exception {
        LogTailBuffer buffer = new LogTailBuffer();
        BootUiLogAppender appender = freshAppender(buffer);
        TestEmitter emitter = new TestEmitter();
        List<String> sent = new CopyOnWriteArrayList<>();
        LogTailController controller = new LogTailController(appender, () -> emitter, 8, (ignored, line) -> {
            // Stands in for a framework that logs while the stream writes a line.
            buffer.add(line("logged while sending"));
            sent.add(line.message());
        });

        try {
            controller.stream();
            buffer.add(line("one live line"));
            await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> assertThat(sent).containsExactly("one live line"));
            Thread.sleep(300);

            assertThat(sent)
                    .as("a line logged on the stream worker is never captured")
                    .hasSize(1);
            assertThat(buffer.recent()).extracting(LogLineDto::message).containsExactly("one live line");
        } finally {
            emitter.fireCompletion();
            controller.shutdown();
        }
    }

    private static BootUiLogAppender freshAppender(LogTailBuffer buffer) {
        BootUiLogAppender appender = new BootUiLogAppender(buffer);
        appender.setName("TEST_APPENDER_" + System.nanoTime());
        appender.start();
        return appender;
    }

    private static LogLineDto line(String message) {
        return new LogLineDto(0L, "INFO", "test", message, "publisher");
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ex) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class TestEmitter extends SseEmitter {

        private Runnable completion = () -> {};
        private Runnable timeout = () -> {};
        private Consumer<Throwable> error = ignored -> {};
        private final AtomicInteger completions = new AtomicInteger();
        private final AtomicReference<Throwable> completedError = new AtomicReference<>();
        private CountDownLatch completionStarted = new CountDownLatch(0);
        private CountDownLatch releaseCompletion = new CountDownLatch(0);
        private CountDownLatch errorCompletionStarted = new CountDownLatch(0);
        private CountDownLatch releaseErrorCompletion = new CountDownLatch(0);
        private volatile boolean rejectErrorCompletion;

        private TestEmitter() {
            super(0L);
        }

        @Override
        public void onCompletion(Runnable callback) {
            completion = callback;
        }

        @Override
        public void onTimeout(Runnable callback) {
            timeout = callback;
        }

        @Override
        public void onError(Consumer<Throwable> callback) {
            error = callback;
        }

        @Override
        public void complete() {
            completionStarted.countDown();
            awaitLatch(releaseCompletion);
            completions.incrementAndGet();
        }

        @Override
        public void completeWithError(Throwable ex) {
            completedError.set(ex);
            errorCompletionStarted.countDown();
            awaitLatch(releaseErrorCompletion);
            if (rejectErrorCompletion) {
                throw new IllegalStateException("AsyncContext already errored");
            }
            error.accept(ex);
        }

        private void blockCompletion(CountDownLatch started, CountDownLatch release) {
            completionStarted = started;
            releaseCompletion = release;
        }

        private void blockErrorCompletion(CountDownLatch started, CountDownLatch release) {
            errorCompletionStarted = started;
            releaseErrorCompletion = release;
        }

        private void rejectErrorCompletion() {
            rejectErrorCompletion = true;
        }

        private void fireError(Throwable failure) {
            error.accept(failure);
        }

        private void fireCompletion() {
            completion.run();
        }

        private void fireTimeout() {
            timeout.run();
        }
    }
}
