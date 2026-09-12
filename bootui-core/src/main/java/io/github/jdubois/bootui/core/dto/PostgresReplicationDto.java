package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Replication, checkpoint and WAL basics.
 *
 * @param inRecovery whether this server is a standby
 * @param checkpointsTimed checkpoints triggered by {@code checkpoint_timeout}
 * @param checkpointsRequested checkpoints forced by WAL volume — a high share means {@code max_wal_size} is
 *     too small for the write rate
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
