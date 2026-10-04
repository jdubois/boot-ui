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
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntConsumer;

/**
 * The runtime journal of one application run ({@code docs/PLAN-v2.md} §5.2): every runtime event recorded once, in a
 * push-based, bounded, in-memory structure.
 *
 * <p>Recorders {@link #offer} events on the application thread. Offering only checks the source, then puts the event
 * in a bounded queue without blocking, with one lock acquisition ({@link JournalQueue}). The last
 * {@link RuntimeJournalSettings#reservedQueueSharePercent()} of the queue admits only failed or slow events, so a burst
 * drops routine events first, and every dropped event is counted per source. One daemon thread, {@value #DISPATCHER_THREAD}, drains the queue in batches of up to {@value #BATCH_SIZE}: it
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

    /**
     * How long the dispatcher pauses after a batch smaller than {@link #BATCH_SIZE} before it takes the next, so under
     * load it drains events in batches rather than being woken, by the offering thread, for each one. An event is
     * recorded about this much later at most, rounded up to the operating system's timer resolution: on Windows, whose
     * default timer tick is about 15.6 ms, the pause can last that long. An offer that brings the queue to half its
     * routine share ends the pause early ({@link JournalQueue#awaitFilling}), so a burst never waits for it.
     */
    static final long DISPATCH_LINGER_NANOS = TimeUnit.MILLISECONDS.toNanos(1);

    /** The dictionary's share of the byte bound, beyond which it interns nothing more. */
    static final int DICTIONARY_BYTES_PERCENT = 25;

    /** The most strings one run's dictionary interns. */
    static final int DICTIONARY_MAX_ENTRIES = 100_000;

    private static final System.Logger log = System.getLogger(RuntimeJournal.class.getName());

    private final RuntimeJournalSettings settings;
    private final RunIdentity run;
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
    private final Runnable beforeQueueOffer;
    private final IntConsumer beforeQueueDrain;
    private final long dispatcherPollMillis;
    private final long dispatchLingerNanos;

    /**
     * Serializes processing a batch with clearing the recording, so a batch is either processed before the clear, and
     * cleared with everything else, or not at all. The queue is replaced only while it is held.
     */
    private final Object processing = new Object();

    /**
     * The queue of the current recording generation, the one application threads offer to now. A clear replaces it
     * and detaches the old one in constant time ({@link JournalQueue#detach()}), so an offer never waits for work
     * proportional to the old queue's depth. A clear replaces it only by compare-and-set, so it never replaces the closed
     * journal's queue that a {@link #close()} installs without {@link #processing} when the dispatcher is stuck.
     */
    private volatile JournalQueue admission;

    private static final VarHandle ADMISSION;

    static {
        try {
            ADMISSION = MethodHandles.lookup().findVarHandle(RuntimeJournal.class, "admission", JournalQueue.class);
        } catch (ReflectiveOperationException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    // Counted when a clear is wholly done, listeners included, and read first by status().
    private volatile long clearsCompleted;

    private GcEventSource gcSource;
    private ResourceSampler resourceSampler;
    private volatile boolean running;

    /** A journal whose dispatcher starts now, when {@code settings} enable it. */
    public RuntimeJournal(RuntimeJournalSettings settings, RunIdentity run) {
        this(settings, run, true, null, null, 250);
    }

    RuntimeJournal(RuntimeJournalSettings settings, RunIdentity run, boolean startDispatcher) {
        this(settings, run, startDispatcher, null, null, 250);
    }

    RuntimeJournal(
            RuntimeJournalSettings settings, RunIdentity run, boolean startDispatcher, Runnable beforeQueueOffer) {
        this(settings, run, startDispatcher, beforeQueueOffer, null, 250);
    }

    RuntimeJournal(
            RuntimeJournalSettings settings,
            RunIdentity run,
            boolean startDispatcher,
            Runnable beforeQueueOffer,
            IntConsumer beforeQueueDrain) {
        this(settings, run, startDispatcher, beforeQueueOffer, beforeQueueDrain, 250);
    }

    RuntimeJournal(
            RuntimeJournalSettings settings,
            RunIdentity run,
            boolean startDispatcher,
            Runnable beforeQueueOffer,
            IntConsumer beforeQueueDrain,
            long dispatcherPollMillis) {
        this(
                settings,
                run,
                startDispatcher,
                beforeQueueOffer,
                beforeQueueDrain,
                dispatcherPollMillis,
                DISPATCH_LINGER_NANOS);
    }

    RuntimeJournal(
            RuntimeJournalSettings settings,
            RunIdentity run,
            boolean startDispatcher,
            Runnable beforeQueueOffer,
            IntConsumer beforeQueueDrain,
            long dispatcherPollMillis,
            long dispatchLingerNanos) {
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
        this.run = Objects.requireNonNull(run, "run must not be null");
        this.beforeQueueOffer = beforeQueueOffer;
        this.beforeQueueDrain = beforeQueueDrain;
        this.dispatcherPollMillis = dispatcherPollMillis;
        this.dispatchLingerNanos = dispatchLingerNanos;
        this.routineQueueLimit = settings.routineQueueLimit();
        this.admission = newQueue();
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
     * Offers an imported application AI event whose span identity was classified before publication. The receiver's
     * {@link CorrelationContext#BOOTUI} scope is deliberately ignored, but BootUI's own worker threads remain excluded.
     */
    @Override
    public boolean offerImported(RuntimeEvent event) {
        if (event == null
                || event.source() != JournalSource.AI
                || !settings.records(event.source())
                || isBootUiThread()) {
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

    /**
     * Puts {@code event} in the current queue with one lock acquisition. An offer racing a clear may find the queue it
     * read already detached; it is then retried once more on the replacement, as an offer made after the clear.
     */
    private boolean enqueue(RuntimeEvent event) {
        int source = event.source().ordinal();
        try {
            event = withThreadKind(event);
            boolean failedOrSlow = event.failedOrSlow();
            int outcome;
            do {
                outcome = admission.offer(event, failedOrSlow);
            } while (outcome == JournalQueue.DETACHED);
            if (outcome == JournalQueue.ACCEPTED) {
                // Counted by the queue as it took the event, before the dispatcher could process it.
                return true;
            }
        } catch (RuntimeException ex) {
            // Fall through: the event is dropped and counted, never propagated to the application.
        }
        dropped[source].increment();
        return false;
    }

    /** Counts an accepted event; the queue calls it with its lock held, before the dispatcher can take the event. */
    private void countAccepted(int source) {
        accepted[source].increment();
        acceptedTotal.increment();
    }

    /**
     * BootUI's own work never enters the journal: its threads, such as its pollers and flushers, and the work its own
     * requests do, such as a panel's SQL, which the adapters mark with {@link CorrelationContext#BOOTUI}.
     */
    private boolean isBootUiWork() {
        return isBootUiThread() || correlation.current().bootUi();
    }

    private static boolean isBootUiThread() {
        return Thread.currentThread().getName().startsWith(BOOTUI_THREAD_PREFIX);
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
        List<RuntimeEvent> batch = new ArrayList<>(BATCH_SIZE);
        while (running) {
            try {
                JournalQueue queue = admission;
                RuntimeEvent first = queue.poll(dispatcherPollMillis, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                synchronized (processing) {
                    if (queue != admission) {
                        // Offered before a clear that ran while the dispatcher held it: cleared with the recording.
                        ring.lost(first);
                        processed.incrementAndGet();
                        continue;
                    }
                    batch.add(first);
                    queue.drainTo(batch, BATCH_SIZE - 1);
                    process(batch);
                }
                if (batch.size() < BATCH_SIZE) {
                    // Lets the next events gather instead of waking the dispatcher for each one: an offer that wakes
                    // it pays for the unpark on the application thread (M4-18d). A burst ends the pause early.
                    queue.awaitFilling(dispatchLingerNanos);
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
        synchronized (processing) {
            drain(admission);
        }
    }

    /** Processes every event of {@code queue}, with {@link #processing} held. */
    private void drain(JournalQueue queue) {
        List<RuntimeEvent> batch = new ArrayList<>(BATCH_SIZE);
        while (queue.drainTo(batch, BATCH_SIZE) > 0) {
            process(batch);
            batch.clear();
        }
    }

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

    /**
     * The retained events recorded after the event with sequence {@code sequence}, newest first: a reader that already
     * read up to it, as {@link #lastSequence()} before its read, copies only what is new.
     */
    public List<JournalEntry> entriesAfter(long sequence) {
        return ring.newestFirstAfter(sequence);
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

    /**
     * The latest start, by the wall clock, of an event of a request, an execution, or a trace that the journal evicted,
     * could not fit, or cleared, or {@code null} while it lost none. A request or an execution that started then or
     * before may be missing some of its events, so a reader that judges what a unit did not do leaves it out; one that
     * started later is complete, since its events start no earlier than it does.
     */
    public Long lossHorizonMillis() {
        return ring.lossHorizonMillis();
    }

    /**
     * When the latest request whose HTTP event the journal evicted, could not fit, or cleared ended, by the wall clock,
     * or {@code null} while it lost none: an {@code ERROR} logged without a request id shortly after, as a container
     * logs a failure once the request's id is gone, may have been that request's.
     */
    public Long lostRequestEndMillis() {
        return ring.lostRequestEndMillis();
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
            JournalQueue detached = admission;
            int queued = 0;
            // A closed journal's queue stays: compare-and-set, since a close with a stuck dispatcher installs it
            // without holding processing.
            if (!detached.refusing() && ADMISSION.compareAndSet(this, detached, newQueue())) {
                // Offers already admitted to the old queue are cleared with it; any later offer finds it detached
                // and goes to the replacement. Detaching also wakes a dispatcher waiting on it.
                JournalQueue.Detached cleared = detached.detach();
                queued = cleared.count();
                // Outside the queue's lock: their requests and executions lost them to the clear.
                cleared.forEach(ring::lost);
            }
            if (beforeQueueDrain != null) {
                beforeQueueDrain.accept(queued);
            }
            processed.addAndGet(queued);
            long dropped = ring.counts().retained() + queued;
            ring.clear();
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
            clearsCompleted++;
            return dropped;
        }
    }

    public JournalStatus status() {
        long clearsDone = clearsCompleted;
        JournalQueue current = admission;
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
                current.size(),
                settings.enabled() ? settings.queueCapacity() : 0,
                perSource(accepted),
                perSource(dropped),
                listenerFailures.sum(),
                clearsDone);
    }

    private JournalQueue newQueue() {
        return new JournalQueue(
                settings.enabled() ? settings.queueCapacity() : 1,
                routineQueueLimit,
                beforeQueueOffer,
                this::countAccepted);
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
            // A dispatcher stuck past the join still processes its batch, but its listeners are not told the run
            // ended. Without waiting for processing, which it holds: later offers are refused and counted as dropped,
            // and the events still queued are dropped with their frames, counted as processed as a clear counts them.
            JournalQueue last = (JournalQueue) ADMISSION.getAndSet(this, JournalQueue.closed());
            JournalQueue.Detached discarded = last.detach();
            processed.addAndGet(discarded.count());
            // Counted as processed, not dropped, so no check is made partial by them: their work lost them instead.
            discarded.forEach(ring::lost);
            return;
        }
        if (settings.enabled()) {
            synchronized (processing) {
                // Later offers are refused and counted as dropped; those already admitted are processed now.
                JournalQueue last = (JournalQueue) ADMISSION.getAndSet(this, JournalQueue.closed());
                last.seal();
                drain(last);
            }
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
