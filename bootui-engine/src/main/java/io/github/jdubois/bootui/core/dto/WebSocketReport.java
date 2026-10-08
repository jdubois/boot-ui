package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Top-level report returned by the WebSockets panel.
 *
 * <p>The same shape is served by Spring MVC, Spring WebFlux, and Quarkus. Where a stack cannot supply a
 * capability the report says so explicitly ({@link #frameCaptureSupported()} /
 * {@link #frameCaptureUnavailableReason()}) rather than silently returning an empty list.</p>
 *
 * @param available whether a supported WebSocket integration is present
 * @param unavailableReason populated when {@code available} is {@code false}
 * @param framework the detected integration: {@code spring-websocket}, {@code spring-webflux-websocket},
 *     or {@code quarkus-websockets-next}
 * @param capturing whether frame/lifecycle activity is currently being recorded
 * @param frameCaptureSupported whether the detected integration lets BootUI observe individual frames
 * @param frameCaptureUnavailableReason why per-frame capture is unavailable, when it is not supported
 * @param sessionTrackingSupported whether the detected integration lets BootUI observe live sessions; when
 *     {@code false} the session and subscription lists are empty because the stack exposes no seam, not
 *     because no client is connected
 * @param sessionTrackingUnavailableReason why live session tracking is unavailable, when it is not
 *     supported
 * @param brokerPrefixes STOMP broker destination prefixes, empty when not applicable
 * @param applicationDestinationPrefixes STOMP application destination prefixes, empty when not applicable
 * @param userDestinationPrefix STOMP user destination prefix, or {@code null} when not applicable
 * @param maxEndpoints independent endpoint cap applied before serialization
 * @param maxSessions independent session cap applied before serialization
 * @param maxSubscriptions independent subscription cap applied before serialization
 * @param maxActivityEntries independent activity-buffer cap
 * @param endpointsTruncated whether endpoints were dropped to honor {@code maxEndpoints}
 * @param sessionsTruncated whether sessions were dropped to honor {@code maxSessions}
 * @param subscriptionsTruncated whether subscriptions were dropped to honor {@code maxSubscriptions}
 * @param endpoints discovered endpoints, ordered by path
 * @param sessions retained sessions, most recently active first
 * @param subscriptions retained subscriptions, most recent first
 * @param activity retained activity entries, most recent first
 * @param stats aggregate counters
 * @param warnings non-fatal advisories about the current WebSocket state
 */
public record WebSocketReport(
        boolean available,
        String unavailableReason,
        String framework,
        boolean capturing,
        boolean frameCaptureSupported,
        String frameCaptureUnavailableReason,
        boolean sessionTrackingSupported,
        String sessionTrackingUnavailableReason,
        List<String> brokerPrefixes,
        List<String> applicationDestinationPrefixes,
        String userDestinationPrefix,
        int maxEndpoints,
        int maxSessions,
        int maxSubscriptions,
        int maxActivityEntries,
        boolean endpointsTruncated,
        boolean sessionsTruncated,
        boolean subscriptionsTruncated,
        List<WebSocketEndpointDto> endpoints,
        List<WebSocketSessionDto> sessions,
        List<WebSocketSubscriptionDto> subscriptions,
        List<WebSocketActivityEntryDto> activity,
        WebSocketStatsDto stats,
        List<String> warnings) {

    public WebSocketReport {
        brokerPrefixes = DtoCollections.immutableCopy(brokerPrefixes);
        applicationDestinationPrefixes = DtoCollections.immutableCopy(applicationDestinationPrefixes);
        endpoints = DtoCollections.immutableCopy(endpoints);
        sessions = DtoCollections.immutableCopy(sessions);
        subscriptions = DtoCollections.immutableCopy(subscriptions);
        activity = DtoCollections.immutableCopy(activity);
        stats = stats == null ? WebSocketStatsDto.empty() : stats;
        warnings = DtoCollections.immutableCopy(warnings);
    }

    public static WebSocketReport unavailable(String reason) {
        return new WebSocketReport(
                false,
                reason,
                null,
                false,
                false,
                null,
                false,
                null,
                List.of(),
                List.of(),
                null,
                0,
                0,
                0,
                0,
                false,
                false,
                false,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                WebSocketStatsDto.empty(),
                List.of());
    }
}
