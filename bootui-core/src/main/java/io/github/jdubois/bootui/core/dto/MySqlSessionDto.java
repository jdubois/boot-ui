package io.github.jdubois.bootui.core.dto;

/** Default-schema association is not exhaustive schema access; state age is not transaction age. */
public record MySqlSessionDto(
        String threadId,
        String connectionId,
        String user,
        String host,
        String database,
        String command,
        String state,
        Double stateSeconds,
        String transactionState,
        Double transactionAgeSeconds,
        String rowsLocked,
        String rowsModified) {}
