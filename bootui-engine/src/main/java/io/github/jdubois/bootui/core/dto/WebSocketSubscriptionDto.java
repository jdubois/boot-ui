package io.github.jdubois.bootui.core.dto;

/**
 * One STOMP (or equivalent framework-exposed) destination subscription.
 *
 * <p>Only the destination and the opaque session identifier are reported: the subscribing principal, the
 * raw STOMP subscription id, and arbitrary subscription headers are never serialized.</p>
 *
 * @param id opaque stable subscription identifier
 * @param endpointId the {@link WebSocketEndpointDto#id()} the subscription was made through
 * @param sessionId the opaque {@link WebSocketSessionDto#id()} that subscribed
 * @param destination the subscribed destination, displayed per the live exposure policy
 * @param subscribedAt epoch milliseconds when the subscription was observed
 */
public record WebSocketSubscriptionDto(
        String id, String endpointId, String sessionId, String destination, long subscribedAt) {}
