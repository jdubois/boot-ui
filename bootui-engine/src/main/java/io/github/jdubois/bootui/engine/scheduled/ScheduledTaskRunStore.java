package io.github.jdubois.bootui.engine.scheduled;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Framework-neutral, in-memory, bounded ring buffer of {@code @Scheduled} task <em>executions</em>
 * (start/success/failure/duration), feeding the {@code SCHEDULED} entries in the Live Activity
 * merged stream (see {@code docs/PLAN.md} §3.4). This is a companion to the existing, purely-static
 * {@link ScheduledTasksService} (which only lists task <em>definitions</em>): this store instead
 * captures what actually ran.
 *
 * <p>Each adapter feeds this store from its own scheduling infrastructure hook — the Spring adapter
 * taps Spring Framework's built-in {@code ScheduledTaskObservationContext} (an
 * {@link io.micrometer.observation.ObservationHandler}, no AOP proxying needed) — so this class itself
 * carries no framework dependency and no scheduling-library import, exactly like {@link
 * io.github.jdubois.bootui.engine.exceptions.ExceptionStore}.
 *
 * <p>All retained data lives only in memory, is bounded to {@code maxEntries} (oldest evicted first),
 * and is reset on application restart or via {@link #clear()}.
 */
public final class ScheduledTaskRunStore {

    private final int maxEntries;
    private final Object lock = new Object();
    private final Deque<Run> runs = new ArrayDeque<>();
    private final AtomicLong sequence = new AtomicLong();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    public ScheduledTaskRunStore(int maxEntries) {
        this.maxEntries = Math.max(1, maxEntries);
    }

    /**
     * Records one completed task execution.
     *
     * @param runnable stable identifier of the executed task (e.g. {@code declaringClass.methodName}),
     *     matching the identifier the static Scheduled Tasks panel uses for the same task
     * @param startTimestamp epoch milliseconds when the execution started
     * @param durationMs wall-clock duration of the execution in milliseconds
     * @param success whether the execution completed without throwing
     * @param exceptionClassName the thrown exception's class name, or {@code null} on success
     * @param message the thrown exception's message, or {@code null} on success or when absent
     * @param thread the thread the task executed on, or {@code null} when unknown
     */
    public void record(
            String runnable,
            long startTimestamp,
            long durationMs,
            boolean success,
            String exceptionClassName,
            String message,
            String thread) {
        try {
            Run run = new Run(
                    sequence.incrementAndGet(),
                    runnable,
                    startTimestamp,
                    Math.max(0L, durationMs),
                    success,
                    exceptionClassName,
                    message,
                    thread);
            synchronized (lock) {
                runs.addFirst(run);
                while (runs.size() > maxEntries) {
                    runs.removeLast();
                }
            }
            notifyListeners();
        } catch (RuntimeException ex) {
            // Recording must never disrupt the scheduled task execution it observes.
        }
    }

    /** Retained runs, newest-first. */
    public List<Run> runs() {
        synchronized (lock) {
            return new ArrayList<>(runs);
        }
    }

    public void clear() {
        synchronized (lock) {
            runs.clear();
        }
        notifyListeners();
    }

    /**
     * Registers a listener invoked (with no payload) whenever the store changes, i.e. on a recorded
     * execution or a {@link #clear()}. Returns a handle that removes the listener when run. Listener
     * failures are isolated so one bad subscriber cannot break capture.
     */
    public Runnable subscribe(Runnable listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private void notifyListeners() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
                // A misbehaving stream subscriber must never disrupt scheduled-task capture.
            }
        }
    }

    /** One captured execution of a scheduled task. */
    public record Run(
            long sequence,
            String runnable,
            long startTimestamp,
            long durationMs,
            boolean success,
            String exceptionClassName,
            String message,
            String thread) {}
}
