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
 * anchor of any type carries it and has a trace window that contains the child. A request and the message
 * or execution it triggers can share one trace, and a reused inbound {@code traceparent} can put one trace
 * on two requests; whenever two such anchors could both hold the child, neither claims it, so it can never
 * be attributed to the wrong execution. An HTTP request's trace window is open, so two requests sharing a
 * trace never attach anything by it.</p>
 *
 * <p>{@link LiveActivityAssembler} uses {@link #parentRequestId(String)} to nest children under their
 * REQUEST entry in the merged feed, and {@link ExecutionProfileAssembler} uses {@link #match(String, long)}
 * for the profile drill-down. While every anchor is an open-window REQUEST, both agree; once bounded anchor
 * types exist, the feed must resolve children through {@link #match(String, long)} too.</p>
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
            anchors.add(ProfileAnchor.request(exchange, null, false));
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
            return new Match(Status.UNCLAIMED, null, carriers, carriers);
        }
        List<ProfileAnchor> claimants = new ArrayList<>(carriers.size());
        for (ProfileAnchor carrier : carriers) {
            if (carrier.traceWindowAdmits(childTimestamp)) {
                claimants.add(carrier);
            }
        }
        if (claimants.isEmpty()) {
            return new Match(Status.OUTSIDE_WINDOW, null, carriers, claimants);
        }
        return claimants.size() == 1
                ? new Match(Status.ATTACHED, claimants.get(0), carriers, claimants)
                : new Match(Status.AMBIGUOUS, null, carriers, claimants);
    }

    private List<ProfileAnchor> carriers(String traceId) {
        String trace = BlankStrings.blankToNull(traceId);
        return trace == null ? List.of() : anchorsByTrace.getOrDefault(trace, List.of());
    }

    /** How a trace id resolved. */
    enum Status {
        /** No trace id, or no anchor carries it. */
        UNCLAIMED,
        /** Exactly one anchor carrying it has a trace window that contains the child. */
        ATTACHED,
        /** Anchors carry it, but none has a trace window that contains the child. */
        OUTSIDE_WINDOW,
        /** More than one anchor carrying it could contain the child, so none may claim it by trace id. */
        AMBIGUOUS
    }

    /**
     * The resolution of one child's trace id.
     *
     * @param status how the trace id resolved
     * @param anchor the attached anchor when {@code status} is {@link Status#ATTACHED}, otherwise {@code null}
     * @param carriers every anchor carrying the trace id
     * @param claimants the carriers whose trace window contains the child
     */
    record Match(Status status, ProfileAnchor anchor, List<ProfileAnchor> carriers, List<ProfileAnchor> claimants) {

        /** Whether {@code candidate} carries the trace id, whatever its window. */
        boolean carriedBy(ProfileAnchor candidate) {
            return carriers.contains(candidate);
        }

        /** Whether {@code candidate} carries the trace id and its trace window contains the child. */
        boolean claimedBy(ProfileAnchor candidate) {
            return claimants.contains(candidate);
        }
    }
}
