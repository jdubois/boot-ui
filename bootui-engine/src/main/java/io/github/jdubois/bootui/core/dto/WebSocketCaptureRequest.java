package io.github.jdubois.bootui.core.dto;

/**
 * Body of {@code POST /bootui/api/websockets/capture}, toggling WebSocket activity capture at runtime.
 *
 * <p>Pausing capture stops BootUI observing new frames; it never closes a session, drops a subscription,
 * or otherwise affects the application's own WebSocket traffic.</p>
 *
 * @param enabled {@code true} to resume capture, {@code false} to pause it; {@code null} toggles
 */
public record WebSocketCaptureRequest(Boolean enabled) {}
