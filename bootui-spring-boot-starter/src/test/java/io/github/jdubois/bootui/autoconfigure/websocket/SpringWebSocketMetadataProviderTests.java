package io.github.jdubois.bootui.autoconfigure.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.spi.WebSocketEndpointSnapshot;
import io.github.jdubois.bootui.spi.WebSocketTopologySnapshot;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.messaging.SubProtocolWebSocketHandler;
import org.springframework.web.socket.server.support.WebSocketHandlerMapping;
import org.springframework.web.socket.server.support.WebSocketHttpRequestHandler;

/**
 * Pins how the servlet topology is read from Spring's own registries: which endpoints are found, how STOMP
 * endpoints are distinguished from native handlers, and the honest capability statement that follows.
 *
 * <p>Resolution is a pure read of already-registered beans. Nothing here opens a session, performs a
 * handshake, or sends a frame, which is why the panel does no network work on render.</p>
 */
class SpringWebSocketMetadataProviderTests {

    private static final class EchoHandler implements WebSocketHandler {
        @Override
        public void afterConnectionEstablished(WebSocketSession session) {}

        @Override
        public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) {}

        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) {}

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) {}

        @Override
        public boolean supportsPartialMessages() {
            return false;
        }
    }

    private static GenericApplicationContext context(Map<String, Object> urlMap) {
        GenericApplicationContext context = new GenericApplicationContext();
        WebSocketHandlerMapping mapping = new WebSocketHandlerMapping();
        mapping.setUrlMap(urlMap);
        context.registerBean("bootUiTestWebSocketHandlerMapping", WebSocketHandlerMapping.class, () -> mapping);
        context.refresh();
        return context;
    }

    private static SubProtocolWebSocketHandler stompHandler() {
        return new SubProtocolWebSocketHandler(new ExecutorSubscribableChannel(), new ExecutorSubscribableChannel());
    }

    @Test
    void reportsNoTopologyWhenTheApplicationDeclaresNoEndpoint() {
        try (GenericApplicationContext context = context(Map.of())) {
            assertThat(new SpringWebSocketMetadataProvider(context).topology())
                    .as("no endpoint means no topology, so the panel reports honestly rather than empty")
                    .isNull();
        }
    }

    @Test
    void describesANativeHandlerEndpointWithoutClaimingFrameCapture() {
        try (GenericApplicationContext context =
                context(Map.of("/echo", new WebSocketHttpRequestHandler(new EchoHandler())))) {
            WebSocketTopologySnapshot topology = new SpringWebSocketMetadataProvider(context).topology();

            assertThat(topology).isNotNull();
            assertThat(topology.framework()).isEqualTo("spring-websocket");
            assertThat(topology.endpoints()).hasSize(1);

            WebSocketEndpointSnapshot endpoint = topology.endpoints().get(0);
            assertThat(endpoint.path()).isEqualTo("/echo");
            assertThat(endpoint.kind()).isEqualTo("HANDLER");
            assertThat(endpoint.handlerClass()).isEqualTo(EchoHandler.class.getName());
            assertThat(endpoint.captureInstalled())
                    .as("Spring offers no decoration seam for native handlers")
                    .isFalse();
            assertThat(endpoint.callbacks())
                    .extracting(callback -> callback.type())
                    .containsExactly("ON_OPEN", "ON_MESSAGE", "ON_ERROR", "ON_CLOSE");

            assertThat(topology.frameCaptureSupported()).isFalse();
            assertThat(topology.frameCaptureUnavailableReason())
                    .as("an unsupported capability must explain itself")
                    .contains("WebSocketHandler");
        }
    }

    @Test
    void recognisesAStompEndpointAndEnablesFrameCapture() {
        try (GenericApplicationContext context =
                context(Map.of("/ws", new WebSocketHttpRequestHandler(stompHandler())))) {
            WebSocketTopologySnapshot topology = new SpringWebSocketMetadataProvider(context).topology();

            assertThat(topology).isNotNull();
            WebSocketEndpointSnapshot endpoint = topology.endpoints().get(0);
            assertThat(endpoint.kind()).isEqualTo("STOMP");
            assertThat(endpoint.captureInstalled()).isTrue();
            assertThat(topology.frameCaptureSupported()).isTrue();
            assertThat(topology.frameCaptureUnavailableReason()).isNull();
        }
    }

    @Test
    void resolvesTheMostSpecificEndpointForAHandshakePath() {
        try (GenericApplicationContext context = context(Map.of(
                "/ws", new WebSocketHttpRequestHandler(stompHandler()),
                "/ws/admin", new WebSocketHttpRequestHandler(new EchoHandler())))) {
            SpringWebSocketMetadataProvider provider = new SpringWebSocketMetadataProvider(context);

            assertThat(provider.endpointIdFor("/ws/admin")).isEqualTo("handler:/ws/admin");
            assertThat(provider.endpointIdFor("/ws")).isEqualTo("stomp:/ws");
            assertThat(provider.endpointIdFor("/nothing/here")).isNull();
            assertThat(provider.endpointIdFor(null)).isNull();
        }
    }

    @Test
    void normalisesPatternsAndDeduplicatesRepeatedEndpoints() {
        try (GenericApplicationContext context = context(Map.of(
                "/ws/**", new WebSocketHttpRequestHandler(stompHandler()),
                "/ws", new WebSocketHttpRequestHandler(stompHandler())))) {
            WebSocketTopologySnapshot topology = new SpringWebSocketMetadataProvider(context).topology();

            assertThat(topology.endpoints())
                    .as("the same normalized path is reported once")
                    .extracting(WebSocketEndpointSnapshot::path)
                    .containsExactly("/ws");
        }
    }

    @Test
    void reportsNoTopologyWhenNoHandlerMappingIsRegisteredAtAll() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.refresh();
            assertThat(new SpringWebSocketMetadataProvider(context).topology()).isNull();
            assertThat(new SpringWebSocketMetadataProvider(context).endpointIdFor("/ws"))
                    .isNull();
        }
    }

    @Test
    void reportsNoBrokerOrApplicationPrefixesWithoutAMessageBroker() {
        try (GenericApplicationContext context =
                context(Map.of("/ws", new WebSocketHttpRequestHandler(stompHandler())))) {
            WebSocketTopologySnapshot topology = new SpringWebSocketMetadataProvider(context).topology();

            assertThat(topology.brokerPrefixes()).isEmpty();
            assertThat(topology.applicationDestinationPrefixes()).isEmpty();
            assertThat(topology.userDestinationPrefix()).isNull();
            assertThat(topology.endpoints().get(0).callbacks())
                    .as("no @MessageMapping bean means no declared destinations")
                    .isEqualTo(List.of());
        }
    }
}
