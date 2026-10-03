package io.github.jdubois.bootui.engine.sqltrace;

import io.github.jdubois.bootui.core.dto.CaptureRetentionDto;
import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import io.github.jdubois.bootui.core.dto.SqlTraceGroupDto;
import io.github.jdubois.bootui.core.dto.SqlTraceReport;
import io.github.jdubois.bootui.core.dto.SqlTraceStatsDto;
import io.github.jdubois.bootui.engine.activity.BootUiJdbcCaptureGuard;
import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.correlation.CorrelationSource;
import io.github.jdubois.bootui.engine.correlation.RequestPhase;
import io.github.jdubois.bootui.engine.correlation.RequestPhases;
import io.github.jdubois.bootui.engine.correlation.ThreadKinds;
import io.github.jdubois.bootui.engine.journal.ApplicationFrames;
import io.github.jdubois.bootui.engine.journal.ConnectionPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeEventPublisher;
import io.github.jdubois.bootui.engine.journal.RuntimeEventSink;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.retention.TieredCaptureBuffer;
import io.github.jdubois.bootui.engine.telemetry.SpanEnricher;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.github.jdubois.bootui.spi.CorrelationContextProvider;
import io.github.jdubois.bootui.spi.IdleReclaimable;
import io.github.jdubois.bootui.spi.MemoryOffloadable;
import io.github.jdubois.bootui.spi.ThreadKind;
import io.github.jdubois.bootui.spi.ThreadKindClassifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * In-memory, bounded buffer of recently executed JDBC statements.
 *
 * <p>This is the hand-written replacement for the listener/registry that a
 * third-party JDBC proxy library (such as datasource-proxy or p6spy) would
 * provide. It is thread-safe and capped at {@code maxEntries} so it never grows
 * unbounded. Retention is failure-preserving ({@link TieredCaptureBuffer}): a
 * bounded share of the capacity is reserved for failed and slow executions, so
 * routine executions are evicted first and a flood of fast queries cannot evict
 * the most recent failures.</p>
 *
 * <p>Recording can be paused and resumed at runtime without unwrapping the
 * {@code DataSource}: {@link #setRecording(boolean)} flips a flag that
 * {@link #record} honours. The recorder also tracks which {@code DataSource}
 * beans were actually wrapped, so the panel can distinguish "no data source"
 * from "tracing disabled".</p>
 */
public final class SqlTraceRecorder implements IdleReclaimable, RuntimeEventPublisher, MemoryOffloadable {

    static final int TOP_STATEMENTS_LIMIT = 20;

    /** Kind of JDBC statement the execution originated from. */
    public enum StatementType {
        STATEMENT,
        PREPARED,
        CALLABLE
    }

    /** Coarse SQL classification derived from the statement text. */
    public enum Category {
        SELECT,
        INSERT,
        UPDATE,
        DELETE,
        DDL,
        OTHER
    }

    /**
     * A single immutable captured execution. Parameter bindings are only retained
     * when capture is enabled; callers decide whether to expose them.
     *
     * <p>{@code durationMicros} is the exact recorded duration. Statements are timed in nanoseconds and
     * recorded in microseconds because an ordinary local-database statement finishes well inside one
     * millisecond; {@link #durationMillis()} is derived from it by rounding, for readers whose contract is
     * a whole-millisecond count.</p>
     */
    public record CapturedStatement(
            long id,
            long timestamp,
            String sql,
            StatementType statementType,
            Category category,
            long durationMicros,
            boolean success,
            String errorMessage,
            Long affectedRows,
            int batchSize,
            String connectionId,
            String thread,
            String traceId,
            List<String> parameters,
            String callSite,
            String requestId,
            String executionId,
            String threadKind,
            String requestPhase) {
        /** Without BootUI's execution identity. */
        public CapturedStatement(
                long id,
                long timestamp,
                String sql,
                StatementType statementType,
                Category category,
                long durationMicros,
                boolean success,
                String errorMessage,
                Long affectedRows,
                int batchSize,
                String connectionId,
                String thread,
                String traceId,
                List<String> parameters,
                String callSite,
                String requestId,
                String executionId,
                String threadKind) {
            this(
                    id,
                    timestamp,
                    sql,
                    statementType,
                    category,
                    durationMicros,
                    success,
                    errorMessage,
                    affectedRows,
                    batchSize,
                    connectionId,
                    thread,
                    traceId,
                    parameters,
                    callSite,
                    requestId,
                    executionId,
                    threadKind,
                    null);
        }

        /** Without the thread kind. */
        public CapturedStatement(
                long id,
                long timestamp,
                String sql,
                StatementType statementType,
                Category category,
                long durationMicros,
                boolean success,
                String errorMessage,
                Long affectedRows,
                int batchSize,
                String connectionId,
                String thread,
                String traceId,
                List<String> parameters,
                String callSite,
                String requestId,
                String executionId) {
            this(
                    id,
                    timestamp,
                    sql,
                    statementType,
                    category,
                    durationMicros,
                    success,
                    errorMessage,
                    affectedRows,
                    batchSize,
                    connectionId,
                    thread,
                    traceId,
                    parameters,
                    callSite,
                    requestId,
                    executionId,
                    null);
        }

        /** Without BootUI's execution identity. */
        public CapturedStatement(
                long id,
                long timestamp,
                String sql,
                StatementType statementType,
                Category category,
                long durationMicros,
                boolean success,
                String errorMessage,
                Long affectedRows,
                int batchSize,
                String connectionId,
                String thread,
                String traceId,
                List<String> parameters,
                String callSite,
                String requestId) {
            this(
                    id,
                    timestamp,
                    sql,
                    statementType,
                    category,
                    durationMicros,
                    success,
                    errorMessage,
                    affectedRows,
                    batchSize,
                    connectionId,
                    thread,
                    traceId,
                    parameters,
                    callSite,
                    requestId,
                    null);
        }

        public CapturedStatement {
            parameters = parameters == null ? List.of() : List.copyOf(parameters);
        }

        /** A captured execution without BootUI's request identity. */
        public CapturedStatement(
                long id,
                long timestamp,
                String sql,
                StatementType statementType,
                Category category,
                long durationMicros,
                boolean success,
                String errorMessage,
                Long affectedRows,
                int batchSize,
                String connectionId,
                String thread,
                String traceId,
                List<String> parameters,
                String callSite) {
            this(
                    id,
                    timestamp,
                    sql,
                    statementType,
                    category,
                    durationMicros,
                    success,
                    errorMessage,
                    affectedRows,
                    batchSize,
                    connectionId,
                    thread,
                    traceId,
                    parameters,
                    callSite,
                    null);
        }

        /** The exact duration rounded to whole milliseconds. */
        public long durationMillis() {
            return SqlDurations.roundedMillis(durationMicros);
        }
    }

    private final boolean enabled;
    private final boolean captureParameters;
    private final boolean captureCallSite;
    private final int maxEntries;
    private final long slowQueryThresholdMillis;
    private final long slowQueryThresholdMicros;
    private final int maxSqlLength;
    private final int maxParameterLength;
    private final int nPlusOneThreshold;

    private final TieredCaptureBuffer<CapturedStatement> buffer;
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong totalCaptured = new AtomicLong();
    private final AtomicBoolean recording;
    private volatile boolean idleSuspended = false;
    private final Set<String> dataSourceNames = new ConcurrentSkipListSet<>();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final CorrelationSource correlation = new CorrelationSource();
    private final ThreadKinds threadKinds = new ThreadKinds();
    private volatile RequestPhases requestPhases;
    private volatile SpanEnricher spanEnricher = SpanEnricher.NO_OP;
    private volatile RuntimeEventSink journal = RuntimeEventSink.NONE;

    /** A recorder reserving the default share of its buffer for failed and slow executions. */
    public SqlTraceRecorder(
            boolean enabled,
            boolean recording,
            boolean captureParameters,
            boolean captureCallSite,
            int maxEntries,
            long slowQueryThresholdMillis,
            int maxSqlLength,
            int maxParameterLength,
            int nPlusOneThreshold) {
        this(
                enabled,
                recording,
                captureParameters,
                captureCallSite,
                maxEntries,
                slowQueryThresholdMillis,
                maxSqlLength,
                maxParameterLength,
                nPlusOneThreshold,
                TieredCaptureBuffer.DEFAULT_RESERVED_SHARE_PERCENT);
    }

    /**
     * @param reservedSharePercent share of {@code maxEntries} reserved for failed and slow executions
     *     ({@code bootui.sql-trace.reserved-share-percent}); {@code 0} evicts strictly oldest first
     */
    public SqlTraceRecorder(
            boolean enabled,
            boolean recording,
            boolean captureParameters,
            boolean captureCallSite,
            int maxEntries,
            long slowQueryThresholdMillis,
            int maxSqlLength,
            int maxParameterLength,
            int nPlusOneThreshold,
            int reservedSharePercent) {
        this.enabled = enabled;
        this.recording = new AtomicBoolean(recording);
        this.captureParameters = captureParameters;
        this.captureCallSite = captureCallSite;
        this.maxEntries = Math.max(1, maxEntries);
        this.slowQueryThresholdMillis = Math.max(0, slowQueryThresholdMillis);
        // A threshold too large to express in microseconds cannot be reached by any recorded duration, so it
        // is held as 0 (never slow) instead of overflowing into a negative bound that would flag everything.
        this.slowQueryThresholdMicros =
                this.slowQueryThresholdMillis > Long.MAX_VALUE / 1_000L ? 0L : this.slowQueryThresholdMillis * 1_000L;
        this.maxSqlLength = Math.max(16, maxSqlLength);
        this.maxParameterLength = Math.max(8, maxParameterLength);
        this.nPlusOneThreshold = Math.max(2, nPlusOneThreshold);
        this.buffer = new TieredCaptureBuffer<>(this.maxEntries, reservedSharePercent);
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

    public boolean isCaptureParameters() {
        return captureParameters;
    }

    public boolean isCaptureCallSite() {
        return captureCallSite;
    }

    /**
     * Replaces the source of the request id stamped on each captured statement ({@code docs/PLAN-v2.md} §5.1).
     * Defaults to the thread's {@link BootUiCorrelation} scope. The Quarkus adapter installs a provider that also reads
     * the request's Vert.x context, because blocking SQL runs on a worker thread. Passing {@code null} restores the
     * default.
     */
    public void setCorrelationContextProvider(CorrelationContextProvider correlationProvider) {
        correlation.set(correlationProvider);
    }

    /**
     * Replaces the classifier of the thread kind stamped on each capture ({@code docs/PLAN-v2.md} §5.1): each adapter
     * installs one that knows its own threads. Defaults to classifying virtual threads only; {@code null} restores it.
     */
    /**
     * Installs the runtime journal ({@code docs/PLAN-v2.md} §5.2), which receives each recorded statement right after
     * the buffer does. {@code null} restores the default, which publishes nothing.
     */
    @Override
    public void setRuntimeEventSink(RuntimeEventSink journal) {
        this.journal = journal == null ? RuntimeEventSink.NONE : journal;
    }

    public void setThreadKindClassifier(ThreadKindClassifier classifier) {
        threadKinds.set(classifier);
    }

    /**
     * Installs the phase markers of recent requests ({@code docs/PLAN-v2.md} §5.1), so each statement records the phase
     * of its request it ran in, such as {@code RESPONSE} for lazy loading during body serialization. {@code null}, the
     * default, records no phase.
     */
    public void setRequestPhases(RequestPhases requestPhases) {
        this.requestPhases = requestPhases;
    }

    private RequestPhase requestPhase(String requestId) {
        RequestPhases phases = requestPhases;
        if (phases == null || requestId == null) {
            return null;
        }
        try {
            return phases.phaseOf(requestId);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * Installs the {@link SpanEnricher} used to stamp {@code bootui.sql.*} depth attributes on the active
     * request span as statements are recorded. Defaults to {@link SpanEnricher#NO_OP}; each adapter installs
     * the OpenTelemetry-backed enricher only when OpenTelemetry tracing is present. Passing {@code null}
     * restores the no-op, so an adapter that never calls this is unaffected.
     */
    public void setSpanEnricher(SpanEnricher spanEnricher) {
        this.spanEnricher = spanEnricher == null ? SpanEnricher.NO_OP : spanEnricher;
    }

    public int getMaxEntries() {
        return maxEntries;
    }

    public long getSlowQueryThresholdMillis() {
        return slowQueryThresholdMillis;
    }

    /** Executions of {@link #getMaxEntries()} reserved for failed and slow executions. */
    public int getReservedCapacity() {
        return buffer.reservedCapacity();
    }

    /**
     * Whether a duration exceeds the configured slow-query threshold. The threshold stays configured in
     * whole milliseconds ({@code bootui.sql-trace.slow-query-threshold-millis}); only the comparison unit is
     * microseconds, so the semantics of the property are unchanged.
     */
    public boolean isSlow(long durationMicros) {
        return slowQueryThresholdMicros > 0 && durationMicros >= slowQueryThresholdMicros;
    }

    /**
     * Whether an execution belongs in the reserved share: it failed, or it reached the slow-query threshold. The one
     * rule both this recorder and the Live Activity persistence capture apply.
     */
    public static boolean isFailedOrSlow(boolean success, boolean slow) {
        return !success || slow;
    }

    /** Remembers a {@code DataSource} bean that BootUI wrapped for tracing. */
    public void registerDataSource(String name) {
        if (name != null && !name.isBlank()) {
            dataSourceNames.add(name);
        }
    }

    public List<String> dataSourceNames() {
        return List.copyOf(dataSourceNames);
    }

    public boolean hasWrappedDataSource() {
        return !dataSourceNames.isEmpty();
    }

    /**
     * Records one execution, truncating oversized SQL and evicting per the failure-preserving policy when full.
     * A failed statement or one at or above the slow-query threshold is eligible for the reserved share. The duration
     * is taken in microseconds so sub-millisecond statements — the normal case against a local database —
     * contribute their real cost to every aggregate instead of collapsing to zero.
     */
    public void record(
            StatementType statementType,
            Category category,
            String sql,
            List<String> parameters,
            long durationMicros,
            boolean success,
            String errorMessage,
            Long affectedRows,
            int batchSize,
            String connectionId,
            String thread) {
        recordNanos(
                statementType,
                category,
                sql,
                parameters,
                Math.max(0, durationMicros) * 1_000,
                success,
                errorMessage,
                affectedRows,
                batchSize,
                connectionId,
                thread);
    }

    /**
     * Records one execution timed in nanoseconds, as the JDBC proxy times it: the panel keeps microseconds, and the
     * runtime journal the nanoseconds ({@code docs/PLAN-v2.md} §5.2).
     */
    public void recordNanos(
            StatementType statementType,
            Category category,
            String sql,
            List<String> parameters,
            long durationNanos,
            boolean success,
            String errorMessage,
            Long affectedRows,
            int batchSize,
            String connectionId,
            String thread) {
        if (!enabled || BootUiJdbcCaptureGuard.isSuppressed()) {
            return;
        }
        // A paused or idle-suspended panel skips its own buffer, listeners, and span enrichment, but the runtime
        // journal keeps recording whenever it records SQL (docs/PLAN-v2.md §5.2).
        boolean panel = capturesForPanel();
        boolean toJournal = journal.records(JournalSource.SQL);
        if (!panel && !toJournal) {
            return;
        }
        CorrelationContext context = correlation.current();
        ThreadKind threadKind = threadKinds.current();
        long completedNanos = System.nanoTime();
        long timestamp = System.currentTimeMillis();
        RequestPhase phase = requestPhase(context.requestId());
        // The stack is walked only for what keeps it: the panel's call sites, or the journal's application frames.
        ApplicationFrames frames =
                ApplicationFrames.wanted(panel, captureCallSite, toJournal) ? ApplicationFrames.capture() : null;
        String truncatedSql = truncate(sql, maxSqlLength);
        long nanos = Math.max(0, durationNanos);
        long micros = nanos / 1_000;
        String traceId = resolveTraceId();
        String callSite = captureCallSite && frames != null ? frames.callSite() : null;
        boolean failedOrSlow = isFailedOrSlow(success, isSlow(micros));
        if (panel) {
            CapturedStatement entry = new CapturedStatement(
                    sequence.incrementAndGet(),
                    timestamp,
                    truncatedSql,
                    statementType == null ? StatementType.STATEMENT : statementType,
                    category == null ? Category.OTHER : category,
                    micros,
                    success,
                    errorMessage,
                    affectedRows,
                    Math.max(0, batchSize),
                    connectionId,
                    thread,
                    traceId,
                    captureParameters ? List.copyOf(parameters == null ? List.of() : parameters) : List.of(),
                    callSite,
                    context.requestId(),
                    context.executionId(),
                    threadKind.name(),
                    phase == null ? null : phase.name());
            buffer.add(entry, failedOrSlow);
        }
        if (toJournal) {
            publish(
                    context,
                    traceId,
                    timestamp,
                    nanos,
                    thread,
                    threadKind,
                    failedOrSlow,
                    truncatedSql,
                    success,
                    frames,
                    phase,
                    completedNanos);
        }
        if (panel) {
            totalCaptured.incrementAndGet();
            notifyListeners();
            enrichActiveSpan(traceId);
        }
    }

    /** Publishes a statement to the journal, stamped when it started; never throws. */
    private void publish(
            CorrelationContext context,
            String traceId,
            long completedEpochMillis,
            long durationNanos,
            String thread,
            ThreadKind threadKind,
            boolean failedOrSlow,
            String sql,
            boolean success,
            ApplicationFrames frames,
            RequestPhase phase,
            long completedNanos) {
        try {
            journal.offer(RuntimeEvent.of(
                    JournalSource.SQL,
                    RuntimeEvent.startMillis(completedEpochMillis, durationNanos),
                    durationNanos,
                    context,
                    traceId,
                    thread,
                    threadKind,
                    failedOrSlow,
                    new SqlPayload(
                            sql,
                            frames == null ? null : frames.callSite(),
                            context.dataSource(),
                            !success,
                            frames,
                            phase,
                            completedNanos)));
        } catch (RuntimeException ex) {
            // Publishing never disturbs the statement it observes.
        }
    }

    /**
     * Whether the SQL Trace panel captures statements now: capture is installed, recording is on, and the panel is not
     * suspended while idle. The runtime journal may still record statements when this is {@code false}.
     */
    public boolean capturesForPanel() {
        return enabled && !idleSuspended && recording.get();
    }

    /**
     * Starts following a logical connection the application has just obtained ({@code docs/PLAN-v2.md} §5.2), or
     * returns {@code null} when nothing is recorded now, so the proxy keeps no state for it.
     *
     * @param dataSource the data source's name, or {@code null}
     * @param waitNanos how long obtaining the connection took
     */
    public ConnectionCheckout checkoutConnection(String dataSource, long waitNanos) {
        // Logical connections only feed the journal, so the panel's pause and idle state do not gate them.
        if (!enabled || BootUiJdbcCaptureGuard.isSuppressed() || !journal.records(JournalSource.CONNECTION)) {
            return null;
        }
        return new ConnectionCheckout(
                dataSource,
                System.currentTimeMillis(),
                System.nanoTime(),
                Math.max(0, waitNanos),
                correlation.current(),
                Thread.currentThread().getName(),
                threadKinds.current(),
                new AtomicInteger(),
                new AtomicBoolean());
    }

    /**
     * Publishes a logical connection to the journal when the application releases it, with how long it waited for it
     * and held it, and the statements that ran on it. A connection held for at least the slow-query threshold is kept
     * with failed and slow events. A second release of the same connection is ignored.
     */
    public void releaseConnection(ConnectionCheckout checkout) {
        if (checkout == null || !checkout.release()) {
            return;
        }
        try {
            long heldNanos = Math.max(0, System.nanoTime() - checkout.obtainedNanos());
            journal.offer(RuntimeEvent.of(
                    JournalSource.CONNECTION,
                    checkout.epochMillis(),
                    heldNanos,
                    checkout.context(),
                    checkout.thread(),
                    checkout.threadKind(),
                    isSlow(heldNanos / 1_000),
                    new ConnectionPayload(
                            checkout.dataSource(),
                            checkout.waitNanos(),
                            checkout.statements().get(),
                            checkout.obtainedNanos())));
        } catch (RuntimeException ex) {
            // Publishing never disturbs the connection's release.
        }
    }

    /**
     * Stamps SQL depth onto the active request span for the cross-service trace waterfall: increments the
     * per-request query count and, when the request's statements now suspect an N+1 pattern (same grouping
     * the panel shows), flags it. Gated behind {@link SpanEnricher#enabled()} so the no-op path pays nothing,
     * and the per-trace grouping is skipped when the statement has no trace correlation.
     */
    private void enrichActiveSpan(String traceId) {
        SpanEnricher enricher = spanEnricher;
        if (!enricher.enabled()) {
            return;
        }
        // Supply the N+1 suspicion lazily: the enricher evaluates it only while the span is not yet flagged,
        // so the per-trace grouping scan is skipped once a request is already suspected (and when uncorrelated).
        enricher.onSqlStatement(() -> traceId != null && suspectsNPlusOne(traceId));
    }

    private boolean suspectsNPlusOne(String traceId) {
        List<SqlTraceEntryDto> forTrace = recent().stream()
                .filter(entry -> traceId.equals(entry.traceId()))
                .map(entry -> toDto(entry, false))
                .toList();
        return SqlTraceGrouping.anySuspectedNPlusOne(forTrace, nPlusOneThreshold);
    }

    /** Returns the retained executions, most recent first, across both retention tiers. */
    public List<CapturedStatement> recent() {
        return new ArrayList<>(buffer.newestFirst());
    }

    public long totalCaptured() {
        return totalCaptured.get();
    }

    public long evicted() {
        return buffer.evicted();
    }

    /** The buffer's retention counts: capacity, reserved share, retained and reserved executions, and evictions. */
    public CaptureRetentionDto retention() {
        return buffer.snapshot().retention(slowQueryThresholdMillis);
    }

    @Override
    public String offloadId() {
        return "sql-trace";
    }

    @Override
    public String offloadLabel() {
        return "SQL Trace statements";
    }

    /** Drops what {@link #clear()} drops, for <b>Free BootUI memory</b>; recording settings are kept. */
    @Override
    public long offloadRetainedData() {
        long retained = recent().size();
        clear();
        return retained;
    }

    public void clear() {
        buffer.clear();
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
     * Registers a listener invoked (with no payload) whenever the trace changes, i.e. on a recorded
     * statement, a {@link #clear()}, or a recording pause/resume. Returns a handle that removes the
     * listener when run. Listener failures are isolated so they cannot disrupt query execution.
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
                // A misbehaving stream subscriber must never disrupt query execution.
            }
        }
    }

    /** Computes aggregate counters over the retained buffer. */
    public SqlTraceStatsDto stats() {
        return stats(buffer.snapshot());
    }

    private SqlTraceStatsDto stats(TieredCaptureBuffer.Snapshot<CapturedStatement> snapshot) {
        long total = 0;
        long totalDurationMicros = 0;
        long maxDurationMicros = 0;
        long slow = 0;
        long failed = 0;
        long batches = 0;
        long selects = 0;
        long inserts = 0;
        long updates = 0;
        long deletes = 0;
        long others = 0;
        for (CapturedStatement entry : snapshot.newestFirst()) {
            total++;
            totalDurationMicros += entry.durationMicros();
            maxDurationMicros = Math.max(maxDurationMicros, entry.durationMicros());
            if (isSlow(entry.durationMicros())) {
                slow++;
            }
            if (!entry.success()) {
                failed++;
            }
            if (entry.batchSize() > 0) {
                batches++;
            }
            switch (entry.category()) {
                case SELECT -> selects++;
                case INSERT -> inserts++;
                case UPDATE -> updates++;
                case DELETE -> deletes++;
                default -> others++;
            }
        }
        double avg = total == 0 ? 0 : SqlDurations.millis((double) totalDurationMicros / total);
        return new SqlTraceStatsDto(
                total,
                SqlDurations.millis(totalDurationMicros),
                SqlDurations.millis(maxDurationMicros),
                avg,
                slow,
                failed,
                batches,
                selects,
                inserts,
                updates,
                deletes,
                others,
                snapshot.evicted());
    }

    /**
     * Groups buffered executions by exact statement text, ordered by execution count descending,
     * and flags repeated {@code SELECT}s that look like an N+1 access pattern. Each group's call
     * sites are aggregated newest-first (bounded to {@link SqlTraceGrouping#MAX_CALL_SITES_PER_GROUP})
     * by walking the snapshot most-recent-first before aggregating.
     */
    public List<SqlTraceGroupDto> topStatements() {
        return topStatements(buffer.snapshot());
    }

    private List<SqlTraceGroupDto> topStatements(TieredCaptureBuffer.Snapshot<CapturedStatement> snapshot) {
        Map<String, Aggregate> byStatement = new LinkedHashMap<>();
        for (CapturedStatement entry : snapshot.newestFirst()) {
            String sql = entry.sql() == null ? "" : entry.sql();
            Aggregate aggregate = byStatement.computeIfAbsent(sql, key -> new Aggregate(key, entry.category()));
            aggregate.executions++;
            aggregate.totalDurationMicros += entry.durationMicros();
            aggregate.maxDurationMicros = Math.max(aggregate.maxDurationMicros, entry.durationMicros());
            aggregate.addCallSite(entry.callSite());
        }
        return byStatement.values().stream()
                .sorted(Comparator.comparingLong((Aggregate a) -> a.executions)
                        .reversed()
                        .thenComparing(a -> a.sql))
                .limit(TOP_STATEMENTS_LIMIT)
                .map(a -> new SqlTraceGroupDto(
                        a.sql,
                        a.category.name(),
                        a.executions,
                        SqlDurations.millis(a.totalDurationMicros),
                        SqlDurations.millis(a.maxDurationMicros),
                        a.category == Category.SELECT && a.executions >= nPlusOneThreshold,
                        a.callSites()))
                .toList();
    }

    String truncateParameter(String value) {
        return truncate(value, maxParameterLength);
    }

    /**
     * Assembles the immutable {@link SqlTraceReport} the panel renders, shared verbatim by the Spring and
     * Quarkus adapters so the wire is byte-identical regardless of capture mechanism. Bound parameter values
     * are surfaced only when {@code exposeParameters} is {@code true} (capture enabled and value exposure not
     * metadata-only); otherwise every entry's parameters collapse to an empty list. The adapter decides the
     * unavailable case (no data source / tracing off); this method covers the available, wrapped case.
     */
    public SqlTraceReport report(boolean exposeParameters) {
        // One snapshot feeds every section, so the entries, statistics, and retention counts always reconcile.
        TieredCaptureBuffer.Snapshot<CapturedStatement> snapshot = buffer.snapshot();
        return new SqlTraceReport(
                true,
                null,
                isRecording(),
                isCaptureParameters(),
                getMaxEntries(),
                totalCaptured(),
                getSlowQueryThresholdMillis(),
                dataSourceNames(),
                stats(snapshot),
                snapshot.newestFirst().stream()
                        .map(entry -> toDto(entry, exposeParameters))
                        .toList(),
                topStatements(snapshot),
                warnings(exposeParameters, snapshot),
                snapshot.retention(slowQueryThresholdMillis));
    }

    /**
     * The retained executions as DTOs, with bound parameters included only when {@code exposeParameters}
     * permits it. Exposed separately from {@link #report(boolean)} so a caller that only needs the
     * executions — such as ranking and route attribution — does not pay for the statistics and top-statement
     * aggregations it will not read.
     */
    public List<SqlTraceEntryDto> entries(boolean exposeParameters) {
        return recent().stream().map(entry -> toDto(entry, exposeParameters)).toList();
    }

    private List<String> warnings(boolean exposeParameters, TieredCaptureBuffer.Snapshot<CapturedStatement> snapshot) {
        List<String> warnings = new ArrayList<>();
        if (!isRecording()) {
            warnings.add("Recording is paused. Resume it to capture new queries.");
        }
        if (exposeParameters) {
            warnings.add("Bound parameter values are captured in clear text. "
                    + "Set bootui.sql-trace.capture-parameters=false to hide them.");
        }
        if (snapshot.evicted() > 0) {
            warnings.add(evictionWarning(snapshot));
        }
        return warnings;
    }

    private static String evictionWarning(TieredCaptureBuffer.Snapshot<CapturedStatement> snapshot) {
        String warning = "Older queries were dropped; the buffer keeps up to " + snapshot.capacity() + " executions";
        if (snapshot.reservedCapacity() == 0) {
            return warning + ", newest first.";
        }
        return warning + ", reserving " + snapshot.reservedCapacity()
                + " for the most recent failed or slow ones, so routine executions are dropped first.";
    }

    private SqlTraceEntryDto toDto(CapturedStatement entry, boolean exposeParameters) {
        return new SqlTraceEntryDto(
                entry.id(),
                entry.timestamp(),
                entry.sql(),
                entry.statementType().name(),
                entry.category().name(),
                entry.durationMicros(),
                entry.durationMillis(),
                entry.success(),
                entry.errorMessage(),
                entry.affectedRows(),
                entry.batchSize(),
                entry.connectionId(),
                entry.thread(),
                isSlow(entry.durationMicros()),
                exposeParameters ? entry.parameters() : List.of(),
                entry.traceId(),
                entry.callSite(),
                entry.requestId(),
                entry.executionId(),
                entry.threadKind(),
                entry.requestPhase());
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        if (stripped.length() <= max) {
            return stripped;
        }
        return stripped.substring(0, max) + "…";
    }

    /**
     * The trace id to stamp on the next captured statement, taken from the configured
     * correlation source and fully guarded so SQL execution is never disrupted by a missing or
     * misbehaving provider. Returns {@code null} (no correlation) when blank or on any failure.
     */
    private String resolveTraceId() {
        try {
            String traceId = correlation.traceId();
            return traceId == null || traceId.isBlank() ? null : traceId;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** Bound on how many stack frames are inspected before giving up on finding an application frame. */
    private static final int MAX_CALL_SITE_FRAMES = 128;

    /**
     * Pure frame-selection logic factored out of {@link #currentCallSite()} so it can be unit-tested with
     * a synthetic frame stream, without depending on the ambient call stack of whatever happens to invoke
     * it (which, inside this codebase's own test suite, never contains a genuine application frame — every
     * frame belongs to BootUI itself, the JDK, JUnit, or the build tool). Package-private for tests.
     */
    static String selectCallSite(Stream<StackWalker.StackFrame> frames) {
        ApplicationFrames selected = ApplicationFrames.select(frames);
        return selected == null ? null : selected.callSite();
    }

    private static final class Aggregate {
        private final String sql;
        private final Category category;
        private long executions;
        private long totalDurationMicros;
        private long maxDurationMicros;
        private final Set<String> callSites = new LinkedHashSet<>();

        private Aggregate(String sql, Category category) {
            this.sql = sql;
            this.category = category;
        }

        private void addCallSite(String callSite) {
            if (callSite != null && callSites.size() < SqlTraceGrouping.MAX_CALL_SITES_PER_GROUP) {
                callSites.add(callSite);
            }
        }

        private List<String> callSites() {
            return List.copyOf(callSites);
        }
    }
}
