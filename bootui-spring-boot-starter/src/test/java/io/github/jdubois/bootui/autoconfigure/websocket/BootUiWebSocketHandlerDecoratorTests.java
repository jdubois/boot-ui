package io.github.jdubois.bootui.autoconfigure.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.github.jdubois.bootui.core.dto.WebSocketActivityEntryDto;
import io.github.jdubois.bootui.core.dto.WebSocketReport;
import io.github.jdubois.bootui.engine.websocket.WebSocketActivityRecorder;
import io.github.jdubois.bootui.engine.websocket.WebSocketService;
import io.github.jdubois.bootui.engine.websocket.WebSocketSettings;
import io.github.jdubois.bootui.spi.WebSocketEndpointSnapshot;
import io.github.jdubois.bootui.spi.WebSocketTopologySnapshot;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

/**
 * Pins the two properties that matter most for the servlet frame decorator: it never reads a message
 * payload, and it never breaks the application's WebSocket handling.
 */
class BootUiWebSocketHandlerDecoratorTests {

    private static final URI URI_WITH_TOKEN = URI.create("http://localhost:8080/ws?access_token=super-secret");

    private final WebSocketActivityRecorder recorder = new WebSocketActivityRecorder(WebSocketSettings.defaults());
    private final BootUiWebSocketSessionRegistry registry =
            new BootUiWebSocketSessionRegistry(WebSocketSettings.defaults());
    private final List<String> delegated = new ArrayList<>();

    private final WebSocketHandler delegate = new AbstractWebSocketHandler() {
        @Override
        public void afterConnectionEstablished(WebSocketSession session) {
            delegated.add("open");
        }

        @Override
        public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) {
            delegated.add("message");
        }

        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) {
            delegated.add("error");
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            delegated.add("close");
        }
    };

    private BootUiWebSocketHandlerDecorator decorator(SpringWebSocketMetadataProvider metadata) {
        return new BootUiWebSocketHandlerDecorator(delegate, recorder, registry, metadata);
    }

    private WebSocketReport report() {
        WebSocketTopologySnapshot topology = new WebSocketTopologySnapshot(
                "spring-websocket",
                List.of(new WebSocketEndpointSnapshot(
                        "stomp:/ws",
                        "/ws",
                        "STOMP",
                        "Handler",
                        List.of(),
                        false,
                        List.of(),
                        List.of(),
                        List.of(),
                        null,
                        true)),
                List.of(),
                List.of(),
                null,
                true,
                null);
        return new WebSocketService(
                        () -> topology,
                        registry,
                        recorder,
                        WebSocketSettings.defaults(),
                        new io.github.jdubois.bootui.spi.ExposurePolicy() {
                            @Override
                            public io.github.jdubois.bootui.core.ValueExposure valueExposure() {
                                return io.github.jdubois.bootui.core.ValueExposure.FULL;
                            }

                            @Override
                            public boolean maskSecrets() {
                                return true;
                            }
                        })
                .report();
    }

    @Test
    void recordsFrameSizeWithoutEverReadingThePayload() {
        AtomicBoolean payloadRead = new AtomicBoolean();
        // A message that fails the test the moment anything asks for its content.
        WebSocketMessage<String> message = new WebSocketMessage<>() {
            @Override
            public String getPayload() {
                payloadRead.set(true);
                throw new AssertionError("BootUI must never read a WebSocket payload");
            }

            @Override
            public int getPayloadLength() {
                return 5;
            }

            @Override
            public boolean isLast() {
                return true;
            }
        };

        StubSession session = new StubSession("s1", URI.create("http://localhost/ws"));
        assertThatCode(() -> {
                    decorator(null).afterConnectionEstablished(session);
                    decorator(null).handleMessage(session, message);
                })
                .doesNotThrowAnyException();

        assertThat(payloadRead).isFalse();
        List<WebSocketActivityEntryDto> activity = report().activity();
        assertThat(activity)
                .as("data frames belong to the STOMP interceptor, which records them once with a destination")
                .extracting(WebSocketActivityEntryDto::frameType)
                .containsExactly("OPEN");
        assertThat(activity)
                .as("no captured field could ever hold the payload")
                .allMatch(entry ->
                        entry.destination() == null || entry.destination().startsWith("/"));
    }

    @Test
    void stripsTheQueryStringBeforeRetainingThePath() throws Exception {
        decorator(null).afterConnectionEstablished(new StubSession("s1", URI_WITH_TOKEN));

        assertThat(registry.sessions()).hasSize(1);
        assertThat(registry.sessions().get(0).path())
                .as("a handshake token in the query string must never be retained")
                .isEqualTo("/ws");
    }

    @Test
    void classifiesFrameTypesFromTheMessageKindOnly() throws Exception {
        StubSession session = new StubSession("s1", URI.create("http://localhost/ws"));
        BootUiWebSocketHandlerDecorator decorator = decorator(null);
        decorator.afterConnectionEstablished(session);
        decorator.handleMessage(session, new TextMessage("abc"));
        decorator.handleMessage(session, new BinaryMessage(new byte[] {1, 2, 3, 4}));
        decorator.handleMessage(session, new PingMessage());

        assertThat(report().activity())
                .as("control frames are the transport's to report; data frames are the STOMP interceptor's, so "
                        + "an inbound application message is counted once and always with its destination")
                .extracting(WebSocketActivityEntryDto::frameType)
                .containsExactlyInAnyOrder("OPEN", "PING");
    }

    @Test
    void recordsCloseWithItsStatusAndMarksTheSessionClosed() throws Exception {
        StubSession session = new StubSession("s1", URI.create("http://localhost/ws"));
        BootUiWebSocketHandlerDecorator decorator = decorator(null);
        decorator.afterConnectionEstablished(session);
        decorator.afterConnectionClosed(session, CloseStatus.NORMAL);

        assertThat(registry.sessions().get(0).open()).isFalse();
        assertThat(registry.sessions().get(0).closeStatus()).isEqualTo(CloseStatus.NORMAL.getCode());
        assertThat(report().activity())
                .extracting(WebSocketActivityEntryDto::frameType)
                .contains("CLOSE");
    }

    @Test
    void recordsTransportErrorsAsFailedFramesWithACategoryNotAMessage() throws Exception {
        StubSession session = new StubSession("s1", URI.create("http://localhost/ws"));
        BootUiWebSocketHandlerDecorator decorator = decorator(null);
        decorator.afterConnectionEstablished(session);
        decorator.handleTransportError(session, new IllegalStateException("secret detail in the message"));

        WebSocketActivityEntryDto failure = report().activity().stream()
                .filter(entry -> !entry.success())
                .findFirst()
                .orElseThrow();
        assertThat(failure.errorCategory())
                .as("only the exception type is retained, never its message")
                .isEqualTo("IllegalStateException");
        assertThat(report().stats().failedFrames()).isEqualTo(1);
    }

    @Test
    void keepsDelegatingWhenBookkeepingFails() throws Exception {
        SpringWebSocketMetadataProvider exploding = new SpringWebSocketMetadataProvider(null) {
            @Override
            public String endpointIdFor(String path) {
                throw new IllegalStateException("boom");
            }
        };
        StubSession session = new StubSession("s1", URI.create("http://localhost/ws"));
        BootUiWebSocketHandlerDecorator decorator = decorator(exploding);

        decorator.afterConnectionEstablished(session);
        decorator.handleMessage(session, new TextMessage("abc"));
        decorator.handleTransportError(session, new IllegalStateException("x"));
        decorator.afterConnectionClosed(session, CloseStatus.NORMAL);

        assertThat(delegated)
                .as("observation failures must never break the application's WebSocket handling")
                .containsExactly("open", "message", "error", "close");
        assertThat(registry.sessions())
                .as("a topology-resolution failure degrades endpoint attribution, never session tracking")
                .hasSize(1);
        assertThat(registry.sessions().get(0).endpointId()).isNull();
    }

    /** Minimal session stub: it exposes identity and transport metadata, and no payload at all. */
    private static final class StubSession implements WebSocketSession {

        private final String id;
        private final URI uri;

        private StubSession(String id, URI uri) {
            this.id = id;
            this.uri = uri;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public URI getUri() {
            return uri;
        }

        @Override
        public org.springframework.http.HttpHeaders getHandshakeHeaders() {
            return org.springframework.http.HttpHeaders.EMPTY;
        }

        @Override
        public Map<String, Object> getAttributes() {
            return Map.of();
        }

        @Override
        public java.security.Principal getPrincipal() {
            return null;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return new InetSocketAddress("127.0.0.1", 8080);
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return new InetSocketAddress("127.0.0.1", 51234);
        }

        @Override
        public String getAcceptedProtocol() {
            return "v12.stomp";
        }

        @Override
        public void setTextMessageSizeLimit(int messageSizeLimit) {}

        @Override
        public int getTextMessageSizeLimit() {
            return 0;
        }

        @Override
        public void setBinaryMessageSizeLimit(int messageSizeLimit) {}

        @Override
        public int getBinaryMessageSizeLimit() {
            return 0;
        }

        @Override
        public List<org.springframework.web.socket.WebSocketExtension> getExtensions() {
            return List.of();
        }

        @Override
        public void sendMessage(WebSocketMessage<?> message) {}

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void close() {}

        @Override
        public void close(CloseStatus status) {}
    }
}
