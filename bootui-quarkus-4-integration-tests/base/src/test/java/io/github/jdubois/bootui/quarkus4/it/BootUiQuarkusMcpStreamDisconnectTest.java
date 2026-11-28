package io.github.jdubois.bootui.quarkus4.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.conformance.McpStreamDisconnectContract;
import io.github.jdubois.bootui.engine.mcp.McpDispatcher;
import io.github.jdubois.bootui.spi.McpPanelPolicy;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.Http2ClientConfig;
import io.vertx.core.http.HttpClientConfig;
import io.vertx.core.http.HttpClientConnection;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpConnectOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpVersion;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Quarkus: closing a request-scoped MCP stream cancels the call (MCP 2026-07-28). */
@QuarkusTest
@TestProfile(BootUiQuarkusMcpStreamDisconnectTest.DisconnectProfile.class)
class BootUiQuarkusMcpStreamDisconnectTest {

    static final McpStreamDisconnectContract CONTRACT = new McpStreamDisconnectContract();
    static final io.github.jdubois.bootui.conformance.McpStreamFrameContract FRAMES =
            new io.github.jdubois.bootui.conformance.McpStreamFrameContract();

    public static class DisconnectProfile implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("bootui.mcp.enabled", "ON", "quarkus.http.http2", "true");
        }

        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(CancellableScanDispatcher.class);
        }
    }

    /** Replaces the MCP dispatcher with one whose only tool is the contract's cancellable scan. */
    @Alternative
    @ApplicationScoped
    public static class CancellableScanDispatcher {

        @Produces
        @Singleton
        McpDispatcher dispatcher() {
            return new McpDispatcher(
                    List.of(CONTRACT.tool(), FRAMES.tool()),
                    List.of(),
                    new AllowAll(),
                    "test",
                    "test",
                    50,
                    20,
                    30_000,
                    (operation, failure) -> {
                        throw new AssertionError("A cancelled call is not a server fault: " + operation, failure);
                    });
        }
    }

    @Test
    void theStreamWritesTheSameRawBytesAsEveryStack() throws Exception {
        FRAMES.assertFrames(baseUrl.getPort(), "/bootui/api/mcp", "test");
    }

    @TestHTTPResource
    URL baseUrl;

    @Inject
    McpDispatcher dispatcher;

    @Test
    void closingTheStreamCancelsTheCall() throws Exception {
        // The transport must observe the close before its next heartbeat.
        CONTRACT.closeAfterFirstEventCancels(
                baseUrl.getPort(),
                "/bootui/api/mcp",
                dispatcher,
                java.time.Duration.ofMillis(
                        io.github.jdubois.bootui.engine.mcp.McpStreamingCall.HEARTBEAT_MILLIS * 3 / 4));
    }

    @Test
    void closingALegacyStreamDoesNotCancelButNotificationsCancelledDoes() throws Exception {
        CONTRACT.legacyCloseRunsOnUntilNotificationsCancelled(
                baseUrl.getPort(),
                "/bootui/api/mcp",
                () -> dispatcher.runtimeStats().snapshot());
    }

    @Test
    void notificationsCancelledStopsALegacyBlockingCall() throws Exception {
        CONTRACT.legacyBlockingCallIsCancelledByNotification(
                baseUrl.getPort(),
                "/bootui/api/mcp",
                () -> dispatcher.runtimeStats().snapshot());
    }

    @Test
    void resettingAnHttp2StreamCancelsOnlyItsCallAndKeepsTheConnectionUsable() throws Exception {
        Vertx vertx = Vertx.vertx();
        var client = vertx.createHttpClient(new HttpClientConfig()
                .setVersions(HttpVersion.HTTP_2)
                .setHttp2Config(new Http2ClientConfig().setClearTextUpgrade(false)));
        try {
            HttpClientConnection connection = client.connect(new HttpConnectOptions()
                            .setHost("localhost")
                            .setPort(baseUrl.getPort())
                            .setSsl(false)
                            .setProtocolVersion(HttpVersion.HTTP_2))
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
            AtomicBoolean goAway = new AtomicBoolean();
            AtomicBoolean connectionClosed = new AtomicBoolean();
            connection.goAwayHandler(ignored -> goAway.set(true));
            connection.closeHandler(ignored -> connectionClosed.set(true));
            var before = dispatcher.runtimeStats().snapshot();

            HttpClientRequest victim = streamingRequest(connection, "architecture_scan");
            HttpClientResponse victimResponse = victim.send(streamingPayload("http2-victim", "architecture_scan"))
                    .map(HttpClientResponse::pause)
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
            assertThat(victimResponse.statusCode()).isEqualTo(200);
            assertThat(victimResponse.version()).isEqualTo(HttpVersion.HTTP_2);
            CompletableFuture<Void> victimProgress = new CompletableFuture<>();
            collectStream(victimResponse, victimProgress);
            victimProgress.get(5, TimeUnit.SECONDS);

            HttpClientRequest sibling = streamingRequest(connection, "vulnerabilities_scan");
            HttpClientResponse siblingResponse = sibling.send(streamingPayload("http2-sibling", "vulnerabilities_scan"))
                    .map(HttpClientResponse::pause)
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
            assertThat(siblingResponse.statusCode()).isEqualTo(200);
            assertThat(siblingResponse.version()).isEqualTo(HttpVersion.HTTP_2);
            assertThat(sibling.connection()).isSameAs(connection);
            CompletableFuture<Void> siblingProgress = new CompletableFuture<>();
            CompletableFuture<String> siblingBody = collectStream(siblingResponse, siblingProgress);
            siblingProgress.get(5, TimeUnit.SECONDS);

            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1_500);
            victim.reset(8).toCompletionStage().toCompletableFuture().get(1, TimeUnit.SECONDS);
            while (dispatcher.availableCallPermits() != dispatcher.maxConcurrentCalls() - 1
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(dispatcher.runtimeStats().snapshot().cancellations() - before.cancellations())
                    .isEqualTo(1);
            assertThat(dispatcher.availableCallPermits()).isEqualTo(dispatcher.maxConcurrentCalls() - 1);
            assertThat(siblingBody.isDone())
                    .as("the sibling is still running after the victim reset")
                    .isFalse();
            assertThat(siblingBody.get(10, TimeUnit.SECONDS))
                    .contains("\"id\":\"http2-sibling\"", "\"resultType\":\"complete\"", "\"isError\":false");
            assertThat(dispatcher.runtimeStats().snapshot().timeouts() - before.timeouts())
                    .isZero();

            HttpClientResponse followUp = connection
                    .request(HttpMethod.GET, "/bootui/api/mcp")
                    .compose(HttpClientRequest::send)
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            assertThat(followUp.statusCode()).isEqualTo(405);
            assertThat(followUp.version()).isEqualTo(HttpVersion.HTTP_2);
            assertThat(followUp.request().connection()).isSameAs(connection);
            followUp.body().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThat(goAway).isFalse();
            assertThat(connectionClosed).isFalse();
        } finally {
            try {
                client.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            } finally {
                vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }
    }

    private static HttpClientRequest streamingRequest(HttpClientConnection connection, String name) throws Exception {
        return connection
                .request(HttpMethod.POST, "/bootui/api/mcp")
                .toCompletionStage()
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS)
                .putHeader("Content-Type", "application/json")
                .putHeader("Accept", "application/json, text/event-stream")
                .putHeader("MCP-Protocol-Version", "2026-07-28")
                .putHeader("Mcp-Method", "tools/call")
                .putHeader("Mcp-Name", name);
    }

    private static Buffer streamingPayload(String id, String name) {
        return Buffer.buffer("""
                {"jsonrpc":"2.0","id":"%s","method":"tools/call","params":{"name":"%s","arguments":{},
                "_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28",
                "io.modelcontextprotocol/clientCapabilities":{},"progressToken":"%s"}}}
                """.formatted(id, name, id));
    }

    private static CompletableFuture<String> collectStream(
            HttpClientResponse response, CompletableFuture<Void> firstProgress) {
        Buffer body = Buffer.buffer();
        CompletableFuture<String> completed = new CompletableFuture<>();
        response.exceptionHandler(failure -> {
            firstProgress.completeExceptionally(failure);
            completed.completeExceptionally(failure);
        });
        response.handler(chunk -> {
            body.appendBuffer(chunk);
            if (body.toString().contains("\"method\":\"notifications/progress\"")) {
                firstProgress.complete(null);
            }
        });
        response.endHandler(ignored -> completed.complete(body.toString()));
        response.resume();
        return completed;
    }

    private static final class AllowAll implements McpPanelPolicy {

        @Override
        public boolean isEnabled(String panelId) {
            return true;
        }

        @Override
        public String disabledReason(String panelId) {
            return "";
        }

        @Override
        public boolean isReadOnly(String panelId) {
            return false;
        }

        @Override
        public String readOnlyReason(String panelId) {
            return "";
        }
    }
}
