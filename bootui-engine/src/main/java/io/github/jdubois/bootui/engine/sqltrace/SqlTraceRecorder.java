package io.github.jdubois.bootui.engine.sqltrace;

import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import io.github.jdubois.bootui.core.dto.SqlTraceGroupDto;
import io.github.jdubois.bootui.core.dto.SqlTraceReport;
import io.github.jdubois.bootui.core.dto.SqlTraceStatsDto;
import io.github.jdubois.bootui.engine.activity.BootUiJdbcCaptureGuard;
import io.github.jdubois.bootui.engine.support.StackFramePrefixes;
import io.github.jdubois.bootui.engine.telemetry.SpanEnricher;
import io.github.jdubois.bootui.spi.IdleReclaimable;
import io.github.jdubois.bootui.spi.TraceIdProvider;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
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
 * provide. It is thread-safe, capped at {@code maxEntries}, and evicts the
 * oldest execution once full so it never grows unbounded.</p>
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
     */
    public record CapturedStatement(
            long id,
            long timestamp,
            String sql,
            StatementType statementType,
            Category category,
            long durationMillis,
            boolean success,
            String errorMessage,
            Long affectedRows,
            int batchSize,
            String connectionId,
            String thread,
            String traceId,
            List<String> parameters,
            String callSite) {

        public CapturedStatement {
            parameters = parameters == null ? List.of() : List.copyOf(parameters);
        }
    }

    private final boolean enabled;
    private final boolean captureParameters;
    private final boolean captureCallSite;
    private final int maxEntries;
    private final long slowQueryThresholdMillis;
    private final int maxSqlLength;
    private final int maxParameterLength;
    private final int nPlusOneThreshold;

    private final Deque<CapturedStatement> buffer = new ArrayDeque<>();
    private final Object lock = new Object();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong totalCaptured = new AtomicLong();
    private final AtomicLong evicted = new AtomicLong();
    private final AtomicBoolean recording;
    private volatile boolean idleSuspended = false;
    private final Set<String> dataSourceNames = new ConcurrentSkipListSet<>();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile TraceIdProvider traceIdProvider = SqlTraceRecorder::mdcTraceId;
    private volatile SpanEnricher spanEnricher = SpanEnricher.NO_OP;

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
        this.enabled = enabled;
        this.recording = new AtomicBoolean(recording);
        this.captureParameters = captureParameters;
        this.captureCallSite = captureCallSite;
        this.maxEntries = Math.max(1, maxEntries);
        this.slowQueryThresholdMillis = Math.max(0, slowQueryThresholdMillis);
        this.maxSqlLength = Math.max(16, maxSqlLength);
        this.maxParameterLength = Math.max(8, maxParameterLength);
        this.nPlusOneThreshold = Math.max(2, nPlusOneThreshold);
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

    public boolean isSlow(long durationMillis) {
        return slowQueryThresholdMillis > 0 && durationMillis >= slowQueryThresholdMillis;
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

    /** Records one execution, truncating oversized SQL and evicting the oldest entry when full. */
    public void record(
            StatementType statementType,
            Category category,
            String sql,
            List<String> parameters,
            long durationMillis,
            boolean success,
            String errorMessage,
            Long affectedRows,
            int batchSize,
            String connectionId,
            String thread) {
        if (!enabled || idleSuspended || !recording.get() || BootUiJdbcCaptureGuard.isSuppressed()) {
            return;
        }
        CapturedStatement entry = new CapturedStatement(
                sequence.incrementAndGet(),
                System.currentTimeMillis(),
                truncate(sql, maxSqlLength),
                statementType == null ? StatementType.STATEMENT : statementType,
                category == null ? Category.OTHER : category,
                Math.max(0, durationMillis),
                success,
                errorMessage,
                affectedRows,
                Math.max(0, batchSize),
                connectionId,
                thread,
                resolveTraceId(),
                captureParameters ? List.copyOf(parameters == null ? List.of() : parameters) : List.of(),
                captureCallSite ? currentCallSite() : null);
        synchronized (lock) {
            buffer.addLast(entry);
            while (buffer.size() > maxEntries) {
                buffer.removeFirst();
                evicted.incrementAndGet();
            }
        }
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
        boolean nPlusOne = traceId != null && suspectsNPlusOne(traceId);
        enricher.onSqlStatement(nPlusOne);
    }

    private boolean suspectsNPlusOne(String traceId) {
        List<SqlTraceEntryDto> forTrace = recent().stream()
                .filter(entry -> traceId.equals(entry.traceId()))
                .map(entry -> toDto(entry, false))
                .toList();
        return SqlTraceGrouping.anySuspectedNPlusOne(forTrace, nPlusOneThreshold);
    }

    /** Returns the retained executions, most recent first. */
    public List<CapturedStatement> recent() {
        synchronized (lock) {
            List<CapturedStatement> snapshot = new ArrayList<>(buffer);
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
        long total = 0;
        long totalDuration = 0;
        long maxDuration = 0;
        long slow = 0;
        long failed = 0;
        long batches = 0;
        long selects = 0;
        long inserts = 0;
        long updates = 0;
        long deletes = 0;
        long others = 0;
        List<CapturedStatement> snapshot;
        synchronized (lock) {
            snapshot = new ArrayList<>(buffer);
        }
        for (CapturedStatement entry : snapshot) {
            total++;
            totalDuration += entry.durationMillis();
            maxDuration = Math.max(maxDuration, entry.durationMillis());
            if (isSlow(entry.durationMillis())) {
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
        double avg = total == 0 ? 0 : (double) totalDuration / total;
        return new SqlTraceStatsDto(
                total,
                totalDuration,
                maxDuration,
                avg,
                slow,
                failed,
                batches,
                selects,
                inserts,
                updates,
                deletes,
                others,
                evicted.get());
    }

    /**
     * Groups buffered executions by exact statement text, ordered by execution count descending,
     * and flags repeated {@code SELECT}s that look like an N+1 access pattern. Each group's call
     * sites are aggregated newest-first (bounded to {@link SqlTraceGrouping#MAX_CALL_SITES_PER_GROUP})
     * by walking the snapshot most-recent-first before aggregating.
     */
    public List<SqlTraceGroupDto> topStatements() {
        List<CapturedStatement> snapshot;
        synchronized (lock) {
            snapshot = new ArrayList<>(buffer);
        }
        java.util.Collections.reverse(snapshot);
        Map<String, Aggregate> byStatement = new LinkedHashMap<>();
        for (CapturedStatement entry : snapshot) {
            String sql = entry.sql() == null ? "" : entry.sql();
            Aggregate aggregate = byStatement.computeIfAbsent(sql, key -> new Aggregate(key, entry.category()));
            aggregate.executions++;
            aggregate.totalDuration += entry.durationMillis();
            aggregate.maxDuration = Math.max(aggregate.maxDuration, entry.durationMillis());
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
                        a.totalDuration,
                        a.maxDuration,
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
        List<SqlTraceEntryDto> entries =
                recent().stream().map(entry -> toDto(entry, exposeParameters)).toList();
        return new SqlTraceReport(
                true,
                null,
                isRecording(),
                isCaptureParameters(),
                getMaxEntries(),
                totalCaptured(),
                getSlowQueryThresholdMillis(),
                dataSourceNames(),
                stats(),
                entries,
                topStatements(),
                warnings(exposeParameters));
    }

    private List<String> warnings(boolean exposeParameters) {
        List<String> warnings = new ArrayList<>();
        if (!isRecording()) {
            warnings.add("Recording is paused. Resume it to capture new queries.");
        }
        if (exposeParameters) {
            warnings.add("Bound parameter values are captured in clear text. "
                    + "Set bootui.sql-trace.capture-parameters=false to hide them.");
        }
        if (evicted() > 0) {
            warnings.add("Older queries were dropped; the buffer keeps the most recent " + getMaxEntries() + ".");
        }
        return warnings;
    }

    private SqlTraceEntryDto toDto(CapturedStatement entry, boolean exposeParameters) {
        return new SqlTraceEntryDto(
                entry.id(),
                entry.timestamp(),
                entry.sql(),
                entry.statementType().name(),
                entry.category().name(),
                entry.durationMillis(),
                entry.success(),
                entry.errorMessage(),
                entry.affectedRows(),
                entry.batchSize(),
                entry.connectionId(),
                entry.thread(),
                isSlow(entry.durationMillis()),
                exposeParameters ? entry.parameters() : List.of(),
                entry.traceId(),
                entry.callSite());
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
        private long totalDuration;
        private long maxDuration;
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
