package io.github.jdubois.bootui.engine.journal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which request made an AI call ({@code docs/PLAN-v2.md} §5.3) whose span started outside any request BootUI knew of,
 * so that it carries no BootUI request id, only its trace id. A call the telemetry store saw start under a request
 * carries that request's id and nests by it like any other child; this is the fallback for the rest. Such a call
 * belongs to the one request recorded with its trace id whose time span contains the call's start. A call that no
 * request's time span contains, or that the time spans of several requests sharing its trace contain, belongs to none:
 * which request made it is unknown, and it is shown on its own.
 *
 * <p>The live feed, request profiles, and Runtime Insights learn the requests they read, and Live Activity's
 * persistence learns every request as it is recorded, so all of them attribute a call the same way: by its time, never
 * by which requests happen to be retained or the order the journal recorded them in. A request's time span is read
 * from the application's clock and the call's start from the tracer's, so a call within {@value #TOLERANCE_MILLIS} ms
 * of a span counts as inside it; a call farther outside belongs to none.</p>
 *
 * <p>One that reads the journal's retained events learns them all, since the journal bounds them. A {@link #bounded()}
 * one, which learns from batch to batch, remembers at most {@value #MAX_TRACES} traces and at most
 * {@value #MAX_REQUESTS_PER_TRACE} requests per trace. Past either bound it fails closed: the calls of a crowded trace,
 * or of a trace it had to forget, belong to none rather than to whichever request it still remembers. Not
 * thread-safe.</p>
 */
public final class AiCallOwners {

    /** The most traces a {@link #bounded()} one remembers, and the most it remembers having forgotten. */
    public static final int MAX_TRACES = 4_096;

    /** The most requests per trace a {@link #bounded()} one remembers, past which its AI calls belong to none. */
    static final int MAX_REQUESTS_PER_TRACE = 16;

    /**
     * How far an AI call may start outside its request's time span: both are wall-clock milliseconds, read from the
     * application's clock and the tracer's, which round independently.
     */
    static final long TOLERANCE_MILLIS = 2;

    private final int maxRequestsPerTrace;
    private final Map<String, Trace> traces;

    /** The traces a bounded one forgot, which it never attributes again: a forgotten request may own their calls. */
    private final Set<String> forgotten;

    /** One that learns every request it is given, for a reader of the journal's retained events. */
    public AiCallOwners() {
        this.maxRequestsPerTrace = Integer.MAX_VALUE;
        this.traces = new HashMap<>();
        this.forgotten = new LinkedHashSet<>();
    }

    private AiCallOwners(int maxTraces, int maxRequestsPerTrace) {
        this.maxRequestsPerTrace = maxRequestsPerTrace;
        this.forgotten = new LinkedHashSet<>();
        this.traces = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Trace> eldest) {
                if (size() <= maxTraces) {
                    return false;
                }
                forget(eldest.getKey(), maxTraces);
                return true;
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
        Trace trace = traces.get(event.traceId());
        if (trace == null) {
            // A trace learned again after it was forgotten may have lost the request that owns its calls.
            trace = new Trace(forgotten.remove(event.traceId()));
            traces.put(event.traceId(), trace);
        }
        // A request records one HTTP event, so each is learned once.
        if (trace.windows.size() < maxRequestsPerTrace) {
            trace.windows.add(Window.of(event));
        } else {
            trace.saturated = true;
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
        Trace trace = traces.get(event.traceId());
        if (trace == null || trace.saturated) {
            return null;
        }
        String owner = null;
        for (Window window : trace.windows) {
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
     * learned request contains, and its trace is neither ambiguous already, crowded, nor forgotten.
     */
    public boolean unresolved(RuntimeEvent event) {
        if (!linksByTrace(event) || forgotten.contains(event.traceId())) {
            return false;
        }
        Trace trace = traces.get(event.traceId());
        if (trace == null) {
            return true;
        }
        if (trace.saturated) {
            return false;
        }
        for (Window window : trace.windows) {
            if (window.contains(event.epochMillis())) {
                return false;
            }
        }
        return true;
    }

    /** Forgets every request, and every forgotten trace, when the recording is cleared. */
    public void clear() {
        traces.clear();
        forgotten.clear();
    }

    /** The traces remembered now, for tests. */
    int traces() {
        return traces.size();
    }

    private void forget(String traceId, int maxForgotten) {
        forgotten.add(traceId);
        if (forgotten.size() > maxForgotten) {
            forgotten.remove(forgotten.iterator().next());
        }
    }

    /** One trace's requests, and whether it had more than it keeps, so that its calls belong to none. */
    private static final class Trace {

        private final List<Window> windows = new ArrayList<>(1);
        private boolean saturated;

        Trace(boolean saturated) {
            this.saturated = saturated;
        }
    }

    /**
     * One request's time span, in wall-clock milliseconds. A request of unknown duration spans only its start, so it
     * never claims a call that started later.
     */
    private record Window(String requestId, long startMillis, long endMillis) {

        static Window of(RuntimeEvent http) {
            long start = http.epochMillis();
            long nanos = http.durationNanos();
            return new Window(http.requestId(), start, nanos <= 0 ? start : start + (nanos + 999_999) / 1_000_000);
        }

        boolean contains(long epochMillis) {
            return epochMillis >= startMillis - TOLERANCE_MILLIS && epochMillis <= endMillis + TOLERANCE_MILLIS;
        }
    }
}
