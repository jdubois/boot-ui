package io.github.jdubois.bootui.engine.javaagent;

import io.github.jdubois.bootui.engine.journal.CaughtExceptionPayload;
import io.github.jdubois.bootui.engine.journal.JournalEntry;
import io.github.jdubois.bootui.engine.journal.JournalListener;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeEventPublisher;
import io.github.jdubois.bootui.engine.journal.RuntimeEventSink;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.ThrowableMarks;
import io.github.jdubois.bootui.spi.ThreadKind;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The engine's side of the BootUI agent's {@code caught-exceptions} sensor ({@code docs/PLAN-v2.md} M5-6a): routes
 * the sensor's records from this run's claim's drainer into the runtime journal as {@link JournalSource
 * #AGENT_CAUGHT_EXCEPTIONS} events, with the owner, site, class, and thread each record names. It is journal-backed
 * agent evidence (§5.17), so the journal's exposure, source-panel policy (the Exceptions panel owns the source), and
 * loss accounting apply as for every event; records the agent made before the last <b>Clear recording</b> and drained
 * after it are dropped and counted, never published as if recorded after the clear.
 *
 * <p>Framework-neutral; each adapter creates one and starts it once its claim is armed. It starts nothing when the
 * claim did not ask for the sensor or the attached agent predates it. Records of another claim generation, as one
 * still queued from before a DevTools restart or a live reload, are dropped and counted.</p>
 *
 * <p>It also listens to the journal for the outcome reads (M5-6a2): each batch's ended requests are passed to the
 * agent, which then lets their pending exceptions go once they settled; and every loss the evidence suffers, in the
 * agent or here, is remembered as a time interval, so a read never calls a caught exception "not rethrown or logged"
 * over a window it may have lost records of.</p>
 */
public final class AgentCaughtExceptions implements RuntimeEventPublisher, Consumer<long[]>, AutoCloseable {

    /** The ring's layout ({@code AgentRing}): sensor, type, generation, time, then four payload longs. */
    static final int TYPE = 1;

    static final int GENERATION = 2;
    static final int TIME = 3;
    static final int REQUEST = 4;
    static final int EXECUTION = 5;
    static final int SITE = 6;
    static final int FLAGS = 7;

    /** Record types ({@code CaughtExceptions.TYPE_*}). */
    static final int TYPE_CAUGHT = 1;

    static final int TYPE_THROWN = 2;
    static final int TYPE_UNTRACKED = 3;
    static final int TYPE_EVICTED = 4;

    /** How a pending identity was found again ({@code CaughtExceptions.THROWN_*}). */
    static final int THROWN_EXIT = 1;

    static final int THROWN_CAUGHT_AGAIN = 2;

    /** Execution kinds in records ({@code CodePaths.EXECUTION_*}, {@code SideEffects.EXECUTION_OWN}). */
    static final int EXECUTION_ASYNC = 1;

    static final int EXECUTION_TASK = 2;
    static final int EXECUTION_OWN = 3;

    private final AgentBridgeAccess access;
    private final Supplier<AgentClaim> claims;
    private final LongSupplier clock;
    /** The most loss intervals remembered; past it, the oldest two merge, so a loss is never forgotten. */
    static final int MAX_LOSSES = 64;

    /** How often a batch samples the agent's loss count at most. */
    static final long LOSS_SAMPLE_MILLIS = 200L;

    /** The most ended requests passed to the agent per batch. */
    static final int MAX_ENDED = RuntimeJournal.BATCH_SIZE;

    private final ClearWatermark watermark = new ClearWatermark();
    /** Loss intervals {@code [from, to]}, by the wall clock, oldest first; guarded by itself. */
    private final Deque<long[]> losses = new ArrayDeque<>();

    private long lastLossCount = Long.MIN_VALUE;
    private long lastLossSample = Long.MIN_VALUE;
    private volatile boolean lossUnaccounted;
    private final long[] ended = new long[MAX_ENDED];
    private boolean marking;
    private volatile boolean executors;
    /** When this run's records started being routed, so logged throwables carry marks, by the wall clock. */
    private volatile long routingSince = Long.MAX_VALUE;

    private volatile RuntimeEventSink sink = RuntimeEventSink.NONE;
    private RuntimeJournal listened;

    private final AtomicLong published = new AtomicLong();
    private final AtomicLong stale = new AtomicLong();
    private final AtomicLong cleared = new AtomicLong();
    private final AtomicLong unpublished = new AtomicLong();
    private final AtomicLong unresolved = new AtomicLong();

    private volatile AgentRecordDrainer drainer;
    /** Whether {@link #start()} was asked for before a journal was installed, so the installation starts routing. */
    private boolean startWaiting;

    private long generation = Long.MIN_VALUE;
    /** The ring's interned strings of this generation, by id, as far as resolved. */
    private String[] strings = new String[1];
    /** The sites by id, as far as resolved: {@code key, types, line, flags}. */
    private final List<String[]> sites = new ArrayList<>();

    public AgentCaughtExceptions(AgentBridgeAccess access, Supplier<AgentClaim> claims) {
        this(access, claims, System::currentTimeMillis);
    }

    AgentCaughtExceptions(AgentBridgeAccess access, Supplier<AgentClaim> claims, LongSupplier clock) {
        this.access = access;
        this.claims = claims;
        this.clock = clock;
    }

    /** Installs the journal, and listens to it for <b>Clear recording</b>. */
    @Override
    public synchronized void setRuntimeEventSink(RuntimeEventSink journal) {
        if (listened != null) {
            listened.removeListener(watermark);
            listened = null;
        }
        sink = journal == null ? RuntimeEventSink.NONE : journal;
        if (journal instanceof RuntimeJournal runtime) {
            runtime.addListener(watermark);
            listened = runtime;
        }
        if (startWaiting && journal != null) {
            // Started first, as when Quarkus runs the claim's startup observer before the journal's.
            start();
        }
    }

    /**
     * Starts routing this run's records, once its claim is armed and a journal is installed, else once it is: does
     * nothing when the claim did not ask for the sensor, the attached agent predates it, or it already routes.
     */
    public synchronized void start() {
        if (sink == RuntimeEventSink.NONE) {
            // Nothing to publish into yet: routing starts once the journal is installed.
            startWaiting = true;
            return;
        }
        startWaiting = false;
        AgentClaim current = claims.get();
        if (drainer != null
                || current == null
                || !current.armed()
                || current.generation() == null
                || !current.sensors().caughtExceptions()
                || !access.caughtExceptionsSupported()) {
            return;
        }
        AgentRecordDrainer routed = current.drainer();
        if (routed == null) {
            return;
        }
        generation = current.generation();
        executors = current.sensors().executors();
        drainer = routed;
        routed.route(AgentRecordDrainer.SENSOR_CAUGHT_EXCEPTIONS, this);
        if (!marking) {
            marking = true;
            ThrowableMarks.retain();
        }
        routingSince = clock.getAsLong();
        sampleLosses();
    }

    /** Whether the claim also asked for the executors sensor, so work a request hands over is followed. */
    public boolean executorsRecorded() {
        return executors;
    }

    /**
     * Since when this run's records are routed, and logged or reported throwables carry identity marks, by the wall
     * clock; {@link Long#MAX_VALUE} while they are not.
     */
    public long routingSinceMillis() {
        return routingSince;
    }

    /** Whether this run's records are routed into the journal. */
    public boolean routing() {
        return drainer != null;
    }

    /**
     * Drains what the agent recorded so far on the calling thread, as before a read: whether a drain ran, false while
     * nothing is routed.
     */
    public boolean drainNow() {
        AgentRecordDrainer current = drainer;
        if (current == null) {
            return false;
        }
        current.drainNow();
        return true;
    }

    /** Stops routing. Idempotent. */
    @Override
    public synchronized void close() {
        if (drainer != null) {
            drainer.unroute(AgentRecordDrainer.SENSOR_CAUGHT_EXCEPTIONS, this);
            drainer = null;
        }
        if (marking) {
            marking = false;
            ThrowableMarks.release();
        }
        if (listened != null) {
            listened.removeListener(watermark);
            listened = null;
        }
    }

    /** One record, on the drain thread: the reused array is read here and not kept. */
    @Override
    public void accept(long[] record) {
        RuntimeEvent event;
        synchronized (this) {
            if (record[GENERATION] != generation) {
                stale.incrementAndGet();
                return;
            }
            if (record[TIME] <= watermark.clearedAt) {
                // Recorded before the last clear, drained after it.
                cleared.incrementAndGet();
                return;
            }
            event = event(record);
        }
        if (event == null) {
            unresolved.incrementAndGet();
            lost(record[TIME], record[TIME]);
            return;
        }
        RuntimeEventSink journal = sink;
        // Published from BootUI's drain thread for the application thread that recorded it.
        if (!journal.records(JournalSource.AGENT_CAUGHT_EXCEPTIONS) || !journal.offerAgentRecord(event)) {
            unpublished.incrementAndGet();
            lost(record[TIME], record[TIME]);
            return;
        }
        published.incrementAndGet();
    }

    /** The record's event, or {@code null} when its site cannot be named. */
    private RuntimeEvent event(long[] record) {
        int type = (int) record[TYPE];
        long flags = record[FLAGS];
        int siteId = (int) (record[SITE] >>> 32);
        String[] site = site(siteId);
        if (site == null) {
            return null;
        }
        String[] key = site[0].split("#", 4);
        if (key.length < 4) {
            return null;
        }
        String siteClass = key[0].replace('/', '.');
        String siteMethod = key[1];
        int line = parse(site[2]);
        int siteFlags = parse(site[3]);
        List<String> declared = key[3].isEmpty() ? List.of() : Arrays.asList(key[3].split("\\|"));
        int identity = (int) record[SITE];
        int executionKind = (int) ((flags >>> 8) & 0xF);
        String kind;
        String exceptionClass = null;
        String family = null;
        long count = 1L;
        String foundBy = null;
        String foundAt = null;
        String thread = null;
        ThreadKind threadKind = null;
        switch (type) {
            case TYPE_CAUGHT -> {
                kind = CaughtExceptionPayload.CAUGHT;
                exceptionClass = string((int) (flags >>> 32));
                family = family((int) (flags & 0x3L));
                thread = string((int) ((flags >>> 16) & 0xFFFFL));
                threadKind = threadKind((int) ((flags >>> 4) & 0x3L));
            }
            case TYPE_THROWN -> {
                kind = CaughtExceptionPayload.THROWN;
                int how = (int) (flags & 0xFFL);
                foundBy =
                        how == THROWN_CAUGHT_AGAIN ? CaughtExceptionPayload.CAUGHT_AGAIN : CaughtExceptionPayload.EXIT;
                String[] found = site((int) (flags >>> 32));
                if (found != null) {
                    String[] foundKey = found[0].split("#", 4);
                    foundAt = foundKey.length < 2 ? null : foundKey[0].replace('/', '.') + "#" + foundKey[1];
                }
            }
            case TYPE_UNTRACKED -> {
                kind = CaughtExceptionPayload.UNTRACKED;
                count = record[SITE] & 0xFFFFFFFFL;
                identity = 0;
                thread = string((int) ((flags >>> 16) & 0xFFFFL));
                threadKind = threadKind((int) ((flags >>> 4) & 0x3L));
            }
            case TYPE_EVICTED -> kind = CaughtExceptionPayload.EVICTED;
            default -> {
                return null;
            }
        }
        CaughtExceptionPayload payload = new CaughtExceptionPayload(
                kind,
                siteClass,
                siteMethod,
                line,
                declared,
                siteFlags,
                exceptionClass,
                family,
                count,
                foundBy,
                foundAt,
                identity);
        return new RuntimeEvent(
                JournalSource.AGENT_CAUGHT_EXCEPTIONS,
                record[TIME],
                -1,
                id(record[REQUEST]),
                executionId(record[EXECUTION], executionKind),
                null,
                thread,
                threadKind,
                false,
                payload);
    }

    /**
     * The site {@code id}, resolved from the bridge, or {@code null}: an id not yet known, or known while its key was
     * not written yet, is fetched again from that id.
     */
    private String[] site(int id) {
        if (id < 0) {
            return null;
        }
        if (id >= sites.size() || sites.get(id) == null) {
            int from = Math.min(id, sites.size());
            String[] more = access.caughtExceptionSites(from);
            for (int i = 0; i < more.length; i++) {
                String[] parsed = more[i] == null ? null : more[i].split("\t", -1);
                if (from + i < sites.size()) {
                    sites.set(from + i, parsed);
                } else {
                    sites.add(parsed);
                }
            }
        }
        String[] site = id < sites.size() ? sites.get(id) : null;
        return site == null || site.length < 4 ? null : site;
    }

    /**
     * The ring's interned string {@code id} of this generation, or {@code null}: an id not yet known, or known while
     * it was not filled yet, is fetched again from that id.
     */
    private String string(int id) {
        if (id <= 0) {
            return null;
        }
        if (id >= strings.length || strings[id] == null) {
            int from = Math.min(id, strings.length);
            String[] more = access.interned(generation, from);
            if (more != null && more.length > 0) {
                if (from + more.length > strings.length) {
                    strings = Arrays.copyOf(strings, from + more.length);
                }
                System.arraycopy(more, 0, strings, from, more.length);
            }
        }
        return id < strings.length ? strings[id] : null;
    }

    static String family(int family) {
        return switch (family) {
            case 1 -> "sql";
            case 2 -> "io";
            case 3 -> "data-access";
            default -> null;
        };
    }

    static ThreadKind threadKind(int kind) {
        return kind == 2 ? ThreadKind.VIRTUAL_THREAD : null;
    }

    static String id(long bits) {
        return bits == 0L ? null : String.format("%016x", bits);
    }

    static String executionId(long bits, int kind) {
        if (bits == 0L) {
            return null;
        }
        return switch (kind) {
            case EXECUTION_ASYNC -> "async-" + id(bits);
            case EXECUTION_TASK -> "task-" + id(bits);
            case EXECUTION_OWN -> id(bits);
            default -> null;
        };
    }

    private static int parse(String text) {
        try {
            return Integer.parseInt(text);
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    /** Counters for the Java Agent panel and tests. */
    public Map<String, Long> counters() {
        return Map.of(
                "published", published.get(),
                "staleGeneration", stale.get(),
                "beforeClear", cleared.get(),
                "unpublished", unpublished.get(),
                "unresolved", unresolved.get());
    }

    /**
     * How many times the agent left an exception handler uninstrumented because the JVM already holds its 16,384 handler
     * sites, whose catches go unseen: a handler retransformed again counts again, so this says whether any were, not how
     * many; {@code 0} when none, or when the bridge does not count them.
     */
    public long sitesOverLimit() {
        try {
            Long over = AgentBridgeAccess.number(
                    AgentBridgeAccess.map(access.status(), AgentSensorSettings.CAUGHT_EXCEPTIONS), "sitesOverLimit");
            return over == null ? 0L : over;
        } catch (RuntimeException ex) {
            return 0L;
        }
    }

    // ---- losses -----------------------------------------------------------------------------------------------------

    /**
     * Samples the agent's loss count: a growth since the last sample is a loss somewhere between the two samples. A
     * bridge that cannot count its losses makes every window unaccounted.
     */
    public void sampleLosses() {
        long now = clock.getAsLong();
        long count = access.caughtLosses();
        synchronized (losses) {
            if (count < 0L) {
                lossUnaccounted = true;
                lastLossSample = now;
                return;
            }
            if (lastLossCount != Long.MIN_VALUE && count > lastLossCount) {
                addLoss(lastLossSample == Long.MIN_VALUE ? now : lastLossSample, now);
            } else if (lastLossCount == Long.MIN_VALUE && count > 0L) {
                // Lost before the first sample: since an unknown time.
                addLoss(Long.MIN_VALUE, now);
            }
            lastLossCount = count;
            lastLossSample = now;
        }
    }

    private void lost(long from, long to) {
        synchronized (losses) {
            addLoss(from, to);
        }
    }

    private void addLoss(long from, long to) {
        long[] last = losses.peekLast();
        if (last != null && from <= last[1]) {
            last[0] = Math.min(last[0], from);
            last[1] = Math.max(last[1], to);
            return;
        }
        losses.addLast(new long[] {from, to});
        if (losses.size() > MAX_LOSSES) {
            long[] oldest = losses.pollFirst();
            long[] next = losses.peekFirst();
            next[0] = Math.min(next[0], oldest[0]);
        }
    }

    /**
     * Whether the sensor's evidence may have lost records between {@code from} and {@code to}, by the wall clock, or
     * cannot tell, as with an agent that does not count its losses.
     */
    public boolean lostBetween(long from, long to) {
        if (lossUnaccounted) {
            return true;
        }
        synchronized (losses) {
            for (long[] loss : losses) {
                if (loss[0] <= to && loss[1] >= from) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether the attached agent cannot count its losses, so no window is known complete. */
    public boolean lossUnaccounted() {
        return lossUnaccounted;
    }

    /** When the journal was last cleared, by the wall clock, or {@code null} while it was not. */
    public Long clearedAtMillis() {
        long at = watermark.clearedAt;
        return at == Long.MIN_VALUE ? null : at;
    }

    /**
     * Listens to the journal: remembers when it was last cleared, so records made before it are never published after
     * it; passes each batch's ended requests to the agent and lets it sweep; and samples its losses.
     */
    final class ClearWatermark implements JournalListener {

        volatile long clearedAt = Long.MIN_VALUE;

        @Override
        public void onEntries(List<JournalEntry> entries) {
            if (!routing()) {
                return;
            }
            int count = 0;
            for (JournalEntry entry : entries) {
                RuntimeEvent event = entry.event();
                if (event.source() == JournalSource.HTTP && count < MAX_ENDED) {
                    long bits = requestBits(event.requestId());
                    if (bits != 0L) {
                        ended[count++] = bits;
                    }
                }
            }
            access.caughtRequestsEnded(ended, count);
            access.caughtSweep();
            long now = clock.getAsLong();
            if (lastLossSample == Long.MIN_VALUE || now - lastLossSample >= LOSS_SAMPLE_MILLIS) {
                sampleLosses();
            }
        }

        @Override
        public void onClear() {
            clearedAt = clock.getAsLong();
        }
    }

    /** The agent's bits of a request id written as 16 hexadecimal digits, or 0 when it is not one. */
    static long requestBits(String id) {
        if (id == null || id.length() != 16) {
            return 0L;
        }
        try {
            return Long.parseUnsignedLong(id, 16);
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }
}
