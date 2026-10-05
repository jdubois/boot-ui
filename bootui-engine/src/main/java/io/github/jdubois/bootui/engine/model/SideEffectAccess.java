package io.github.jdubois.bootui.engine.model;

/**
 * An execution's access to a file pattern or an environment variable, as the BootUI agent's Side Effects sensors
 * observed it in this run ({@code docs/PLAN-v2.md} §5.4, §5.16, M5-5d): a route, GraphQL operation, or scheduled job
 * {@link EdgeType#OPENS} a {@link NodeType#FILE_PATTERN} or {@link EdgeType#READS} an
 * {@link NodeType#ENVIRONMENT_VARIABLE}. Not part of change impact's closure: an access observed in one run says what the
 * requests sent did, not what a change can reach.
 *
 * @param fromType the execution's node type
 * @param fromKey the execution's key, such as {@code GET /reports}
 * @param type {@link EdgeType#OPENS} or {@link EdgeType#READS}
 * @param toType {@link NodeType#FILE_PATTERN} or {@link NodeType#ENVIRONMENT_VARIABLE}
 * @param toKey the path pattern, or {@code env:NAME} or {@code property:name}
 * @param count how many times it was observed
 * @param firstSeenEpochMillis when first, or 0 when unknown
 * @param lastSeenEpochMillis when last, or 0 when unknown
 */
public record SideEffectAccess(
        NodeType fromType,
        String fromKey,
        EdgeType type,
        NodeType toType,
        String toKey,
        long count,
        long firstSeenEpochMillis,
        long lastSeenEpochMillis) {}
