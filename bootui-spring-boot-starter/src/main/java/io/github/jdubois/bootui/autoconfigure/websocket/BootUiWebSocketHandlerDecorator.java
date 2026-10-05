package io.github.jdubois.bootui.autoconfigure.websocket;

import io.github.jdubois.bootui.engine.websocket.WebSocketActivityRecorder;
import io.github.jdubois.bootui.spi.WebSocketSessionSnapshot;
import java.net.InetSocketAddress;
import java.net.URI;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

/**
 * Records session lifecycle and per-frame <em>metadata</em> for STOMP transport sessions.
 *
 * <p>Installed through {@code WebSocketTransportRegistration#addDecoratorFactory}, which is Spring's
 * sanctioned decoration seam: application decorators registered by the application keep their own place
 * in the chain, and every callback is delegated before or after BootUI's bookkeeping so a BootUI failure
 * can never break the application's WebSocket handling.</p>
 *
 * <p><strong>Payloads are never read.</strong> Frame size comes from
 * {@link WebSocketMessage#getPayloadLength()}, which reports the byte/character count without touching
 * the payload, so message content never reaches BootUI's memory, logs, or JSON.</p>
 */
public class BootUiWebSocketHandlerDecorator extends WebSocketHandlerDecorator {

    private final WebSocketActivityRecorder recorder;
    private final BootUiWebSocketSessionRegistry registry;
    private final SpringWebSocketMetadataProvider metadata;

    public BootUiWebSocketHandlerDecorator(
            WebSocketHandler delegate,
            WebSocketActivityRecorder recorder,
            BootUiWebSocketSessionRegistry registry,
            SpringWebSocketMetadataProvider metadata) {
        super(delegate);
        this.recorder = recorder;
        this.registry = registry;
        this.metadata = metadata;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        try {
            String path = path(session);
            String endpointId = endpointIdFor(path);
            registry.opened(new WebSocketSessionSnapshot(
                    session.getId(),
                    endpointId,
                    path,
                    true,
                    System.currentTimeMillis(),
                    session.getAcceptedProtocol(),
                    address(session.getRemoteAddress()),
                    address(session.getLocalAddress()),
                    null));
            recorder.recordFrame(
                    endpointId,
                    session.getId(),
                    WebSocketActivityRecorder.Direction.INBOUND,
                    WebSocketActivityRecorder.FrameType.OPEN,
                    null,
                    null,
                    null,
                    true,
                    null);
        } catch (RuntimeException ignored) {
            // Observation must never break the application's WebSocket handling.
        }
        super.afterConnectionEstablished(session);
    }

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
        try {
            WebSocketActivityRecorder.FrameType frameType = frameType(message);
            // Data frames carry STOMP messages, and BootUiStompChannelInterceptor records them once with
            // their destination. Recording the transport view as well would double count every inbound
            // message and add a destination-less duplicate, so only control frames are recorded here.
            if (frameType == WebSocketActivityRecorder.FrameType.PING
                    || frameType == WebSocketActivityRecorder.FrameType.PONG) {
                recorder.recordFrame(
                        registry.endpointIdFor(session.getId()),
                        session.getId(),
                        WebSocketActivityRecorder.Direction.INBOUND,
                        frameType,
                        null,
                        (long) message.getPayloadLength(),
                        null,
                        true,
                        null);
            }
        } catch (RuntimeException ignored) {
            // Observation must never break the application's WebSocket handling.
        }
        super.handleMessage(session, message);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        try {
            recorder.recordFrame(
                    registry.endpointIdFor(session.getId()),
                    session.getId(),
                    WebSocketActivityRecorder.Direction.INBOUND,
                    WebSocketActivityRecorder.FrameType.CLOSE,
                    null,
                    null,
                    null,
                    false,
                    exception == null ? "transport" : exception.getClass().getSimpleName());
        } catch (RuntimeException ignored) {
            // Observation must never break the application's WebSocket handling.
        }
        super.handleTransportError(session, exception);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
        try {
            String endpointId = registry.endpointIdFor(session.getId());
            recorder.recordFrame(
                    endpointId,
                    session.getId(),
                    WebSocketActivityRecorder.Direction.INBOUND,
                    WebSocketActivityRecorder.FrameType.CLOSE,
                    null,
                    null,
                    null,
                    true,
                    null);
            registry.closed(session.getId(), closeStatus == null ? null : closeStatus.getCode());
        } catch (RuntimeException ignored) {
            // Observation must never break the application's WebSocket handling.
        }
        super.afterConnectionClosed(session, closeStatus);
    }

    private WebSocketActivityRecorder.FrameType frameType(WebSocketMessage<?> message) {
        return switch (message.getClass().getSimpleName()) {
            case "BinaryMessage" -> WebSocketActivityRecorder.FrameType.BINARY;
            case "PingMessage" -> WebSocketActivityRecorder.FrameType.PING;
            case "PongMessage" -> WebSocketActivityRecorder.FrameType.PONG;
            default -> WebSocketActivityRecorder.FrameType.TEXT;
        };
    }

    /**
     * Resolves the endpoint the handshake path belongs to. Topology resolution reads the application's own
     * beans, so it is isolated here: a failure (or an absent provider) must degrade the endpoint attribution
     * of one session, never lose the session itself.
     */
    private String endpointIdFor(String path) {
        if (metadata == null) {
            return null;
        }
        try {
            return metadata.endpointIdFor(path);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** Returns the request path without the query string, which may carry tokens or identifiers. */
    private String path(WebSocketSession session) {
        URI uri = session.getUri();
        return uri == null ? null : uri.getPath();
    }

    private String address(InetSocketAddress address) {
        if (address == null) {
            return null;
        }
        String host = address.getHostString();
        return host == null ? null : host + ":" + address.getPort();
    }
}
