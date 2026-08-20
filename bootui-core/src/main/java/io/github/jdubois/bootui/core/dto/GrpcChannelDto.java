package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One framework-managed outbound gRPC client channel, described from local configuration only.
 *
 * <p>Reading this DTO never creates a channel or stub, resolves DNS, or issues an RPC. The target is reported
 * after safe normalization: user-info and secret-bearing query parameters are removed before display.</p>
 *
 * @param name the channel registration name
 * @param target the configured target after safe normalization and masking
 * @param authority the resolved authority (host and port) after normalization, or {@code null} when unknown
 * @param loadBalancingPolicy the configured load-balancing policy, or {@code null} when not exposed
 * @param transportSecurity {@code PLAINTEXT}, {@code TLS} or {@code UNKNOWN}
 * @param retryEnabled whether transparent retry is enabled, or {@code null} when the framework does not expose it
 * @param maxInboundMessageSize maximum inbound message size in bytes, or {@code null} when not exposed
 * @param maxInboundMetadataSize maximum inbound metadata size in bytes, or {@code null} when not exposed
 * @param keepAlive keepalive settings the framework exposes, as neutral name/value pairs
 * @param settings other bounded, framework-exposed channel settings
 * @param interceptors client interceptor class names, where the framework exposes them
 */
public record GrpcChannelDto(
        String name,
        String target,
        String authority,
        String loadBalancingPolicy,
        String transportSecurity,
        Boolean retryEnabled,
        Long maxInboundMessageSize,
        Long maxInboundMetadataSize,
        List<GrpcSettingDto> keepAlive,
        List<GrpcSettingDto> settings,
        List<String> interceptors) {

    public GrpcChannelDto {
        keepAlive = keepAlive == null ? List.of() : List.copyOf(keepAlive);
        settings = settings == null ? List.of() : List.copyOf(settings);
        interceptors = interceptors == null ? List.of() : List.copyOf(interceptors);
    }
}
