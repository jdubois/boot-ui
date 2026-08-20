package io.github.jdubois.bootui.spi;

import java.util.List;

/**
 * One locally configured gRPC server, already mapped away from framework and {@code io.grpc} types.
 *
 * <p>Adapters build this from local server definitions and configuration only. Building it must never start a
 * server, bind a socket, enable reflection, or register an interceptor.</p>
 *
 * @param id stable identity for this server on this adapter
 * @param name display name of the server
 * @param address the configured listening address, before engine-side normalization and masking
 * @param port the listening port, or {@code null} when not exposed
 * @param security the configured transport security state
 * @param reflectionEnabled whether server reflection is enabled, or {@code null} when not exposed
 * @param maxInboundMessageSize maximum inbound message size in bytes, or {@code null} when not exposed
 * @param maxInboundMetadataSize maximum inbound metadata size in bytes, or {@code null} when not exposed
 * @param keepAlive keepalive settings the framework exposes
 * @param settings other bounded, framework-exposed transport settings
 * @param interceptors global server interceptor class names, where the framework exposes them
 * @param services the registered services; the engine applies ordering and cardinality bounds
 */
public record GrpcServerSnapshot(
        String id,
        String name,
        String address,
        Integer port,
        GrpcTransportSecurity security,
        Boolean reflectionEnabled,
        Long maxInboundMessageSize,
        Long maxInboundMetadataSize,
        List<GrpcSetting> keepAlive,
        List<GrpcSetting> settings,
        List<String> interceptors,
        List<GrpcServiceSnapshot> services) {

    public GrpcServerSnapshot {
        keepAlive = keepAlive == null ? List.of() : List.copyOf(keepAlive);
        settings = settings == null ? List.of() : List.copyOf(settings);
        interceptors = interceptors == null ? List.of() : List.copyOf(interceptors);
        services = services == null ? List.of() : List.copyOf(services);
    }
}
