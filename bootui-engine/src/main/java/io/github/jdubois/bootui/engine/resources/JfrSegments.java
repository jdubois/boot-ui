package io.github.jdubois.bootui.engine.resources;

/**
 * The gate between {@link SegmentMeter} and JFR ({@code docs/PLAN-v2.md} §5.11): while no <b>Profile resources</b>
 * session runs, opening a segment costs one volatile read, and no {@code jdk.jfr} class is loaded, so a runtime without
 * JFR is unaffected.
 */
final class JfrSegments {

    private static volatile boolean active;

    private JfrSegments() {}

    /** Starts or stops creating segment events, which only {@link JfrProfiler} does. */
    static void activate(boolean on) {
        active = on;
    }

    /** Whether segment events are created now. */
    static boolean active() {
        return active;
    }

    /** Begins a segment event for {@code requestId} on the calling thread, or returns {@code null} when inactive. */
    static Object begin(String requestId) {
        if (!active) {
            return null;
        }
        try {
            return Events.begin(requestId);
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    /** Commits a segment event {@link #begin} returned, on the thread that began it; ignores {@code null}. */
    static void end(Object event) {
        if (event == null) {
            return;
        }
        try {
            Events.end(event);
        } catch (RuntimeException | LinkageError ex) {
            // Profiling never disturbs the application's work.
        }
    }

    /** The only class here that names the event, loaded once a session starts. */
    private static final class Events {

        static Object begin(String requestId) {
            ExecutionSegmentEvent event = new ExecutionSegmentEvent();
            event.requestId = requestId;
            event.begin();
            return event;
        }

        static void end(Object event) {
            ((ExecutionSegmentEvent) event).commit();
        }
    }
}
