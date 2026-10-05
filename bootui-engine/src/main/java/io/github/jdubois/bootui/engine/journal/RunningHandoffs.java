package io.github.jdubois.bootui.engine.journal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The tasks the BootUI agent propagated that are still running ({@code docs/PLAN-v2.md} M5-2), so Live Activity can
 * show work still running after its request answered, such as a hung task. Each handoff is keyed by an id of its own,
 * since the handoffs of one scheduled run or consumed message share its execution id. Bounded: beyond
 * {@link #MAX_RUNNING}, the oldest are forgotten (approximately, under concurrent starts); a forgotten task is still
 * published when it ends. Lock-free, and holds strings only.
 */
public final class RunningHandoffs {

    /** The most running handoffs remembered. */
    public static final int MAX_RUNNING = 256;

    /** The key {@link #started} returns when it remembered nothing. */
    public static final long NONE = 0L;

    private static final RunningHandoffs SHARED = new RunningHandoffs(MAX_RUNNING);

    private final int max;
    private final AtomicLong keys = new AtomicLong();
    private final Map<Long, Running> running = new ConcurrentHashMap<>();
    /** When the registry last forgot a running handoff to stay bounded, by the wall clock; 0 while it forgot none. */
    private volatile long forgottenAt;

    public RunningHandoffs(int max) {
        this.max = Math.max(1, max);
    }

    /** The registry the adapters' handoffs report to and Live Activity reads. */
    public static RunningHandoffs shared() {
        return SHARED;
    }

    /** Remembers a handoff that started, and returns the key that {@link #ended} forgets it by, or {@link #NONE}. */
    public long started(Running handoff) {
        if (handoff == null || handoff.executionId() == null) {
            return NONE;
        }
        long key = keys.incrementAndGet();
        running.put(key, handoff.withId(key));
        if (running.size() > max) {
            forgetOldest();
        }
        return key;
    }

    /** Forgets a handoff that ended, by the key {@link #started} returned. */
    public void ended(long key) {
        if (key != NONE) {
            running.remove(key);
        }
    }

    /** The handoffs running now, oldest first. */
    public List<Running> snapshot() {
        List<Running> snapshot = new ArrayList<>(running.values());
        snapshot.sort(Comparator.comparingLong(Running::id));
        return snapshot;
    }

    /** How many handoffs run now. */
    public int size() {
        return running.size();
    }

    /** Whether a handoff of request {@code requestId} is running now, as far as the registry remembers. */
    public boolean runningFor(String requestId) {
        if (requestId == null) {
            return false;
        }
        for (Running handoff : running.values()) {
            if (requestId.equals(handoff.requestId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * When the registry last forgot a running handoff to stay within {@link #MAX_RUNNING}, by the wall clock, or
     * {@code null} while it forgot none: a reader that judges a request's work over cannot tell after then.
     */
    public Long forgottenMillis() {
        long at = forgottenAt;
        return at == 0L ? null : at;
    }

    private void forgetOldest() {
        while (running.size() > max) {
            long oldest = Long.MAX_VALUE;
            for (Long key : running.keySet()) {
                oldest = Math.min(oldest, key);
            }
            if (oldest == Long.MAX_VALUE) {
                return;
            }
            if (running.remove(oldest) != null) {
                forgottenAt = System.currentTimeMillis();
            }
        }
    }

    /**
     * One handoff still running.
     *
     * @param requestId the request it works for, or {@code null} for a scheduled run's or consumed message's
     * @param executionId its child execution, which a scheduled run's or consumed message's handoffs share
     * @param parentExecutionId the execution that submitted it, or {@code null}
     * @param traceId its trace id, or {@code null}
     * @param thread the thread it runs on
     * @param startEpochMillis when it started
     * @param taskClass the class of the task the executor runs
     * @param hook the agent hook that reopened its context
     * @param id its key in the registry, unique among running handoffs; {@link #NONE} until remembered
     */
    public record Running(
            String requestId,
            String executionId,
            String parentExecutionId,
            String traceId,
            String thread,
            long startEpochMillis,
            String taskClass,
            String hook,
            long id) {

        /** A handoff the registry has not keyed yet. */
        public Running(
                String requestId,
                String executionId,
                String parentExecutionId,
                String traceId,
                String thread,
                long startEpochMillis,
                String taskClass,
                String hook) {
            this(requestId, executionId, parentExecutionId, traceId, thread, startEpochMillis, taskClass, hook, NONE);
        }

        Running withId(long key) {
            return new Running(
                    requestId, executionId, parentExecutionId, traceId, thread, startEpochMillis, taskClass, hook, key);
        }
    }
}
