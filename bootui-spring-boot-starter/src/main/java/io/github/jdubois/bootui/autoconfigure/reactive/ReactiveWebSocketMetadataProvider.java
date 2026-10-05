package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.spi.WebSocketCallbackSnapshot;
import io.github.jdubois.bootui.spi.WebSocketEndpointSnapshot;
import io.github.jdubois.bootui.spi.WebSocketMetadataProvider;
import io.github.jdubois.bootui.spi.WebSocketTopologySnapshot;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.ApplicationContext;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;

/**
 * Reads the WebFlux WebSocket topology from the {@link SimpleUrlHandlerMapping} beans applications use to
 * publish reactive {@link WebSocketHandler}s.
 *
 * <p>WebFlux has no STOMP broker — {@code @EnableWebSocketMessageBroker} is servlet-only — and no
 * sanctioned seam for observing frames or enumerating live sessions without wrapping application handler
 * beans. BootUI therefore reports the endpoints honestly and marks frame capture unsupported with a
 * reason, rather than silently showing an always-empty activity list or reaching into private state.</p>
 *
 * <p>Resolving the topology is a pure read of already-registered beans: no handshake, no session, no
 * frame, so the panel stays network-free on render.</p>
 */
public class ReactiveWebSocketMetadataProvider implements WebSocketMetadataProvider {

    public static final String FRAMEWORK = "spring-webflux-websocket";

    static final String CAPTURE_UNAVAILABLE =
            "Spring WebFlux has no supported interception point for WebSocket frames or sessions, so BootUI "
                    + "lists endpoints only. Frame activity is available on the Spring MVC stack, where STOMP "
                    + "exposes a decorator seam.";

    private final ApplicationContext applicationContext;

    public ReactiveWebSocketMetadataProvider(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
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
        List<WebSocketEndpointSnapshot> endpoints = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (SimpleUrlHandlerMapping mapping : beans(SimpleUrlHandlerMapping.class)) {
            for (Map.Entry<?, ?> entry : mapping.getHandlerMap().entrySet()) {
                if (!(entry.getValue() instanceof WebSocketHandler handler)) {
                    continue;
                }
                String path = normalizePath(String.valueOf(entry.getKey()));
                String id = "handler:" + path;
                if (!seen.add(id)) {
                    continue;
                }
                endpoints.add(new WebSocketEndpointSnapshot(
                        id,
                        path,
                        "HANDLER",
                        handler.getClass().getName(),
                        subprotocols(handler),
                        false,
                        List.of(),
                        List.of(),
                        callbacks(handler),
                        null,
                        false));
            }
        }
        return endpoints;
    }

    private List<WebSocketCallbackSnapshot> callbacks(WebSocketHandler handler) {
        return List.of(WebSocketCallbackSnapshot.lifecycle(
                "ON_SESSION", handler.getClass().getName(), "handle"));
    }

    private List<String> subprotocols(WebSocketHandler handler) {
        List<String> protocols = handler.getSubProtocols();
        return protocols == null ? List.of() : List.copyOf(protocols);
    }

    private <T> List<T> beans(Class<T> type) {
        try {
            return List.copyOf(
                    applicationContext.getBeansOfType(type, false, false).values());
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    static String normalizePath(String pattern) {
        if (pattern == null) {
            return null;
        }
        String path = pattern;
        if (path.endsWith("/**")) {
            path = path.substring(0, path.length() - 3);
        }
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path.isEmpty() ? "/" : path;
    }
}
