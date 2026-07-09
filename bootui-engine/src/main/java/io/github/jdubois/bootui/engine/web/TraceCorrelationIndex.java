package io.github.jdubois.bootui.engine.web;

import io.github.jdubois.bootui.core.dto.HttpExchangeDto;
import io.github.jdubois.bootui.engine.support.BlankStrings;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maps a distributed-trace id to the single captured HTTP request (exchange id) that carries it, so a
 * SQL/exception/security signal sharing that trace id can be attributed to the request that produced it.
 *
 * <p>A trace id shared by more than one captured exchange (an ambiguous, likely reused, inbound
 * {@code traceparent}) is deliberately excluded — {@link #parentRequestId(String)} returns {@code null} for
 * it — so a signal can never be attributed to the wrong request, or to a request it may not actually belong
 * to at all.</p>
 *
 * <p>Extracted from {@link LiveActivityAssembler}, which uses it to nest SQL/exception/security entries
 * under their owning REQUEST entry in the merged Live Activity feed; {@link RequestProfileAssembler} reuses
 * the exact same primitive to correlate signals for the reduced, trace-id-only per-request profile
 * drill-down. Both call sites get the identical uniqueness guarantee for free, proven once by
 * {@code LiveActivityAssemblerTests}.</p>
 */
final class TraceCorrelationIndex {

    private final Map<String, String> requestIdByTrace;
    private final Set<String> ambiguousTraces;

    private TraceCorrelationIndex(Map<String, String> requestIdByTrace, Set<String> ambiguousTraces) {
        this.requestIdByTrace = requestIdByTrace;
        this.ambiguousTraces = ambiguousTraces;
    }

    /** Builds the index over the given exchanges' {@code (traceId, id)} pairs. */
    static TraceCorrelationIndex of(List<HttpExchangeDto> exchanges) {
        Map<String, String> requestIdByTrace = new HashMap<>();
        Set<String> ambiguousTraces = new HashSet<>();
        for (HttpExchangeDto e : exchanges) {
            String trace = BlankStrings.blankToNull(e.traceId());
            if (trace != null && requestIdByTrace.putIfAbsent(trace, e.id()) != null) {
                ambiguousTraces.add(trace);
            }
        }
        return new TraceCorrelationIndex(requestIdByTrace, ambiguousTraces);
    }

    /**
     * The id of the REQUEST entry a signal carrying {@code childTraceId} should be attributed to, or
     * {@code null} when the signal carries no trace id, no request shares it, or — the uniqueness guard —
     * more than one request carries it (an ambiguous, likely reused, inbound {@code traceparent}).
     */
    String parentRequestId(String childTraceId) {
        String trace = BlankStrings.blankToNull(childTraceId);
        if (trace == null || ambiguousTraces.contains(trace)) {
            return null;
        }
        return requestIdByTrace.get(trace);
    }
}
