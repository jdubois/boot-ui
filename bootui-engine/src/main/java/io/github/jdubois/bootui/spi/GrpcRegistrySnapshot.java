package io.github.jdubois.bootui.spi;

import java.util.List;

/**
 * One consistent read of the host application's gRPC registry.
 *
 * <p>Servers, channels, and warnings travel together in a single record so a provider reads its framework's
 * registry and configuration exactly once per report, and the engine can never mix a server list from one
 * read with a channel list from another.</p>
 *
 * @param servers the locally configured servers, unsorted and unbounded; the engine orders and bounds them
 * @param channels the framework-managed client channels, unsorted and unbounded
 * @param warnings bounded, non-fatal notices raised while reading local metadata; never a stack trace,
 *     credential, or raw target
 */
public record GrpcRegistrySnapshot(
        List<GrpcServerSnapshot> servers, List<GrpcChannelSnapshot> channels, List<String> warnings) {

    /** The empty registry, used when no supported gRPC integration is present. */
    public static final GrpcRegistrySnapshot EMPTY = new GrpcRegistrySnapshot(List.of(), List.of(), List.of());

    public GrpcRegistrySnapshot {
        servers = servers == null ? List.of() : List.copyOf(servers);
        channels = channels == null ? List.of() : List.copyOf(channels);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
