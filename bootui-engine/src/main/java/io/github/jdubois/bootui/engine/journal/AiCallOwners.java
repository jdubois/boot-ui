package io.github.jdubois.bootui.engine.journal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which request made an AI call ({@code docs/PLAN-v2.md} §5.3) whose span started outside any request BootUI knew of,
 * so that it carries no BootUI request id, only its trace id. A call the telemetry store saw start under a request
 * carries that request's id and nests by it like any other child; this is the fallback for the rest. Such a call
 * belongs to the one request recorded with its trace id whose time span contains the call's start. A call that no
 * request's time span contains, or that the time spans of several requests sharing its trace contain, belongs to none:
 * which request made it is unknown, and it is shown on its own.
 *
 * <p>The live feed, request profiles, and Runtime Insights learn every request the journal retains and attribute a
 * call the same way: by its time, never by which requests happen to be retained or the order the journal recorded
 * them in. A request's time span is read from the application's clock and the call's start from the tracer's, so a
 * call within {@value #TOLERANCE_MILLIS} ms of a span counts as inside it; a call farther outside belongs to none.
 * Live Activity's history learns no request, since a request recorded later could change the inference and a written
 * row is never revised. Not thread-safe.</p>
 */
public final class AiCallOwners {

    /**
     * How far an AI call may start outside its request's time span: both are wall-clock milliseconds, read from the
     * application's clock and the tracer's, which round independently.
     */
    static final long TOLERANCE_MILLIS = 2;

    private final Map<String, List<Window>> windowsByTrace = new HashMap<>();

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
        windowsByTrace
                .computeIfAbsent(event.traceId(), trace -> new ArrayList<>(1))
                .add(Window.of(event));
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
        if (windows == null) {
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
