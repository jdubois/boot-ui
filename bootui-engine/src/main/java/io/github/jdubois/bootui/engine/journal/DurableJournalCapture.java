package io.github.jdubois.bootui.engine.journal;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * A bounded, asynchronous capture of one runtime journal into a pluggable durable backend.
 *
 * <p>The listener always preserves the original {@link JournalEntry#sequence()}; capture never uses the legacy
 * activity sequencer or coordinator. Entries are converted to {@link JournalEntryDiskView} on admission and only that
 * masked form can enter the pending queue. Acknowledged entries cross the backend's documented crash-durability
 * boundary; pending rows are explicitly volatile process state.</p>
 */
public final class DurableJournalCapture implements JournalListener, AutoCloseable {

    private static final System.Logger log = System.getLogger(DurableJournalCapture.class.getName());
    private static final String RUNNING = "RUNNING";
    private static final String STOPPED = "STOPPED";

    private final RuntimeJournal journal;
    private final DurableJournalWriter store;
    private final DurableJournalScope scope;
    private final String durableRunId;
    private final String instanceId;
    private final DurableJournalCaptureSettings settings;
    private final JournalActivityFeed feed;
    private final List<DurableJournalDetailProvider> detailProviders;
    private final Duration retention;
    private final ScheduledExecutorService scheduler;
    private final Deque<PendingEntry> pendingQueue = new ArrayDeque<>();
    private final List<JournalEntry> deferredSeed = new ArrayList<>();
    private final Map<String, Map<String, Integer>> pendingSelects = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Map<String, Integer>> eldest) {
            return size() > JournalActivityCapture.MAX_PENDING_REQUESTS;
        }
    };
    private final Map<String, Long> pendingWorkEnds = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > JournalActivityCapture.MAX_PENDING_HANDOFFS;
        }
    };
    private final Object pendingLock = new Object();
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong quarantined = new AtomicLong();
    private final AtomicReference<String> lastError = new AtomicReference<>();
    private final AtomicBoolean flushRequested = new AtomicBoolean();
    private final AtomicBoolean heartbeatFailureLogged = new AtomicBoolean();
    private final AtomicBoolean metadataFailureLogged = new AtomicBoolean();
    private final AtomicLong durableWatermark = new AtomicLong();
    private final AtomicLong lastFlushAtEpochMillis = new AtomicLong();
    private volatile long clearedThrough;
    private int hotMaxEntries = Integer.MAX_VALUE;
    private Function<DurableJournalRunMetadata.Phase, DurableJournalRunMetadata> metadataSupplier;

    private int pending;
    private long pendingBytes;
    private List<PendingEntry> inFlight = List.of();
    private boolean accepting;
    private boolean subscribed;
    private boolean seedComplete;
    private boolean runReady;
    private boolean finalFlushCompleted;
    private long closedAtEpochMillis;
    private State state = State.STARTING;

    private enum State {
        STARTING,
        RUNNING,
        CLOSING,
        CLOSED
    }

    private DurableJournalCapture(
            RuntimeJournal journal,
            DurableJournalWriter store,
            DurableJournalScope scope,
            String instanceId,
            JournalActivityFeed feed,
            List<DurableJournalDetailProvider> detailProviders,
            DurableJournalCaptureSettings settings) {
        this(journal, store, scope, instanceId, feed, detailProviders, settings, null);
    }

    private DurableJournalCapture(
            RuntimeJournal journal,
            DurableJournalWriter store,
            DurableJournalScope scope,
            String instanceId,
            JournalActivityFeed feed,
            List<DurableJournalDetailProvider> detailProviders,
            DurableJournalCaptureSettings settings,
            Duration retention) {
        this.journal = Objects.requireNonNull(journal, "journal must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.scope = Objects.requireNonNull(scope, "scope must not be null");
        this.durableRunId = scope.run();
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId must not be null");
        this.feed = Objects.requireNonNull(feed, "feed must not be null");
        this.detailProviders = List.copyOf(Objects.requireNonNull(detailProviders, "detailProviders must not be null"));
        if (this.detailProviders.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("detailProviders must not contain null");
        }
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
        this.retention = retention;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bootui-durable-journal");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Starts capture with the default bounded queue and cadence. */
    public static DurableJournalCapture start(
            RuntimeJournal journal, DurableJournalScope scope, DurableJournalWriter store) {
        return start(
                journal,
                new DurableJournalIdentity(
                        scope.namespace(),
                        scope.application(),
                        scope.run(),
                        DurableJournalIds.processId(),
                        RunIdentity.instanceId()),
                store);
    }

    public static DurableJournalCapture start(
            RuntimeJournal journal, DurableJournalIdentity identity, DurableJournalWriter store) {
        return start(
                journal,
                identity,
                store,
                new JournalActivityFeed(0, 2, null),
                DurableJournalCaptureSettings.defaults());
    }

    /**
     * Starts capture by atomically obtaining the journal's retained snapshot and future subscription. A startup
     * backend outage is recorded and retried without blocking registration.
     */
    public static DurableJournalCapture start(
            RuntimeJournal journal,
            DurableJournalScope scope,
            DurableJournalWriter store,
            DurableJournalCaptureSettings settings) {
        return start(
                journal,
                new DurableJournalIdentity(
                        scope.namespace(),
                        scope.application(),
                        scope.run(),
                        DurableJournalIds.processId(),
                        RunIdentity.instanceId()),
                store,
                new JournalActivityFeed(0, 2, null),
                settings);
    }

    public static DurableJournalCapture start(
            RuntimeJournal journal,
            DurableJournalIdentity identity,
            DurableJournalWriter store,
            JournalActivityFeed feed,
            DurableJournalCaptureSettings settings) {
        return start(journal, identity, store, feed, settings, List.of());
    }

    /**
     * Starts capture with optional post-ack detail providers. Event acknowledgement and checkpoint progression stay
     * independent from a detail provider or upsert failure.
     */
    public static DurableJournalCapture start(
            RuntimeJournal journal,
            DurableJournalIdentity identity,
            DurableJournalWriter store,
            JournalActivityFeed feed,
            DurableJournalCaptureSettings settings,
            List<DurableJournalDetailProvider> detailProviders) {
        return start(journal, identity, store, detailProviders, feed, settings);
    }

    public static DurableJournalCapture start(
            RuntimeJournal journal,
            DurableJournalIdentity identity,
            DurableJournalWriter store,
            List<DurableJournalDetailProvider> detailProviders,
            JournalActivityFeed feed,
            DurableJournalCaptureSettings settings) {
        Objects.requireNonNull(identity, "identity must not be null");
        DurableJournalCapture capture = new DurableJournalCapture(
                journal, store, identity.scope(), identity.instanceId(), feed, detailProviders, settings);
        try {
            capture.begin();
            return capture;
        } catch (RuntimeException | Error ex) {
            capture.abortStart();
            throw ex;
        }
    }

    /** Starts capture from startup settings, including periodic retention pruning. */
    public static DurableJournalCapture start(
            RuntimeJournal journal,
            DurableJournalSettings durable,
            DurableJournalWriter store,
            JournalActivityFeed feed) {
        return start(journal, durable, store, feed, DurableJournalCaptureSettings.of(durable));
    }

    /** Starts capture from the adapter-neutral startup identity and queue bounds. */
    public static DurableJournalCapture start(
            RuntimeJournal journal,
            DurableJournalSettings durable,
            DurableJournalWriter store,
            JournalActivityFeed feed,
            DurableJournalCaptureSettings settings) {
        return start(journal, durable, store, feed, settings, List.of());
    }

    public static DurableJournalCapture start(
            RuntimeJournal journal,
            DurableJournalSettings durable,
            DurableJournalWriter store,
            JournalActivityFeed feed,
            DurableJournalCaptureSettings settings,
            List<DurableJournalDetailProvider> detailProviders) {
        return start(journal, durable, store, feed, settings, detailProviders, null);
    }

    public static DurableJournalCapture start(
            RuntimeJournal journal,
            DurableJournalSettings durable,
            DurableJournalWriter store,
            JournalActivityFeed feed,
            DurableJournalCaptureSettings settings,
            List<DurableJournalDetailProvider> detailProviders,
            Function<DurableJournalRunMetadata.Phase, DurableJournalRunMetadata> metadataSupplier) {
        Objects.requireNonNull(durable, "durable settings must not be null");
        DurableJournalCapture capture = new DurableJournalCapture(
                journal,
                store,
                durable.scope(),
                durable.instanceId(),
                feed,
                detailProviders,
                settings,
                durable.retention());
        capture.hotMaxEntries = durable.hotMaxEntries();
        capture.metadataSupplier = metadataSupplier;
        try {
            capture.begin();
            return capture;
        } catch (RuntimeException | Error ex) {
            capture.abortStart();
            throw ex;
        }
    }

    private void begin() {
        List<JournalEntry> retained;
        synchronized (pendingLock) {
            accepting = true;
        }
        retained = journal.snapshotAndSubscribe(this);
        subscribed = true;
        synchronized (pendingLock) {
            List<JournalEntry> seed = new ArrayList<>(retained.size() + deferredSeed.size());
            seed.addAll(retained);
            seed.addAll(deferredSeed);
            seed.sort(Comparator.comparingLong(JournalEntry::sequence));
            accepted.addAndGet(retained.size());
            for (DurableJournalEvent event : project(seed)) {
                enqueue(event);
            }
            deferredSeed.clear();
            seedComplete = true;
            state = State.RUNNING;
            if (thresholdReached()) {
                requestFlush();
            }
        }
        scheduleTasks();
        scheduler.execute(() -> writeHeartbeat(RUNNING));
    }

    private void scheduleTasks() {
        long flushMillis = Math.max(1, settings.flushInterval().toMillis());
        long heartbeatMillis = Math.max(1, settings.heartbeatInterval().toMillis());
        scheduler.scheduleWithFixedDelay(this::safeFlush, flushMillis, flushMillis, TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(this::safeHeartbeat, heartbeatMillis, heartbeatMillis, TimeUnit.MILLISECONDS);
        if (retention != null) {
            long pruneMillis = Math.max(TimeUnit.MINUTES.toMillis(1), saturatedMultiply(flushMillis, 10));
            scheduler.scheduleWithFixedDelay(this::safePrune, pruneMillis, pruneMillis, TimeUnit.MILLISECONDS);
        }
    }

    private void abortStart() {
        synchronized (pendingLock) {
            accepting = false;
            state = State.CLOSED;
            closedAtEpochMillis = System.currentTimeMillis();
        }
        if (subscribed) {
            journal.removeListener(this);
            subscribed = false;
        }
        scheduler.shutdownNow();
    }

    @Override
    public void onEntries(List<JournalEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        accept(entries);
    }

    private void accept(List<JournalEntry> entries) {
        accepted.addAndGet(entries.size());
        boolean threshold = false;
        synchronized (pendingLock) {
            if (!accepting || state == State.CLOSED || state == State.CLOSING) {
                dropped.addAndGet(entries.size());
                return;
            }
            if (!seedComplete) {
                deferredSeed.addAll(entries);
                return;
            }
            for (DurableJournalEvent durable : project(entries)) {
                enqueue(durable);
            }
            threshold = thresholdReached();
        }
        if (threshold) {
            requestFlush();
        }
    }

    private List<DurableJournalEvent> project(List<JournalEntry> entries) {
        if (entries.isEmpty()) {
            return List.of();
        }
        try {
            return feed.durableEvents(entries, this::durableEventId, pendingSelects, pendingWorkEnds, ignored -> {});
        } catch (RuntimeException | LinkageError ex) {
            quarantine(ex, "Cannot project durable journal entry");
            quarantined.addAndGet(Math.max(0, entries.size() - 1));
            for (JournalEntry entry : entries) {
                journal.durableCaptureLost(entry);
            }
            return List.of();
        }
    }

    private String durableEventId(JournalEntry entry) {
        return durableRunId + "-" + entry.sequence();
    }

    @Override
    public void onClear() {
        long sequence = journal.status().lastSequence();
        synchronized (pendingLock) {
            clearedThrough = Math.max(clearedThrough, sequence);
            dropCleared(pendingQueue);
            dropCleared(inFlight);
            deferredSeed.removeIf(entry -> entry.sequence() <= clearedThrough);
            pendingSelects.clear();
            pendingWorkEnds.clear();
            // A clear consumes the sequence even when it deletes never-flushed rows. Checkpoints are monotonic
            // progress and may consequently remain ahead of the currently retained durable rows.
            durableWatermark.accumulateAndGet(clearedThrough, Math::max);
        }
        try {
            scheduler.execute(() -> clearRunThrough(sequence));
        } catch (RuntimeException ex) {
            clearWarning(ex, sequence);
        }
    }

    private void enqueue(DurableJournalEvent event) {
        if (event.entry().sequence() <= clearedThrough) {
            return;
        }
        try {
            JournalEntry diskEntry = JournalEntryDiskView.of(event.entry());
            ActivityEntryDto activity = JournalEntryDiskView.activity(diskEntry, event.activity());
            PendingEntry pendingEntry = new PendingEntry(
                    new DurableJournalEvent(diskEntry, activity),
                    JournalEntryCodec.encode(diskEntry).length + projectionBytes(activity));
            pendingQueue.addLast(pendingEntry);
            pending++;
            pendingBytes += pendingEntry.bytes();
        } catch (RuntimeException ex) {
            quarantine(ex, "Cannot encode durable journal entry");
            journal.durableCaptureLost(event.entry());
        }
        trimToBounds();
    }

    private void prependFailed(List<PendingEntry> failed) {
        for (int index = failed.size() - 1; index >= 0; index--) {
            pendingQueue.addFirst(failed.get(index));
        }
        trimToBounds();
    }

    private void trimToBounds() {
        reconcilePending();
        while (pending > settings.maxPendingEntries() || pendingBytes > settings.maxPendingBytes()) {
            PendingEntry first = pendingQueue.pollFirst();
            if (first == null) {
                return;
            }
            pending--;
            pendingBytes -= first.bytes();
            dropped.incrementAndGet();
            journal.durableCaptureLost(first.event().entry());
        }
    }

    private boolean thresholdReached() {
        return pendingQueue.size() >= settings.flushThresholdEntries()
                || pendingQueue.stream().mapToLong(PendingEntry::bytes).sum() >= settings.flushThresholdBytes();
    }

    private void requestFlush() {
        if (!flushRequested.compareAndSet(false, true)) {
            return;
        }
        try {
            scheduler.execute(this::requestedFlush);
        } catch (RuntimeException ex) {
            flushRequested.set(false);
            throw ex;
        }
    }

    private void requestedFlush() {
        flushRequested.set(false);
        safeFlush();
    }

    /** Requests one asynchronous flush on the writer's serialized worker. */
    public void flushNow() {
        requestFlush();
    }

    /** Runs one retention pass synchronously, for focused tests and explicit administrative tooling. */
    void pruneNow() {
        safePrune();
    }

    private void safeFlush() {
        try {
            for (int chunk = 0; chunk < 8; chunk++) {
                int before;
                synchronized (pendingLock) {
                    reconcilePending();
                    before = pending;
                }
                if (before == 0) {
                    return;
                }
                flushOneChunk();
                synchronized (pendingLock) {
                    reconcilePending();
                    if (pending >= before) {
                        return;
                    }
                }
            }
        } catch (Throwable ex) {
            error(ex, "Unexpected durable journal flush failure");
        }
    }

    private void flushOneChunk() {
        List<PendingEntry> chunk;
        synchronized (pendingLock) {
            if (scheduler.isShutdown() || !seedComplete || pendingQueue.isEmpty()) {
                return;
            }
            chunk = new ArrayList<>(Math.min(settings.maxFlushEntries(), pendingQueue.size()));
            for (int count = 0; count < settings.maxFlushEntries() && !pendingQueue.isEmpty(); count++) {
                chunk.add(pendingQueue.removeFirst());
            }
            inFlight = new ArrayList<>(chunk);
        }
        lastFlushAtEpochMillis.set(System.currentTimeMillis());
        if (!ensureRun(RUNNING)) {
            requeue(chunk);
            return;
        }

        List<DurableJournalEvent> entries =
                new ArrayList<>(chunk.stream().map(PendingEntry::event).toList());
        try {
            while (!entries.isEmpty()) {
                try {
                    store.appendEvents(scope, entries);
                    for (DurableJournalEvent event : entries) {
                        durableWatermark.accumulateAndGet(event.entry().sequence(), Math::max);
                    }
                    persistDetails(entries);
                    completed(chunk, entries);
                    checkpoint();
                    journal.offloadDurableThrough(durableWatermark.get(), hotMaxEntries);
                    return;
                } catch (DurableJournalConflictException conflict) {
                    DurableJournalEvent poison = removeConflict(entries, conflict.journalSequence());
                    if (poison == null) {
                        throw conflict;
                    }
                    PendingEntry rejected = removePending(chunk, poison);
                    if (rejected != null) {
                        quarantine(
                                conflict,
                                "Conflicting durable journal event at sequence " + conflict.journalSequence());
                        journal.durableCaptureLost(poison.entry());
                    }
                }
            }
            completed(chunk, List.of());
        } catch (RuntimeException ex) {
            error(ex, "Durable journal flush failed; the chunk will be retried");
            requeue(chunk);
        }
    }

    private void persistDetails(List<DurableJournalEvent> events) {
        if (detailProviders.isEmpty() || events.isEmpty()) {
            return;
        }
        List<JournalEntry> entries =
                List.copyOf(events.stream().map(DurableJournalEvent::entry).toList());
        Set<Long> sequences =
                Set.copyOf(entries.stream().map(JournalEntry::sequence).toList());
        for (DurableJournalDetailProvider provider : detailProviders) {
            List<DurableJournalDetail> details;
            try {
                List<DurableJournalDetail> provided = provider.details(scope, entries);
                if (provided == null) {
                    throw new IllegalStateException("detail provider returned null");
                }
                details = List.copyOf(provided);
            } catch (RuntimeException | LinkageError ex) {
                detailWarning(ex, provider, "cannot derive details");
                continue;
            }
            for (DurableJournalDetail detail : details) {
                try {
                    if (detail == null
                            || !scope.equals(detail.scope())
                            || !sequences.contains(detail.journalSequence())) {
                        throw new IllegalStateException(
                                "detail provider returned a detail outside the acknowledged batch");
                    }
                    store.upsertDetail(detail);
                } catch (RuntimeException | LinkageError ex) {
                    detailWarning(ex, provider, "cannot persist detail");
                }
            }
        }
    }

    private void detailWarning(Throwable ex, DurableJournalDetailProvider provider, String operation) {
        String message =
                "Durable journal detail provider " + provider.getClass().getSimpleName() + " " + operation
                        + "; the owning event append is already durable";
        error(ex, message);
        log.log(Level.WARNING, message, ex);
    }

    private void completed(List<PendingEntry> chunk, List<DurableJournalEvent> durableEntries) {
        List<Long> durable = durableEntries.stream()
                .map(DurableJournalEvent::entry)
                .map(JournalEntry::sequence)
                .toList();
        synchronized (pendingLock) {
            for (PendingEntry entry : chunk) {
                if (durable.contains(entry.event().entry().sequence()) && inFlight.remove(entry)) {
                    pending--;
                    pendingBytes -= entry.bytes();
                }
            }
            inFlight = List.of();
            reconcilePending();
        }
    }

    private PendingEntry removePending(List<PendingEntry> chunk, DurableJournalEvent poison) {
        synchronized (pendingLock) {
            PendingEntry found = null;
            for (int index = 0; index < chunk.size(); index++) {
                if (chunk.get(index).event().entry().sequence()
                        == poison.entry().sequence()) {
                    found = chunk.remove(index);
                    if (inFlight.remove(found)) {
                        pending--;
                        pendingBytes -= found.bytes();
                    }
                    break;
                }
            }
            return found;
        }
    }

    private static DurableJournalEvent removeConflict(List<DurableJournalEvent> entries, long sequence) {
        for (int index = 0; index < entries.size(); index++) {
            if (entries.get(index).entry().sequence() == sequence) {
                return entries.remove(index);
            }
        }
        return null;
    }

    private static int projectionBytes(ActivityEntryDto activity) {
        if (activity == null) {
            return 0;
        }
        return 256
                + chars(activity.id())
                + chars(activity.type())
                + chars(activity.severity())
                + chars(activity.summary())
                + chars(activity.detail())
                + chars(activity.correlationId())
                + chars(activity.method())
                + chars(activity.path())
                + chars(activity.thread())
                + chars(activity.parentId())
                + chars(activity.securedPrincipal())
                + activity.badges().stream()
                        .mapToInt(DurableJournalCapture::chars)
                        .sum()
                + chars(activity.exceptionGroupId());
    }

    private static int chars(String value) {
        return value == null ? 0 : Math.multiplyExact(value.length(), 2);
    }

    private void requeue(List<PendingEntry> chunk) {
        synchronized (pendingLock) {
            List<PendingEntry> retryable = new ArrayList<>();
            for (PendingEntry entry : chunk) {
                if (entry.event().entry().sequence() > clearedThrough) {
                    retryable.add(entry);
                }
            }
            inFlight = List.of();
            prependFailed(retryable);
            reconcilePending();
        }
    }

    private void reconcilePending() {
        long bytes = 0;
        for (PendingEntry entry : pendingQueue) {
            bytes += entry.bytes();
        }
        for (PendingEntry entry : inFlight) {
            bytes += entry.bytes();
        }
        pending = pendingQueue.size() + inFlight.size();
        pendingBytes = bytes;
    }

    private void dropCleared(Iterable<PendingEntry> entries) {
        var iterator = entries.iterator();
        while (iterator.hasNext()) {
            PendingEntry entry = iterator.next();
            if (entry.event().entry().sequence() <= clearedThrough) {
                iterator.remove();
                pending--;
                pendingBytes -= entry.bytes();
            }
        }
    }

    private void clearRunThrough(long sequence) {
        RuntimeException failure = null;
        try {
            store.clearRunThrough(scope, sequence);
        } catch (RuntimeException ex) {
            failure = ex;
        }
        checkpoint();
        requestFlush();
        if (failure != null) {
            clearWarning(failure, sequence);
        }
    }

    private void clearWarning(Throwable ex, long sequence) {
        String message = "Durable journal clear through sequence " + sequence
                + " failed; retained durable history is unchanged and volatile rows were not restored";
        error(ex, message);
        log.log(Level.WARNING, message, ex);
    }

    private void safePrune() {
        try {
            store.prune(scope, System.currentTimeMillis() - retention.toMillis());
        } catch (Throwable ex) {
            error(ex, "Durable journal retention failed; the next scheduled pass will retry");
            log.log(Level.WARNING, "Durable journal retention failed; the next scheduled pass will retry", ex);
        }
    }

    private static long saturatedMultiply(long value, int multiplier) {
        if (value > Long.MAX_VALUE / multiplier) {
            return Long.MAX_VALUE;
        }
        return value * multiplier;
    }

    private boolean ensureRun(String status) {
        if (runReady && RUNNING.equals(status)) {
            return true;
        }
        return writeHeartbeat(status);
    }

    private boolean writeHeartbeat(String status) {
        DurableJournalRunMetadata runMetadata = null;
        try {
            long watermark = durableWatermark.get();
            try {
                runMetadata = metadata(status);
                metadataFailureLogged.set(false);
            } catch (RuntimeException | LinkageError ex) {
                String message = "Durable journal metadata snapshot failed; heartbeat continues without metadata";
                error(ex, message);
                if (metadataFailureLogged.compareAndSet(false, true)) {
                    log.log(Level.WARNING, message, ex);
                }
            }
            store.upsertRun(
                    runMetadata == null
                            ? new DurableJournalRun(
                                    scope,
                                    journal.run().startedAtEpochMillis(),
                                    System.currentTimeMillis(),
                                    status,
                                    null,
                                    watermark,
                                    instanceId)
                            : new DurableJournalRun(
                                    scope,
                                    journal.run().startedAtEpochMillis(),
                                    System.currentTimeMillis(),
                                    status,
                                    null,
                                    watermark,
                                    instanceId,
                                    runMetadata));
            runReady = true;
            heartbeatFailureLogged.set(false);
            return true;
        } catch (RuntimeException ex) {
            runReady = false;
            String message = "Durable journal run heartbeat failed";
            error(ex, message);
            if (heartbeatFailureLogged.compareAndSet(false, true)) {
                log.log(Level.WARNING, message, ex);
            }
            return false;
        }
    }

    private DurableJournalRunMetadata metadata(String status) {
        if (metadataSupplier == null) {
            return null;
        }
        DurableJournalRunMetadata.Phase phase = STOPPED.equals(status)
                ? DurableJournalRunMetadata.Phase.END
                : durableWatermark.get() == 0
                        ? DurableJournalRunMetadata.Phase.START
                        : DurableJournalRunMetadata.Phase.CHECKPOINT;
        return metadataSupplier.apply(phase).withCaptureLoss(dropped.get(), quarantined.get(), clearedThrough);
    }

    private void checkpoint() {
        try {
            store.checkpoint(scope, durableWatermark.get());
        } catch (RuntimeException ex) {
            error(ex, "Durable journal checkpoint failed; heartbeat will retry it");
        }
        writeHeartbeat(RUNNING);
    }

    private void heartbeat() {
        boolean hasPending;
        synchronized (pendingLock) {
            if (state == State.CLOSED || state == State.CLOSING) {
                return;
            }
            reconcilePending();
            hasPending = pending > 0;
        }
        writeHeartbeat(RUNNING);
        if (hasPending) {
            requestFlush();
        }
    }

    private void safeHeartbeat() {
        try {
            heartbeat();
        } catch (Throwable ex) {
            error(ex, "Unexpected durable journal heartbeat failure; the next heartbeat will retry");
            log.log(Level.WARNING, "Unexpected durable journal heartbeat failure; the next heartbeat will retry", ex);
            try {
                requestFlush();
            } catch (RuntimeException | LinkageError flushEx) {
                error(flushEx, "Cannot re-arm the durable journal flush after a heartbeat failure");
            }
        }
    }

    private void quarantine(Throwable ex, String message) {
        quarantined.incrementAndGet();
        error(ex, message);
        log.log(Level.WARNING, message, ex);
    }

    private void error(Throwable ex, String message) {
        String detail = ex == null || ex.getMessage() == null ? "" : ": " + ex.getMessage();
        lastError.set(message + detail);
    }

    public DurableJournalCaptureStatus status() {
        synchronized (pendingLock) {
            reconcilePending();
            return new DurableJournalCaptureStatus(
                    state.name(),
                    accepted.get(),
                    pending,
                    pendingBytes,
                    durableWatermark.get(),
                    dropped.get(),
                    quarantined.get(),
                    lastError.get(),
                    lastFlushAtEpochMillis.get(),
                    closedAtEpochMillis,
                    finalFlushCompleted);
        }
    }

    int pendingProjectionOwners() {
        synchronized (pendingLock) {
            return pendingSelects.size() + pendingWorkEnds.size();
        }
    }

    @Override
    public void onClose() {
        close();
    }

    @Override
    public void close() {
        synchronized (pendingLock) {
            if (state == State.CLOSED || state == State.CLOSING) {
                return;
            }
            accepting = false;
            state = State.CLOSING;
        }
        journal.removeListener(this);
        subscribed = false;

        CountDownLatch finished = new CountDownLatch(1);
        long timeoutNanos = settings.closeTimeout().toNanos();
        long deadline = System.nanoTime() + timeoutNanos;
        scheduler.execute(() -> {
            try {
                while (System.nanoTime() < deadline) {
                    int before;
                    synchronized (pendingLock) {
                        reconcilePending();
                        before = pending;
                    }
                    flushOneChunk();
                    synchronized (pendingLock) {
                        if (pending == 0) {
                            break;
                        }
                        if (pending >= before) {
                            sleepForRetry(deadline);
                        }
                    }
                }
                synchronized (pendingLock) {
                    reconcilePending();
                    finalFlushCompleted = pending == 0;
                }
                writeHeartbeat(STOPPED);
            } finally {
                finished.countDown();
            }
        });
        await(finished, settings.closeTimeout());
        scheduler.shutdownNow();

        synchronized (pendingLock) {
            reconcilePending();
            finalFlushCompleted = finalFlushCompleted && pending == 0;
            closedAtEpochMillis = System.currentTimeMillis();
            state = State.CLOSED;
        }
    }

    private static void sleepForRetry(long deadline) {
        long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (remainingMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(Math.min(25, remainingMillis));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(CountDownLatch finished, Duration timeout) {
        try {
            finished.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private record PendingEntry(DurableJournalEvent event, int bytes) {}
}
