package io.github.jdubois.bootui.webfluxsample;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.autoconfigure.web.BootUiLogAppender;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Real-server regression test for a Log Tail stream feeding on its own output. With Spring's web debug logging on,
 * the SSE writer logs an {@code Encoding [...]} line for every event it encodes, often on the Netty event loop. If the
 * stream let Spring encode each line, every encoded line would log a new captured line, which would be streamed and
 * encoded in turn. The stream serializes each line itself on a delivery thread, so no such line is ever captured.
 */
@SpringBootTest(
        classes = BootUiWebfluxSampleApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "bootui.show-banner=false",
            "bootui.overrides-file=target/log-tail-feedback/application-bootui.properties",
            "bootui.claude-code.enabled=OFF"
        })
// Web debug logging is switched on only inside the test and reset afterwards, because Logback levels are JVM-wide and
// test classes share forked JVMs.
@DirtiesContext
class WebFluxLogTailFeedbackIntegrationTest {

    private static final List<String> DEBUG_LOGGERS = List.of("org.springframework.web", "org.springframework.http");

    private static final int LINES = 300;

    private static final List<String> STALL_THREADS = List.of(
            "main",
            "bootui-log-tail-stream-reactive-",
            "reactor-http-",
            "HttpClient-",
            "bootui-conformance-stream-reader");

    @LocalServerPort
    int port;

    @Test
    void streamingLogLinesNeverCapturesTheFrameworkOutputAboutEncodingThem() throws Exception {
        LoggingSystem logging = LoggingSystem.get(getClass().getClassLoader());
        DEBUG_LOGGERS.forEach(name -> logging.setLogLevel(name, LogLevel.DEBUG));
        try {
            assertNoEncodingLineIsCaptured();
        } finally {
            DEBUG_LOGGERS.forEach(name -> logging.setLogLevel(name, null));
        }
    }

    private void assertNoEncodingLineIsCaptured() throws Exception {
        String logger = "com.example.webflux.FeedbackProbe" + System.nanoTime();
        for (int line = 0; line < LINES; line++) {
            Logger.getLogger(logger).warning("backlog line " + line);
        }
        BootUiHttpProbe probe = new BootUiHttpProbe("http://localhost:" + port);

        // The stream has stalled partway through the backlog on CI runners, rarely and without reproducing elsewhere,
        // so a stall is described while the stream is still open, before the probe gives up and closes it. That
        // snapshot stays passive: an HTTP request logs an "Encoding [" line with web debug logging on, which the open
        // stream would deliver, so requests that probe the stall wait until the stream has already failed.
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "log-tail-feedback-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        String streamed;
        String stall = "none";
        try {
            ScheduledFuture<String> snapshot =
                    watchdog.schedule(WebFluxLogTailFeedbackIntegrationTest::snapshotStall, 20, TimeUnit.SECONDS);
            streamed = probe.readStreamUntil(
                    "/bootui/api/log-tail/stream",
                    "backlog line " + (LINES - 1),
                    () -> Logger.getLogger(logger).warning("live line"),
                    "live line",
                    Duration.ofSeconds(30));
            if (!streamed.contains("live line")) {
                stall = awaitSnapshot(snapshot) + probeStall(probe);
            }
        } finally {
            watchdog.shutdownNow();
        }
        // The live line is only logged once the whole backlog has been replayed, which proves the stream is subscribed.
        assertThat(streamed)
                .as(
                        "the stream delivers the backlog and the live line (received %d chars); stall: %s",
                        streamed.length(), stall)
                .contains("backlog line " + (LINES - 1), "live line");
        Thread.sleep(500);

        JsonNode recent = probe.get("/bootui/api/log-tail/recent").json();
        int encoding = 0;
        for (JsonNode line : recent) {
            String message = line.path("message").asText("");
            if (line.path("logger").asText("").startsWith("org.springframework") && message.contains("Encoding [")) {
                encoding++;
            }
        }
        assertThat(encoding)
                .as("no framework line about encoding a streamed event is captured")
                .isZero();
        assertThat(streamed).doesNotContain("Encoding [");
    }

    /**
     * What a stalled stream is waiting for, read in-process without a request: whether the buffer captured the whole
     * backlog (capture loss or delivery stall), its live subscribers, and the stacks of the threads that capture,
     * deliver, write, and read the stream.
     */
    private static String snapshotStall() {
        StringBuilder out = new StringBuilder();
        BootUiLogAppender appender = BootUiLogAppender.find();
        if (appender == null) {
            out.append("\nno log-tail appender is installed");
        } else {
            String last = "backlog line " + (LINES - 1);
            out.append("\ncaptured: ")
                    .append(appender.buffer().recent().stream().anyMatch(line -> last.equals(line.message())))
                    .append(", live subscribers: ")
                    .append(appender.buffer().subscriberCount());
        }
        for (ThreadInfo thread : ManagementFactory.getThreadMXBean().dumpAllThreads(true, true)) {
            String name = thread.getThreadName();
            if (STALL_THREADS.stream().anyMatch(name::startsWith)) {
                out.append("\n\"").append(name).append("\" ").append(thread.getThreadState());
                if (thread.getLockName() != null) {
                    out.append(" on ")
                            .append(thread.getLockName())
                            .append(" owned by ")
                            .append(thread.getLockOwnerName());
                }
                for (StackTraceElement frame : thread.getStackTrace()) {
                    out.append("\n    at ").append(frame);
                }
            }
        }
        return out.toString();
    }

    private static String awaitSnapshot(Future<String> snapshot) throws InterruptedException {
        try {
            return snapshot.get(15, TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            return "\nthe stall snapshot did not complete in 15 s";
        } catch (ExecutionException ex) {
            return "\nthe stall snapshot failed: " + ex.getCause();
        }
    }

    /**
     * Once the stream has failed: whether a second stream receives the whole backlog, which tells a stalled connection
     * from a stalled server. It reuses the test's client, so no second client is left open.
     */
    private static String probeStall(BootUiHttpProbe probe) {
        String last = "backlog line " + (LINES - 1);
        try {
            String second = probe.readStreamUntil("/bootui/api/log-tail/stream", () -> {}, last, Duration.ofSeconds(5));
            return "\na second stream delivers the backlog: " + second.contains(last);
        } catch (RuntimeException ex) {
            return "\nthe second stream failed: " + ex;
        }
    }
}
