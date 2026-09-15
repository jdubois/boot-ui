package io.github.jdubois.bootui.core.dto;

/** Local channel observations only. Null states/counts mean unknown; there is no inferred lag/topology. */
public record MySqlReplicationChannelDto(
        String channel,
        String receiverState,
        String applierState,
        String workerCount,
        String errorCount,
        Integer lastErrorNumber) {}
