package io.github.jdubois.bootui.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class BootUiHttpProbeTest {

    @Test
    void waitsForTheBlankLineThatEndsTheEventCarryingTheNeedle() {
        String partial = "event:log\ndata:{\"logger\":\"needle\",\"message\":\"cut here";

        assertThat(BootUiHttpProbe.holdsCompleteEventWith(partial, "needle")).isFalse();
        assertThat(BootUiHttpProbe.holdsCompleteEventWith(partial + "\"}\n\n", "needle"))
                .isTrue();
        assertThat(BootUiHttpProbe.holdsCompleteEventWith(partial + "\"}\r\n\r\n", "needle"))
                .isTrue();
    }

    @Test
    void ignoresEventsThatEndBeforeTheNeedle() {
        assertThat(BootUiHttpProbe.holdsCompleteEventWith("data:{}\n\ndata:{\"logger\":\"needle\"", "needle"))
                .isFalse();
        assertThat(BootUiHttpProbe.holdsCompleteEventWith("data:{}\n\n", "needle"))
                .isFalse();
    }

    @Test
    void runsTheActionOnlyOnceTheReadyEventArrivesAndReturnsTheLiveEvent() throws Exception {
        CountDownLatch actionRan = new CountDownLatch(1);
        AtomicBoolean actionRanBeforeReady = new AtomicBoolean();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/stream", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write("data:backlog\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                Thread.sleep(300);
                actionRanBeforeReady.set(actionRan.getCount() == 0);
                out.write("data:ready\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                actionRan.await(5, TimeUnit.SECONDS);
                out.write("data:live\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        try {
            String streamed = new BootUiHttpProbe(
                            "http://localhost:" + server.getAddress().getPort())
                    .readStreamUntil("/stream", "ready", actionRan::countDown, "live", Duration.ofSeconds(10));

            assertThat(actionRanBeforeReady).isFalse();
            assertThat(streamed).contains("backlog", "ready", "live");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void returnsWhatArrivedWithoutRunningTheActionWhenTheReadyEventNeverComes() throws Exception {
        AtomicBoolean actionRan = new AtomicBoolean();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/stream", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write("data:backlog\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                Thread.sleep(1500);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        try {
            String streamed = new BootUiHttpProbe(
                            "http://localhost:" + server.getAddress().getPort())
                    .readStreamUntil("/stream", "ready", () -> actionRan.set(true), "live", Duration.ofMillis(500));

            assertThat(actionRan).isFalse();
            assertThat(streamed).contains("backlog").doesNotContain("live");
        } finally {
            server.stop(0);
        }
    }
}
