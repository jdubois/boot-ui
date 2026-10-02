package io.github.jdubois.bootui.engine.model;

import java.util.Objects;

/**
 * An observed edge as a whole run counted it ({@code docs/PLAN-v2.md} §5.8): the journal's aggregates count it as
 * events arrive, so it still counts the events the journal later evicted, and the run summary keeps it.
 *
 * @param edge the edge, by its nodes' types and keys
 * @param count how many events observed it
 * @param firstSeenEpochMillis when the run first observed it
 * @param lastSeenEpochMillis when the run last observed it
 */
public record ObservedEdge(EdgeDiff.EdgeRef edge, long count, long firstSeenEpochMillis, long lastSeenEpochMillis) {

    public ObservedEdge {
        Objects.requireNonNull(edge, "edge");
    }
}
