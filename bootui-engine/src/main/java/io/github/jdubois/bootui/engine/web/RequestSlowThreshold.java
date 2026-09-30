package io.github.jdubois.bootui.engine.web;

/**
 * The one request slow threshold, {@code bootui.activity.request-slow-threshold-ms}, shared by every adapter.
 *
 * <p>It classifies an HTTP exchange as slow for failure-preserving retention and sets the {@code SLOW} severity of
 * Live Activity {@code REQUEST} entries, so Spring MVC, Spring WebFlux, and Quarkus agree on which requests are slow.
 * A threshold of {@code 0} (or less) disables slow classification, leaving only failures reserved.</p>
 */
public final class RequestSlowThreshold {

    /** The documented default of {@code bootui.activity.request-slow-threshold-ms}. */
    public static final long DEFAULT_MILLIS = 1_000L;

    private RequestSlowThreshold() {}

    /** Whether {@code durationMs} is at or above a positive {@code thresholdMs}. */
    public static boolean isSlow(Long durationMs, long thresholdMs) {
        return thresholdMs > 0 && durationMs != null && durationMs >= thresholdMs;
    }

    /** Whether an exchange belongs in the reserved share: a {@code 5xx} response or a slow request. */
    public static boolean isFailedOrSlow(int status, Long durationMs, long thresholdMs) {
        return status >= 500 || isSlow(durationMs, thresholdMs);
    }
}
