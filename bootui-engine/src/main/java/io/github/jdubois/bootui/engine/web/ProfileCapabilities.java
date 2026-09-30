package io.github.jdubois.bootui.engine.web;

/**
 * The correlation tiers an adapter can prove, and the adapter hooks behind them.
 *
 * <p>{@link CorrelationTier#TRACE_ID} is always available. The serving-thread and time-window tiers rely
 * on a thread serving one request start to finish, which Spring MVC's servlet model guarantees and which
 * the Spring WebFlux and Quarkus event-loop models do not, so those adapters report both tiers
 * unavailable rather than inferring them.</p>
 *
 * @param servingThreads resolves a request's serving thread, or {@code null} when the adapter has no
 *     thread-per-request model
 * @param securityThreads classifies a security audit event against a serving thread, or {@code null}
 * @param timeWindow whether the adapter can use a request's time window as a last-resort tier
 * @param limitedReason why the tiers this adapter lacks are unavailable, or {@code null}
 */
public record ProfileCapabilities(
        ServingThreadResolver servingThreads,
        SecurityThreadClassifier securityThreads,
        boolean timeWindow,
        String limitedReason) {

    /** Why an event-loop adapter reports the serving-thread and time-window tiers unavailable. */
    public static final String EVENT_LOOP_REASON =
            "This adapter serves requests on shared event-loop and worker threads, so neither a thread nor a"
                    + " time window identifies a single request.";

    /** An adapter that correlates by trace id only (Spring WebFlux, Quarkus). */
    public static ProfileCapabilities traceIdOnly() {
        return new ProfileCapabilities(null, null, false, EVENT_LOOP_REASON);
    }

    /** A thread-per-request adapter (Spring MVC) that provides every tier. */
    public static ProfileCapabilities threadPerRequest(
            ServingThreadResolver servingThreads, SecurityThreadClassifier securityThreads) {
        return new ProfileCapabilities(servingThreads, securityThreads, true, null);
    }

    /** Whether the adapter provides {@code tier}. */
    public boolean provides(CorrelationTier tier) {
        return switch (tier) {
            case REQUEST_ID, TRACE_ID -> true;
            case SERVING_THREAD -> servingThreads != null;
            case TIME_WINDOW -> timeWindow;
        };
    }

    /** Whether the adapter provides only the trace-id tier. */
    boolean traceIdOnlyAdapter() {
        return !provides(CorrelationTier.SERVING_THREAD) && !provides(CorrelationTier.TIME_WINDOW);
    }

    /**
     * The one thread that served a request, and the window it served it in.
     *
     * @param thread the serving thread's name
     * @param startMillis when the thread started serving the request, in epoch milliseconds
     * @param endMillis when it finished, in epoch milliseconds
     */
    public record ServingThread(String thread, long startMillis, long endMillis) {}

    /** Resolves the unique serving thread of a request, returning {@code null} unless exactly one matches. */
    @FunctionalInterface
    public interface ServingThreadResolver {
        ServingThread resolve(String method, String path, long startMillis, long endMillis);
    }

    /** Whether a security audit event provably fired on a serving thread, on another thread, or neither. */
    public enum ThreadMatch {
        /** A capture on the serving thread matches the event. */
        OURS,
        /** Captures match the event, but only on other threads. */
        FOREIGN,
        /** No capture matches the event. */
        UNKNOWN
    }

    /** Classifies a displayed security audit event against a serving thread. */
    @FunctionalInterface
    public interface SecurityThreadClassifier {
        ThreadMatch classify(String servingThread, String type, long timestamp);
    }
}
