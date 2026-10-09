package io.github.jdubois.bootui.core.dto;

/**
 * Aggregate counters over the WebSocket endpoints, sessions, subscriptions, and captured activity
 * currently retained by the panel.
 *
 * @param endpoints number of discovered endpoints (before truncation)
 * @param openSessions number of currently open sessions (before truncation)
 * @param closedSessions number of recently closed sessions still retained (before truncation)
 * @param subscriptions number of retained subscriptions (before truncation)
 * @param inboundFrames inbound frames captured since startup
 * @param outboundFrames outbound frames captured since startup
 * @param inboundBytes inbound payload bytes counted since startup
 * @param outboundBytes outbound payload bytes counted since startup
 * @param failedFrames captured frames whose handling failed
 * @param capturedActivity total activity entries captured since startup (may exceed the buffer)
 * @param evictedActivity activity entries dropped because the buffer reached capacity
 */
public record WebSocketStatsDto(
        int endpoints,
        int openSessions,
        int closedSessions,
        int subscriptions,
        long inboundFrames,
        long outboundFrames,
        long inboundBytes,
        long outboundBytes,
        long failedFrames,
        long capturedActivity,
        long evictedActivity) {

    public static WebSocketStatsDto empty() {
        return new WebSocketStatsDto(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
