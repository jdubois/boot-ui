package io.github.jdubois.bootui.core.dto;

/**
 * One captured WebSocket frame or connection-lifecycle event.
 *
 * <p><strong>Metadata only.</strong> BootUI counts a frame's payload size but never reads, copies, decodes,
 * or stores its bytes or text, and never captures frame headers, principals, or query values. This buffer
 * is independent of Live Activity and of every other panel's retained data.</p>
 *
 * @param id sequence number, increasing in capture order
 * @param timestamp epoch milliseconds when the frame or event was observed
 * @param endpointId the {@link WebSocketEndpointDto#id()} the frame belongs to
 * @param sessionId the opaque {@link WebSocketSessionDto#id()} the frame belongs to
 * @param direction {@code INBOUND} for client-to-server, {@code OUTBOUND} for server-to-client
 * @param frameType frame or event kind: {@code TEXT}, {@code BINARY}, {@code PING}, {@code PONG},
 *     {@code OPEN}, {@code CLOSE}, {@code SUBSCRIBE}, {@code UNSUBSCRIBE}, or {@code CONNECT}
 * @param destination the STOMP destination when the framework exposes one, otherwise {@code null}
 * @param payloadBytes payload size in bytes, or {@code null} when the framework does not expose a size
 * @param durationMillis handling time in milliseconds when the framework exposes one, otherwise
 *     {@code null}
 * @param success whether the frame was handled without an application-visible failure
 * @param errorCategory the failure category (an exception simple name) when {@code success} is
 *     {@code false}; never a message, stack trace, or payload
 */
public record WebSocketActivityEntryDto(
        long id,
        long timestamp,
        String endpointId,
        String sessionId,
        String direction,
        String frameType,
        String destination,
        Long payloadBytes,
        Long durationMillis,
        boolean success,
        String errorCategory) {}
