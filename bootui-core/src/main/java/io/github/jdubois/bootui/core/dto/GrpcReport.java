package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Top-level report for the gRPC panel.
 *
 * <p>Read-only by construction: it is assembled from local service registries, server definitions,
 * managed-channel configuration, and metrics the application already publishes. Rendering it never creates a
 * channel or stub, performs a DNS lookup or RPC, enables server reflection, or registers an interceptor.</p>
 *
 * @param available whether a supported gRPC integration is present on this runtime
 * @param unavailableReason why the panel is unavailable, or {@code null} when it is available
 * @param integration the detected integration (for example {@code Spring gRPC} or {@code Quarkus gRPC}), or
 *     {@code null} when unavailable
 * @param serverCount number of locally configured gRPC servers
 * @param serviceCount total registered services across all servers
 * @param methodCount total methods across all registered services
 * @param channelCount number of framework-managed outbound client channels
 * @param metricsAvailable whether any native gRPC metric series was found
 * @param metricsUnavailableReason why aggregate metrics are unavailable, or {@code null}
 * @param servers the locally configured servers, ordered by name
 * @param channels the framework-managed client channels, ordered by name
 * @param clientServices remote services this application called, observed only through existing client-side
 *     metrics; empty when no such series exists
 * @param warnings bounded, non-fatal notices (for example truncation notices) surfaced to the user
 */
public record GrpcReport(
        boolean available,
        String unavailableReason,
        String integration,
        int serverCount,
        int serviceCount,
        int methodCount,
        int channelCount,
        boolean metricsAvailable,
        String metricsUnavailableReason,
        List<GrpcServerDto> servers,
        List<GrpcChannelDto> channels,
        List<GrpcServiceDto> clientServices,
        List<String> warnings) {

    public GrpcReport {
        servers = servers == null ? List.of() : List.copyOf(servers);
        channels = channels == null ? List.of() : List.copyOf(channels);
        clientServices = clientServices == null ? List.of() : List.copyOf(clientServices);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** The stable empty report served when no supported gRPC integration is present. */
    public static GrpcReport unavailable(String reason, String metricsUnavailableReason) {
        return new GrpcReport(
                false,
                reason,
                null,
                0,
                0,
                0,
                0,
                false,
                metricsUnavailableReason,
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }
}
