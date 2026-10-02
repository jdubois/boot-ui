package io.github.jdubois.bootui.engine.journal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which request made an AI call ({@code docs/PLAN-v2.md} §5.3). An AI call is recorded from its exported span, which
 * carries no BootUI request id, only its trace id, so it belongs to the one request recorded with that trace id whose
 * time span contains the call's start. A call that no request's time span contains, or that the time spans of several
 * requests sharing its trace contain, belongs to none: which request made it is unknown, and it is shown on its own.
 *
 * <p>The live feed, request profiles, and Runtime Insights learn the requests they read, and Live Activity's
 * persistence learns every request as it is recorded, so all of them attribute a call the same way: by its time, never
 * by which requests happen to be retained or the order the journal recorded them in.</p>
 *
 * <p>One that reads the journal's retained events learns them all, since the journal bounds them. A {@link #bounded()}
 * one, which learns from batch to batch, remembers at most {@value #MAX_TRACES} traces, the least recently used
 * forgotten first, and at most {@value #MAX_REQUESTS_PER_TRACE} requests per trace, past which the trace's calls belong
 * to none. Not thread-safe.</p>
 */
public final class AiCallOwners {

    /** The most traces a {@link #bounded()} one remembers. */
    public static final int MAX_TRACES = 4_096;

    /** The most requests per trace a {@link #bounded()} one remembers, past which its AI calls belong to none. */
    static final int MAX_REQUESTS_PER_TRACE = 16;

    /**
     * How far an AI call may start outside its request's time span: both are wall-clock milliseconds, read from the
     * application's clock and the tracer's, which round independently.
     */
    static final long TOLERANCE_MILLIS = 2;

    private final int maxRequestsPerTrace;
    private final Map<String, List<Window>> windowsByTrace;

    /** One that learns every request it is given, for a reader of the journal's retained events. */
    public AiCallOwners() {
        this.maxRequestsPerTrace = Integer.MAX_VALUE - 1;
        this.windowsByTrace = new HashMap<>();
    }

    private AiCallOwners(int maxTraces, int maxRequestsPerTrace) {
        this.maxRequestsPerTrace = maxRequestsPerTrace;
        this.windowsByTrace = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, List<Window>> eldest) {
                return size() > maxTraces;
            }
        };
    }

    /** One that remembers at most {@value #MAX_TRACES} traces, for a listener that learns from batch to batch. */
    public static AiCallOwners bounded() {
        return new AiCallOwners(MAX_TRACES, MAX_REQUESTS_PER_TRACE);
    }

    /** Whether {@code event} is an AI call that only its trace id can link to a request. */
    public static boolean linksByTrace(RuntimeEvent event) {
        return event.source() == JournalSource.AI
                && event.requestId() == null
                && event.executionId() == null
                && event.traceId() != null;
    }

    /** Learns {@code event} when it is a request recorded with a trace id; any other event is ignored. */
    public void learn(RuntimeEvent event) {
        if (event.source() != JournalSource.HTTP || event.requestId() == null || event.traceId() == null) {
            return;
        }
        // A request records one HTTP event, so each is learned once.
        List<Window> windows = windowsByTrace.computeIfAbsent(event.traceId(), trace -> new ArrayList<>(1));
        if (windows.size() <= maxRequestsPerTrace) {
            // One past the bound marks the trace as saturated, so its calls belong to none.
            windows.add(Window.of(event));
        }
    }

    /**
     * The request {@code event} belongs to: its own request id, or, for an AI call linked only by its trace id, the
     * one learned request with that trace whose time span contains the call's start; otherwise {@code null}.
     */
    public String ownerOf(RuntimeEvent event) {
        if (event.requestId() != null) {
            return event.requestId();
        }
        if (!linksByTrace(event)) {
            return null;
        }
        List<Window> windows = windowsByTrace.get(event.traceId());
        if (windows == null || windows.size() > maxRequestsPerTrace) {
            return null;
        }
        String owner = null;
        for (Window window : windows) {
            if (window.contains(event.epochMillis())) {
                if (owner != null) {
                    return null;
                }
                owner = window.requestId();
            }
        }
        return owner;
    }

    /**
     * Whether a request learned later could still claim {@code event}: it is an AI call linked by trace id that no
     * learned request contains, and no learned request with its trace makes the call's attribution ambiguous already.
     */
    public boolean unresolved(RuntimeEvent event) {
        if (!linksByTrace(event)) {
            return false;
        }
        List<Window> windows = windowsByTrace.get(event.traceId());
        if (windows == null) {
            return true;
        }
        if (windows.size() > maxRequestsPerTrace) {
            return false;
        }
        for (Window window : windows) {
            if (window.contains(event.epochMillis())) {
                return false;
            }
        }
        return true;
    }

    /** Forgets every request, when the recording is cleared. */
    public void clear() {
        windowsByTrace.clear();
    }

    /** The traces remembered now, for tests. */
    int traces() {
        return windowsByTrace.size();
    }

    /** One request's time span, in wall-clock milliseconds; {@code endMillis} is open when its duration is unknown. */
    private record Window(String requestId, long startMillis, long endMillis) {

        static Window of(RuntimeEvent http) {
            long start = http.epochMillis();
            long nanos = http.durationNanos();
            long end = nanos < 0 ? Long.MAX_VALUE : start + (nanos + 999_999) / 1_000_000;
            return new Window(http.requestId(), start, end);
        }

        boolean contains(long epochMillis) {
            return epochMillis >= startMillis - TOLERANCE_MILLIS
                    && (endMillis == Long.MAX_VALUE || epochMillis <= endMillis + TOLERANCE_MILLIS);
        }
    }
}
