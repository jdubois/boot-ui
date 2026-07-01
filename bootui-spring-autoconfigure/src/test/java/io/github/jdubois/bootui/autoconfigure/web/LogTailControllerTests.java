package io.github.jdubois.bootui.autoconfigure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.dto.LogLineDto;
import io.github.jdubois.bootui.engine.logtail.LogTailBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Tests for {@link LogTailController} and {@link BootUiLogAppender}.
 *
 * <p>The capped ring-buffer behaviour is tested in the engine ({@code LogTailBufferTests}); here the
 * appender's field mapping into the shared {@link LogTailBuffer} and the controller's {@code /recent}
 * endpoint are verified by feeding synthetic events to the globally installed appender and querying
 * through MockMvc.</p>
 */
class LogTailControllerTests {

    /** Creates a fresh appender feeding its own buffer, NOT installed in Logback. */
    private static BootUiLogAppender freshAppender(LogTailBuffer buffer) {
        BootUiLogAppender appender = new BootUiLogAppender(buffer);
        appender.setName("TEST_APPENDER_" + System.nanoTime());
        appender.start();
        return appender;
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

    @Test
    void appendedEventFieldsMappedCorrectly() {
        LogTailBuffer buffer = new LogTailBuffer();
        BootUiLogAppender appender = freshAppender(buffer);
        long ts = System.currentTimeMillis();

        ILoggingEvent evt = mock(ILoggingEvent.class);
        when(evt.getTimeStamp()).thenReturn(ts);
        when(evt.getLevel()).thenReturn(Level.WARN);
        when(evt.getLoggerName()).thenReturn("com.example.Foo");
        when(evt.getFormattedMessage()).thenReturn("something went wrong");
        when(evt.getThreadName()).thenReturn("worker-3");
        appender.doAppend(evt);

        assertThat(buffer.recent()).hasSize(1);
        LogLineDto line = buffer.recent().get(0);
        assertThat(line.timestamp()).isEqualTo(ts);
        assertThat(line.level()).isEqualTo("WARN");
        assertThat(line.logger()).isEqualTo("com.example.Foo");
        assertThat(line.message()).isEqualTo("something went wrong");
        assertThat(line.thread()).isEqualTo("worker-3");
    }

    @Test
    void levelStringMatchesLogbackLevelToString() {
        LogTailBuffer buffer = new LogTailBuffer();
        BootUiLogAppender appender = freshAppender(buffer);

        for (Level level : new Level[] {Level.TRACE, Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR}) {
            appender.doAppend(event(level, "l", "m"));
        }

        List<String> levels = buffer.recent().stream().map(LogLineDto::level).toList();
        assertThat(levels).containsExactly("TRACE", "DEBUG", "INFO", "WARN", "ERROR");
    }

    @Test
    void recentEndpointReturnsTailFromInstalledAppender() throws Exception {
        // LogTailController's ctor installs (idempotently) and owns the buffer; reuse its appender.
        LogTailController controller = new LogTailController(new BootUiProperties());
        BootUiLogAppender installedAppender = BootUiLogAppender.find();

        String uniqueMsg = "unique-test-" + System.nanoTime();
        installedAppender.doAppend(event(Level.ERROR, "io.github.jdubois.Test", uniqueMsg));

        MockMvc mvc = standaloneSetup(controller).build();

        mvc.perform(get("/bootui/api/log-tail/recent"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$[?(@.message == '" + uniqueMsg + "')].level").value("ERROR"))
                .andExpect(jsonPath("$[?(@.message == '" + uniqueMsg + "')].logger")
                        .value("io.github.jdubois.Test"));
    }

    @Test
    void recentEndpointDtoShapeMatchesLogLineDtoRecord() throws Exception {
        LogTailController controller = new LogTailController(new BootUiProperties());
        BootUiLogAppender installedAppender = BootUiLogAppender.find();
        long ts = System.currentTimeMillis();
        String uniqueMsg = "shape-check-" + System.nanoTime();

        ILoggingEvent evt = mock(ILoggingEvent.class);
        when(evt.getTimeStamp()).thenReturn(ts);
        when(evt.getLevel()).thenReturn(Level.DEBUG);
        when(evt.getLoggerName()).thenReturn("shape.Logger");
        when(evt.getFormattedMessage()).thenReturn(uniqueMsg);
        when(evt.getThreadName()).thenReturn("shape-thread");
        installedAppender.doAppend(evt);

        MockMvc mvc = standaloneSetup(controller).build();

        mvc.perform(get("/bootui/api/log-tail/recent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.message == '" + uniqueMsg + "')].timestamp")
                        .value(ts))
                .andExpect(
                        jsonPath("$[?(@.message == '" + uniqueMsg + "')].level").value("DEBUG"))
                .andExpect(jsonPath("$[?(@.message == '" + uniqueMsg + "')].logger")
                        .value("shape.Logger"))
                .andExpect(jsonPath("$[?(@.message == '" + uniqueMsg + "')].thread")
                        .value("shape-thread"));
    }

    @Test
    void defaultLogTailMaxBytesIsUnbounded() {
        assertThat(new BootUiProperties().getLogTail().getMaxBytes()).isZero();
    }

    @Test
    void controllerBoundsInstalledBufferWithConfiguredMaxBytes() {
        // Drop any globally installed appender so this controller's bounded buffer is the one installed.
        BootUiLogAppender existing = BootUiLogAppender.find();
        if (existing != null) {
            existing.uninstall();
        }
        try {
            BootUiProperties properties = new BootUiProperties();
            properties.getLogTail().setMaxBytes(4096L);

            new LogTailController(properties);

            assertThat(BootUiLogAppender.find().buffer().maxBytes()).isEqualTo(4096L);
        } finally {
            BootUiLogAppender installed = BootUiLogAppender.find();
            if (installed != null) {
                installed.uninstall();
            }
        }
    }
}
