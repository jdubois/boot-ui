package io.github.jdubois.bootui.engine.scheduled;

/**
 * The pure "seconds vs milliseconds" display-unit rule for scheduled-task intervals and initial delays,
 * shared by the Spring Boot and Quarkus adapters so the unit choice cannot drift between them.
 *
 * <p>A value is shown in whole seconds only when both the interval and the initial delay are absent or a
 * whole multiple of 1000 ms; otherwise milliseconds are used. This matches the Vue {@code Scheduled.vue}
 * {@code formatExpression}, which divides the numeric expression by 1000 when {@code timeUnit === 's'}.</p>
 */
public final class ScheduledTimeUnits {

    private ScheduledTimeUnits() {}

    /** {@code "s"} when both values are whole seconds (or null), otherwise {@code "ms"}. */
    public static String intervalUnit(Long intervalMs, Long initialDelayMs) {
        if (isWholeSeconds(intervalMs) && isWholeSeconds(initialDelayMs)) {
            return "s";
        }
        return "ms";
    }

    private static boolean isWholeSeconds(Long value) {
        return value == null || value % 1000 == 0;
    }
}
