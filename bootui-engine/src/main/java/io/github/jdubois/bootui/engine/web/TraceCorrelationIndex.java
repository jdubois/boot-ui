package io.github.jdubois.bootui.engine.web;

import io.github.jdubois.bootui.core.dto.HttpExchangeDto;
import io.github.jdubois.bootui.engine.support.BlankStrings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps a distributed-trace id to the anchors that carry it, so a child signal sharing that trace id can
 * be attributed to the one execution that produced it.
 *
 * <p>The uniqueness guard spans every anchor type: a trace id attaches a child only when exactly one
 * anchor of any type carries it and that anchor's trace window admits the child. A request and the
 * message or execution it triggers can share one trace, and a reused inbound {@code traceparent} can put
 * one trace on two requests; in both cases no anchor claims the child, so it can never be attributed to
 * the wrong execution.</p>
 *
 * <p>{@link LiveActivityAssembler} uses {@link #parentRequestId(String)} to nest children under their
 * REQUEST entry in the merged feed, and {@link ExecutionProfileAssembler} uses {@link #match(String, long)}
 * for the profile drill-down, so both get the identical guarantee.</p>
 */
final class TraceCorrelationIndex {

    private final Map<String, List<ProfileAnchor>> anchorsByTrace;

    private TraceCorrelationIndex(Map<String, List<ProfileAnchor>> anchorsByTrace) {
        this.anchorsByTrace = anchorsByTrace;
    }

    /** Builds the index over the given exchanges, each a REQUEST anchor with an open trace window. */
    static TraceCorrelationIndex of(List<HttpExchangeDto> exchanges) {
        List<ProfileAnchor> anchors = new ArrayList<>(exchanges.size());
        for (HttpExchangeDto exchange : exchanges) {
            anchors.add(ProfileAnchor.request(exchange, null));
        }
        return ofAnchors(anchors);
    }

    /** Builds the index over anchors of any type. */
    static TraceCorrelationIndex ofAnchors(List<ProfileAnchor> anchors) {
        Map<String, List<ProfileAnchor>> anchorsByTrace = new HashMap<>();
        for (ProfileAnchor anchor : anchors) {
            String trace = BlankStrings.blankToNull(anchor.traceId());
            if (trace != null) {
                anchorsByTrace.computeIfAbsent(trace, key -> new ArrayList<>()).add(anchor);
            }
        }
        return new TraceCorrelationIndex(anchorsByTrace);
    }

    /**
     * The id of the anchor a signal carrying {@code childTraceId} should be attributed to, or {@code null}
     * when the signal carries no trace id, no anchor carries it, or — the uniqueness guard — more than one
     * anchor carries it. For the merged feed, whose REQUEST anchors all have open trace windows.
     */
    String parentRequestId(String childTraceId) {
        List<ProfileAnchor> carriers = carriers(childTraceId);
        return carriers.size() == 1 ? carriers.get(0).id() : null;
    }

    /** Whether more than one anchor carries {@code traceId}. */
    boolean isShared(String traceId) {
        return carriers(traceId).size() > 1;
    }

    /** Resolves a child carrying {@code childTraceId} at {@code childTimestamp} against every anchor. */
    Match match(String childTraceId, long childTimestamp) {
        List<ProfileAnchor> carriers = carriers(childTraceId);
        if (carriers.isEmpty()) {
            return new Match(Status.UNCLAIMED, null, carriers);
        }
        if (carriers.size() > 1) {
            return new Match(Status.AMBIGUOUS, null, carriers);
        }
        ProfileAnchor anchor = carriers.get(0);
        return anchor.traceWindowAdmits(childTimestamp)
                ? new Match(Status.ATTACHED, anchor, carriers)
                : new Match(Status.OUTSIDE_WINDOW, null, carriers);
    }

    private List<ProfileAnchor> carriers(String traceId) {
        String trace = BlankStrings.blankToNull(traceId);
        return trace == null ? List.of() : anchorsByTrace.getOrDefault(trace, List.of());
    }

    /** How a trace id resolved. */
    enum Status {
        /** No trace id, or no anchor carries it. */
        UNCLAIMED,
        /** Exactly one anchor carries it and its trace window admits the child. */
        ATTACHED,
        /** Exactly one anchor carries it but its bounded trace window does not contain the child. */
        OUTSIDE_WINDOW,
        /** More than one anchor carries it, so no anchor may claim the child by trace id. */
        AMBIGUOUS
    }

    /**
     * The resolution of one child's trace id.
     *
     * @param status how the trace id resolved
     * @param anchor the attached anchor when {@code status} is {@link Status#ATTACHED}, otherwise {@code null}
     * @param carriers every anchor carrying the trace id
     */
    record Match(Status status, ProfileAnchor anchor, List<ProfileAnchor> carriers) {

        /** Whether {@code candidate} is among the anchors carrying the trace id. */
        boolean carriedBy(ProfileAnchor candidate) {
            return carriers.contains(candidate);
        }
    }
}
