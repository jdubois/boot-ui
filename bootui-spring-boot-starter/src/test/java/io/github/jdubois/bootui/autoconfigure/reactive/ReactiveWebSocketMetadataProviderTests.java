package io.github.jdubois.bootui.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.spi.WebSocketEndpointSnapshot;
import io.github.jdubois.bootui.spi.WebSocketTopologySnapshot;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

/**
 * Pins the WebFlux topology read and — just as importantly — the honest capability statement that goes with
 * it: WebFlux has no STOMP broker and no sanctioned interception point for frames or sessions, so the panel
 * lists endpoints and says so rather than showing an always-empty activity list.
 */
class ReactiveWebSocketMetadataProviderTests {

    private static final class EchoHandler implements WebSocketHandler {
        @Override
        public Mono<Void> handle(WebSocketSession session) {
            return session.send(session.receive());
        }

        @Override
        public List<String> getSubProtocols() {
            return List.of("v12.stomp");
        }
    }

    private static GenericApplicationContext context(Map<String, Object> urlMap) {
        GenericApplicationContext context = new GenericApplicationContext();
        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setUrlMap(urlMap);
        context.registerBean("bootUiTestReactiveHandlerMapping", SimpleUrlHandlerMapping.class, () -> mapping);
        context.refresh();
        mapping.setApplicationContext(context);
        mapping.initApplicationContext();
        return context;
    }

    @Test
    void reportsNoTopologyWhenNoReactiveWebSocketHandlerIsPublished() {
        try (GenericApplicationContext context = context(Map.of())) {
            assertThat(new ReactiveWebSocketMetadataProvider(context).topology())
                    .isNull();
        }
    }

    @Test
    void listsPublishedHandlersAndMarksFrameCaptureUnsupportedWithAReason() {
        try (GenericApplicationContext context = context(Map.of("/reactive-echo", new EchoHandler()))) {
            WebSocketTopologySnapshot topology = new ReactiveWebSocketMetadataProvider(context).topology();

            assertThat(topology).isNotNull();
            assertThat(topology.framework()).isEqualTo("spring-webflux-websocket");
            assertThat(topology.endpoints()).hasSize(1);

            WebSocketEndpointSnapshot endpoint = topology.endpoints().get(0);
            assertThat(endpoint.path()).isEqualTo("/reactive-echo");
            assertThat(endpoint.kind()).isEqualTo("HANDLER");
            assertThat(endpoint.handlerClass()).isEqualTo(EchoHandler.class.getName());
            assertThat(endpoint.subprotocols()).containsExactly("v12.stomp");
            assertThat(endpoint.captureInstalled()).isFalse();
            assertThat(endpoint.callbacks())
                    .extracting(callback -> callback.type())
                    .containsExactly("ON_SESSION");

            assertThat(topology.frameCaptureSupported()).isFalse();
            assertThat(topology.frameCaptureUnavailableReason())
                    .as("the missing capability is explained instead of silently rendering an empty list")
                    .contains("WebFlux");
            assertThat(topology.brokerPrefixes())
                    .as("WebFlux has no STOMP broker")
                    .isEmpty();
            assertThat(topology.applicationDestinationPrefixes()).isEmpty();
            assertThat(topology.userDestinationPrefix()).isNull();
        }
    }

    @Test
    void ignoresNonWebSocketHandlersPublishedOnTheSameMapping() {
        try (GenericApplicationContext context = context(Map.of(
                "/reactive-echo",
                new EchoHandler(),
                "/not-a-socket",
                (org.springframework.web.reactive.function.server.HandlerFunction<
                                org.springframework.web.reactive.function.server.ServerResponse>)
                        request -> Mono.empty()))) {
            WebSocketTopologySnapshot topology = new ReactiveWebSocketMetadataProvider(context).topology();

            assertThat(topology.endpoints())
                    .extracting(WebSocketEndpointSnapshot::path)
                    .containsExactly("/reactive-echo");
        }
    }

    @Test
    void normalisesWildcardPatternsToTheirBasePath() {
        assertThat(ReactiveWebSocketMetadataProvider.normalizePath("/ws/**")).isEqualTo("/ws");
        assertThat(ReactiveWebSocketMetadataProvider.normalizePath("/ws/")).isEqualTo("/ws");
        assertThat(ReactiveWebSocketMetadataProvider.normalizePath("/**")).isEqualTo("/");
        assertThat(ReactiveWebSocketMetadataProvider.normalizePath(null)).isNull();
    }
}
