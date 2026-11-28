package io.github.jdubois.bootui.quarkus4.websocket;

import java.util.List;

/**
 * Runtime holder for the build-time-captured {@code @WebSocket} endpoint inventory.
 *
 * <p>Backs a synthetic CDI bean produced by the deployment processor's {@code registerWebSockets} build
 * step. When that step does not run — production launch mode, or no {@code quarkus-websockets-next} on the
 * classpath — the bean is simply absent and the WebSockets panel reports itself unavailable.</p>
 */
public class QuarkusWebSockets {

    private final List<RawWebSocketEndpoint> endpoints;

    public QuarkusWebSockets(List<RawWebSocketEndpoint> endpoints) {
        this.endpoints = endpoints == null ? List.of() : List.copyOf(endpoints);
    }

    public List<RawWebSocketEndpoint> endpoints() {
        return endpoints;
    }
}
