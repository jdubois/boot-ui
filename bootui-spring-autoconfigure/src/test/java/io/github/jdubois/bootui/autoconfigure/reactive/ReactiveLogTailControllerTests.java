package io.github.jdubois.bootui.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.autoconfigure.web.BootUiLogAppender;
import io.github.jdubois.bootui.core.dto.LogLineDto;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import reactor.test.StepVerifier;

/**
 * Tests for {@link ReactiveLogTailController}. The capped ring-buffer behaviour is already covered
 * by the engine's {@code LogTailBufferTests} and the appender field-mapping by the servlet-side
 * {@code LogTailControllerTests}; this suite focuses on what is genuinely new here - the
 * {@code Flux.create}-based backlog-then-live streaming built on {@code LogTailBuffer#subscribeWithReplay}.
 */
class ReactiveLogTailControllerTests {

    @AfterEach
    void uninstallAppender() {
        BootUiLogAppender installed = BootUiLogAppender.find();
        if (installed != null) {
            installed.uninstall();
        }
    }

    @Test
    void recentEndpointReturnsTailFromInstalledAppender() {
        ReactiveLogTailController controller =
                new ReactiveLogTailController(new BootUiProperties(), new BootUiExposure(new BootUiProperties()));
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
                new ReactiveLogTailController(new BootUiProperties(), new BootUiExposure(new BootUiProperties()));
        BootUiLogAppender installedAppender = BootUiLogAppender.find();

        String backlogMsg = "backlog-" + System.nanoTime();
        installedAppender.doAppend(event(Level.INFO, "backlog.Logger", backlogMsg));

        String liveMsg = "live-" + System.nanoTime();

        StepVerifier.create(controller.stream())
                .assertNext(sse -> {
                    assertThat(sse.event()).isEqualTo("log");
                    assertThat(sse.data().message()).isEqualTo(backlogMsg);
                })
                .then(() -> installedAppender.doAppend(event(Level.WARN, "live.Logger", liveMsg)))
                .assertNext(sse -> {
                    assertThat(sse.event()).isEqualTo("log");
                    assertThat(sse.data().message()).isEqualTo(liveMsg);
                    assertThat(sse.data().level()).isEqualTo("WARN");
                })
                .thenCancel()
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void recentAppliesTheLiveExposurePolicyToRetainedLines() {
        MockEnvironment environment = new MockEnvironment();
        BootUiProperties properties = new BootUiProperties();
        ReactiveLogTailController controller =
                new ReactiveLogTailController(properties, new BootUiExposure(environment, properties));
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
        ReactiveLogTailController controller =
                new ReactiveLogTailController(properties, new BootUiExposure(environment, properties));
        BootUiLogAppender installedAppender = BootUiLogAppender.find();
        String logger = "reactive.stream." + System.nanoTime();
        installedAppender.doAppend(event(Level.INFO, logger, "backlog password=hunter2"));

        StepVerifier.create(controller.stream()
                        .filter(sse -> logger.equals(sse.data().logger())))
                .assertNext(sse -> assertThat(sse.data().message()).isEqualTo("backlog password=******"))
                .then(() -> installedAppender.doAppend(event(Level.WARN, logger, "live api_key=ak-1")))
                .assertNext(sse -> assertThat(sse.data().message()).isEqualTo("live api_key=******"))
                .then(() -> {
                    environment.setProperty("bootui.expose-values", "METADATA_ONLY");
                    installedAppender.doAppend(event(Level.WARN, logger, "omitted password=hunter2"));
                })
                .assertNext(sse -> {
                    assertThat(sse.data().message()).isNull();
                    assertThat(sse.data().messageOmitted()).isTrue();
                    assertThat(sse.data().level()).isEqualTo("WARN");
                })
                .then(() -> {
                    environment.setProperty("bootui.expose-values", "FULL");
                    installedAppender.doAppend(event(Level.WARN, logger, "verbatim password=hunter2"));
                })
                .assertNext(sse -> assertThat(sse.data().message()).isEqualTo("verbatim password=hunter2"))
                .thenCancel()
                .verify(Duration.ofSeconds(5));
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
