package io.github.jdubois.bootui.core.dto;

/**
 * One WebSocket session (Spring) or connection (Quarkus WebSockets Next) known to the application.
 *
 * <p>{@link #id()} is an opaque, stable hash of the framework session identifier: the raw identifier is
 * never serialized, so a BootUI reader cannot use it to address, impersonate, or hijack a live session.
 * Principals, cookies, authorization headers, query strings, and payloads are never part of this record.</p>
 *
 * @param id opaque stable session identifier (truncated SHA-256 of the framework identifier)
 * @param endpointId the {@link WebSocketEndpointDto#id()} this session belongs to
 * @param path the handshake path the session connected on
 * @param open whether the session is still open
 * @param openedAt epoch milliseconds when the session was opened
 * @param lastActivityAt epoch milliseconds of the last frame BootUI observed, or {@code null} when capture
 *     is disabled or no frame has been observed
 * @param subprotocol the negotiated subprotocol, or {@code null} when none was negotiated
 * @param remoteAddress the remote transport address displayed per the live exposure policy, or
 *     {@code null} when unavailable
 * @param localAddress the local transport address displayed per the live exposure policy, or {@code null}
 *     when unavailable
 * @param messagesIn inbound frames observed for this session since capture started
 * @param messagesOut outbound frames observed for this session since capture started
 * @param bytesIn inbound payload bytes counted (never stored) for this session
 * @param bytesOut outbound payload bytes counted (never stored) for this session
 * @param closeStatus the close status code when {@code open} is {@code false}, or {@code null}
 */
public record WebSocketSessionDto(
        String id,
        String endpointId,
        String path,
        boolean open,
        long openedAt,
        Long lastActivityAt,
        String subprotocol,
        String remoteAddress,
        String localAddress,
        long messagesIn,
        long messagesOut,
        long bytesIn,
        long bytesOut,
        Integer closeStatus) {}
