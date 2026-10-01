package io.github.jdubois.bootui.engine.model;

/**
 * An edge of the runtime model, from one interned node to another.
 *
 * @param from the source node's id
 * @param type what it says
 * @param to the target node's id
 * @param provenance where it comes from
 * @param count how many times this run observed it, or {@code 0} when it is only declared
 * @param firstSeenEpochMillis when this run first observed it, or {@code -1}
 * @param lastSeenEpochMillis when this run last observed it, or {@code -1}
 */
public record ModelEdge(
        int from,
        EdgeType type,
        int to,
        Provenance provenance,
        long count,
        long firstSeenEpochMillis,
        long lastSeenEpochMillis) {}
