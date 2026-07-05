package io.github.jdubois.bootui.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.web.BootUiLogAppender;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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
        ReactiveLogTailController controller = new ReactiveLogTailController(new BootUiProperties());
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
        ReactiveLogTailController controller = new ReactiveLogTailController(new BootUiProperties());
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
