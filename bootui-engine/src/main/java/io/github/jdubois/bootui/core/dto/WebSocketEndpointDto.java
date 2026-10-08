package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One WebSocket endpoint declared by the running application.
 *
 * <p>Metadata only: BootUI never opens, closes, or writes to a WebSocket endpoint, and never records the
 * bytes flowing through it.</p>
 *
 * @param id stable identifier for the endpoint, used to group sessions, subscriptions, and activity
 * @param path the normalized handshake path (Spring) or endpoint path template (Quarkus WebSockets Next)
 * @param kind endpoint flavour: {@code HANDLER} for a native Spring {@code WebSocketHandler},
 *     {@code STOMP} for a STOMP/SockJS broker endpoint, or {@code ENDPOINT} for a WebSockets Next
 *     {@code @WebSocket} endpoint
 * @param handlerClass the application class implementing the endpoint, or {@code null} when the framework
 *     does not expose one
 * @param subprotocols declared subprotocols, empty when none are declared
 * @param sockJs whether the Spring endpoint also exposes the SockJS fallback transports
 * @param allowedOrigins declared handshake origin policy entries, displayed per the live exposure policy;
 *     empty when the framework does not expose one
 * @param interceptors handshake interceptor or channel interceptor class names, empty when none are
 *     exposed safely
 * @param callbacks the endpoint's declared callbacks and message mappings
 * @param openSessions number of currently open sessions attributed to this endpoint
 * @param inboundProcessingMode Quarkus WebSockets Next inbound processing mode, or {@code null} on Spring
 * @param captureInstalled whether BootUI has a sanctioned observation seam on this endpoint; when
 *     {@code false} the endpoint is still fully described but reports no sessions or frame activity
 */
public record WebSocketEndpointDto(
        String id,
        String path,
        String kind,
        String handlerClass,
        List<String> subprotocols,
        boolean sockJs,
        List<String> allowedOrigins,
        List<String> interceptors,
        List<WebSocketCallbackDto> callbacks,
        int openSessions,
        String inboundProcessingMode,
        boolean captureInstalled) {

    public WebSocketEndpointDto {
        subprotocols = DtoCollections.immutableCopy(subprotocols);
        allowedOrigins = DtoCollections.immutableCopy(allowedOrigins);
        interceptors = DtoCollections.immutableCopy(interceptors);
        callbacks = DtoCollections.immutableCopy(callbacks);
    }
}
