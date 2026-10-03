package io.github.jdubois.bootui.engine.correlation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The phase markers of recent requests, keyed by BootUI request id ({@code docs/PLAN-v2.md} §5.1). Adapters mark
 * where each request's processing is, from hooks that know it: the handler starts, the handler returns, the response
 * starts being written. Recorders read the current phase of the request that owns the work, from whichever thread the
 * work runs on, which an immutable correlation context could not carry.
 *
 * <p>Bounded: the oldest requests are forgotten first, and work recorded for a forgotten request carries no phase. All
 * methods are thread-safe and ignore a {@code null} request id.</p>
 */
public final class RequestPhases {

    /** Default number of requests remembered. */
    public static final int DEFAULT_MAX_REQUESTS = 4_096;

    private final int maxRequests;
    private final Map<String, Timeline> timelines = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Timeline> eldest) {
            return size() > maxRequests;
        }
    };

    public RequestPhases() {
        this(DEFAULT_MAX_REQUESTS);
    }

    public RequestPhases(int maxRequests) {
        this.maxRequests = Math.max(1, maxRequests);
    }

    /** Starts tracking a request in its {@link RequestPhase#FILTERS} phase. */
    public void begin(String requestId) {
        begin(requestId, RequestPhase.FILTERS, true);
    }

    /**
     * Starts tracking a request whose adapter marks no phase, as Spring WebFlux does: its operation, authentication
     * time, start, and end are recorded, while {@link #phaseOf(String)} stays unknown rather than reporting a phase
     * the adapter never observed. A request already tracked keeps the timeline it has, so a reactive chain
     * subscribed again, by a retry around it, keeps what was recorded on its earlier attempt rather than starting over.
     */
    public void beginUnphased(String requestId) {
        begin(requestId, null, false);
    }

    private void begin(String requestId, RequestPhase current, boolean replace) {
        if (requestId != null) {
            synchronized (timelines) {
                if (!replace && timelines.containsKey(requestId)) {
                    return;
                }
                timelines.put(requestId, new Timeline(epochMicros(), current));
            }
        }
    }

    /** Moves a tracked request to {@code phase}, recording the first time it entered it. */
    public void mark(String requestId, RequestPhase phase) {
        if (requestId == null || phase == null) {
            return;
        }
        synchronized (timelines) {
            Timeline timeline = timelines.get(requestId);
            if (timeline != null) {
                timeline.enter(phase, epochMicros());
            }
        }
    }

    /**
     * Records that a tracked request ended, its response complete, so work it handed over can tell whether it was still
     * running then even when the adapter marked no {@link RequestPhase#RESPONSE} phase, as for a failed handler.
     */
    public void end(String requestId) {
        if (requestId == null) {
            return;
        }
        synchronized (timelines) {
            Timeline timeline = timelines.get(requestId);
            if (timeline != null && timeline.endedAt == null) {
                timeline.endedAt = epochMicros();
            }
        }
    }

    /**
     * Adds an authentication interval to a tracked request, such as one Spring Security observed while authenticating
     * it. A request authenticated more than once accumulates its intervals.
     */
    public void addAuthentication(String requestId, long micros) {
        if (requestId == null || micros < 0) {
            return;
        }
        synchronized (timelines) {
            Timeline timeline = timelines.get(requestId);
            if (timeline != null) {
                timeline.authenticationMicros += micros;
            }
        }
    }

    /**
     * Names the operation a tracked request carried, such as {@code query ProductList} for a GraphQL request, so each
     * operation is a route of its own.
     */
    public void setOperation(String requestId, String operation) {
        if (requestId == null || operation == null || operation.isBlank()) {
            return;
        }
        synchronized (timelines) {
            Timeline timeline = timelines.get(requestId);
            if (timeline != null) {
                timeline.operation = operation;
            }
        }
    }

    /** The operation a tracked request carried, or {@code null}. */
    public String operationOf(String requestId) {
        if (requestId == null) {
            return null;
        }
        synchronized (timelines) {
            Timeline timeline = timelines.get(requestId);
            return timeline == null ? null : timeline.operation;
        }
    }

    /** The phase the request is in, or {@code null} when it is not tracked or its adapter marks no phase. */
    public RequestPhase phaseOf(String requestId) {
        if (requestId == null) {
            return null;
        }
        synchronized (timelines) {
            Timeline timeline = timelines.get(requestId);
            return timeline == null ? null : timeline.current;
        }
    }

    /** When the request first entered each phase, or {@code null} when it is not tracked. */
    public Markers markers(String requestId) {
        if (requestId == null) {
            return null;
        }
        synchronized (timelines) {
            Timeline timeline = timelines.get(requestId);
            return timeline == null
                    ? null
                    : new Markers(
                            timeline.filtersAt,
                            timeline.handlerAt,
                            timeline.responseAt,
                            timeline.current,
                            timeline.authenticationMicros,
                            timeline.endedAt);
        }
    }

    private static long epochMicros() {
        java.time.Instant now = java.time.Instant.now();
        return now.getEpochSecond() * 1_000_000L + now.getNano() / 1_000L;
    }

    /**
     * When a request first entered each phase, in epoch microseconds, {@code null} for a phase it has not entered.
     *
     * @param current the phase it is in now, {@code null} when its adapter marks no phase
     * @param authenticationMicros time spent authenticating it, summed over its authentication intervals
     * @param endedAt when it ended, or {@code null} while it runs or when its adapter does not say
     */
    public record Markers(
            Long filtersAt,
            Long handlerAt,
            Long responseAt,
            RequestPhase current,
            long authenticationMicros,
            Long endedAt) {

        /** Markers of a request whose end is not known. */
        public Markers(
                Long filtersAt, Long handlerAt, Long responseAt, RequestPhase current, long authenticationMicros) {
            this(filtersAt, handlerAt, responseAt, current, authenticationMicros, null);
        }
    }

    private static final class Timeline {

        private RequestPhase current;
        private final Long filtersAt;
        private Long handlerAt;
        private Long responseAt;
        private long authenticationMicros;
        private String operation;
        private Long endedAt;

        private Timeline(long startedAt, RequestPhase current) {
            this.filtersAt = startedAt;
            this.current = current;
        }

        private void enter(RequestPhase phase, long at) {
            current = phase;
            if (phase == RequestPhase.HANDLER && handlerAt == null) {
                handlerAt = at;
            } else if (phase == RequestPhase.RESPONSE && responseAt == null) {
                responseAt = at;
            }
        }
    }
}
