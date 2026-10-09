package io.github.jdubois.bootui.core.dto;

/**
 * One streaming replica reported by {@code pg_stat_replication} on this server.
 *
 * <p>The client address is reported as the server sees it; it is host-local infrastructure metadata, never
 * application data.</p>
 */
public record PostgresReplicaDto(
        String applicationName,
        String clientAddress,
        String state,
        String syncState,
        Long sentLagBytes,
        Long flushLagBytes,
        Long replayLagBytes) {}
