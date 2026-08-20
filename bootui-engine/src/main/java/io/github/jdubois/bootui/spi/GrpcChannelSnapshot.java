package io.github.jdubois.bootui.spi;

import java.util.List;

/**
 * One framework-managed outbound gRPC client channel, described from local configuration only.
 *
 * <p>Adapters build this by reading declared channel configuration. Building it must never create a channel or
 * stub, resolve a name, or issue an RPC. The raw {@code target} may still contain user-info or query
 * parameters; the engine normalizes and redacts it before it reaches the response.</p>
 *
 * @param name the channel registration name
 * @param target the configured target, before engine-side normalization and redaction
 * @param loadBalancingPolicy the configured load-balancing policy, or {@code null} when not exposed
 * @param security the configured transport security state
 * @param retryEnabled whether transparent retry is enabled, or {@code null} when not exposed
 * @param maxInboundMessageSize maximum inbound message size in bytes, or {@code null} when not exposed
 * @param maxInboundMetadataSize maximum inbound metadata size in bytes, or {@code null} when not exposed
 * @param keepAlive keepalive settings the framework exposes
 * @param settings other bounded, framework-exposed channel settings
 * @param interceptors client interceptor class names, where the framework exposes them
 */
public record GrpcChannelSnapshot(
        String name,
        String target,
        String loadBalancingPolicy,
        GrpcTransportSecurity security,
        Boolean retryEnabled,
        Long maxInboundMessageSize,
        Long maxInboundMetadataSize,
        List<GrpcSetting> keepAlive,
        List<GrpcSetting> settings,
        List<String> interceptors) {

    public GrpcChannelSnapshot {
        keepAlive = keepAlive == null ? List.of() : List.copyOf(keepAlive);
        settings = settings == null ? List.of() : List.copyOf(settings);
        interceptors = interceptors == null ? List.of() : List.copyOf(interceptors);
    }
}
