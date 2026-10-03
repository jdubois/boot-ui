package io.github.jdubois.bootui.engine.correlation;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * The one window rule for the work of a task the BootUI agent propagated ({@code docs/PLAN-v2.md} M5-2,
 * {@code bootui.agent.executors.max-handoff}), shared by the request profiles, the journal profile, the
 * {@code work-after-response} observation, and the handoff itself:
 *
 * <ul>
 *   <li>a handoff belongs to its request when it <em>started</em> no later than {@code max-handoff} after the
 *       request ended; a later one is only counted;</li>
 *   <li>work a handoff recorded more than {@code max-handoff} after the handoff started is not attributed to the
 *       request;</li>
 *   <li>a handoff is <em>capped</em> when it closed more than {@code max-handoff} after it started.</li>
 * </ul>
 */
public final class HandoffWindow {

    /** The default {@code bootui.agent.executors.max-handoff}. */
    public static final Duration DEFAULT_MAX_HANDOFF = Duration.ofMinutes(5);

    /** The default {@code bootui.agent.executors.max-handoff}, in milliseconds. */
    public static final long DEFAULT_MAX_HANDOFF_MILLIS = DEFAULT_MAX_HANDOFF.toMillis();

    private HandoffWindow() {}

    /** {@code maxHandoff} in milliseconds, or the default when it is {@code null}, zero, or negative. */
    public static long millis(Duration maxHandoff) {
        return maxHandoff == null || maxHandoff.isNegative() || maxHandoff.isZero()
                ? DEFAULT_MAX_HANDOFF_MILLIS
                : maxHandoff.toMillis();
    }

    /** {@code maxHandoffMillis}, or the default when it is not positive. */
    public static long millis(long maxHandoffMillis) {
        return maxHandoffMillis <= 0 ? DEFAULT_MAX_HANDOFF_MILLIS : maxHandoffMillis;
    }

    /** {@code fromMillis + maxHandoffMillis}, saturated. */
    public static long deadline(long fromMillis, long maxHandoffMillis) {
        long sum = fromMillis + maxHandoffMillis;
        return ((fromMillis ^ sum) & (maxHandoffMillis ^ sum)) < 0 ? Long.MAX_VALUE : sum;
    }

    /** Whether a handoff that started at {@code handoffStartMillis} belongs to a request that ended at the given time. */
    public static boolean attaches(long requestEndMillis, long handoffStartMillis, long maxHandoffMillis) {
        return handoffStartMillis <= deadline(requestEndMillis, maxHandoffMillis);
    }

    /** Whether work recorded at {@code recordedMillis} by a handoff that started at the given time is attributed. */
    public static boolean attributed(long handoffStartMillis, long recordedMillis, long maxHandoffMillis) {
        return recordedMillis <= deadline(handoffStartMillis, maxHandoffMillis);
    }

    /** Whether a handoff that ran for {@code durationNanos} closed past its deadline. */
    public static boolean capped(long durationNanos, long maxHandoffNanos) {
        return durationNanos > maxHandoffNanos;
    }

    /**
     * When each propagated execution started, learned from the evidence at hand: its handoff's start when the handoff
     * is known, otherwise the earliest work it recorded, which a still-running handoff or evidence without handoffs
     * leaves as the best bound. Not thread-safe; build one per read.
     */
    public static final class Starts {

        private final Map<String, Long> handoffs = new HashMap<>();
        private final Map<String, Long> earliest = new HashMap<>();

        /** Learns a handoff's start. */
        public void handoff(String executionId, long startMillis) {
            if (ExecutionIds.isAsync(executionId)) {
                handoffs.merge(executionId, startMillis, Math::min);
            }
        }

        /** Learns work an execution recorded. */
        public void recorded(String executionId, long recordedMillis) {
            if (ExecutionIds.isAsync(executionId)) {
                earliest.merge(executionId, recordedMillis, Math::min);
            }
        }

        /** The execution's start, or {@code null} when nothing about it was learned. */
        public Long startOf(String executionId) {
            Long start = handoffs.get(executionId);
            return start != null ? start : earliest.get(executionId);
        }

        /**
         * Whether work {@code executionId} recorded at {@code recordedMillis} is attributed to the request that ended
         * at {@code requestEndMillis}: its handoff attaches to the request, and the work is within the handoff's
         * window. Work of an execution nothing else is known about stands for its own start.
         */
        public boolean attributed(
                String executionId, long recordedMillis, long requestEndMillis, long maxHandoffMillis) {
            Long known = startOf(executionId);
            long start = known == null ? recordedMillis : known;
            return attaches(requestEndMillis, start, maxHandoffMillis)
                    && HandoffWindow.attributed(start, recordedMillis, maxHandoffMillis);
        }
    }
}
