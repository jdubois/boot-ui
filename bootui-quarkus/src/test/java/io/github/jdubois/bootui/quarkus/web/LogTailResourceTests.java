package io.github.jdubois.bootui.quarkus.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.LogLineDto;
import io.github.jdubois.bootui.engine.logtail.LogTailBuffer;
import io.github.jdubois.bootui.engine.logtail.LogTailReader;
import io.github.jdubois.bootui.quarkus.QuarkusExposurePolicy;
import io.github.jdubois.bootui.quarkus.StubConfig;
import io.smallrye.mutiny.helpers.test.AssertSubscriber;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Pins that the Quarkus Log Tail resource reads the snapshot and every streamed line, backlog and live alike,
 * through the engine read path under the live exposure policy.
 */
class LogTailResourceTests {

    private final AtomicReference<ValueExposure> mode = new AtomicReference<>(ValueExposure.MASKED);

    private final AtomicBoolean maskSecrets = new AtomicBoolean(true);

    private final QuarkusExposurePolicy policy = new QuarkusExposurePolicy(StubConfig.empty()) {
        @Override
        public ValueExposure valueExposure() {
            return mode.get();
        }

        @Override
        public boolean maskSecrets() {
            return maskSecrets.get();
        }
    };

    @Test
    void recentAppliesTheLiveExposurePolicyToRetainedLines() {
        LogTailBuffer buffer = new LogTailBuffer();
        buffer.add(line("login password=hunter2\nthen token: tok-1"));
        LogTailResource resource = new LogTailResource(buffer, policy);

        assertThat(resource.recent().get(0).message()).isEqualTo("login password=******\nthen token: ******");

        mode.set(ValueExposure.METADATA_ONLY);
        assertThat(resource.recent())
                .containsExactly(new LogLineDto(7L, "WARN", "com.example.Db", null, "executor-1", true));

        mode.set(ValueExposure.FULL);
        assertThat(resource.recent().get(0).message()).isEqualTo("login password=hunter2\nthen token: tok-1");

        mode.set(ValueExposure.MASKED);
        maskSecrets.set(false);
        assertThat(resource.recent().get(0).message()).isEqualTo("login password=hunter2\nthen token: tok-1");
    }

    @Test
    void streamExposesBacklogAndLiveLinesUnderThePolicyInForceWhenEachIsDelivered() throws Exception {
        LogTailBuffer buffer = new LogTailBuffer();
        buffer.add(line("backlog password=hunter2"));
        LogTailResource resource = new LogTailResource(buffer, policy);

        AssertSubscriber<OutboundSseEvent> subscriber =
                resource.stream(deliveredSse()).subscribe().withSubscriber(AssertSubscriber.create(Long.MAX_VALUE));
        subscriber.awaitItems(1);
        logOn(buffer, "live api_key=ak-1");
        subscriber.awaitItems(2);
        mode.set(ValueExposure.METADATA_ONLY);
        logOn(buffer, "omitted password=hunter2");
        subscriber.awaitItems(3);
        mode.set(ValueExposure.FULL);
        logOn(buffer, "verbatim password=hunter2");
        subscriber.awaitItems(4);
        subscriber.cancel();

        assertThat(subscriber.getItems())
                .extracting(event -> (LogLineDto) event.getData())
                .extracting(LogLineDto::message, LogLineDto::messageOmitted)
                .containsExactly(
                        tuple("backlog password=******", false),
                        tuple("live api_key=******", false),
                        tuple(null, true),
                        tuple("verbatim password=hunter2", false));
        assertThat(buffer.subscriberCount()).isZero();
        assertThat(resource.activeStreamCount()).isZero();
    }

    @Test
    void loggingNeverWaitsForStreamDeliveryOrPolicyResolution() throws Exception {
        CountDownLatch policyBlocked = new CountDownLatch(1);
        List<String> policyThreads = new CopyOnWriteArrayList<>();
        QuarkusExposurePolicy blocking = new QuarkusExposurePolicy(StubConfig.empty()) {
            @Override
            public ValueExposure valueExposure() {
                policyThreads.add(Thread.currentThread().getName());
                awaitQuietly(policyBlocked);
                return ValueExposure.MASKED;
            }
        };
        LogTailBuffer buffer = new LogTailBuffer();
        LogTailResource resource = new LogTailResource(buffer, blocking);
        AssertSubscriber<OutboundSseEvent> subscriber =
                resource.stream(deliveredSse()).subscribe().withSubscriber(AssertSubscriber.create(Long.MAX_VALUE));
        Thread logging = new Thread(() -> buffer.add(line("live password=hunter2")), "application-logging-thread");
        try {
            logging.start();
            logging.join(Duration.ofSeconds(2).toMillis());
            assertThat(logging.isAlive())
                    .as("the logging thread returns while delivery is still blocked on the policy")
                    .isFalse();

            policyBlocked.countDown();
            subscriber.awaitItems(1);
            assertThat(((LogLineDto) subscriber.getItems().get(0).getData()).message())
                    .isEqualTo("live password=******");
            assertThat(policyThreads)
                    .as("the policy is resolved on the delivery thread, never while the application is logging")
                    .isNotEmpty()
                    .doesNotContain(
                            "application-logging-thread", Thread.currentThread().getName());
        } finally {
            policyBlocked.countDown();
            subscriber.cancel();
            logging.join(Duration.ofSeconds(5).toMillis());
        }
    }

    @Test
    void linesLoggedWhileDeliveringAreNotStreamedBack() throws Exception {
        LogTailBuffer buffer = new LogTailBuffer();
        QuarkusExposurePolicy logging = new QuarkusExposurePolicy(StubConfig.empty()) {
            @Override
            public ValueExposure valueExposure() {
                // Stands in for SmallRye Config warning on a read, captured by the Quarkus log handler.
                buffer.add(new LogLineDto(1L, "WARN", "io.smallrye.config", "SRCFG01008", "delivery"));
                return ValueExposure.MASKED;
            }
        };
        LogTailResource resource = new LogTailResource(buffer, logging);
        AssertSubscriber<OutboundSseEvent> subscriber =
                resource.stream(deliveredSse()).subscribe().withSubscriber(AssertSubscriber.create(Long.MAX_VALUE));
        try {
            logOn(buffer, "one live line");
            subscriber.awaitItems(1);
            Thread.sleep(300);

            assertThat(subscriber.getItems())
                    .as("a line logged on a delivery thread is never captured, so it cannot loop")
                    .hasSize(1);
            assertThat(buffer.recent()).extracting(LogLineDto::logger).containsExactly("com.example.Db");
        } finally {
            subscriber.cancel();
            resource.shutdown();
        }
    }

    @Test
    void streamThatFallsTooFarBehindDisconnectsAndReleasesItsSubscription() {
        LogTailBuffer buffer = new LogTailBuffer();
        LogTailResource resource = new LogTailResource(buffer, policy);

        AssertSubscriber<OutboundSseEvent> subscriber =
                resource.stream(deliveredSse()).subscribe().withSubscriber(AssertSubscriber.create(0));
        assertThat(buffer.subscriberCount()).isOne();
        for (int line = 0; line < 2 * LogTailReader.MAX_PENDING_LINES; line++) {
            buffer.add(line("line " + line));
        }

        assertThat(buffer.subscriberCount())
                .as("the overflowing stream unsubscribes")
                .isZero();
        assertThat(resource.activeStreamCount()).as("and frees its stream slot").isZero();
        subscriber.awaitFailure(Duration.ofSeconds(5));
        assertThat(subscriber.getFailure())
                .as("the client is disconnected while it still has no demand, not after it drains")
                .hasMessageContaining("pending event queue is full");
        assertThat(subscriber.getItems()).isEmpty();
    }

    @Test
    void aReaderThatKeepsUpIsNeverDisconnectedByABurst() {
        LogTailBuffer buffer = new LogTailBuffer(LogTailBuffer.DEFAULT_MAX_LINES, Long.MAX_VALUE);
        for (int line = 0; line < LogTailBuffer.DEFAULT_MAX_LINES; line++) {
            buffer.add(line("backlog " + line));
        }
        ManualExecutor delivery = new ManualExecutor();
        LogTailResource resource = new LogTailResource(buffer, policy, () -> delivery);
        AssertSubscriber<OutboundSseEvent> subscriber =
                resource.stream(deliveredSse()).subscribe().withSubscriber(AssertSubscriber.create(Long.MAX_VALUE));

        delivery.runAll();
        for (int line = 0; line < LogTailReader.MAX_PENDING_LINES - 1 - LogTailBuffer.DEFAULT_MAX_LINES; line++) {
            buffer.add(line("live " + line));
            delivery.runAll();
        }
        assertThat(subscriber.getItems()).hasSize(LogTailReader.MAX_PENDING_LINES - 1);

        // Two lines arrive before the next drain, just as a batch of upstream demand runs out.
        buffer.add(line("burst 1"));
        buffer.add(line("burst 2"));
        delivery.runAll();
        for (int line = 0; line < 3 * LogTailReader.MAX_PENDING_LINES; line++) {
            buffer.add(line("steady " + line));
            delivery.runAll();
        }

        assertThat(subscriber.getFailure()).isNull();
        assertThat(subscriber.getItems()).hasSize(4 * LogTailReader.MAX_PENDING_LINES + 1);
        assertThat(resource.activeStreamCount()).isOne();
        subscriber.cancel();
        assertThat(resource.activeStreamCount()).isZero();
    }

    @Test
    void rejectedDeliveryReleasesTheSubscriptionAndTheStreamSlot() {
        LogTailBuffer buffer = new LogTailBuffer();
        buffer.add(line("backlog password=hunter2"));
        Executor rejecting = command -> {
            throw new RejectedExecutionException("worker pool is shut down");
        };
        LogTailResource resource = new LogTailResource(buffer, policy, () -> rejecting);

        AssertSubscriber<OutboundSseEvent> subscriber =
                resource.stream(deliveredSse()).subscribe().withSubscriber(AssertSubscriber.create(Long.MAX_VALUE));

        subscriber.assertFailedWith(RejectedExecutionException.class);
        assertThat(buffer.subscriberCount()).isZero();
        assertThat(resource.activeStreamCount()).isZero();
    }

    @Test
    void refusesStreamsBeyondTheLimitAndReusesReleasedSlots() {
        LogTailBuffer buffer = new LogTailBuffer();
        LogTailResource resource = new LogTailResource(buffer, policy);
        List<AssertSubscriber<OutboundSseEvent>> open = new ArrayList<>();
        for (int stream = 0; stream < LogTailResource.MAX_CONCURRENT_STREAMS; stream++) {
            open.add(resource.stream(deliveredSse()).subscribe().withSubscriber(AssertSubscriber.create(1)));
        }

        resource.stream(deliveredSse())
                .subscribe()
                .withSubscriber(AssertSubscriber.create(1))
                .awaitCompletion(Duration.ofSeconds(5));
        assertThat(resource.activeStreamCount()).isEqualTo(LogTailResource.MAX_CONCURRENT_STREAMS);
        assertThat(buffer.subscriberCount()).isEqualTo(LogTailResource.MAX_CONCURRENT_STREAMS);

        open.forEach(AssertSubscriber::cancel);
        assertThat(resource.activeStreamCount()).isZero();
        assertThat(buffer.subscriberCount()).isZero();
        AssertSubscriber<OutboundSseEvent> reopened =
                resource.stream(deliveredSse()).subscribe().withSubscriber(AssertSubscriber.create(1));
        assertThat(resource.activeStreamCount()).isOne();
        reopened.cancel();
    }

    @Test
    void appliesThePolicyInForceWhenAQueuedLineIsDeliveredToASlowClient() {
        LogTailBuffer buffer = new LogTailBuffer();
        buffer.add(line("backlog password=hunter2"));
        LogTailResource resource = new LogTailResource(buffer, policy);
        mode.set(ValueExposure.FULL);

        AssertSubscriber<OutboundSseEvent> subscriber =
                resource.stream(deliveredSse()).subscribe().withSubscriber(AssertSubscriber.create(0));
        buffer.add(line("live password=hunter2"));
        subscriber.assertHasNotReceivedAnyItem();

        mode.set(ValueExposure.METADATA_ONLY);
        subscriber.request(2);
        subscriber.awaitItems(2);
        subscriber.cancel();

        assertThat(subscriber.getItems())
                .extracting(event -> (LogLineDto) event.getData())
                .extracting(LogLineDto::message, LogLineDto::messageOmitted)
                .containsExactly(tuple(null, true), tuple(null, true));
    }

    /** Runs delivery tasks only when asked, so a test controls exactly when lines are drained. */
    private static final class ManualExecutor implements Executor {

        private final java.util.ArrayDeque<Runnable> tasks = new java.util.ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        void runAll() {
            Runnable task;
            while ((task = tasks.poll()) != null) {
                task.run();
            }
        }
    }

    /** Logs {@code message} from a dedicated thread, standing in for an application thread. */
    private static void logOn(LogTailBuffer buffer, String message) throws InterruptedException {
        Thread thread = new Thread(() -> buffer.add(line(message)), "application-logging-thread");
        thread.start();
        thread.join(Duration.ofSeconds(5).toMillis());
        assertThat(thread.isAlive()).as("logging returns promptly").isFalse();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static LogLineDto line(String message) {
        return new LogLineDto(7L, "WARN", "com.example.Db", message, "executor-1");
    }

    /** An {@link Sse} whose built events carry the data they were given, so tests inspect what is delivered. */
    private static Sse deliveredSse() {
        Sse sse = mock(Sse.class);
        when(sse.newEventBuilder()).thenAnswer(ignored -> new RecordingBuilder());
        return sse;
    }

    private static final class RecordingBuilder implements OutboundSseEvent.Builder {

        private String name;

        private MediaType mediaType;

        private Object data;

        @Override
        public OutboundSseEvent.Builder id(String id) {
            return this;
        }

        @Override
        public OutboundSseEvent.Builder name(String name) {
            this.name = name;
            return this;
        }

        @Override
        public OutboundSseEvent.Builder reconnectDelay(long milliseconds) {
            return this;
        }

        @Override
        public OutboundSseEvent.Builder mediaType(MediaType mediaType) {
            this.mediaType = mediaType;
            return this;
        }

        @Override
        public OutboundSseEvent.Builder comment(String comment) {
            return this;
        }

        @Override
        @SuppressWarnings("rawtypes")
        public OutboundSseEvent.Builder data(Class type, Object data) {
            return data(data);
        }

        @Override
        @SuppressWarnings("rawtypes")
        public OutboundSseEvent.Builder data(GenericType type, Object data) {
            return data(data);
        }

        @Override
        public OutboundSseEvent.Builder data(Object data) {
            this.data = data;
            return this;
        }

        @Override
        public OutboundSseEvent build() {
            return new Delivered(name, mediaType, data);
        }
    }

    private record Delivered(String name, MediaType mediaType, Object data) implements OutboundSseEvent {

        @Override
        public String getId() {
            return null;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getComment() {
            return null;
        }

        @Override
        public long getReconnectDelay() {
            return RECONNECT_NOT_SET;
        }

        @Override
        public boolean isReconnectDelaySet() {
            return false;
        }

        @Override
        public Class<?> getType() {
            return data == null ? null : data.getClass();
        }

        @Override
        public Type getGenericType() {
            return getType();
        }

        @Override
        public MediaType getMediaType() {
            return mediaType;
        }

        @Override
        public Object getData() {
            return data;
        }
    }
}
