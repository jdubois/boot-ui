package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Pins M4-10 on Quarkus ({@code docs/PLAN-v2.md} §5.18): a message an {@code @OnTextMessage} method receives is an
 * execution of its own in the runtime journal, named by the endpoint's path, and nothing of the message is recorded.
 */
@QuarkusTest
class BootUiQuarkusWebSocketExecutionTest {

    @TestHTTPResource
    URL baseUrl;

    @Test
    void aTextMessageIsAnExecutionInTheJournalsFeed() throws Exception {
        CompletableFuture<String> echoed = new CompletableFuture<>();
        WebSocket socket = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(
                        URI.create(baseUrl.toExternalForm().replaceFirst("^http", "ws") + "it/echo"),
                        new Listener(echoed))
                .get(10, TimeUnit.SECONDS);
        socket.sendText("secret order 42", true).get(10, TimeUnit.SECONDS);
        assertThat(echoed.get(10, TimeUnit.SECONDS)).isEqualTo("secret order 42");
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(10, TimeUnit.SECONDS);

        JsonNode message = null;
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (message == null && System.nanoTime() < deadline) {
            JsonNode feed = new BootUiHttpProbe(baseUrl.toExternalForm())
                    .get("/bootui/api/activity?source=journal")
                    .json();
            for (JsonNode entry : feed.path("entries")) {
                if ("WEBSOCKET".equals(entry.path("type").asText())) {
                    message = entry;
                }
            }
            if (message == null) {
                Thread.sleep(100);
            }
        }

        assertThat(message).as("a WEBSOCKET execution in the journal's feed").isNotNull();
        assertThat(message.path("summary").asText()).isEqualTo("← /it/echo");
        assertThat(message.path("parentId").isNull() || message.path("parentId").isMissingNode())
                .as("an execution is a root row")
                .isTrue();
        assertThat(message.toString())
                .as("the message itself is never recorded")
                .doesNotContain("secret order");
    }

    private record Listener(CompletableFuture<String> echoed) implements WebSocket.Listener {

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (!"ready".contentEquals(data)) {
                echoed.complete(data.toString());
            }
            webSocket.request(1);
            return null;
        }
    }
}
