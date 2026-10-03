package io.github.jdubois.bootui.engine.journal;

import io.github.jdubois.bootui.engine.correlation.CorrelationSource;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.correlation.ThreadKinds;
import io.github.jdubois.bootui.engine.resources.GcEventSource;
import io.github.jdubois.bootui.engine.resources.ResourceSampler;
import io.github.jdubois.bootui.engine.resources.ResourceSettings;
import io.github.jdubois.bootui.engine.resources.ResourceTrack;
import io.github.jdubois.bootui.engine.sqltrace.SqlShapes;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.github.jdubois.bootui.spi.CorrelationContextProvider;
import io.github.jdubois.bootui.spi.MemoryOffloadable;
import io.github.jdubois.bootui.spi.ThreadKindClassifier;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * The runtime journal of one application run ({@code docs/PLAN-v2.md} §5.2): every runtime event recorded once, in a
 * push-based, bounded, in-memory structure.
 *
 * <p>Recorders {@link #offer} events on the application thread. Offering only checks the source, then puts the event
 * in a bounded queue without blocking. The last {@link RuntimeJournalSettings#reservedQueueSharePercent()} of the queue
 * admits only failed or slow events, so a burst drops routine events first, and every dropped event is counted per
 * source. One daemon thread, {@value #DISPATCHER_THREAD}, drains the queue in batches of up to {@value #BATCH_SIZE}: it
 * gives each event its sequence number, retains it in the {@link EvidenceRing}, and hands the batch to the listeners,
 * such as the incremental aggregates, which therefore see every accepted event, including those later evicted.</p>
 *
 * <p>BootUI's own work is never recorded: work on its threads, named {@value #BOOTUI_THREAD_PREFIX}…, and work
 * its own requests do, which the adapters mark with {@link CorrelationContext#BOOTUI}.</p>
 *
 * <p>Sequence numbers are unique within the run, and each event's id joins the run id and its sequence, so a restart
 * never collides with an earlier run.</p>
 */
public final class RuntimeJournal implements RuntimeEventSink, AutoCloseable, MemoryOffloadable {

    /** The dispatcher thread's name. */
    public static final String DISPATCHER_THREAD = "bootui-journal-dispatch";

    /** The name prefix of BootUI's own threads, whose work is never recorded. */
    public static final String BOOTUI_THREAD_PREFIX = "bootui-";

    /** The most events the dispatcher processes at once. */
    public static final int BATCH_SIZE = 512;

    /** The dictionary's share of the byte bound, beyond which it interns nothing more. */
    static final int DICTIONARY_BYTES_PERCENT = 25;

    /** The most strings one run's dictionary interns. */
    static final int DICTIONARY_MAX_ENTRIES = 100_000;

    private static final System.Logger log = System.getLogger(RuntimeJournal.class.getName());

    private final RuntimeJournalSettings settings;
    private final RunIdentity run;
    private final ArrayBlockingQueue<Queued> queue;
    private final int routineQueueLimit;
    private final JournalDictionary dictionary;
    private final EvidenceRing ring;
    private final List<JournalListener> listeners = new CopyOnWriteArrayList<>();
    private final LongAdder[] accepted = adders();
    private final LongAdder[] dropped = adders();
    private final LongAdder acceptedTotal = new LongAdder();
    private final AtomicLong processed = new AtomicLong();
    private final AtomicLong lastSequence = new AtomicLong();
    private final LongAdder listenerFailures = new LongAdder();
    private final CorrelationSource correlation = new CorrelationSource();
    private final ThreadKinds threadKinds = new ThreadKinds();
    private final Thread dispatcher;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Serializes processing a batch with clearing the recording, so a batch is either processed before the clear, and
     * cleared with everything else, or not at all.
     */
    private final Object processing = new Object();

    /** How many times the recording was cleared, so the dispatcher drops an event it took before a clear. */
    private volatile long clears;
    // Counted once a clear is done, so a reader that sees it also sees the emptied ring.
    private volatile long clearsCompleted;

    private GcEventSource gcSource;
    private ResourceSampler resourceSampler;
    private volatile boolean running;

    /** A journal whose dispatcher starts now, when {@code settings} enable it. */
    public RuntimeJournal(RuntimeJournalSettings settings, RunIdentity run) {
        this(settings, run, true);
    }

    RuntimeJournal(RuntimeJournalSettings settings, RunIdentity run, boolean startDispatcher) {
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
        this.run = Objects.requireNonNull(run, "run must not be null");
        this.queue = new ArrayBlockingQueue<>(settings.enabled() ? settings.queueCapacity() : 1);
        this.routineQueueLimit = settings.routineQueueLimit();
        this.dictionary =
                new JournalDictionary(DICTIONARY_MAX_ENTRIES, settings.maxBytes() * DICTIONARY_BYTES_PERCENT / 100);
        this.ring = new EvidenceRing(
                settings.maxEvents(), settings.maxBytes(), settings.reservedSharePercent(), dictionary::bytes);
        if (settings.enabled() && startDispatcher) {
            running = true;
            dispatcher = new Thread(this::dispatchLoop, DISPATCHER_THREAD);
            dispatcher.setDaemon(true);
            dispatcher.start();
        } else {
            dispatcher = null;
        }
    }

    /**
     * Offers {@code event} without blocking. Never throws: an event the journal cannot take is dropped and counted.
     */
    @Override
    public boolean offer(RuntimeEvent event) {
        if (event == null || !settings.records(event.source()) || isBootUiWork()) {
            return false;
        }
        return enqueue(event);
    }

    /**
     * Offers a {@code lifecycle} marker that BootUI publishes about its own action ({@link ControlMarkers}), which runs
     * in BootUI's own request and would otherwise be dropped as BootUI's work.
     */
    boolean offerMarker(RuntimeEvent event) {
        if (event == null || event.source() != JournalSource.LIFECYCLE || !settings.records(event.source())) {
            return false;
        }
        return enqueue(event);
    }

    private boolean enqueue(RuntimeEvent event) {
        int source = event.source().ordinal();
        try {
            event = withThreadKind(event);
            if ((event.failedOrSlow() || queue.size() < routineQueueLimit) && queue.offer(new Queued(event, clears))) {
                accepted[source].increment();
                acceptedTotal.increment();
                return true;
            }
        } catch (RuntimeException ex) {
            // Fall through: the event is dropped and counted, never propagated to the application.
        }
        dropped[source].increment();
        return false;
    }

    /**
     * BootUI's own work never enters the journal: its threads, such as its pollers and flushers, and the work its own
     * requests do, such as a panel's SQL, which the adapters mark with {@link CorrelationContext#BOOTUI}.
     */
    private boolean isBootUiWork() {
        return Thread.currentThread().getName().startsWith(BOOTUI_THREAD_PREFIX)
                || correlation.current().bootUi();
    }

    /**
     * Fills in the thread kind of an event offered on the thread it ran on, so every event knows whether it ran on a
     * pooled worker, a virtual thread, or an event loop ({@code docs/PLAN-v2.md} §5.1). An event offered elsewhere, such
     * as a message acknowledged on the broker client's I/O thread, keeps the kind its recorder gave it, if any.
     */
    private RuntimeEvent withThreadKind(RuntimeEvent event) {
        if (event.threadKind() != null || event.thread() == null) {
            return event;
        }
        return event.thread().equals(Thread.currentThread().getName())
                ? event.withThreadKind(threadKinds.current())
                : event;
    }

    /**
     * Starts the {@code gc} source, which publishes one event per completed collection until the journal closes
     * ({@code docs/PLAN-v2.md} §5.11). Does nothing when the journal is disabled, does not record {@code gc}, has no
     * dispatcher, or already started it.
     *
     * @return whether the source listens to at least one collector
     */
    public synchronized boolean startGcSource() {
        if (gcSource == null && dispatcher != null && !closed.get() && settings.records(JournalSource.GC)) {
            gcSource = GcEventSource.start(this);
        }
        return gcSource != null && gcSource.collectors() > 0;
    }

    /**
     * Starts the CPU ledger and resource track, which sweep the JVM into {@code track} until the journal closes
     * ({@code docs/PLAN-v2.md} §5.11). Does nothing when the journal is disabled, does not record {@code resources}, has
     * no dispatcher, or already started it.
     *
     * @return whether the sampler runs
     */
    public synchronized boolean startResourceSampler(ResourceSettings resourceSettings, ResourceTrack track) {
        if (resourceSampler == null
                && dispatcher != null
                && !closed.get()
                && settings.records(JournalSource.RESOURCES)) {
            resourceSampler = ResourceSampler.start(resourceSettings, track, this::lastSequence);
        }
        return resourceSampler != null && !closed.get();
    }

    /** Whether the resource sampler runs: it was started and the journal has not closed. */
    public synchronized boolean resourceSamplerRunning() {
        return resourceSampler != null && !closed.get();
    }

    /** The sequence number of the last event the dispatcher processed in this run, {@code 0} before the first. */
    public long lastSequence() {
        return lastSequence.get();
    }

    /** Installs this stack's classifier of the thread an event is offered on; {@code null} restores the default. */
    public void setThreadKindClassifier(ThreadKindClassifier classifier) {
        threadKinds.set(classifier);
    }

    /**
     * Installs where the journal reads the correlation of the work offering an event, to recognize BootUI's own. The
     * default is the thread's scope; the Quarkus adapter also reads the request's Vert.x context.
     */
    public void setCorrelationContextProvider(CorrelationContextProvider provider) {
        correlation.set(provider);
    }

    private void dispatchLoop() {
        List<Queued> batch = new ArrayList<>(BATCH_SIZE);
        while (running) {
            try {
                Queued first = queue.poll(250, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                synchronized (processing) {
                    batch.add(first);
                    queue.drainTo(batch, BATCH_SIZE - 1);
                    processCurrent(batch);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable ex) {
                // The dispatcher must survive anything a batch throws, or the journal would silently stop recording.
                log.log(Level.WARNING, "BootUI's runtime journal could not process a batch of events", ex);
            } finally {
                batch.clear();
            }
        }
    }

    /** Drains and processes every queued event on the calling thread; for tests without a dispatcher. */
    void dispatchPending() {
        List<Queued> batch = new ArrayList<>(BATCH_SIZE);
        synchronized (processing) {
            while (queue.drainTo(batch, BATCH_SIZE) > 0) {
                processCurrent(batch);
                batch.clear();
            }
        }
    }

    /**
     * Processes the events of {@code batch} offered since the last clear, with {@link #processing} held. One offered
     * before it, which the dispatcher took from the queue just as the clear ran, is dropped and counted as processed:
     * it was cleared with the recording.
     */
    private void processCurrent(List<Queued> batch) {
        long generation = clears;
        List<RuntimeEvent> events = new ArrayList<>(batch.size());
        for (Queued queued : batch) {
            if (queued.clears() == generation) {
                events.add(queued.event());
            }
        }
        processed.addAndGet(batch.size() - events.size());
        if (!events.isEmpty()) {
            process(events);
        }
    }

    /** An accepted event and how many times the recording had been cleared when it was offered. */
    private record Queued(RuntimeEvent event, long clears) {}

    private void process(List<RuntimeEvent> batch) {
        List<JournalEntry> entries = new ArrayList<>(batch.size());
        try {
            for (RuntimeEvent offered : batch) {
                RuntimeEvent event = interned(offered);
                long sequence = lastSequence.get() + 1;
                JournalEntry entry = new JournalEntry(sequence, event, event.estimatedBytes(dictionary));
                ring.add(entry);
                lastSequence.set(sequence);
                entries.add(entry);
            }
            List<JournalEntry> view = Collections.unmodifiableList(entries);
            for (JournalListener listener : listeners) {
                try {
                    listener.onEntries(view);
                } catch (RuntimeException ex) {
                    listenerFailures.increment();
                    log.log(Level.WARNING, "A runtime journal listener failed to process a batch of events", ex);
                }
            }
        } finally {
            processed.addAndGet(batch.size());
        }
    }

    private RuntimeEvent interned(RuntimeEvent event) {
        RuntimeEventPayload payload = event.payload();
        if (payload == null) {
            return event;
        }
        try {
            RuntimeEventPayload shared = payload.interned(dictionary);
            return shared == payload ? event : event.withPayload(shared);
        } catch (RuntimeException ex) {
            return event;
        }
    }

    /**
     * Waits until every event accepted before this call has been processed, or {@code timeout} passes.
     *
     * @return whether they all were
     */
    public boolean awaitDrained(Duration timeout) throws InterruptedException {
        long target = acceptedTotal.sum();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (processed.get() < target) {
            if (System.nanoTime() >= deadline) {
                return false;
            }
            Thread.sleep(2);
        }
        return true;
    }

    @Override
    public boolean records(JournalSource source) {
        return settings.records(source);
    }

    /** Registers a listener for every batch accepted from now on. */
    public void addListener(JournalListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener must not be null"));
    }

    public void removeListener(JournalListener listener) {
        listeners.remove(listener);
    }

    /**
     * Calls {@code onChange} on the dispatcher after each batch of recorded events, so a view can refresh when the
     * journal records anything, whichever source it came from ({@code docs/PLAN-v2.md} §5.3). BootUI's own work is
     * never recorded, so a view's refresh never triggers another.
     *
     * @return the action that stops the calls
     */
    public Runnable subscribe(Runnable onChange) {
        JournalListener listener = entries -> {
            if (!entries.isEmpty()) {
                onChange.run();
            }
        };
        addListener(listener);
        return () -> removeListener(listener);
    }

    /** The retained events, newest first. */
    public List<JournalEntry> entries() {
        return ring.newestFirst();
    }

    /** The stable id of {@code entry}: its run id and its sequence number. */
    public String eventId(JournalEntry entry) {
        return run.id() + "-" + entry.sequence();
    }

    /** The run's dictionary, which payloads intern repeated strings into. */
    public JournalDictionary dictionary() {
        return dictionary;
    }

    public RuntimeJournalSettings settings() {
        return settings;
    }

    public RunIdentity run() {
        return run;
    }

    /**
     * Whether a request recorded with {@code traceId} was evicted from the retained events, so a reader attributes no
     * AI call linked only by that trace ({@link AiCallOwners}): the evicted request may have made it.
     */
    public boolean evictedARequestOf(String traceId) {
        return ring.evictedARequestOf(traceId);
    }

    /** Whether {@code listener} is told of each batch and of each clear. */
    boolean notifies(JournalListener listener) {
        return listeners.contains(listener);
    }

    @Override
    public String offloadId() {
        return "runtime-journal";
    }

    @Override
    public String offloadLabel() {
        return "Runtime journal events";
    }

    /**
     * Drops what {@link #clear()} drops, for <b>Free BootUI memory</b>, and counts the retained and still-queued events
     * it discarded; recording settings are kept.
     */
    @Override
    public long offloadRetainedData() {
        return clearAndCount();
    }

    /**
     * Drops every retained event and every event still queued: the confirmation-gated <b>Clear recording</b> action.
     * Counts since startup are kept, the run's dictionary is emptied once the ring is, the statements derived from the
     * dropped events are forgotten, and every listener is told ({@link JournalListener#onClear}) to forget what it keeps
     * about them; the aggregates are theirs to clear.
     * It waits for a batch being processed, so no event recorded before the clear is processed after it.
     */
    public void clear() {
        clearAndCount();
    }

    /** {@link #clear()}, returning how many retained and queued events it dropped, counted under the same lock. */
    private long clearAndCount() {
        synchronized (processing) {
            clears++;
            List<Queued> queued = new ArrayList<>();
            queue.drainTo(queued);
            processed.addAndGet(queued.size());
            long dropped = ring.counts().retained() + queued.size();
            ring.clear();
            clearsCompleted++;
            dictionary.clear();
            SqlShapes.clear();
            for (JournalListener listener : listeners) {
                try {
                    listener.onClear();
                } catch (RuntimeException ex) {
                    listenerFailures.increment();
                    log.log(Level.WARNING, "A listener of BootUI's runtime journal failed to clear its state", ex);
                }
            }
            return dropped;
        }
    }

    public JournalStatus status() {
        EvidenceRing.Counts counts = ring.counts();
        return new JournalStatus(
                settings.enabled(),
                RunIdentity.instanceId(),
                run.id(),
                lastSequence.get(),
                counts.retained(),
                counts.retainedBytes(),
                dictionary.size(),
                dictionary.bytes(),
                settings.maxEvents(),
                settings.maxBytes(),
                counts.reserved(),
                counts.reservedCapacity(),
                counts.evictedByCount(),
                counts.evictedByBytes(),
                counts.lastBound() == null ? null : counts.lastBound().name(),
                counts.oldestRetainedEpochMillis(),
                queue.size(),
                settings.enabled() ? settings.queueCapacity() : 0,
                perSource(accepted),
                perSource(dropped),
                listenerFailures.sum(),
                clearsCompleted);
    }

    /**
     * Ends the run: stops the dispatcher, processes the events still queued on the calling thread, and tells every
     * listener the run ended, so the run summary counts the run's last events. Only the first call does anything.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        running = false;
        synchronized (this) {
            if (gcSource != null) {
                gcSource.close();
            }
            if (resourceSampler != null) {
                resourceSampler.close();
            }
        }
        if (dispatcher != null) {
            dispatcher.interrupt();
            try {
                dispatcher.join(TimeUnit.SECONDS.toMillis(2));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        if (dispatcher != null && dispatcher.isAlive()) {
            // A dispatcher stuck past the join still processes its batch: its listeners are not told the run ended.
            return;
        }
        if (settings.enabled()) {
            dispatchPending();
            for (JournalListener listener : listeners) {
                try {
                    listener.onClose();
                } catch (Throwable ex) {
                    listenerFailures.increment();
                    log.log(Level.WARNING, "A listener of BootUI's runtime journal failed when the run ended", ex);
                }
            }
        }
        // After the run's last events are processed, so the next run starts with its own statements
        // (docs/PLAN-v2.md §5.4).
        SqlShapes.clear();
    }

    private static LongAdder[] adders() {
        LongAdder[] adders = new LongAdder[JournalSource.values().length];
        for (int i = 0; i < adders.length; i++) {
            adders[i] = new LongAdder();
        }
        return adders;
    }

    private static Map<JournalSource, Long> perSource(LongAdder[] adders) {
        Map<JournalSource, Long> counts = new EnumMap<>(JournalSource.class);
        for (JournalSource source : JournalSource.values()) {
            long count = adders[source.ordinal()].sum();
            if (count > 0) {
                counts.put(source, count);
            }
        }
        return counts;
    }
}
