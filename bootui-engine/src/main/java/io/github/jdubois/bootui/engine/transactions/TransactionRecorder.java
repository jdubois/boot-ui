package io.github.jdubois.bootui.engine.transactions;

import io.github.jdubois.bootui.core.dto.TransactionEntryDto;
import io.github.jdubois.bootui.core.dto.TransactionReport;
import io.github.jdubois.bootui.core.dto.TransactionStatsDto;
import io.github.jdubois.bootui.engine.correlation.CorrelationSource;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeEventPublisher;
import io.github.jdubois.bootui.engine.journal.RuntimeEventSink;
import io.github.jdubois.bootui.engine.journal.TransactionPayload;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.engine.support.BootUiThreadLocal;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.github.jdubois.bootui.spi.CorrelationContextProvider;
import io.github.jdubois.bootui.spi.IdleReclaimable;
import io.github.jdubois.bootui.spi.MemoryOffloadable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory, bounded buffer of recently completed transaction boundaries.
 *
 * <p>This is the hand-written replacement for the listener/registry that a third-party
 * transaction-observability library (such as spring-tx-board) would provide. It is thread-safe,
 * capped at {@code maxEntries}, and evicts the oldest transaction once full so it never grows
 * unbounded, mirroring {@link SqlTraceRecorder}'s buffer shape.</p>
 *
 * <p>A transaction is recorded in two steps: {@link #beginTransaction} when the boundary starts
 * (from an {@code afterBegin} callback) and {@link #completeTransaction} when it finishes (from an
 * {@code afterCommit}/{@code afterRollback} callback). Nesting is tracked with a per-thread stack of
 * in-flight transaction ids so a participating transaction records the id of the transaction already
 * active on the same thread as its {@code parentId}.</p>
 *
 * <p>When a {@link SqlTraceRecorder} is supplied, each completed transaction is correlated to the SQL
 * it likely ran by reusing that recorder's already-captured executions: statements on the same thread
 * whose completion timestamp falls within the transaction's begin/end window are counted, and their
 * distinct connection ids are counted separately. This is the same thread/time-window correlation
 * heuristic {@code SqlTraceRecorder} itself falls back to when no trace id is available, applied here
 * rather than duplicated.</p>
 */
public final class TransactionRecorder implements IdleReclaimable, RuntimeEventPublisher, MemoryOffloadable {

    /** Outcome of a completed transaction boundary. */
    public enum Status {
        COMMITTED,
        ROLLED_BACK,
        UNKNOWN
    }

    /** Best-effort propagation classification; see {@link TransactionEntryDto#propagation()}. */
    public static final String PROPAGATION_NEW = "NEW";

    public static final String PROPAGATION_PARTICIPATING = "PARTICIPATING";

    public static final String ISOLATION_UNKNOWN = "UNKNOWN";

    private final boolean enabled;
    private final int maxEntries;
    private final long slowTransactionThresholdMillis;
    private final long connectionHoldThresholdMillis;
    private final SqlTraceRecorder sqlTraceRecorder;

    private final Deque<TransactionEntryDto> buffer = new ArrayDeque<>();
    private final Object lock = new Object();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong totalCaptured = new AtomicLong();
    private final AtomicLong evicted = new AtomicLong();
    private final AtomicBoolean recording;
    private volatile boolean idleSuspended = false;
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    private final Map<Long, ActiveTransaction> active = new ConcurrentHashMap<>();
    private final ThreadLocal<Deque<Long>> threadStack = BootUiThreadLocal.withInitial(ArrayDeque::new);
    private final CorrelationSource correlation = new CorrelationSource();
    private volatile RuntimeEventSink journal = RuntimeEventSink.NONE;

    public TransactionRecorder(
            boolean enabled,
            boolean recording,
            int maxEntries,
            long slowTransactionThresholdMillis,
            long connectionHoldThresholdMillis,
            SqlTraceRecorder sqlTraceRecorder) {
        this.enabled = enabled;
        this.recording = new AtomicBoolean(recording);
        this.maxEntries = Math.max(1, maxEntries);
        this.slowTransactionThresholdMillis = Math.max(0, slowTransactionThresholdMillis);
        this.connectionHoldThresholdMillis = Math.max(0, connectionHoldThresholdMillis);
        this.sqlTraceRecorder = sqlTraceRecorder;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isRecording() {
        return recording.get();
    }

    public void setRecording(boolean value) {
        boolean changed = recording.getAndSet(value) != value;
        if (changed) {
            notifyListeners();
        }
    }

    /**
     * Installs the runtime journal ({@code docs/PLAN-v2.md} §5.2), which receives each recorded transaction right after this
     * recorder retains it. {@code null} restores the default, which publishes nothing.
     */
    public void setRuntimeEventSink(RuntimeEventSink journal) {
        this.journal = journal == null ? RuntimeEventSink.NONE : journal;
    }

    public int getMaxEntries() {
        return maxEntries;
    }

    public long getSlowTransactionThresholdMillis() {
        return slowTransactionThresholdMillis;
    }

    public long getConnectionHoldThresholdMillis() {
        return connectionHoldThresholdMillis;
    }

    public boolean isSlow(long durationMillis) {
        return slowTransactionThresholdMillis > 0 && durationMillis >= slowTransactionThresholdMillis;
    }

    public boolean isConnectionHeld(long durationMillis) {
        return connectionHoldThresholdMillis > 0 && durationMillis >= connectionHoldThresholdMillis;
    }

    /** Whether the Transactions panel captures now: capture is enabled, recording is on, and it is not idle. */
    private boolean capturesForPanel() {
        return enabled && !idleSuspended && recording.get();
    }

    /**
     * Records the start of a transaction boundary, resolving its parent from the thread's stack of
     * already-active transactions. Returns {@code -1} (a sentinel completion no-ops on) when capture is
     * disabled, or when the panel is paused or idle-suspended and the runtime journal does not record transactions,
     * so callers never need a null check.
     */
    public long beginTransaction(String methodName, boolean readOnly, String isolation, String thread, String traceId) {
        return beginTransaction(methodName, readOnly, isolation, thread, traceId, false);
    }

    /**
     * Records the start of a transaction boundary, as {@link #beginTransaction(String, boolean, String, String, String)}
     * does, saying whether it is a savepoint inside the enclosing transaction rather than a physical transaction of its
     * own ({@code docs/PLAN-v2.md} §5.5).
     */
    public long beginTransaction(
            String methodName, boolean readOnly, String isolation, String thread, String traceId, boolean savepoint) {
        return beginTransaction(methodName, readOnly, isolation, thread, traceId, savepoint, true);
    }

    /**
     * Records the start of a transaction boundary, as {@link #beginTransaction(String, boolean, String, String, String,
     * boolean)} does; with {@code threadBound} {@code false}, as a reactive transaction that begins and completes on
     * whichever threads its pipeline runs, it takes no parent from this thread's stack and is not pushed on it, so it
     * never becomes the parent of a later transaction of this thread.
     */
    public long beginTransaction(
            String methodName,
            boolean readOnly,
            String isolation,
            String thread,
            String traceId,
            boolean savepoint,
            boolean threadBound) {
        if (!enabled) {
            return -1;
        }
        // A paused or idle-suspended panel skips its own buffer, but the runtime journal keeps recording
        // transactions (docs/PLAN-v2.md §5.2).
        boolean panel = capturesForPanel();
        if (!panel && !journal.records(JournalSource.TRANSACTION)) {
            return -1;
        }
        long id = sequence.incrementAndGet();
        Deque<Long> stack = threadBound ? threadStack.get() : null;
        Long parentId = stack == null ? null : stack.peekLast();
        ActiveTransaction transaction = new ActiveTransaction(
                id,
                methodName == null ? "unknown" : methodName,
                parentId == null ? PROPAGATION_NEW : PROPAGATION_PARTICIPATING,
                isolation == null || isolation.isBlank() ? ISOLATION_UNKNOWN : isolation,
                readOnly,
                parentId,
                thread,
                traceId,
                System.currentTimeMillis(),
                System.nanoTime(),
                savepoint,
                correlation.current(),
                panel);
        active.put(id, transaction);
        if (stack != null) {
            stack.addLast(id);
        }
        return id;
    }

    /**
     * Records the completion of a transaction boundary previously started with {@link
     * #beginTransaction} (including the case where starting it failed, which the caller reports as
     * {@link Status#UNKNOWN} with an {@code errorMessage}). No-ops for the {@code -1} sentinel or an id
     * this recorder never saw begin (e.g. capture was toggled on mid-transaction).
     */
    public void completeTransaction(long id, Status status, String errorMessage) {
        completeTransaction(id, status, errorMessage, false, null);
    }

    /**
     * Records the completion of a transaction boundary, as {@link #completeTransaction(long, Status, String)} does, with
     * whether it was marked rollback-only and the exception class that failed its commit or rollback, if any
     * ({@code docs/PLAN-v2.md} §5.18).
     */
    public void completeTransaction(
            long id, Status status, String errorMessage, boolean rollbackOnly, String failureClass) {
        if (id < 0) {
            return;
        }
        popStack(id);
        ActiveTransaction transaction = active.remove(id);
        if (transaction == null) {
            return;
        }
        record(transaction, status == null ? Status.UNKNOWN : status, errorMessage, rollbackOnly, failureClass);
    }

    private void popStack(long id) {
        Deque<Long> stack = threadStack.get();
        stack.removeLastOccurrence(id);
        if (stack.isEmpty()) {
            threadStack.remove();
        }
    }

    private void record(
            ActiveTransaction transaction,
            Status status,
            String errorMessage,
            boolean rollbackOnly,
            String failureClass) {
        long end = System.currentTimeMillis();
        long duration = Math.max(0, end - transaction.startTimestamp);
        if (transaction.panel()) {
            Correlation correlation = correlate(transaction, end);
            TransactionEntryDto entry = new TransactionEntryDto(
                    transaction.id,
                    transaction.methodName,
                    transaction.propagation,
                    transaction.isolation,
                    status.name(),
                    transaction.startTimestamp,
                    end,
                    duration,
                    transaction.parentId,
                    transaction.thread,
                    transaction.traceId,
                    correlation.statementCount(),
                    correlation.connectionCount(),
                    transaction.readOnly,
                    isSlow(duration),
                    isConnectionHeld(duration),
                    errorMessage);
            synchronized (lock) {
                buffer.addLast(entry);
                while (buffer.size() > maxEntries) {
                    buffer.removeFirst();
                    evicted.incrementAndGet();
                }
            }
            totalCaptured.incrementAndGet();
        }
        try {
            journal.offer(RuntimeEvent.of(
                    JournalSource.TRANSACTION,
                    transaction.startTimestamp(),
                    System.nanoTime() - transaction.startNanos(),
                    transaction.context(),
                    transaction.traceId(),
                    transaction.thread(),
                    null,
                    status != Status.COMMITTED || isSlow(duration),
                    new TransactionPayload(
                            transaction.methodName(),
                            status == Status.ROLLED_BACK,
                            transaction.parentId() != null,
                            transaction.savepoint(),
                            transaction.startNanos(),
                            transaction.readOnly(),
                            ISOLATION_UNKNOWN.equals(transaction.isolation()) ? null : transaction.isolation(),
                            rollbackOnly,
                            failureClass)));
        } catch (RuntimeException ex) {
            // Publishing never disturbs the transaction it observes.
        }
        if (transaction.panel()) {
            notifyListeners();
        }
    }

    /**
     * Installs where this recorder reads the request or execution a transaction belongs to, when it begins
     * ({@code docs/PLAN-v2.md} §5.1). The default is the thread's correlation scope.
     */
    public void setCorrelationContextProvider(CorrelationContextProvider provider) {
        correlation.set(provider);
    }

    /**
     * Correlates the completed transaction to SQL Trace executions on the same thread whose completion
     * fell within the transaction's begin/end window, reusing {@link SqlTraceRecorder#recent()} rather
     * than tracking JDBC activity a second time. Returns a zero correlation when no SQL Trace recorder
     * is wired (panel disabled or unavailable) or the transaction's thread is unknown.
     */
    private Correlation correlate(ActiveTransaction transaction, long end) {
        if (sqlTraceRecorder == null || transaction.thread == null) {
            return Correlation.EMPTY;
        }
        int statements = 0;
        Set<String> connections = new HashSet<>();
        for (SqlTraceRecorder.CapturedStatement statement : sqlTraceRecorder.recent()) {
            if (!transaction.thread.equals(statement.thread())) {
                continue;
            }
            long timestamp = statement.timestamp();
            if (timestamp < transaction.startTimestamp || timestamp > end) {
                continue;
            }
            statements++;
            if (statement.connectionId() != null) {
                connections.add(statement.connectionId());
            }
        }
        return new Correlation(statements, connections.size());
    }

    private record Correlation(int statementCount, int connectionCount) {
        private static final Correlation EMPTY = new Correlation(0, 0);
    }

    /** Returns the retained transactions, most recently completed first. */
    public List<TransactionEntryDto> recent() {
        synchronized (lock) {
            List<TransactionEntryDto> snapshot = new ArrayList<>(buffer);
            java.util.Collections.reverse(snapshot);
            return snapshot;
        }
    }

    public long totalCaptured() {
        return totalCaptured.get();
    }

    public long evicted() {
        return evicted.get();
    }

    @Override
    public String offloadId() {
        return "transactions";
    }

    @Override
    public String offloadLabel() {
        return "Transactions";
    }

    /** Drops what {@link #clear()} drops, for <b>Free BootUI memory</b>; recording settings are kept. */
    @Override
    public long offloadRetainedData() {
        long retained = recent().size();
        clear();
        return retained;
    }

    public void clear() {
        synchronized (lock) {
            buffer.clear();
        }
        notifyListeners();
    }

    @Override
    public void suspendForIdle() {
        idleSuspended = true;
        clear();
    }

    @Override
    public void resumeFromIdle() {
        idleSuspended = false;
    }

    /**
     * Registers a listener invoked (with no payload) whenever the trace changes, i.e. on a completed
     * transaction, a {@link #clear()}, or a recording pause/resume. Returns a handle that removes the
     * listener when run. Listener failures are isolated so they cannot disrupt transaction execution.
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
                // A misbehaving stream subscriber must never disrupt transaction execution.
            }
        }
    }

    /** Computes aggregate counters over the retained buffer. */
    public TransactionStatsDto stats() {
        long total = 0;
        long totalDuration = 0;
        long maxDuration = 0;
        long slow = 0;
        long connectionHeld = 0;
        long committed = 0;
        long rolledBack = 0;
        long unknown = 0;
        long nested = 0;
        List<TransactionEntryDto> snapshot;
        synchronized (lock) {
            snapshot = new ArrayList<>(buffer);
        }
        for (TransactionEntryDto entry : snapshot) {
            total++;
            totalDuration += entry.durationMillis();
            maxDuration = Math.max(maxDuration, entry.durationMillis());
            if (entry.slow()) {
                slow++;
            }
            if (entry.connectionHeld()) {
                connectionHeld++;
            }
            if (entry.parentId() != null) {
                nested++;
            }
            switch (Status.valueOf(entry.status())) {
                case COMMITTED -> committed++;
                case ROLLED_BACK -> rolledBack++;
                case UNKNOWN -> unknown++;
            }
        }
        double avg = total == 0 ? 0 : (double) totalDuration / total;
        return new TransactionStatsDto(
                total,
                totalDuration,
                maxDuration,
                avg,
                slow,
                connectionHeld,
                committed,
                rolledBack,
                unknown,
                nested,
                evicted.get());
    }

    /**
     * Assembles the immutable {@link TransactionReport} the panel renders, shared verbatim by the
     * Spring adapter so the wire stays stable regardless of capture mechanism. The adapter decides the
     * unavailable case (no transaction manager wired); this method covers the available case.
     */
    public TransactionReport report() {
        return new TransactionReport(
                true,
                null,
                isRecording(),
                getMaxEntries(),
                totalCaptured(),
                getSlowTransactionThresholdMillis(),
                getConnectionHoldThresholdMillis(),
                stats(),
                recent(),
                warnings());
    }

    private List<String> warnings() {
        List<String> warnings = new ArrayList<>();
        if (!isRecording()) {
            warnings.add("Recording is paused. Resume it to capture new transactions.");
        }
        if (sqlTraceRecorder == null || !sqlTraceRecorder.hasWrappedDataSource()) {
            warnings.add("SQL Trace is not active, so SQL statement/connection counts are not correlated.");
        }
        if (evicted() > 0) {
            warnings.add("Older transactions were dropped; the buffer keeps the most recent " + getMaxEntries() + ".");
        }
        return warnings;
    }

    private record ActiveTransaction(
            long id,
            String methodName,
            String propagation,
            String isolation,
            boolean readOnly,
            Long parentId,
            String thread,
            String traceId,
            long startTimestamp,
            long startNanos,
            boolean savepoint,
            CorrelationContext context,
            boolean panel) {}
}
