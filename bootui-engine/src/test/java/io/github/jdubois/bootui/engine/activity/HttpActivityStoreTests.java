package io.github.jdubois.bootui.engine.activity;

import static io.github.jdubois.bootui.engine.activity.ActivityTestFixtures.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Behavior tests for {@link HttpActivityStore}, both directly (this class's own request-building,
 * error-mapping and validation) and composed with a real, unmodified {@link BufferedActivityStore} (the
 * four behaviors the task calls out explicitly: successful forwarding, retry/requeue on failure,
 * bounded-buffer drop under a sustained outage, and clean shutdown/final-flush). An embedded {@code
 * com.sun.net.httpserver.HttpServer} — already this module's established convention, see {@link
 * io.github.jdubois.bootui.engine.web.HttpProbeServiceTests} — plays the peer BootUI instance; no JSON
 * parser is added even in test scope, so the received body is asserted with targeted substring checks
 * rather than parsed.
 */
class HttpActivityStoreTests {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(2);

    private HttpServer server;

    @AfterEach
    void stopEmbeddedServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * Records every request the store sends (raw body + headers), and answers according to a script:
     * respond with a configured failure status for {@code failuresRemaining} requests, then 200:
     * mirrors {@code BufferedActivityStoreTests}'s {@code FakeDurableStore} pattern, but backed by a real
     * embedded HTTP server instead of an in-JVM fake.
     */
    private static final class ScriptedForwardHandler implements HttpHandler {
        final List<String> receivedBodies = new CopyOnWriteArrayList<>();
        // Headers is case-insensitive for both storage and lookup (JDK-normalized), so a defensive copy of
        // the exchange's own instance is asserted against directly rather than reimplementing that
        // normalization in a hand-rolled Map here.
        final List<Headers> receivedHeaders = new CopyOnWriteArrayList<>();
        final AtomicInteger failuresRemaining = new AtomicInteger();
        final AtomicInteger successCount = new AtomicInteger();
        volatile int failureStatus = 500;
        volatile long responseDelayMillis;

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
            receivedBodies.add(new String(bodyBytes, StandardCharsets.UTF_8));
            Headers headersCopy = new Headers();
            headersCopy.putAll(exchange.getRequestHeaders());
            receivedHeaders.add(headersCopy);
            if (responseDelayMillis > 0) {
                try {
                    Thread.sleep(responseDelayMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            if (failuresRemaining.get() > 0) {
                failuresRemaining.decrementAndGet();
                exchange.sendResponseHeaders(failureStatus, -1);
                exchange.close();
                return;
            }
            successCount.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        }
    }

    private HttpServer startServer(HttpHandler handler) throws IOException {
        HttpServer newServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        newServer.createContext(ActivityForwardService.FORWARD_PATH, handler);
        newServer.start();
        this.server = newServer;
        return newServer;
    }

    private String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    // ── construction / validation ────────────────────────────────────────────

    @Test
    void constructorRejectsBlankPeerBaseUrl() {
        assertThatThrownBy(() -> new HttpActivityStore(" ", null, CONNECT_TIMEOUT, REQUEST_TIMEOUT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("peer-base-url");
    }

    @Test
    void constructorRejectsMalformedPeerBaseUrl() {
        assertThatThrownBy(() -> new HttpActivityStore("not a url", null, CONNECT_TIMEOUT, REQUEST_TIMEOUT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructorRejectsRelativePeerBaseUrl() {
        assertThatThrownBy(() -> new HttpActivityStore("/no-host", null, CONNECT_TIMEOUT, REQUEST_TIMEOUT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absolute");
    }

    @Test
    void constructorRejectsNonPositiveConnectTimeout() {
        assertThatThrownBy(() -> new HttpActivityStore("http://localhost:1", null, Duration.ZERO, REQUEST_TIMEOUT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connect-timeout");
    }

    @Test
    void constructorRejectsNonPositiveRequestTimeout() {
        assertThatThrownBy(() ->
                        new HttpActivityStore("http://localhost:1", null, CONNECT_TIMEOUT, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("request-timeout");
    }

    @Test
    void connectTimeoutIsWiredIntoTheBuiltHttpClient() {
        // Asserted structurally off the built HttpClient rather than by letting a real connection attempt
        // time out: a real-timeout test would need to wait out the exact configured duration to prove
        // anything, which is slow and can flake on a loaded CI machine.
        HttpActivityStore store =
                new HttpActivityStore("http://localhost:1", null, Duration.ofMillis(1234), REQUEST_TIMEOUT);
        assertThat(store.connectTimeoutForTesting()).isEqualTo(Duration.ofMillis(1234));
        store.close();
    }

    // ── appendBatch: request shape ───────────────────────────────────────────

    @Test
    void appendBatchNoOpsOnNullOrEmptyListWithoutContactingThePeer() throws IOException {
        ScriptedForwardHandler handler = new ScriptedForwardHandler();
        startServer(handler);
        try (HttpActivityStore store = new HttpActivityStore(baseUrl(), null, CONNECT_TIMEOUT, REQUEST_TIMEOUT)) {
            store.appendBatch(null);
            store.appendBatch(List.of());

            assertThat(handler.receivedBodies).isEmpty();
        }
    }

    @Test
    void appendBatchPostsJsonEncodedEntriesToTheForwardPath() throws IOException {
        ScriptedForwardHandler handler = new ScriptedForwardHandler();
        startServer(handler);
        try (HttpActivityStore store = new HttpActivityStore(baseUrl(), null, CONNECT_TIMEOUT, REQUEST_TIMEOUT)) {
            store.appendBatch(List.of(
                    new StoredActivityEntry("sender-1", 7, entry("e-1", "REQUEST", 1000, "OK", "hello \"world\""))));

            assertThat(handler.receivedBodies).hasSize(1);
            String body = handler.receivedBodies.get(0);
            assertThat(body).contains("\"instanceId\":\"sender-1\"");
            assertThat(body).contains("\"seq\":7");
            assertThat(body).contains("\"id\":\"e-1\"");
            assertThat(body).contains("\"type\":\"REQUEST\"");
            // The embedded quote/backslash in the summary must be escaped, not break the JSON structure.
            assertThat(body).contains("\"summary\":\"hello \\\"world\\\"\"");
            assertThat(handler.receivedHeaders.get(0).getFirst("Content-Type"))
                    .isEqualTo("application/json; charset=UTF-8");
        }
    }

    @Test
    void appendBatchOmitsTokenHeaderWhenNoSecretIsConfigured() throws IOException {
        ScriptedForwardHandler handler = new ScriptedForwardHandler();
        startServer(handler);
        try (HttpActivityStore store = new HttpActivityStore(baseUrl(), null, CONNECT_TIMEOUT, REQUEST_TIMEOUT)) {
            store.append(new StoredActivityEntry("sender-1", 1, entry("e-1", "REQUEST", 1, "OK", "hi")));

            assertThat(handler.receivedHeaders.get(0).containsKey(ActivityForwardService.FORWARD_TOKEN_HEADER))
                    .isFalse();
        }
    }

    @Test
    void appendBatchAttachesConfiguredSecretAsForwardTokenHeader() throws IOException {
        ScriptedForwardHandler handler = new ScriptedForwardHandler();
        startServer(handler);
        try (HttpActivityStore store = new HttpActivityStore(baseUrl(), "s3cr3t", CONNECT_TIMEOUT, REQUEST_TIMEOUT)) {
            store.append(new StoredActivityEntry("sender-1", 1, entry("e-1", "REQUEST", 1, "OK", "hi")));

            assertThat(handler.receivedHeaders.get(0).getFirst(ActivityForwardService.FORWARD_TOKEN_HEADER))
                    .isEqualTo("s3cr3t");
        }
    }

    @Test
    void appendBatchTreatsBlankSecretAsUnconfigured() throws IOException {
        ScriptedForwardHandler handler = new ScriptedForwardHandler();
        startServer(handler);
        try (HttpActivityStore store = new HttpActivityStore(baseUrl(), "   ", CONNECT_TIMEOUT, REQUEST_TIMEOUT)) {
            store.append(new StoredActivityEntry("sender-1", 1, entry("e-1", "REQUEST", 1, "OK", "hi")));

            assertThat(handler.receivedHeaders.get(0).containsKey(ActivityForwardService.FORWARD_TOKEN_HEADER))
                    .isFalse();
        }
    }

    // ── appendBatch: failure mapping ─────────────────────────────────────────

    @Test
    void appendBatchThrowsActivityStoreExceptionOnNon2xxResponse() throws IOException {
        ScriptedForwardHandler handler = new ScriptedForwardHandler();
        handler.failureStatus = 503;
        handler.failuresRemaining.set(Integer.MAX_VALUE);
        startServer(handler);
        try (HttpActivityStore store = new HttpActivityStore(baseUrl(), null, CONNECT_TIMEOUT, REQUEST_TIMEOUT)) {
            assertThatThrownBy(() -> store.append(
                            new StoredActivityEntry("sender-1", 1, entry("e-1", "REQUEST", 1, "OK", "hi"))))
                    .isInstanceOf(ActivityStoreException.class)
                    .hasMessageContaining("503");
        }
    }

    @Test
    void appendBatchThrowsActivityStoreExceptionWhenConnectionIsRefused() throws IOException {
        int closedPort = closedPort();
        try (HttpActivityStore store =
                new HttpActivityStore("http://localhost:" + closedPort, null, CONNECT_TIMEOUT, REQUEST_TIMEOUT)) {
            assertThatThrownBy(() -> store.append(
                            new StoredActivityEntry("sender-1", 1, entry("e-1", "REQUEST", 1, "OK", "hi"))))
                    .isInstanceOf(ActivityStoreException.class);
        }
    }

    @Test
    void appendBatchThrowsActivityStoreExceptionWhenThePeerExceedsTheRequestTimeout() throws IOException {
        ScriptedForwardHandler handler = new ScriptedForwardHandler();
        handler.responseDelayMillis = 1500;
        startServer(handler);
        try (HttpActivityStore store =
                new HttpActivityStore(baseUrl(), null, CONNECT_TIMEOUT, Duration.ofMillis(300))) {
            long start = System.nanoTime();
            assertThatThrownBy(() -> store.append(
                            new StoredActivityEntry("sender-1", 1, entry("e-1", "REQUEST", 1, "OK", "hi"))))
                    .isInstanceOf(ActivityStoreException.class);
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
            // Failed well before the handler's own delay would have returned a (late) 200.
            assertThat(elapsedMillis).isLessThan(1500);
        }
    }

    // ── query / close ─────────────────────────────────────────────────────────

    @Test
    void queryAlwaysReturnsEmptyPage() throws IOException {
        try (HttpActivityStore store =
                new HttpActivityStore("http://localhost:1", null, CONNECT_TIMEOUT, REQUEST_TIMEOUT)) {
            assertThat(store.query(ActivityQuery.firstPage("any-instance"))).isEqualTo(ActivityPage.EMPTY);
        }
    }

    @Test
    void closeNeverThrowsAndIsIdempotent() {
        HttpActivityStore store = new HttpActivityStore("http://localhost:1", null, CONNECT_TIMEOUT, REQUEST_TIMEOUT);
        store.close();
        store.close(); // must not throw when called a second time
    }

    // ── composition with the real, unmodified BufferedActivityStore ─────────

    @Test
    void bufferedStoreForwardsSuccessfullyThroughHttpActivityStore() throws IOException {
        ScriptedForwardHandler handler = new ScriptedForwardHandler();
        startServer(handler);
        try (HttpActivityStore http = new HttpActivityStore(baseUrl(), null, CONNECT_TIMEOUT, REQUEST_TIMEOUT);
                BufferedActivityStore buffered =
                        new BufferedActivityStore(new InMemoryActivityStore(10), http, Duration.ofSeconds(60), 100)) {
            buffered.append(new StoredActivityEntry("sender-1", 1, entry("e-1", "REQUEST", 1, "OK", "hi")));
            assertThat(buffered.pendingCount()).isEqualTo(1);

            buffered.flushNow();

            assertThat(buffered.pendingCount()).isZero();
            assertThat(handler.successCount.get()).isEqualTo(1);
            assertThat(handler.receivedBodies.get(0)).contains("\"id\":\"e-1\"");
        }
    }

    @Test
    void bufferedStoreRetriesAndRequeuesAfterATransientForwardingFailureThenSucceeds() throws IOException {
        ScriptedForwardHandler handler = new ScriptedForwardHandler();
        handler.failuresRemaining.set(1); // first attempt fails, second succeeds
        startServer(handler);
        try (HttpActivityStore http = new HttpActivityStore(baseUrl(), null, CONNECT_TIMEOUT, REQUEST_TIMEOUT);
                BufferedActivityStore buffered =
                        new BufferedActivityStore(new InMemoryActivityStore(10), http, Duration.ofSeconds(60), 100)) {
            buffered.append(new StoredActivityEntry("sender-1", 1, entry("e-1", "REQUEST", 1, "OK", "hi")));

            buffered.flushNow(); // fails against the peer; re-queued at the front
            assertThat(buffered.pendingCount()).isEqualTo(1);
            assertThat(handler.successCount.get()).isZero();

            buffered.flushNow(); // peer is healthy again now
            assertThat(buffered.pendingCount()).isZero();
            assertThat(handler.successCount.get()).isEqualTo(1);
        }
    }

    @Test
    void bufferedStoreDropsOldestEntriesUnderASustainedPeerOutageWithoutUnboundedGrowth() throws IOException {
        ScriptedForwardHandler handler = new ScriptedForwardHandler();
        handler.failuresRemaining.set(Integer.MAX_VALUE); // peer never recovers within this test
        startServer(handler);
        try (HttpActivityStore http = new HttpActivityStore(baseUrl(), null, CONNECT_TIMEOUT, REQUEST_TIMEOUT);
                BufferedActivityStore buffered =
                        new BufferedActivityStore(new InMemoryActivityStore(10), http, Duration.ofSeconds(60), 3)) {
            for (long seq = 1; seq <= 5; seq++) {
                buffered.append(
                        new StoredActivityEntry("sender-1", seq, entry("e-" + seq, "REQUEST", seq, "OK", "hi " + seq)));
            }
            buffered.flushNow(); // fails; re-queue trims to the configured bound rather than growing to 5

            assertThat(buffered.pendingCount()).isEqualTo(3);
            assertThat(handler.successCount.get()).isZero();
            // All 5 remain readable from the hot cache regardless of the pending-queue cap.
            assertThat(buffered.query(new ActivityQuery("sender-1", null, null, null, null, null, null, 10))
                            .entryDtos())
                    .hasSize(5);
        }
    }

    @Test
    void bufferedStoreCloseMakesABoundedBestEffortFinalFlushWithoutBlockingIndefinitely() throws IOException {
        ScriptedForwardHandler handler = new ScriptedForwardHandler();
        startServer(handler);
        HttpActivityStore http = new HttpActivityStore(baseUrl(), null, CONNECT_TIMEOUT, REQUEST_TIMEOUT);
        BufferedActivityStore buffered =
                new BufferedActivityStore(new InMemoryActivityStore(10), http, Duration.ofSeconds(60), 100);
        buffered.append(new StoredActivityEntry("sender-1", 1, entry("e-1", "REQUEST", 1, "OK", "hi")));
        assertThat(buffered.pendingCount()).isEqualTo(1);

        buffered.close();

        assertThat(handler.successCount.get()).isEqualTo(1);
        assertThat(buffered.pendingCount()).isZero();
    }

    @Test
    void bufferedStoreCloseReturnsPromptlyEvenWhenThePeerIsCompletelyUnreachable() throws IOException {
        int closedPort = closedPort();
        HttpActivityStore http =
                new HttpActivityStore("http://localhost:" + closedPort, null, CONNECT_TIMEOUT, REQUEST_TIMEOUT);
        // A flush interval at/under BufferedActivityStore's 2s floor makes its close-flush timeout exactly
        // that floor, so this test is bounded regardless of the interval configured here.
        BufferedActivityStore buffered =
                new BufferedActivityStore(new InMemoryActivityStore(10), http, Duration.ofMillis(200), 100);
        buffered.append(new StoredActivityEntry("sender-1", 1, entry("e-1", "REQUEST", 1, "OK", "hi")));

        long start = System.nanoTime();
        buffered.close();
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        // Comfortably above the ~2s floor (to avoid flakiness) but well under what an indefinite hang
        // would take, proving close() did not wait for the unreachable peer forever.
        assertThat(elapsedMillis).isLessThan(6000);
    }
}
