package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Replication, checkpoint and WAL basics.
 *
 * @param inRecovery whether this server is a standby
 * @param checkpointsTimed checkpoints triggered by {@code checkpoint_timeout}
 * @param checkpointsRequested checkpoints that were requested rather than triggered by
 *     {@code checkpoint_timeout}: WAL volume reaching {@code max_wal_size}, an explicit {@code CHECKPOINT}, and
 *     other internally requested checkpoints all count here
 */
public record PostgresReplicationDto(
        boolean inRecovery,
        List<PostgresReplicaDto> replicas,
        Long checkpointsTimed,
        Long checkpointsRequested,
        Double checkpointWriteSeconds,
        Long replicationSlots,
        Long inactiveReplicationSlots,
        String walLevel) {

    public PostgresReplicationDto {
        replicas = DtoCollections.immutableCopy(replicas);
    }
}
