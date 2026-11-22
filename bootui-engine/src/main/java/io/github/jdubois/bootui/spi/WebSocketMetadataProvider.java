package io.github.jdubois.bootui.spi;

/**
 * Supplies the application's WebSocket endpoint topology to the engine.
 *
 * <p>Implemented in each adapter over its native registry so the engine stays free of Spring WebSocket
 * and Quarkus WebSockets Next types. Implementations must be read-only: resolving the topology must never
 * open a connection, send a frame, or alter a subscription.</p>
 */
public interface WebSocketMetadataProvider {

    /**
     * Returns the current topology, or {@code null} when no supported WebSocket integration is active.
     * Must never throw; an implementation that cannot resolve its registry returns {@code null}.
     */
    WebSocketTopologySnapshot topology();

    /**
     * Reports whether an endpoint is registered. Adapters can override this to check registrations
     * without resolving the detail used by the WebSockets report.
     */
    default boolean hasEndpoints() {
        return topology() != null;
    }
}
