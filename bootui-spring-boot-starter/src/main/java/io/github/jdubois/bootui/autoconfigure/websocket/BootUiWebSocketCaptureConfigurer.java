package io.github.jdubois.bootui.autoconfigure.websocket;

import io.github.jdubois.bootui.engine.websocket.WebSocketActivityRecorder;
import org.springframework.core.Ordered;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * Installs BootUI's capture seams into an application's STOMP configuration.
 *
 * <p>This is a plain {@link WebSocketMessageBrokerConfigurer}, so it only ever contributes when the
 * application itself enabled {@code @EnableWebSocketMessageBroker}; BootUI never enables STOMP, never
 * registers an endpoint, and never changes broker, transport limit, or destination configuration. Every
 * other configurer the application declares still runs, and BootUI adds one decorator factory plus one
 * interceptor per client channel.</p>
 *
 * <p>Ordered last so that application decorators wrap closer to the handler and BootUI observes the frames
 * the application actually receives.</p>
 */
public class BootUiWebSocketCaptureConfigurer implements WebSocketMessageBrokerConfigurer, Ordered {

    private final WebSocketActivityRecorder recorder;
    private final BootUiWebSocketSessionRegistry registry;
    private final SpringWebSocketMetadataProvider metadata;

    public BootUiWebSocketCaptureConfigurer(
            WebSocketActivityRecorder recorder,
            BootUiWebSocketSessionRegistry registry,
            SpringWebSocketMetadataProvider metadata) {
        this.recorder = recorder;
        this.registry = registry;
        this.metadata = metadata;
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.addDecoratorFactory(
                delegate -> new BootUiWebSocketHandlerDecorator(delegate, recorder, registry, metadata));
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(
                new BootUiStompChannelInterceptor(recorder, registry, WebSocketActivityRecorder.Direction.INBOUND));
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.interceptors(
                new BootUiStompChannelInterceptor(recorder, registry, WebSocketActivityRecorder.Direction.OUTBOUND));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
