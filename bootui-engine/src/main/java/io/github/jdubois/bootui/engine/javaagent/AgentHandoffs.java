package io.github.jdubois.bootui.engine.javaagent;

import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.correlation.ExecutionIds;
import io.github.jdubois.bootui.engine.correlation.HandoffWindow;
import io.github.jdubois.bootui.engine.correlation.RequestPhases;
import io.github.jdubois.bootui.engine.journal.AsyncHandoffPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RunningHandoffs;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeEventPublisher;
import io.github.jdubois.bootui.engine.journal.RuntimeEventSink;
import io.github.jdubois.bootui.engine.resources.ThreadAllocations;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.github.jdubois.bootui.spi.CorrelationContextProvider;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * The engine's side of executor propagation ({@code docs/PLAN-v2.md} M5-2, D32): what the BootUI agent captures where a
 * request hands a task to a JDK executor, and what it reopens where the executor runs it. Framework-neutral; each
 * adapter builds one with its correlation source and request phases, and attaches it to its run's {@link AgentClaim}
 * once the engine is ready, so the agent captures nothing before.
 *
 * <p>Capture returns a flat {@code Object[]} of strings and longs ({@code requestId, executionId, traceId, spanId,
 * routeTemplate, handler, linkedTraceId, submittedEpochMillis, submittedNanos}), never a transaction or data source,
 * and {@code null} for BootUI's own work, on {@code bootui-} threads, or when no request or execution owns the work.
 * Reopen opens the snapshot's request as a child execution {@code async-…} with
 * {@link BootUiCorrelation#openPropagated}, so the task's work is owned by its request without being metered as the
 * request's own; a snapshot of a scheduled run or consumed message keeps that execution, as M4-15's managed tasks do.
 * Reopen returns {@code null} when the thread already works for the snapshot's request, as when a caller runs its own
 * task.</p>
 *
 * <p>Each handoff publishes one {@link JournalSource#AGENT_EXECUTORS} event when it closes, after restoring the thread's
 * previous context. Neither capture, reopen, nor closing ever submits work or starts a thread.</p>
 */
public final class AgentHandoffs implements RuntimeEventPublisher {

    /** The default {@code bootui.agent.executors.max-handoff}. */
    public static final Duration DEFAULT_MAX_HANDOFF = HandoffWindow.DEFAULT_MAX_HANDOFF;

    /** The name prefix of BootUI's own threads, whose work is never propagated. */
    static final String BOOTUI_THREAD_PREFIX = "bootui-";

    static final int SNAPSHOT_LENGTH = 9;

    private final CorrelationContextProvider correlation;
    private final RequestPhases phases;
    private final Duration maxHandoff;
    private final long maxHandoffNanos;
    private final Clock clock;
    private final LongSupplier nanoTime;
    private final LongSupplier allocatedBytes;
    private final RunningHandoffs running;
    private volatile RuntimeEventSink sink = RuntimeEventSink.NONE;

    /**
     * @param correlation where the submitting thread's context is read, such as {@code BootUiCorrelation::current}
     * @param phases the adapter's request phases, which say when a response started, or {@code null}
     * @param maxHandoff how long a handoff's work is attributed to its request; {@code null} for the default
     */
    public AgentHandoffs(CorrelationContextProvider correlation, RequestPhases phases, Duration maxHandoff) {
        this(
                correlation,
                phases,
                maxHandoff,
                Clock.systemUTC(),
                System::nanoTime,
                ThreadAllocations::currentAllocatedBytes,
                RunningHandoffs.shared());
    }

    AgentHandoffs(
            CorrelationContextProvider correlation,
            RequestPhases phases,
            Duration maxHandoff,
            Clock clock,
            LongSupplier nanoTime,
            LongSupplier allocatedBytes,
            RunningHandoffs running) {
        this.correlation = correlation == null ? BootUiCorrelation::current : correlation;
        this.phases = phases;
        this.maxHandoff =
                maxHandoff == null || maxHandoff.isNegative() || maxHandoff.isZero() ? DEFAULT_MAX_HANDOFF : maxHandoff;
        this.maxHandoffNanos = saturatedNanos(this.maxHandoff);
        this.clock = clock;
        this.nanoTime = nanoTime;
        this.allocatedBytes = allocatedBytes;
        this.running = running;
    }

    @Override
    public void setRuntimeEventSink(RuntimeEventSink journal) {
        this.sink = journal == null ? RuntimeEventSink.NONE : journal;
    }

    /** How long a handoff's work is attributed to its request. */
    public Duration maxHandoff() {
        return maxHandoff;
    }

    /** The submitting thread's snapshot, or {@code null} when nothing BootUI propagates owns the work. Never throws. */
    Object capture() {
        try {
            if (Thread.currentThread().getName().startsWith(BOOTUI_THREAD_PREFIX)) {
                return null;
            }
            CorrelationContext context = current();
            if (context.bootUi() || (context.requestId() == null && context.executionId() == null)) {
                return null;
            }
            return new Object[] {
                context.requestId(),
                context.executionId(),
                context.traceId(),
                context.spanId(),
                context.routeTemplate(),
                context.handler(),
                context.linkedTraceId(),
                Long.valueOf(clock.millis()),
                Long.valueOf(nanoTime.getAsLong())
            };
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    /**
     * Reopens a snapshot on the worker about to run its task: {@code argument} is {@code Object[] {snapshot, taskClass,
     * hook}}. Returns the open {@link Handoff}, or {@code null} when nothing was opened. Never throws.
     */
    AutoCloseable reopen(Object argument) {
        Handoff handoff = null;
        try {
            if (!(argument instanceof Object[] call) || call.length < 3 || !(call[0] instanceof Object[] snapshot)) {
                return null;
            }
            if (snapshot.length < SNAPSHOT_LENGTH) {
                return null;
            }
            String requestId = text(snapshot[0]);
            String parentExecutionId = text(snapshot[1]);
            if (requestId == null && parentExecutionId == null) {
                return null;
            }
            CorrelationContext here = current();
            if (requestId != null ? requestId.equals(here.requestId()) : parentExecutionId.equals(here.executionId())) {
                // Inline, caller-runs, or already propagated by a managed wrapper: the task keeps this thread's
                // context.
                return null;
            }
            String executionId = requestId == null ? parentExecutionId : ExecutionIds.nextAsync();
            CorrelationContext context = new CorrelationContext(
                    requestId,
                    executionId,
                    text(snapshot[2]),
                    text(snapshot[3]),
                    text(snapshot[4]),
                    text(snapshot[5]),
                    null,
                    null,
                    text(snapshot[6]),
                    false);
            long submittedEpochMillis = snapshot[7] instanceof Long millis ? millis : clock.millis();
            Long submittedNanos = snapshot[8] instanceof Long nanos ? nanos : null;
            Thread thread = Thread.currentThread();
            Instant start = clock.instant();
            long startNanos = nanoTime.getAsLong();
            long startAllocated = allocatedBytes.getAsLong();
            handoff = new Handoff(
                    context,
                    parentExecutionId,
                    text(call[1]),
                    text(call[2]),
                    thread.getName(),
                    start,
                    startNanos,
                    submittedEpochMillis,
                    submittedNanos == null ? 0 : Math.max(0, startNanos - submittedNanos),
                    startAllocated);
            handoff.key = running.started(new RunningHandoffs.Running(
                    requestId,
                    executionId,
                    parentExecutionId,
                    context.traceId(),
                    handoff.thread,
                    start.toEpochMilli(),
                    handoff.taskClass,
                    handoff.hook));
            // Last: once the worker carries the request's context, nothing may fail before the task runs, or a
            // pooled thread would keep it.
            handoff.scope = BootUiCorrelation.openPropagated(context);
            return handoff;
        } catch (RuntimeException | LinkageError ex) {
            if (handoff != null) {
                handoff.abandon();
            }
            return null;
        }
    }

    private CorrelationContext current() {
        try {
            CorrelationContext context = correlation.current();
            return context == null ? CorrelationContext.NONE : context;
        } catch (RuntimeException ex) {
            return CorrelationContext.NONE;
        }
    }

    private static String text(Object value) {
        return value instanceof String string && !string.isEmpty() ? string : null;
    }

    private static long saturatedNanos(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException ex) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * One task running in its request's context. The bridge tells it the task's failure, if any, then closes it, which
     * restores the worker's previous context and then publishes the handoff.
     */
    final class Handoff implements AutoCloseable, Consumer<Throwable>, BiConsumer<Throwable, Boolean>, Runnable {

        private final CorrelationContext context;
        private final String parentExecutionId;
        private final String taskClass;
        private final String hook;
        private final String thread;
        private final Instant start;
        private final long startNanos;
        private final long submittedEpochMillis;
        private final long queuedNanos;
        private final long startAllocated;
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile BootUiCorrelation.Scope scope;
        private volatile long key = RunningHandoffs.NONE;
        private volatile String exceptionClass;
        private volatile boolean failed;
        private Boolean bodyAfterResponse;
        private Long bodyAfterResponseMicros;
        private boolean bodyEnded;
        private Boolean failureAfterResponse;

        private Handoff(
                CorrelationContext context,
                String parentExecutionId,
                String taskClass,
                String hook,
                String thread,
                Instant start,
                long startNanos,
                long submittedEpochMillis,
                long queuedNanos,
                long startAllocated) {
            this.context = context;
            this.parentExecutionId = parentExecutionId;
            this.taskClass = taskClass;
            this.hook = hook;
            this.thread = thread;
            this.start = start;
            this.startNanos = startNanos;
            this.submittedEpochMillis = submittedEpochMillis;
            this.queuedNanos = queuedNanos;
            this.startAllocated = startAllocated;
        }

        /** Remembers the task's failure: its class only, never its message. */
        @Override
        public void accept(Throwable failure) {
            accept(failure, true);
        }

        @Override
        public void accept(Throwable failure, Boolean bodyFailure) {
            if (failure != null) {
                failed = true;
                exceptionClass = failure.getClass().getName();
                if (Boolean.TRUE.equals(bodyFailure)) {
                    failureAfterResponse = bodyAfterResponse;
                } else {
                    long failureAt = startMicros() + Math.max(0, nanoTime.getAsLong() - startNanos) / 1_000L;
                    RequestPhases.Markers markers =
                            context.requestId() == null || phases == null ? null : phases.markers(context.requestId());
                    Long responseAt = markers == null
                            ? null
                            : markers.responseAt() != null ? markers.responseAt() : markers.endedAt();
                    failureAfterResponse = markers == null
                            ? null
                            : responseAt != null
                                    && failureAt - responseAt >= HandoffWindow.RESPONSE_TIMESTAMP_SLACK_MICROS;
                }
            }
        }

        /** Called before the JDK publishes the task's result and releases its waiters. */
        @Override
        public void run() {
            if (bodyEnded) {
                return;
            }
            bodyEnded = true;
            RequestPhases.Markers markers =
                    context.requestId() == null || phases == null ? null : phases.markers(context.requestId());
            if (markers == null) {
                return;
            }
            Long responseAt = markers.responseAt() != null ? markers.responseAt() : markers.endedAt();
            // No marker yet is an ordering fact, not a comparison between clocks on different threads.
            bodyAfterResponse = responseAt != null;
            bodyAfterResponseMicros = responseAt == null
                    ? 0L
                    : Math.max(
                            0,
                            startMicros()
                                    + Math.max(0, nanoTime.getAsLong() - startNanos) / 1_000L
                                    - Math.max(startMicros(), responseAt));
        }

        private long startMicros() {
            return start.getEpochSecond() * 1_000_000L + start.getNano() / 1_000L;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            long endNanos;
            long endAllocated;
            try {
                endNanos = nanoTime.getAsLong();
                endAllocated = allocatedBytes.getAsLong();
            } catch (RuntimeException | LinkageError ex) {
                endNanos = startNanos;
                endAllocated = -1;
            }
            restore();
            try {
                running.ended(key);
                publish(Math.max(0, endNanos - startNanos), endAllocated);
            } catch (RuntimeException | LinkageError ex) {
                // Publishing never fails the task.
            }
        }

        /** Undoes a reopen that failed: restores the thread's context if it was changed, and forgets the handoff. */
        private void abandon() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            restore();
            try {
                running.ended(key);
            } catch (RuntimeException | LinkageError ex) {
                // Forgetting never fails the task.
            }
        }

        private void restore() {
            BootUiCorrelation.Scope opened = scope;
            if (opened == null) {
                return;
            }
            try {
                opened.close();
            } catch (RuntimeException | LinkageError ex) {
                // Restoring never fails the task.
            }
        }

        private void publish(long durationNanos, long endAllocated) {
            RuntimeEventSink journal = sink;
            if (!journal.records(JournalSource.AGENT_EXECUTORS)) {
                return;
            }
            Long allocated = startAllocated >= 0 && endAllocated >= startAllocated
                    ? Long.valueOf(endAllocated - startAllocated)
                    : null;
            Boolean afterResponse = null;
            Long afterResponseMicros = null;
            Long responseAt = null;
            if (context.requestId() != null && phases != null) {
                RequestPhases.Markers markers = phases.markers(context.requestId());
                // The response's start, else the request's end when its adapter marked no response, as for a failed
                // handler.
                responseAt = markers == null
                        ? null
                        : markers.responseAt() != null ? markers.responseAt() : markers.endedAt();
                if (responseAt != null) {
                    long startMicros = startMicros();
                    long endMicros = startMicros + durationNanos / 1_000L;
                    afterResponse = endMicros > responseAt;
                    afterResponseMicros = afterResponse ? endMicros - Math.max(startMicros, responseAt) : 0L;
                } else if (markers != null && markers.handlerAt() != null) {
                    // Its request's handler is still running, as when the handler waits for this task: it ended
                    // before the response started.
                    afterResponse = false;
                    afterResponseMicros = 0L;
                }
            }
            AsyncHandoffPayload payload = new AsyncHandoffPayload(
                    context.executionId(),
                    parentExecutionId,
                    taskClass,
                    hook,
                    submittedEpochMillis,
                    queuedNanos,
                    allocated,
                    failed,
                    exceptionClass,
                    afterResponse,
                    afterResponseMicros,
                    HandoffWindow.capped(durationNanos, maxHandoffNanos),
                    bodyAfterResponse,
                    bodyAfterResponseMicros,
                    responseAt,
                    failureAfterResponse);
            journal.offer(RuntimeEvent.of(
                    JournalSource.AGENT_EXECUTORS,
                    start.toEpochMilli(),
                    durationNanos,
                    context,
                    thread,
                    null,
                    failed,
                    payload));
        }
    }
}
