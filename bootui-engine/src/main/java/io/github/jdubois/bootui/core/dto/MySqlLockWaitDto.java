package io.github.jdubois.bootui.core.dto;

/** ROW edges or METADATA pending observations. Metadata blockers are unknown, not guessed. No lock payloads. */
public record MySqlLockWaitDto(
        String kind,
        String requestingThreadId,
        String blockingThreadId,
        String schemaName,
        String objectName,
        String indexName,
        String requestedMode,
        String blockingMode,
        String status) {}
