package io.github.jdubois.bootui.autoconfigure.web;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * Tests for {@link LogTailController} and {@link BootUiLogAppender}.
 *
 * <p>{@link BootUiLogAppender} is tested in isolation (direct instantiation) for
 * ring-buffer and message-shaping behaviour. The controller's {@code /recent} endpoint
 * is verified by feeding synthetic log events to the globally installed appender and then
 * querying through MockMvc.</p>
 */
class LogTailControllerTests {

    /**
     * Creates a fresh, started {@link BootUiLogAppender} that is NOT installed in Logback.
     */
    private static BootUiLogAppender freshAppender() {
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        LoggerContext context = (LoggerContext) factory;
        BootUiLogAppender appender = new BootUiLogAppender();
        appender.setContext(context);
        appender.setName("TEST_APPENDER_" + System.nanoTime());
        appender.start();
        return appender;
    }

    // ── ring-buffer behaviour ─────────────────────────────────────────────────

    private static ILoggingEvent event(Level level, String logger, String message) {
        ILoggingEvent evt = mock(ILoggingEvent.class);
        when(evt.getTimeStamp()).thenReturn(System.currentTimeMillis());
        when(evt.getLevel()).thenReturn(level);
        when(evt.getLoggerName()).thenReturn(logger);
        when(evt.getFormattedMessage()).thenReturn(message);
        when(evt.getThreadName()).thenReturn("test-thread");
        return evt;
    }

    @AfterEach
    void cleanupInstalledAppender() {
        // The installed appender is re-used across tests; we don't uninstall it because
        // BootUiLogAppender.install() is idempotent, and the ring buffer is bounded.
    }

    @Test
    void ringBufferCapIs500Lines() {
        BootUiLogAppender appender = freshAppender();

        for (int i = 0; i < 500; i++) {
            appender.doAppend(event(Level.INFO, "logger", "msg-" + i));
        }

        assertThat(appender.getRecentLines()).hasSize(500);
    }

    // ── message shaping ───────────────────────────────────────────────────────

    @Test
    void overflowDropsOldestLine() {
        BootUiLogAppender appender = freshAppender();

        // Fill to capacity
        for (int i = 0; i < 500; i++) {
            appender.doAppend(event(Level.INFO, "logger", "msg-" + i));
        }
        // One extra → msg-0 must be evicted
        appender.doAppend(event(Level.INFO, "logger", "msg-500"));

        List<BootUiLogAppender.LogLineDto> lines = appender.getRecentLines();
        assertThat(lines).hasSize(500);
        assertThat(lines).noneMatch(l -> "msg-0".equals(l.message()));
        assertThat(lines).anyMatch(l -> "msg-500".equals(l.message()));
    }

    @Test
    void multipleOverflowsKeepNewestLines() {
        BootUiLogAppender appender = freshAppender();

        // Add 600 events; only the last 500 should survive
        for (int i = 0; i < 600; i++) {
            appender.doAppend(event(Level.DEBUG, "logger", "msg-" + i));
        }

        List<BootUiLogAppender.LogLineDto> lines = appender.getRecentLines();
        assertThat(lines).hasSize(500);
        // First surviving message should be msg-100 (oldest after 100 evictions)
        assertThat(lines.get(0).message()).isEqualTo("msg-100");
        assertThat(lines.get(499).message()).isEqualTo("msg-599");
    }

    // ── subscription ──────────────────────────────────────────────────────────

    @Test
    void appendedEventFieldsMappedCorrectly() {
        BootUiLogAppender appender = freshAppender();
        long ts = System.currentTimeMillis();

        ILoggingEvent evt = mock(ILoggingEvent.class);
        when(evt.getTimeStamp()).thenReturn(ts);
        when(evt.getLevel()).thenReturn(Level.WARN);
        when(evt.getLoggerName()).thenReturn("com.example.Foo");
        when(evt.getFormattedMessage()).thenReturn("something went wrong");
        when(evt.getThreadName()).thenReturn("worker-3");
        appender.doAppend(evt);

        assertThat(appender.getRecentLines()).hasSize(1);
        BootUiLogAppender.LogLineDto line = appender.getRecentLines().get(0);
        assertThat(line.timestamp()).isEqualTo(ts);
        assertThat(line.level()).isEqualTo("WARN");
        assertThat(line.logger()).isEqualTo("com.example.Foo");
        assertThat(line.message()).isEqualTo("something went wrong");
        assertThat(line.thread()).isEqualTo("worker-3");
    }

    // ── controller endpoint ───────────────────────────────────────────────────

    @Test
    void levelStringMatchesLogbackLevelToString() {
        BootUiLogAppender appender = freshAppender();

        for (Level level : new Level[]{Level.TRACE, Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR}) {
            appender.doAppend(event(level, "l", "m"));
        }

        List<String> levels = appender.getRecentLines().stream()
            .map(BootUiLogAppender.LogLineDto::level)
            .toList();
        assertThat(levels).containsExactly("TRACE", "DEBUG", "INFO", "WARN", "ERROR");
    }

    @Test
    void subscriberReceivesNewEventsAndCanUnsubscribe() {
        BootUiLogAppender appender = freshAppender();
        List<BootUiLogAppender.LogLineDto> received = new java.util.ArrayList<>();

        Runnable unsubscribe = appender.subscribe(received::add);

        appender.doAppend(event(Level.INFO, "sub.logger", "event-a"));
        appender.doAppend(event(Level.INFO, "sub.logger", "event-b"));
        unsubscribe.run();
        appender.doAppend(event(Level.INFO, "sub.logger", "event-c")); // must not arrive

        assertThat(received).hasSize(2);
        assertThat(received.get(0).message()).isEqualTo("event-a");
        assertThat(received.get(1).message()).isEqualTo("event-b");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    @Test
    void recentEndpointReturnsTailFromInstalledAppender() throws Exception {
        BootUiLogAppender installedAppender = BootUiLogAppender.install();

        String uniqueMsg = "unique-test-" + System.nanoTime();
        installedAppender.doAppend(event(Level.ERROR, "io.github.jdubois.Test", uniqueMsg));

        // LogTailController's no-arg ctor calls BootUiLogAppender.install() → same instance
        MockMvc mvc = standaloneSetup(new LogTailController()).build();

        mvc.perform(get("/bootui/api/logs/recent"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.message == '" + uniqueMsg + "')].level").value("ERROR"))
            .andExpect(jsonPath("$[?(@.message == '" + uniqueMsg + "')].logger")
                .value("io.github.jdubois.Test"));
    }

    @Test
    void recentEndpointDtoShapeMatchesLogLineDtoRecord() throws Exception {
        BootUiLogAppender installedAppender = BootUiLogAppender.install();
        long ts = System.currentTimeMillis();
        String uniqueMsg = "shape-check-" + System.nanoTime();

        ILoggingEvent evt = mock(ILoggingEvent.class);
        when(evt.getTimeStamp()).thenReturn(ts);
        when(evt.getLevel()).thenReturn(Level.DEBUG);
        when(evt.getLoggerName()).thenReturn("shape.Logger");
        when(evt.getFormattedMessage()).thenReturn(uniqueMsg);
        when(evt.getThreadName()).thenReturn("shape-thread");
        installedAppender.doAppend(evt);

        MockMvc mvc = standaloneSetup(new LogTailController()).build();

        mvc.perform(get("/bootui/api/logs/recent"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.message == '" + uniqueMsg + "')].timestamp").value(ts))
            .andExpect(jsonPath("$[?(@.message == '" + uniqueMsg + "')].level").value("DEBUG"))
            .andExpect(jsonPath("$[?(@.message == '" + uniqueMsg + "')].logger").value("shape.Logger"))
            .andExpect(jsonPath("$[?(@.message == '" + uniqueMsg + "')].thread").value("shape-thread"));
    }
}
