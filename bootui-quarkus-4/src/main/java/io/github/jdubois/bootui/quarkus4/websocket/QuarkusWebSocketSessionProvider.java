package io.github.jdubois.bootui.quarkus4.websocket;

import io.github.jdubois.bootui.spi.WebSocketSessionProvider;
import io.github.jdubois.bootui.spi.WebSocketSessionSnapshot;
import io.quarkus.websockets.next.OpenConnections;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import java.util.ArrayList;
import java.util.List;

/**
 * Reports the live WebSockets Next connections through Quarkus' own {@link OpenConnections} bean.
 *
 * <p>This is the first and only importer of {@code io.quarkus.websockets.next} in BootUI's read path, so it
 * is registered by a class-presence-gated build step and actively excluded from bean discovery when
 * {@code quarkus-websockets-next} is absent — otherwise Arc would discover this {@code @ApplicationScoped}
 * bean unconditionally and link an API that must stay absent.</p>
 *
 * <p>Reading connections is a pure enumeration of already-open connections: nothing is opened, closed, or
 * sent, so the panel remains network-free on render. Only the connection id, endpoint id, handshake path,
 * subprotocol, and transport addresses are read; the handshake query string, headers, and user data are
 * deliberately ignored, and the connection id is hashed by the engine before it reaches the UI.</p>
 */
@ApplicationScoped
public class QuarkusWebSocketSessionProvider implements WebSocketSessionProvider {

    private final Instance<OpenConnections> openConnections;
    private final QuarkusWebSocketConnectionCapture capture;

    public QuarkusWebSocketSessionProvider(
            Instance<OpenConnections> openConnections, QuarkusWebSocketConnectionCapture capture) {
        this.openConnections = openConnections;
        this.capture = capture;
    }

    @Override
    public List<WebSocketSessionSnapshot> sessions() {
        List<WebSocketSessionSnapshot> sessions = new ArrayList<>(capture.closedConnections());
        if (openConnections == null || !openConnections.isResolvable()) {
            return sessions;
        }
        for (WebSocketConnection connection : openConnections.get().listAll()) {
            sessions.add(toSnapshot(connection));
        }
        return sessions;
    }

    @Override
    public void clearRetainedSessions() {
        capture.clear();
    }

    private WebSocketSessionSnapshot toSnapshot(WebSocketConnection connection) {
        return new WebSocketSessionSnapshot(
                connection.id(),
                connection.endpointId(),
                path(connection),
                connection.isOpen(),
                capture.openedAt(connection),
                connection.subprotocol(),
                remoteAddress(connection),
                localAddress(connection),
                null);
    }

    /** Returns the handshake path without its query string, which may carry a token or identifier. */
    static String path(WebSocketConnection connection) {
        try {
            return connection.handshakeRequest() == null
                    ? null
                    : connection.handshakeRequest().path();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    static String remoteAddress(WebSocketConnection connection) {
        try {
            return connection.handshakeRequest() == null
                    ? null
                    : connection.handshakeRequest().remoteAddress();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    static String localAddress(WebSocketConnection connection) {
        try {
            return connection.handshakeRequest() == null
                    ? null
                    : connection.handshakeRequest().localAddress();
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
