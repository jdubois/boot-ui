package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One locally configured gRPC server, with the services it exposes.
 *
 * <p>All values come from local registries and configuration. Opening the panel never starts a server, opens a
 * channel, resolves a name, or enables reflection.</p>
 *
 * @param id stable identity for this server within the report
 * @param name display name of the server
 * @param address the listening address, already normalized and masked
 * @param port the listening port, or {@code null} when the framework does not expose one
 * @param transportSecurity {@code PLAINTEXT}, {@code TLS} or {@code UNKNOWN}
 * @param reflectionEnabled whether server reflection is enabled, or {@code null} when not exposed
 * @param maxInboundMessageSize maximum inbound message size in bytes, or {@code null} when not exposed
 * @param maxInboundMetadataSize maximum inbound metadata size in bytes, or {@code null} when not exposed
 * @param keepAlive keepalive settings the framework exposes, as neutral name/value pairs
 * @param settings other bounded, framework-exposed transport settings
 * @param interceptors global server interceptor class names, where the framework exposes them
 * @param serviceCount total registered services, before any display bound is applied
 * @param methodCount total methods across all registered services
 * @param services the retained services, ordered by name
 * @param servicesTruncated whether {@code services} was truncated by the cardinality bound
 */
public record GrpcServerDto(
        String id,
        String name,
        String address,
        Integer port,
        String transportSecurity,
        Boolean reflectionEnabled,
        Long maxInboundMessageSize,
        Long maxInboundMetadataSize,
        List<GrpcSettingDto> keepAlive,
        List<GrpcSettingDto> settings,
        List<String> interceptors,
        int serviceCount,
        int methodCount,
        List<GrpcServiceDto> services,
        boolean servicesTruncated) {

    public GrpcServerDto {
        keepAlive = keepAlive == null ? List.of() : List.copyOf(keepAlive);
        settings = settings == null ? List.of() : List.copyOf(settings);
        interceptors = interceptors == null ? List.of() : List.copyOf(interceptors);
        services = services == null ? List.of() : List.copyOf(services);
    }
}
