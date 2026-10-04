package io.github.jdubois.bootui.engine.model;

import io.github.jdubois.bootui.engine.journal.JournalAggregates;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.AggregatesSnapshot;
import io.github.jdubois.bootui.engine.journal.RunSummary;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The behavior diff's edge comparison of two whole runs ({@code docs/PLAN-v2.md} §5.8): the observed edges the current
 * run counted and the previous run did not, and the reverse, from the edge sets the aggregates count, never from the
 * retained window. Each side keeps its own counts, so "run 5 added {@code GET /api/orders → pay.internal:8443}, 15
 * calls" reads directly.
 *
 * @param previousRunId the run compared with, or {@code null} when none is kept
 * @param added edges the current run has and the previous one did not, most observed first, with the current counts
 * @param removed edges the previous run had and the current one does not, most observed first, with the previous counts
 * @param limitations why either list may be incomplete, such as a previous run that kept no edges or left some out
 */
public record RunEdgeDiff(
        String previousRunId, List<ObservedEdge> added, List<ObservedEdge> removed, List<String> limitations) {

    private static final Comparator<ObservedEdge> MOST_OBSERVED = Comparator.comparingLong(ObservedEdge::count)
            .reversed()
            .thenComparing(edge -> edge.edge().fromKey())
            .thenComparing(edge -> edge.edge().toKey());

    public RunEdgeDiff {
        added = List.copyOf(added);
        removed = List.copyOf(removed);
        limitations = List.copyOf(limitations);
    }

    /**
     * Compares {@code current}, the current run's aggregates, with {@code previous}, the previous run's summary.
     *
     * @param previous the previous run's summary, or {@code null} when none is kept
     * @param noPreviousReason why no previous run is kept, when it is known, such as the run history's reason
     */
    public static RunEdgeDiff compare(RunSummary previous, AggregatesSnapshot current, String noPreviousReason) {
        Objects.requireNonNull(current, "current");
        List<String> limitations = new ArrayList<>();
        if (previous == null) {
            limitations.add(
                    noPreviousReason == null || noPreviousReason.isBlank()
                            ? "No previous run is kept, so no edge is compared."
                            : noPreviousReason);
            return new RunEdgeDiff(null, List.of(), List.of(), limitations);
        }
        AggregatesSnapshot before = previous.aggregates();
        String run = "Run " + previous.header().ordinal();
        if (before.edges().isEmpty() && previous.header().events() > 0) {
            limitations.add(run + " kept no edges, so every edge of this run is reported as added.");
        }
        if (previous.header().omittedEdges() > 0) {
            limitations.add(run + "'s summary left out its " + previous.header().omittedEdges()
                    + " least-observed edges to stay within its size, so an edge reported as added may have been"
                    + " among them.");
        }
        long previousOverflow = before.overflowed().getOrDefault(JournalAggregates.EDGES, 0L);
        if (previousOverflow > 0) {
            limitations.add(run + " observed more than " + JournalAggregates.MAX_EDGES + " edges; " + previousOverflow
                    + " observations were not counted, so an edge reported as added may have occurred in it.");
        }
        long currentOverflow = current.overflowed().getOrDefault(JournalAggregates.EDGES, 0L);
        if (currentOverflow > 0) {
            limitations.add("This run observed more than " + JournalAggregates.MAX_EDGES + " edges; " + currentOverflow
                    + " observations were not counted, so an edge reported as removed may still occur.");
        }
        long previousUnattributed = unattributed(before);
        if (previousUnattributed > 0) {
            limitations.add(run + " could not attribute " + previousUnattributed
                    + " late events to their request once its bounded record expired, so an edge reported as added"
                    + " may have occurred in it.");
        }
        long currentUnattributed = unattributed(current);
        if (currentUnattributed > 0) {
            limitations.add("This run could not attribute " + currentUnattributed
                    + " late events to their request once its bounded record expired, so an edge reported as removed"
                    + " may still occur.");
        }
        Map<EdgeDiff.EdgeRef, ObservedEdge> earlier = byEdge(before.edges());
        Map<EdgeDiff.EdgeRef, ObservedEdge> later = byEdge(current.edges());
        List<ObservedEdge> added = new ArrayList<>();
        later.forEach((edge, observed) -> {
            if (!earlier.containsKey(edge)) {
                added.add(observed);
            }
        });
        List<ObservedEdge> removed = new ArrayList<>();
        earlier.forEach((edge, observed) -> {
            if (!later.containsKey(edge)) {
                removed.add(observed);
            }
        });
        added.sort(MOST_OBSERVED);
        removed.sort(MOST_OBSERVED);
        return new RunEdgeDiff(previous.header().runId(), added, removed, limitations);
    }

    /** The late events of {@code aggregates}' run whose edges were not counted, since no owner was kept for them. */
    private static long unattributed(AggregatesSnapshot aggregates) {
        return aggregates.overflowed().getOrDefault(JournalAggregates.TRACE_AI_ATTRIBUTIONS, 0L)
                + aggregates.overflowed().getOrDefault(JournalAggregates.LATE_REQUEST_ATTRIBUTIONS, 0L);
    }

    private static Map<EdgeDiff.EdgeRef, ObservedEdge> byEdge(List<ObservedEdge> edges) {
        Map<EdgeDiff.EdgeRef, ObservedEdge> map = new LinkedHashMap<>();
        for (ObservedEdge edge : edges) {
            map.put(edge.edge(), edge);
        }
        return map;
    }
}
