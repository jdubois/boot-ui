package io.github.jdubois.bootui.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.autoconfigure.web.BootUiLogAppender;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.LogLineDto;
import io.github.jdubois.bootui.engine.logtail.LogTailReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.Disposable;
import reactor.core.Exceptions;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests for {@link ReactiveLogTailController}. The capped ring-buffer behaviour is already covered
 * by the engine's {@code LogTailBufferTests} and the appender field-mapping by the servlet-side
 * {@code LogTailControllerTests}; this suite focuses on what is genuinely new here - the
 * {@code Flux.create}-based backlog-then-live streaming built on {@code LogTailBuffer#subscribeWithReplay}.
 */
class ReactiveLogTailControllerTests {

    private static final JsonMapper JSON =
            JsonMapper.builder().findAndAddModules().build();

    private final List<ReactiveLogTailController> controllers = new ArrayList<>();

    @AfterEach
    void uninstallAppender() {
        controllers.forEach(ReactiveLogTailController::shutdown);
        BootUiLogAppender installed = BootUiLogAppender.find();
        if (installed != null) {
            installed.uninstall();
        }
    }

    /** Builds a controller that teardown shuts down, disposing its delivery scheduler. */
    private ReactiveLogTailController controller(BootUiProperties properties, BootUiExposure exposure) {
        ReactiveLogTailController controller = new ReactiveLogTailController(properties, exposure);
        controllers.add(controller);
        return controller;
    }

    @Test
    void recentEndpointReturnsTailFromInstalledAppender() {
        ReactiveLogTailController controller =
                controller(new BootUiProperties(), new BootUiExposure(new BootUiProperties()));
        BootUiLogAppender installedAppender = BootUiLogAppender.find();

        String uniqueMsg = "unique-reactive-test-" + System.nanoTime();
        installedAppender.doAppend(event(Level.ERROR, "io.github.jdubois.Test", uniqueMsg));

        assertThat(controller.recent()).anySatisfy(line -> {
            assertThat(line.message()).isEqualTo(uniqueMsg);
            assertThat(line.level()).isEqualTo("ERROR");
            assertThat(line.logger()).isEqualTo("io.github.jdubois.Test");
        });
    }

    @Test
    void streamEmitsBacklogThenLiveLines() {
        ReactiveLogTailController controller =
                controller(new BootUiProperties(), new BootUiExposure(new BootUiProperties()));
        BootUiLogAppender installedAppender = BootUiLogAppender.find();

        String backlogMsg = "backlog-" + System.nanoTime();
        installedAppender.doAppend(event(Level.INFO, "backlog.Logger", backlogMsg));

        String liveMsg = "live-" + System.nanoTime();

        StepVerifier.create(controller.stream())
                .assertNext(sse -> {
                    assertThat(sse.event()).isEqualTo("log");
                    assertThat(line(sse).message()).isEqualTo(backlogMsg);
                })
                .then(() -> installedAppender.doAppend(event(Level.WARN, "live.Logger", liveMsg)))
                .assertNext(sse -> {
                    assertThat(sse.event()).isEqualTo("log");
                    assertThat(line(sse).message()).isEqualTo(liveMsg);
                    assertThat(line(sse).level()).isEqualTo("WARN");
                })
                .thenCancel()
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void recentAppliesTheLiveExposurePolicyToRetainedLines() {
        MockEnvironment environment = new MockEnvironment();
        BootUiProperties properties = new BootUiProperties();
        ReactiveLogTailController controller = controller(properties, new BootUiExposure(environment, properties));
        String logger = "reactive.exposure." + System.nanoTime();
        BootUiLogAppender.find().doAppend(event(Level.WARN, logger, "login password=hunter2\nthen token: tok-1"));

        assertThat(lineFrom(controller.recent(), logger).message())
                .isEqualTo("login password=******\nthen token: ******");

        environment.setProperty("bootui.expose-values", "METADATA_ONLY");
        LogLineDto omitted = lineFrom(controller.recent(), logger);
        assertThat(omitted.message()).isNull();
        assertThat(omitted.messageOmitted()).isTrue();
        assertThat(omitted.level()).isEqualTo("WARN");
        assertThat(omitted.thread()).isEqualTo("test-thread");

        environment.setProperty("bootui.expose-values", "FULL");
        assertThat(lineFrom(controller.recent(), logger).message())
                .isEqualTo("login password=hunter2\nthen token: tok-1");

        environment.setProperty("bootui.expose-values", "MASKED");
        environment.setProperty("bootui.mask-secrets", "false");
        assertThat(lineFrom(controller.recent(), logger).message())
                .isEqualTo("login password=hunter2\nthen token: tok-1");
    }

    @Test
    void streamExposesBacklogAndLiveLinesUnderThePolicyInForceWhenEachIsEmitted() {
        MockEnvironment environment = new MockEnvironment();
        BootUiProperties properties = new BootUiProperties();
        ReactiveLogTailController controller = controller(properties, new BootUiExposure(environment, properties));
        BootUiLogAppender installedAppender = BootUiLogAppender.find();
        String logger = "reactive.stream." + System.nanoTime();
        installedAppender.doAppend(event(Level.INFO, logger, "backlog password=hunter2"));

        StepVerifier.create(controller.stream()
                        .filter(sse -> logger.equals(line(sse).logger())))
                .assertNext(sse -> assertThat(line(sse).message()).isEqualTo("backlog password=******"))
                .then(() -> installedAppender.doAppend(event(Level.WARN, logger, "live api_key=ak-1")))
                .assertNext(sse -> assertThat(line(sse).message()).isEqualTo("live api_key=******"))
                .then(() -> {
                    environment.setProperty("bootui.expose-values", "METADATA_ONLY");
                    installedAppender.doAppend(event(Level.WARN, logger, "omitted password=hunter2"));
                })
                .assertNext(sse -> {
                    assertThat(line(sse).message()).isNull();
                    assertThat(line(sse).messageOmitted()).isTrue();
                    assertThat(line(sse).level()).isEqualTo("WARN");
                })
                .then(() -> {
                    environment.setProperty("bootui.expose-values", "FULL");
                    installedAppender.doAppend(event(Level.WARN, logger, "verbatim password=hunter2"));
                })
                .assertNext(sse -> assertThat(line(sse).message()).isEqualTo("verbatim password=hunter2"))
                .thenCancel()
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void loggingNeverWaitsForStreamDeliveryOrPolicyResolution() throws Exception {
        CountDownLatch policyBlocked = new CountDownLatch(1);
        List<String> policyThreads = new CopyOnWriteArrayList<>();
        BootUiProperties properties = new BootUiProperties();
        BootUiExposure blocking = new BootUiExposure(new MockEnvironment(), properties) {
            @Override
            public ValueExposure valueExposure() {
                policyThreads.add(Thread.currentThread().getName());
                awaitQuietly(policyBlocked);
                return super.valueExposure();
            }
        };
        ReactiveLogTailController controller = controller(properties, blocking);
        BootUiLogAppender installedAppender = BootUiLogAppender.find();
        String logger = "reactive.thread." + System.nanoTime();
        List<LogLineDto> received = new CopyOnWriteArrayList<>();
        Disposable subscription = controller.stream()
                .map(ReactiveLogTailControllerTests::line)
                .filter(line -> logger.equals(line.logger()))
                .subscribe(received::add);
        Thread logging = new Thread(
                () -> installedAppender.doAppend(event(Level.WARN, logger, "live password=hunter2")),
                "application-logging-thread");
        try {
            logging.start();
            logging.join(Duration.ofSeconds(2).toMillis());
            assertThat(logging.isAlive())
                    .as("the logging thread returns while delivery is still blocked on the policy")
                    .isFalse();

            policyBlocked.countDown();
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(received)
                            .singleElement()
                            .extracting(LogLineDto::message)
                            .isEqualTo("live password=******"));
            assertThat(policyThreads)
                    .as("the policy is resolved on the delivery thread, never while the application is logging")
                    .isNotEmpty()
                    .doesNotContain(
                            "application-logging-thread", Thread.currentThread().getName());
        } finally {
            policyBlocked.countDown();
            subscription.dispose();
            logging.join(Duration.ofSeconds(5).toMillis());
        }
    }

    @Test
    void linesLoggedWhileDeliveringAreNotStreamedBack() throws Exception {
        BootUiProperties properties = new BootUiProperties();
        org.slf4j.Logger chatty = LoggerFactory.getLogger("reactive.feedback.Probe");
        BootUiExposure logging = new BootUiExposure(new MockEnvironment(), properties) {
            @Override
            public ValueExposure valueExposure() {
                // Stands in for a framework that logs while the stream exposes or encodes a line.
                chatty.warn("resolved the exposure policy");
                return super.valueExposure();
            }
        };
        ReactiveLogTailController controller = controller(properties, logging);
        BootUiLogAppender installedAppender = BootUiLogAppender.find();
        String logger = "reactive.feedback." + System.nanoTime();
        List<LogLineDto> received = new CopyOnWriteArrayList<>();
        Disposable subscription =
                controller.stream().map(ReactiveLogTailControllerTests::line).subscribe(received::add);
        try {
            installedAppender.doAppend(event(Level.WARN, logger, "one live line"));

            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() ->
                            assertThat(received).extracting(LogLineDto::logger).contains(logger));
            Thread.sleep(300);
            assertThat(received)
                    .as("a line logged on a delivery thread is never captured, so it cannot loop")
                    .extracting(LogLineDto::logger)
                    .doesNotContain("reactive.feedback.Probe");
            assertThat(installedAppender.buffer().recent())
                    .extracting(LogLineDto::logger)
                    .doesNotContain("reactive.feedback.Probe");
        } finally {
            subscription.dispose();
        }
    }

    @Test
    void streamThatFallsTooFarBehindDisconnectsAndReleasesItsSubscription() {
        ReactiveLogTailController controller =
                controller(new BootUiProperties(), new BootUiExposure(new BootUiProperties()));
        BootUiLogAppender installedAppender = BootUiLogAppender.find();
        int subscribersBefore = installedAppender.buffer().subscriberCount();

        StepVerifier.create(controller.stream(), 0)
                .expectSubscription()
                .then(() -> {
                    assertThat(installedAppender.buffer().subscriberCount()).isEqualTo(subscribersBefore + 1);
                    for (int line = 0; line < 2 * LogTailReader.MAX_PENDING_LINES; line++) {
                        installedAppender.doAppend(event(Level.INFO, "overflow.Logger", "line " + line));
                    }
                })
                .expectErrorMatches(Exceptions::isOverflow)
                .verify(Duration.ofSeconds(5));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(installedAppender.buffer().subscriberCount()).isEqualTo(subscribersBefore);
            assertThat(controller.activeStreamCount()).isZero();
        });
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void streamWritesEachLineAsTheSameJsonAsTheRecentSnapshot() {
        ReactiveLogTailController controller =
                controller(new BootUiProperties(), new BootUiExposure(new BootUiProperties()));
        String logger = "reactive.json." + System.nanoTime();
        BootUiLogAppender.find().doAppend(event(Level.WARN, logger, "multi\nline password=hunter2"));
        LogLineDto snapshot = lineFrom(controller.recent(), logger);

        StepVerifier.create(controller.stream().filter(sse -> line(sse).logger().equals(logger)))
                .assertNext(sse -> {
                    assertThat(sse.event()).isEqualTo("log");
                    assertThat(sse.data())
                            .as("serialized on the delivery thread, one JSON line with escaped newlines")
                            .isEqualTo(JSON.writeValueAsString(snapshot))
                            .doesNotContain("\n", "hunter2");
                })
                .thenCancel()
                .verify(Duration.ofSeconds(5));
    }

    private static LogLineDto line(ServerSentEvent<String> sse) {
        return JSON.readValue(sse.data(), LogLineDto.class);
    }

    private static LogLineDto lineFrom(List<LogLineDto> lines, String logger) {
        return lines.stream()
                .filter(line -> logger.equals(line.logger()))
                .findFirst()
                .orElseThrow();
    }

    private static ILoggingEvent event(Level level, String logger, String message) {
        ILoggingEvent evt = mock(ILoggingEvent.class);
        when(evt.getTimeStamp()).thenReturn(System.currentTimeMillis());
        when(evt.getLevel()).thenReturn(level);
        when(evt.getLoggerName()).thenReturn(logger);
        when(evt.getFormattedMessage()).thenReturn(message);
        when(evt.getThreadName()).thenReturn("test-thread");
        return evt;
    }
}
