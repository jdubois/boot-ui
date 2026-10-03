package io.github.jdubois.bootui.engine.scheduled;

import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeEventPublisher;
import io.github.jdubois.bootui.engine.journal.RuntimeEventSink;
import io.github.jdubois.bootui.engine.journal.ScheduledPayload;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.github.jdubois.bootui.spi.MemoryOffloadable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Framework-neutral, in-memory, bounded ring buffer of {@code @Scheduled} task <em>executions</em>
 * (start/success/failure/duration), feeding the {@code SCHEDULED} entries in the Live Activity
 * merged stream (see {@code docs/features/overview.md}). This is a companion to the existing, purely-static
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
public final class ScheduledTaskRunStore implements RuntimeEventPublisher, MemoryOffloadable {

    private final int maxEntries;
    private final Object lock = new Object();
    private volatile RuntimeEventSink journal = RuntimeEventSink.NONE;
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
    /**
     * Installs the runtime journal ({@code docs/PLAN-v2.md} §5.2), which receives each recorded run right after this
     * recorder retains it. {@code null} restores the default, which publishes nothing.
     */
    @Override
    public void setRuntimeEventSink(RuntimeEventSink journal) {
        this.journal = journal == null ? RuntimeEventSink.NONE : journal;
    }

    public void record(
            String runnable,
            long startTimestamp,
            long durationMs,
            boolean success,
            String exceptionClassName,
            String message,
            String thread) {
        record(runnable, startTimestamp, durationMs, success, exceptionClassName, message, thread, null);
    }

    /**
     * Records one run with the BootUI execution id it ran under ({@code docs/PLAN-v2.md} §5.1), so the signals it
     * produced, which carry the same id, nest under it.
     */
    public void record(
            String runnable,
            long startTimestamp,
            long durationMs,
            boolean success,
            String exceptionClassName,
            String message,
            String thread,
            String executionId) {
        recordNanos(
                runnable,
                startTimestamp,
                Math.max(0L, durationMs) * 1_000_000,
                success,
                exceptionClassName,
                message,
                thread,
                executionId);
    }

    /**
     * Records one run timed in nanoseconds by a monotonic clock: the panel keeps milliseconds, and the runtime journal
     * the nanoseconds ({@code docs/PLAN-v2.md} §5.2). Adapters that only know the run's wall-clock start, such as
     * Quarkus's scheduler, which reports its fire time, pass whole milliseconds.
     */
    public void recordNanos(
            String runnable,
            long startTimestamp,
            long durationNanos,
            boolean success,
            String exceptionClassName,
            String message,
            String thread,
            String executionId) {
        try {
            long nanos = Math.max(0L, durationNanos);
            Run run = new Run(
                    sequence.incrementAndGet(),
                    runnable,
                    startTimestamp,
                    nanos / 1_000_000,
                    success,
                    exceptionClassName,
                    message,
                    thread,
                    executionId);
            synchronized (lock) {
                runs.addFirst(run);
                while (runs.size() > maxEntries) {
                    runs.removeLast();
                }
            }
            journal.offer(RuntimeEvent.of(
                    JournalSource.SCHEDULED,
                    startTimestamp,
                    nanos,
                    CorrelationContext.forExecution(executionId),
                    thread,
                    null,
                    !success,
                    new ScheduledPayload(runnable, exceptionClassName)));
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

    @Override
    public String offloadId() {
        return "scheduled-runs";
    }

    @Override
    public String offloadLabel() {
        return "Scheduled task runs";
    }

    /** Drops what {@link #clear()} drops, for <b>Free BootUI memory</b>; recording settings are kept. */
    @Override
    public long offloadRetainedData() {
        long retained = runs().size();
        clear();
        return retained;
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
            String thread,
            String executionId) {

        /** Without BootUI's execution identity. */
        public Run(
                long sequence,
                String runnable,
                long startTimestamp,
                long durationMs,
                boolean success,
                String exceptionClassName,
                String message,
                String thread) {
            this(sequence, runnable, startTimestamp, durationMs, success, exceptionClassName, message, thread, null);
        }
    }
}
