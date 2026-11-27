package io.github.jdubois.bootui.quarkus.websocket;

import io.github.jdubois.bootui.spi.WebSocketCallbackSnapshot;
import io.github.jdubois.bootui.spi.WebSocketEndpointSnapshot;
import io.github.jdubois.bootui.spi.WebSocketMetadataProvider;
import io.github.jdubois.bootui.spi.WebSocketTopologySnapshot;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import java.util.List;

/**
 * Framework-neutral view of the Quarkus WebSockets Next topology.
 *
 * <p>Reads the build-time-captured endpoint inventory only, so it imports no
 * {@code io.quarkus.websockets.next} type and stays loadable on a classpath without the extension: when the
 * synthetic {@link QuarkusWebSockets} bean is absent the provider reports no topology and the panel is
 * honestly unavailable.</p>
 *
 * <p>WebSockets Next intentionally exposes no per-message interception SPI, so frame capture is reported
 * unsupported with a reason rather than shown as an always-empty activity list. Connection open/close
 * events <em>are</em> observable and are captured by {@code QuarkusWebSocketConnectionCapture}.</p>
 */
@ApplicationScoped
public class QuarkusWebSocketMetadataProvider implements WebSocketMetadataProvider {

    public static final String FRAMEWORK = "quarkus-websockets-next";

    static final String CAPTURE_UNAVAILABLE =
            "Quarkus WebSockets Next exposes no message-interception SPI, so BootUI records connection "
                    + "open/close events only. Per-frame metadata is available on the Spring MVC stack, "
                    + "where STOMP provides a decorator seam.";

    private final Instance<QuarkusWebSockets> captured;

    public QuarkusWebSocketMetadataProvider(Instance<QuarkusWebSockets> captured) {
        this.captured = captured;
    }

    /** Build-time inventory only. Empty topology origin lists are not declared policy evidence. */
    public io.github.jdubois.bootui.engine.security.WebSocketOriginObservation originObservation() {
        if (captured == null || captured.isUnsatisfied()) {
            return io.github.jdubois.bootui.engine.security.WebSocketOriginObservation.absent();
        }
        if (!captured.isResolvable()) {
            return io.github.jdubois.bootui.engine.security.WebSocketOriginObservation.unavailable();
        }
        var builder = new io.github.jdubois.bootui.engine.security.WebSocketOriginObservation.Builder();
        int count = 0;
        for (var endpoint : captured.get().endpoints()) {
            if (++count > io.github.jdubois.bootui.engine.security.WebSocketOriginObservation.MAX_ENDPOINTS) {
                builder.incomplete();
                break;
            }
            builder.endpoint(endpoint.path(), null, false);
        }
        return builder.build();
    }

    @Override
    public WebSocketTopologySnapshot topology() {
        List<WebSocketEndpointSnapshot> endpoints = endpoints();
        if (endpoints.isEmpty()) {
            return null;
        }
        return new WebSocketTopologySnapshot(
                FRAMEWORK, endpoints, List.of(), List.of(), null, false, CAPTURE_UNAVAILABLE);
    }

    private List<WebSocketEndpointSnapshot> endpoints() {
        if (captured == null || !captured.isResolvable()) {
            return List.of();
        }
        return captured.get().endpoints().stream()
                .map(QuarkusWebSocketMetadataProvider::toSnapshot)
                .toList();
    }

    private static WebSocketEndpointSnapshot toSnapshot(RawWebSocketEndpoint endpoint) {
        List<WebSocketCallbackSnapshot> callbacks = endpoint.callbacks().stream()
                .map(callback -> new WebSocketCallbackSnapshot(
                        callback.type(), null, callback.declaringClass(), callback.method(), callback.messageType()))
                .toList();
        return new WebSocketEndpointSnapshot(
                endpoint.id(),
                endpoint.path(),
                "ENDPOINT",
                endpoint.handlerClass(),
                List.of(),
                false,
                List.of(),
                List.of(),
                callbacks,
                endpoint.inboundProcessingMode(),
                false);
    }
}
