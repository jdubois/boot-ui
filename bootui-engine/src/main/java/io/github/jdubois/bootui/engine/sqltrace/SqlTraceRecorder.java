package io.github.jdubois.bootui.engine.sqltrace;

import io.github.jdubois.bootui.core.dto.CaptureRetentionDto;
import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import io.github.jdubois.bootui.core.dto.SqlTraceGroupDto;
import io.github.jdubois.bootui.core.dto.SqlTraceReport;
import io.github.jdubois.bootui.core.dto.SqlTraceStatsDto;
import io.github.jdubois.bootui.engine.activity.BootUiJdbcCaptureGuard;
import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.correlation.CorrelationSource;
import io.github.jdubois.bootui.engine.correlation.ThreadKinds;
import io.github.jdubois.bootui.engine.retention.TieredCaptureBuffer;
import io.github.jdubois.bootui.engine.support.StackFramePrefixes;
import io.github.jdubois.bootui.engine.telemetry.SpanEnricher;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.github.jdubois.bootui.spi.CorrelationContextProvider;
import io.github.jdubois.bootui.spi.IdleReclaimable;
import io.github.jdubois.bootui.spi.ThreadKindClassifier;
import io.github.jdubois.bootui.spi.TraceIdProvider;
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
public final class SqlTraceRecorder implements IdleReclaimable {

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
            String threadKind) {
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
    private volatile TraceIdProvider traceIdProvider = SqlTraceRecorder::mdcTraceId;
    private final CorrelationSource correlation = new CorrelationSource();
    private final ThreadKinds threadKinds = new ThreadKinds();
    private volatile SpanEnricher spanEnricher = SpanEnricher.NO_OP;

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
     * Replaces the trace-id source used to stamp each captured statement. Defaults to the SLF4J MDC
     * {@code traceId} key that Micrometer Tracing publishes on Spring, which works because Spring MVC
     * serves a request start-to-finish on one thread. The Quarkus adapter installs an OpenTelemetry-backed
     * provider instead, because its blocking SQL runs on a worker thread the MDC key never reaches but the
     * OpenTelemetry context does. Passing {@code null} restores the default MDC lookup, so the Spring
     * adapter (which never calls this) is unaffected.
     */
    public void setTraceIdProvider(TraceIdProvider traceIdProvider) {
        this.traceIdProvider = traceIdProvider == null ? SqlTraceRecorder::mdcTraceId : traceIdProvider;
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
    public void setThreadKindClassifier(ThreadKindClassifier classifier) {
        threadKinds.set(classifier);
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
        if (!enabled || idleSuspended || !recording.get() || BootUiJdbcCaptureGuard.isSuppressed()) {
            return;
        }
        CorrelationContext context = correlation.current();
        CapturedStatement entry = new CapturedStatement(
                sequence.incrementAndGet(),
                System.currentTimeMillis(),
                truncate(sql, maxSqlLength),
                statementType == null ? StatementType.STATEMENT : statementType,
                category == null ? Category.OTHER : category,
                Math.max(0, durationMicros),
                success,
                errorMessage,
                affectedRows,
                Math.max(0, batchSize),
                connectionId,
                thread,
                resolveTraceId(),
                captureParameters ? List.copyOf(parameters == null ? List.of() : parameters) : List.of(),
                captureCallSite ? currentCallSite() : null,
                context.requestId(),
                context.executionId(),
                threadKinds.current().name());
        buffer.add(entry, isFailedOrSlow(entry.success(), isSlow(entry.durationMicros())));
        totalCaptured.incrementAndGet();
        notifyListeners();
        enrichActiveSpan(entry.traceId());
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
                entry.threadKind());
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
     * {@link TraceIdProvider} and fully guarded so SQL execution is never disrupted by a missing or
     * misbehaving provider. Returns {@code null} (no correlation) when blank or on any failure.
     */
    private String resolveTraceId() {
        try {
            String traceId = traceIdProvider.currentTraceId();
            return traceId == null || traceId.isBlank() ? null : traceId;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * Default trace-id source: the SLF4J MDC where Micrometer Tracing publishes it (the {@code traceId}
     * correlation key). Returns {@code null} when no tracer is active or the key is absent, in which case
     * downstream correlation falls back to its time-window heuristic. The lookup is fully guarded so SQL
     * execution is never disrupted by a missing or misbehaving MDC.
     */
    private static String mdcTraceId() {
        try {
            String traceId = org.slf4j.MDC.get("traceId");
            return traceId == null || traceId.isBlank() ? null : traceId;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** Bound on how many stack frames are inspected before giving up on finding an application frame. */
    private static final int MAX_CALL_SITE_FRAMES = 128;

    private static final StackWalker STACK_WALKER = StackWalker.getInstance();

    /**
     * Best-effort location of the first application stack frame above the JDBC call — i.e. the first
     * frame that isn't the JDK, a JDBC driver/connection pool, Hibernate, or BootUI's own
     * instrumentation (see {@link StackFramePrefixes}) — formatted the same way as
     * {@link io.github.jdubois.bootui.engine.exceptions.ExceptionStore}'s exception location:
     * {@code ClassName.methodName(File.java:42)}. Walks at most {@link #MAX_CALL_SITE_FRAMES} frames of
     * the current thread's stack, short-circuiting at the first match rather than materializing the
     * whole stack, since this runs on every captured statement rather than only on exceptions. Fully
     * guarded so a stack-walking failure can never disrupt SQL execution; returns {@code null} when no
     * application frame is found within the bound, or on any failure.
     */
    private static String currentCallSite() {
        try {
            return STACK_WALKER.walk(SqlTraceRecorder::selectCallSite);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * Pure frame-selection logic factored out of {@link #currentCallSite()} so it can be unit-tested with
     * a synthetic frame stream, without depending on the ambient call stack of whatever happens to invoke
     * it (which, inside this codebase's own test suite, never contains a genuine application frame — every
     * frame belongs to BootUI itself, the JDK, JUnit, or the build tool). Package-private for tests.
     */
    static String selectCallSite(Stream<StackWalker.StackFrame> frames) {
        return frames.limit(MAX_CALL_SITE_FRAMES)
                .filter(frame -> !StackFramePrefixes.isFrameworkClass(frame.getClassName()))
                .findFirst()
                .map(SqlTraceRecorder::formatFrame)
                .orElse(null);
    }

    private static String formatFrame(StackWalker.StackFrame frame) {
        String file = frame.getFileName();
        String position = file == null
                ? "Unknown Source"
                : (frame.getLineNumber() >= 0 ? file + ":" + frame.getLineNumber() : file);
        return frame.getClassName() + "." + frame.getMethodName() + "(" + position + ")";
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
