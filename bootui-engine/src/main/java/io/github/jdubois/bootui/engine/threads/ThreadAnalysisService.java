package io.github.jdubois.bootui.engine.threads;

import io.github.jdubois.bootui.core.dto.CapturedThreadDto;
import io.github.jdubois.bootui.core.dto.ThreadAnalysisReport;
import io.github.jdubois.bootui.core.dto.ThreadCaptureArtifact;
import io.github.jdubois.bootui.core.dto.ThreadCaptureList;
import io.github.jdubois.bootui.core.dto.ThreadCapturePage;
import io.github.jdubois.bootui.core.dto.ThreadCaptureSummary;
import io.github.jdubois.bootui.core.dto.ThreadLockReport;
import io.github.jdubois.bootui.core.dto.ThreadObservationReport;
import io.github.jdubois.bootui.engine.action.ActionBusyException;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.support.BootUiThreads;
import io.github.jdubois.bootui.engine.support.PagedList;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import io.github.jdubois.bootui.spi.LowFootprintSuspendable;
import io.github.jdubois.bootui.spi.McpPanelPolicy;
import io.github.jdubois.bootui.spi.MemoryOffloadable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Explicit, finite thread investigations. Only sanitized snapshots are retained; all acquisition
 * shares the live reader's fail-fast gate. There is no worker until an observation is requested.
 */
public final class ThreadAnalysisService implements MemoryOffloadable, LowFootprintSuspendable, AutoCloseable {
    private static final System.Logger LOG = System.getLogger(ThreadAnalysisService.class.getName());
    private static final String COMPONENT_ID = "thread-analysis";
    private static final Set<String> STATES =
            Set.of("NEW", "RUNNABLE", "BLOCKED", "WAITING", "TIMED_WAITING", "TERMINATED", "UNKNOWN");
    private final ThreadDumpService reader;
    private final ThreadCaptureSettings settings;
    private final RunIdentity run;
    private final ExposurePolicy exposure;
    private final McpPanelPolicy policy;
    private final BooleanSupplier globalHold;
    private final LongSupplier wallClock;
    private final LongSupplier nanoClock;
    private final Supplier<ScheduledExecutorService> schedulerFactory;
    private final LinkedHashMap<String, Entry> captures = new LinkedHashMap<>();
    private String exposureFingerprint;
    private long generation;
    private long retainedBytes;
    private int reservedCount;
    private long reservedBytes;
    private boolean suspended;
    private boolean closed;
    private Observation observation;
    private ScheduledExecutorService scheduler;

    public ThreadAnalysisService(
            ThreadDumpService reader,
            ThreadCaptureSettings settings,
            RunIdentity run,
            ExposurePolicy exposure,
            McpPanelPolicy policy,
            BooleanSupplier globalHold) {
        this(
                reader,
                settings,
                run,
                exposure,
                policy,
                globalHold,
                System::currentTimeMillis,
                System::nanoTime,
                ThreadAnalysisService::newScheduler);
    }

    ThreadAnalysisService(
            ThreadDumpService reader,
            ThreadCaptureSettings settings,
            RunIdentity run,
            ExposurePolicy exposure,
            McpPanelPolicy policy,
            BooleanSupplier globalHold,
            LongSupplier wallClock,
            LongSupplier nanoClock,
            Supplier<ScheduledExecutorService> schedulerFactory) {
        this.reader = reader;
        this.settings = settings;
        this.run = run;
        this.exposure = exposure;
        this.policy = policy;
        this.globalHold = globalHold;
        this.wallClock = wallClock;
        this.nanoClock = nanoClock;
        this.schedulerFactory = schedulerFactory;
    }

    public ThreadCaptureSummary capture() {
        return collect(null).summary();
    }

    public synchronized ThreadCaptureList list(Integer limit) {
        readAccess();
        List<ThreadCaptureSummary> summaries = captures.values().stream()
                .map(Entry::summary)
                .sorted(Comparator.comparingLong(ThreadCaptureSummary::capturedAt)
                        .reversed()
                        .thenComparing(ThreadCaptureSummary::id))
                .toList();
        var page = PagedList.from(summaries, 0, boundedLimit(limit));
        return new ThreadCaptureList(
                reader.available(),
                held(),
                retainedBytes,
                settings.maxCaptures(),
                settings.maxRetainedBytes(),
                settings.observationSamples(),
                settings.maxObservationSamples(),
                settings.observationIntervalMillis(),
                settings.captureDeadlineMillis(),
                observation == null ? null : observation.id,
                page.items(),
                page.page());
    }

    public synchronized ThreadAnalysisReport analysis(String id, Integer offset, Integer limit) {
        readAccess();
        validatePage(offset, limit);
        Entry entry = find(id);
        return ThreadContentionAnalysis.analyze(entry.summary(), entry.rows(), offset, boundedLimit(limit));
    }

    public synchronized ThreadCapturePage threads(
            String id, String query, String state, String sort, String direction, Integer offset, Integer limit) {
        return threads(id, query, state, sort, direction, offset, limit, true);
    }

    public synchronized ThreadCapturePage threads(
            String id,
            String query,
            String state,
            String sort,
            String direction,
            Integer offset,
            Integer limit,
            boolean includeBootUi) {
        readAccess();
        validatePage(offset, limit);
        if (query != null && query.length() > 256) {
            throw failure(400, "Thread filter is limited to 256 characters.");
        }
        String normalizedState = PagedList.normalize(state).toUpperCase(java.util.Locale.ROOT);
        if (!normalizedState.isEmpty() && !STATES.contains(normalizedState)) {
            throw failure(400, "Unknown thread state.");
        }
        Entry entry = find(id);
        Comparator<CapturedThreadDto> comparator = comparator(sort);
        if (direction != null && !direction.equals("asc") && !direction.equals("desc")) {
            throw failure(400, "Thread sort direction must be asc or desc.");
        }
        if ("desc".equals(direction)) {
            comparator = comparator.reversed();
        }
        List<CapturedThreadDto> sorted = entry.rows().stream()
                .sorted(comparator.thenComparingLong(row -> row.thread().id()))
                .toList();
        String q = PagedList.normalize(query);
        var page = PagedList.from(
                sorted,
                row -> (includeBootUi
                                || !entry.snapshot()
                                        .bootUiThreadIds()
                                        .contains(row.thread().id()))
                        && (normalizedState.isEmpty() || row.thread().state().equals(normalizedState))
                        && (PagedList.contains(row.thread().name(), q)
                                || PagedList.contains(row.family(), q)
                                || PagedList.contains(row.activity(), q)
                                || row.thread().stackTrace().stream().anyMatch(frame -> PagedList.contains(frame, q))),
                offset,
                boundedLimit(limit));
        return new ThreadCapturePage(
                entry.summary().id(),
                page.items(),
                page.page(),
                includeBootUi ? 0 : entry.snapshot().bootUiThreadIds().size());
    }

    public synchronized CapturedThreadDto thread(String observationId) {
        readAccess();
        String captureId = captureId(observationId);
        return detail(find(captureId), observationId);
    }

    public synchronized ThreadLockReport lock(String id, String lockId, Integer offset, Integer limit) {
        readAccess();
        validatePage(offset, limit);
        Entry entry = find(id);
        CapturedThreadDto selected = detail(entry, lockId);
        if (selected.thread().lockName() == null) {
            throw failure(404, "No waiting-lock evidence exists for this observation.");
        }
        Map<Long, CapturedThreadDto> byId = new LinkedHashMap<>();
        entry.rows().forEach(row -> byId.put(row.thread().id(), row));
        String identity = entry.snapshot().waitingLocks().get(selected.thread().id());
        var page = PagedList.from(
                entry.rows().stream()
                        .filter(row -> (identity == null
                                        ? row.observationId().equals(selected.observationId())
                                        : identity.equals(entry.snapshot()
                                                .waitingLocks()
                                                .get(row.thread().id())))
                                && java.util.Objects.equals(
                                        row.thread().lockOwnerId(),
                                        selected.thread().lockOwnerId()))
                        .map(row -> ThreadContentionAnalysis.wait(row, byId))
                        .toList(),
                offset,
                boundedLimit(limit));
        return new ThreadLockReport(
                entry.summary().id(),
                lockId,
                selected.thread().lockName(),
                selected.activity(),
                byId.get(selected.thread().lockOwnerId()),
                page.items(),
                page.page(),
                List.of(
                        "Capture-scoped identity/owner matches only; identity hashes may collide.",
                        "A missing owner is unknown evidence, not proof of garbage collection."));
    }

    public synchronized ThreadCaptureArtifact export(String id) {
        readAccess();
        actionAccess();
        Entry entry = find(id);
        return new ThreadCaptureArtifact("bootui-thread-capture", 1, entry.summary(), entry.rows());
    }

    public synchronized long clear() {
        readAccess();
        actionAccess();
        return offloadRetainedData();
    }

    public synchronized ThreadObservationReport observe() {
        return observe(null, null);
    }

    public synchronized ThreadObservationReport observe(Integer requestedSamples, Long requestedIntervalMillis) {
        return observe(requestedSamples, requestedIntervalMillis, true);
    }

    public synchronized ThreadObservationReport observe(
            Integer requestedSamples, Long requestedIntervalMillis, boolean includeBootUi) {
        readAccess();
        actionAccess();
        retainedAdmission();
        int samples = requestedSamples == null ? settings.observationSamples() : requestedSamples;
        long interval =
                requestedIntervalMillis == null ? settings.observationIntervalMillis() : requestedIntervalMillis;
        if (samples < 2
                || samples > 5
                || samples > settings.maxCaptures()
                || interval < 1000
                || interval > 30_000
                || interval * (samples - 1) + settings.captureDeadlineMillis() > 30_000) {
            throw failure(
                    400,
                    "Observation requires 2 to 5 samples, at least 1000 ms apart, with the final capture deadline within 30 seconds.");
        }
        if (samples * settings.maxCaptureBytes() + ThreadCaptureSettings.SESSION_BYTES > settings.maxRetainedBytes()) {
            throw failure(400, "Observation exceeds the configured retained-byte reservation.");
        }
        if (observation != null && (observation.running() || observation.inFlight)) {
            throw failure(409, "A thread observation is already running.");
        }
        reserve(samples);
        observation = new Observation(
                UUID.randomUUID().toString(), wallClock.getAsLong(), nanoClock.getAsLong(), samples, interval);
        try {
            scheduler = schedulerFactory.get();
            schedule(observation, 0);
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.DEBUG, "Could not schedule thread observation", ex);
            stopObservation("FAILED", "Thread observation could not be scheduled.");
            throw failure(503, "Thread observation could not be scheduled.");
        }
        return report(observation, 0, 200, includeBootUi);
    }

    public synchronized ThreadObservationReport observation(String id, Integer offset, Integer limit) {
        return observation(id, offset, limit, true);
    }

    public synchronized ThreadObservationReport observation(
            String id, Integer offset, Integer limit, boolean includeBootUi) {
        readAccess();
        validatePage(offset, limit);
        if (id != null) {
            validateId(id);
        }
        if (observation == null || (id != null && !id.equals(observation.id))) {
            throw failure(404, "No matching thread observation is retained.");
        }
        return report(observation, offset, boundedLimit(limit), includeBootUi);
    }

    public synchronized ThreadObservationReport cancel(String id) {
        return cancel(id, true);
    }

    public synchronized ThreadObservationReport cancel(String id, boolean includeBootUi) {
        readAccess();
        actionAccess();
        validateId(id);
        if (observation == null || !id.equals(observation.id)) {
            throw failure(404, "No matching thread observation is retained.");
        }
        stopObservation(
                observation.inFlight ? "CANCEL_REQUESTED" : "CANCELLED",
                "No further samples will be published; an in-flight JVM read may still finish.");
        return report(observation, 0, 200, includeBootUi);
    }

    private Entry collect(Observation session) {
        synchronized (this) {
            readAccess();
            actionAccess();
            retainedAdmission();
        }
        CaptureClaim claim = new CaptureClaim();
        ThreadSnapshot snapshot;
        try {
            snapshot = reader.acquire(() -> {
                synchronized (this) {
                    readAccess();
                    actionAccess();
                    retainedAdmission();
                    if (session != null && (observation != session || !session.running())) {
                        throw failure(409, "Thread observation is no longer running.");
                    }
                    if (session == null) {
                        reserve(1);
                        claim.reserved = true;
                    }
                    claim.generation = generation;
                }
            });
        } catch (ActionBusyException | ThreadAnalysisException ex) {
            releaseClaim(claim);
            throw ex;
        } catch (RuntimeException ex) {
            releaseClaim(claim);
            LOG.log(System.Logger.Level.DEBUG, "Explicit thread capture failed", ex);
            throw failure(503, "Thread information could not be captured.");
        }
        synchronized (this) {
            try {
                readAccess();
                actionAccess();
                retainedAdmission();
                if (session != null && (observation != session || !session.running())) {
                    throw failure(409, "Thread observation was stopped before publication.");
                }
                if (session != null && nanoClock.getAsLong() - session.startedNanos > 30_000_000_000L) {
                    stopObservation("FAILED", "Observation soft deadline reached before publication.");
                    throw failure(409, "Observation soft deadline reached before publication.");
                }
                if (claim.generation != generation) {
                    if (session != null) {
                        session.completed++;
                        session.cleared++;
                        consumeSessionReservation(session);
                        return null;
                    }
                    throw failure(409, "Capture invalidated by a policy, lifecycle, or memory change.");
                }
                String id = UUID.randomUUID().toString();
                List<CapturedThreadDto> rows = snapshot.threads().stream()
                        .map(row -> new CapturedThreadDto(
                                id + ":" + row.thread().id(),
                                row.thread(),
                                row.family(),
                                row.activity(),
                                row.stackTruncated(),
                                row.ownershipComplete(),
                                row.ownedLocks()))
                        .toList();
                long bytes = snapshot.retainedBytes();
                if (bytes > settings.maxCaptureBytes()) {
                    throw failure(409, "Capture and analysis metadata exceed the per-capture retained limit.");
                }
                var report = snapshot.report();
                ThreadCaptureSummary summary = new ThreadCaptureSummary(
                        id,
                        run.toDto(),
                        report.capturedAt(),
                        snapshot.completedAt(),
                        wallClock.getAsLong() + settings.ttlMillis(),
                        snapshot.durationNanos() / 1_000_000L,
                        bytes,
                        report.quality(),
                        report.stateCounts(),
                        report.deadlockDetected(),
                        report.deadlockedThreadIds());
                Entry entry = new Entry(summary, snapshot, rows, nanoClock.getAsLong());
                captures.put(id, entry);
                retainedBytes += bytes;
                if (session != null) {
                    session.captureIds.add(id);
                    session.completed++;
                    consumeSessionReservation(session);
                }
                return entry;
            } finally {
                releaseClaim(claim);
            }
        }
    }

    private synchronized void releaseClaim(CaptureClaim claim) {
        if (claim.reserved) {
            reservedCount--;
            reservedBytes -= settings.maxCaptureBytes();
            claim.reserved = false;
        }
    }

    private void reserve(int count) {
        long bytes = count * settings.maxCaptureBytes();
        int excessCount = captures.size() + reservedCount + count - settings.maxCaptures();
        long excessBytes = retainedBytes
                + reservedBytes
                + bytes
                + ThreadCaptureSettings.SESSION_BYTES
                - settings.maxRetainedBytes();
        List<String> evictions = new ArrayList<>();
        for (var entry : captures.entrySet()) {
            if (excessCount <= 0 && excessBytes <= 0) {
                break;
            }
            if (observation != null && observation.running() && observation.captureIds.contains(entry.getKey())) {
                continue;
            }
            evictions.add(entry.getKey());
            excessCount--;
            excessBytes -= entry.getValue().summary().retainedBytes();
        }
        if (excessCount > 0 || excessBytes > 0) {
            throw failure(409, "Thread capture capacity is reserved by active acquisitions or an observation.");
        }
        evictions.forEach(this::remove);
        reservedCount += count;
        reservedBytes += bytes;
    }

    private void consumeSessionReservation(Observation session) {
        if (session.remainingReservations > 0) {
            session.remainingReservations--;
            reservedCount--;
            reservedBytes -= settings.maxCaptureBytes();
        }
    }

    private void tick(Observation session) {
        synchronized (this) {
            if (!session.running() || observation != session) {
                return;
            }
            if (nanoClock.getAsLong() - session.startedNanos > 30_000_000_000L) {
                stopObservation("FAILED", "Observation soft deadline reached.");
                return;
            }
            session.inFlight = true;
        }
        try {
            collect(session);
        } catch (ActionBusyException ex) {
            synchronized (this) {
                if (session.running()) {
                    stopObservation("FAILED", "A scheduled sample was busy; no catch-up burst was started.");
                }
            }
        } catch (ThreadAnalysisException ex) {
            synchronized (this) {
                if (session.running()) {
                    stopObservation("FAILED", ex.getMessage());
                }
            }
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.DEBUG, "Thread observation failed", ex);
            synchronized (this) {
                if (session.running()) {
                    stopObservation("FAILED", "Thread observation failed.");
                }
            }
        } finally {
            synchronized (this) {
                session.inFlight = false;
                if (session.status.equals("CANCEL_REQUESTED")) {
                    session.status = "CANCELLED";
                }
            }
        }
        synchronized (this) {
            if (!session.running()) {
                return;
            }
            if (session.completed >= session.requestedSamples) {
                stopObservation("COMPLETED", session.cleared > 0 ? "Completed with cleared evidence." : null);
            } else {
                long target = session.startedNanos + session.completed * session.intervalMillis * 1_000_000L;
                long delay = target - nanoClock.getAsLong();
                if (delay < 0) {
                    stopObservation("FAILED", "A scheduled sample was missed; no catch-up burst was started.");
                } else {
                    try {
                        schedule(session, delay);
                    } catch (RuntimeException ex) {
                        LOG.log(System.Logger.Level.DEBUG, "Could not schedule next thread sample", ex);
                        stopObservation("FAILED", "The next thread sample could not be scheduled.");
                    }
                }
            }
        }
    }

    private void schedule(Observation session, long delayNanos) {
        session.future = scheduler.schedule(() -> tick(session), delayNanos, TimeUnit.NANOSECONDS);
    }

    private ThreadObservationReport report(Observation session, Integer offset, Integer limit, boolean includeBootUi) {
        List<ThreadComparison.Sample> samples = session.captureIds.stream()
                .map(captures::get)
                .filter(java.util.Objects::nonNull)
                .map(entry -> new ThreadComparison.Sample(entry.snapshot(), entry.rows()))
                .toList();
        Set<String> bootUiObservations = samples.stream()
                .flatMap(sample -> sample.rows().stream()
                        .filter(row -> sample.snapshot()
                                .bootUiThreadIds()
                                .contains(row.thread().id()))
                        .map(CapturedThreadDto::observationId))
                .collect(java.util.stream.Collectors.toSet());
        var comparisons = ThreadComparison.compare(samples);
        int hidden = includeBootUi
                ? 0
                : (int) comparisons.stream()
                        .filter(row -> bootUiObservations.contains(row.observationId()))
                        .count();
        var page = PagedList.from(
                comparisons, row -> includeBootUi || !bootUiObservations.contains(row.observationId()), offset, limit);
        return new ThreadObservationReport(
                session.id,
                session.status,
                session.startedAt,
                session.completedAt,
                session.requestedSamples,
                session.completed,
                session.cleared,
                session.captureIds,
                session.message,
                page.items(),
                page.page(),
                hidden);
    }

    private void stopObservation(String status, String message) {
        if (observation == null || !observation.running()) {
            return;
        }
        observation.status = status;
        observation.message = message;
        observation.completedAt = wallClock.getAsLong();
        while (observation.remainingReservations > 0) {
            consumeSessionReservation(observation);
        }
        if (observation.future != null) {
            observation.future.cancel(false);
            observation.future = null;
        }
        if (scheduler != null) {
            scheduler.shutdown();
            scheduler = null;
        }
    }

    private void readAccess() {
        if (closed) {
            throw failure(503, "Thread analysis is closed.");
        }
        if (!policy.isEnabled(BootUiPanels.THREADS)) {
            stopObservation("POLICY_STOPPED", "Threads panel was disabled.");
            dropEvidence();
            throw failure(403, policy.disabledReason(BootUiPanels.THREADS));
        }
        String fingerprint = exposure.valueExposure().name() + ":" + exposure.maskSecrets();
        if (exposureFingerprint != null && !exposureFingerprint.equals(fingerprint)) {
            stopObservation("POLICY_STOPPED", "Exposure policy changed; previous evidence was cleared.");
            dropEvidence();
        }
        exposureFingerprint = fingerprint;
        if (policy.isReadOnly(BootUiPanels.THREADS)) {
            stopObservation("POLICY_STOPPED", "Threads became read-only.");
        }
        long now = nanoClock.getAsLong();
        List<String> expired = captures.entrySet().stream()
                .filter(entry -> observation == null
                        || !observation.running()
                        || !observation.captureIds.contains(entry.getKey()))
                .filter(entry -> now - entry.getValue().retainedAtNanos() >= settings.ttlMillis() * 1_000_000L)
                .map(Map.Entry::getKey)
                .toList();
        expired.forEach(this::remove);
    }

    private void actionAccess() {
        if (policy.isReadOnly(BootUiPanels.THREADS)) {
            throw failure(403, policy.readOnlyReason(BootUiPanels.THREADS));
        }
    }

    private void retainedAdmission() {
        if (!reader.available()) {
            throw failure(503, "ThreadMXBean is not available on this JVM.");
        }
    }

    private boolean held() {
        return suspended || globalHold.getAsBoolean();
    }

    private Entry find(String id) {
        if (id == null) {
            return captures.values().stream()
                    .reduce((previous, next) -> next)
                    .orElseThrow(() -> failure(404, "No retained thread capture. Capture explicitly first."));
        }
        validateId(id);
        Entry entry = captures.get(id);
        if (entry == null) {
            throw failure(
                    409, "Thread capture is not retained; it may be expired or cleared. No recapture was started.");
        }
        return entry;
    }

    private static CapturedThreadDto detail(Entry entry, String observationId) {
        captureId(observationId);
        return entry.rows().stream()
                .filter(row -> row.observationId().equals(observationId))
                .findFirst()
                .orElseThrow(() -> failure(404, "Thread observation is not in this capture."));
    }

    private static String captureId(String observationId) {
        if (observationId == null
                || observationId.length() > 64
                || !observationId.matches("[0-9a-f-]{36}:[1-9][0-9]{0,18}")) {
            throw failure(400, "Invalid captured thread observation ID.");
        }
        String id = observationId.substring(0, 36);
        validateId(id);
        return id;
    }

    private static void validateId(String id) {
        if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw failure(400, "Invalid thread capture or observation ID.");
        }
    }

    private static void validatePage(Integer offset, Integer limit) {
        if (offset != null && offset < 0 || limit != null && (limit < 1 || limit > 1000)) {
            throw failure(400, "Thread analysis offset must be nonnegative and limit must be between 1 and 1000.");
        }
    }

    private static int boundedLimit(Integer limit) {
        validatePage(null, limit);
        return limit == null ? 200 : limit;
    }

    private static Comparator<CapturedThreadDto> comparator(String sort) {
        return switch (sort == null ? "id" : sort) {
            case "id" -> Comparator.comparingLong(row -> row.thread().id());
            case "name" ->
                Comparator.comparing(row -> row.thread().name(), Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "state" -> Comparator.comparing(row -> row.thread().state());
            case "family" -> Comparator.comparing(CapturedThreadDto::family);
            case "activity" -> Comparator.comparing(CapturedThreadDto::activity);
            case "cpu" ->
                Comparator.comparing(
                        row -> row.thread().cpuTimeMillis(), Comparator.nullsLast(Comparator.naturalOrder()));
            default -> throw failure(400, "Unknown thread sort. Use id, name, state, family, activity, or cpu.");
        };
    }

    private void remove(String id) {
        Entry removed = captures.remove(id);
        if (removed != null) {
            retainedBytes -= removed.summary().retainedBytes();
            if (observation != null && observation.captureIds.remove(id)) {
                observation.cleared++;
            }
        }
    }

    private long dropEvidence() {
        generation++;
        long count = captures.size();
        List.copyOf(captures.keySet()).forEach(this::remove);
        return count;
    }

    @Override
    public synchronized long offloadRetainedData() {
        return dropEvidence();
    }

    @Override
    public String offloadId() {
        return COMPONENT_ID;
    }

    @Override
    public String offloadLabel() {
        return "Thread analysis captures";
    }

    @Override
    public String suspendId() {
        return COMPONENT_ID;
    }

    @Override
    public String suspendLabel() {
        return "Pre-existing thread observations";
    }

    @Override
    public synchronized void suspendForLowFootprint() {
        // Reset pre-existing work on entry, without blocking new explicit, bounded investigations.
        suspended = true;
        stopObservation("POLICY_STOPPED", "Low-footprint mode stopped the finite observation.");
        dropEvidence();
    }

    @Override
    public synchronized void resumeFromLowFootprint() {
        suspended = false;
    }

    @Override
    public synchronized void close() {
        closed = true;
        stopObservation("CANCELLED", "Application context closed.");
        dropEvidence();
    }

    private static ScheduledExecutorService newScheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
                1, BootUiThreads.daemonFactory("bootui-thread-observation-", BootUiThreads.ENGINE_LOADER));
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return executor;
    }

    private static ThreadAnalysisException failure(int status, String message) {
        return new ThreadAnalysisException(status, message);
    }

    private record Entry(
            ThreadCaptureSummary summary,
            ThreadSnapshot snapshot,
            List<CapturedThreadDto> rows,
            long retainedAtNanos) {}

    private static final class CaptureClaim {
        private long generation;
        private boolean reserved;
    }

    private static final class Observation {
        private final String id;
        private final long startedAt;
        private final long startedNanos;
        private final int requestedSamples;
        private final long intervalMillis;
        private final List<String> captureIds = new ArrayList<>();
        private String status = "RUNNING";
        private Long completedAt;
        private String message;
        private int completed;
        private int cleared;
        private int remainingReservations;
        private boolean inFlight;
        private ScheduledFuture<?> future;

        private Observation(String id, long startedAt, long startedNanos, int requestedSamples, long intervalMillis) {
            this.id = id;
            this.startedAt = startedAt;
            this.startedNanos = startedNanos;
            this.requestedSamples = requestedSamples;
            this.intervalMillis = intervalMillis;
            this.remainingReservations = requestedSamples;
        }

        private boolean running() {
            return status.equals("RUNNING");
        }
    }
}
