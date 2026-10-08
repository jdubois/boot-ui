package io.github.jdubois.bootui.agent.bridge;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * The request value holder of the {@code security-sinks} sensor's request-value matching (PLAN-v2 §5.16, M5-6b): the
 * query, path, and form parameter values of the requests running now, held only so a sink reached by the request (SQL
 * text, a command, a file path, an outbound URL) can be checked for one of them appearing verbatim, then forgotten when
 * the response completes. Opt-in (D37): an adapter pushes nothing unless {@code
 * bootui.agent.security-sinks.request-values} is on and {@link #active()} answers true.
 *
 * <p><b>What it holds.</b> A preallocated table of {@value #ENTRIES} entries, one per request, each with at most
 * {@value #MAX_VALUES} values of {@value #MIN_LENGTH} to {@value #MAX_LENGTH} characters and their parameter names,
 * kept only when they are at most {@value #MAX_NAME} characters of {@code [A-Za-z0-9_.\-\[\]]}, else shown as
 * {@code param#n}. The values are the {@code String} instances the adapter passed, never copies. A request beyond the
 * table's room holds nothing, and the miss is counted; so are values too short, too long, or past the per-request cap.
 *
 * <p><b>Lifetime.</b> An adapter pushes at the handler phase ({@link #begin}) and removes the request's entry where the
 * response really completes ({@link #end}), keyed by the request id, never by a scope, so a push on a worker or after
 * assembly is removed too. An entry older than {@value #DEADLINE_SECONDS} s, an async request whose end was missed, is
 * swept at the next {@link #begin}. A newer claim generation, a disarm, a release, a DevTools restart, or a live reload
 * (all of which change or end the claim) wipes the whole table ({@link #refresh()}), and so does the sensor going off.
 * The holder is not evidence, so <b>Clear recording</b> leaves it alone: it is empty between requests.
 *
 * <p><b>Never in a snapshot.</b> The holder is not part of the correlation context, so nothing that propagates a
 * context copies it. {@link #match} answers only to the request's own work: the calling thread's top owner slot must be
 * a scope ({@code SLOT_SCOPE}) naming the request, or, on a thread without a slot (a Quarkus worker, a WebFlux hop),
 * the claim's capture must name the request with no child execution. A propagated task ({@code SLOT_HANDOFF}, or an
 * {@code async-} or {@code task-} execution) and another request's thread always get no values.
 *
 * <p><b>Matching.</b> {@link #match} compares each value with {@link String#indexOf(String, int)}, an intrinsic, within a
 * per-request budget: at most {@value #MAX_CHECKS} checks, {@value #MAX_SCAN} characters of sink text per check (the
 * rest is not scanned, and the check is marked partial), and {@value #MAX_COMPARISONS} character comparisons in all
 * (text length times the held values' total length); past either budget matching stops for the request, counted. An identical sink text of
 * the same kind seen again by the request, as one SQL statement inside a loop, is not checked again (the last
 * {@value #HASHES} text hashes). Only parameter indices, their names, and span offsets leave it: <b>never a value</b>.
 * The caller redacts every matched span ({@link #redact}) before it builds any target, record, or log.
 *
 * <p><b>Never stored, logged, or displayed.</b> No {@code toString}, no logging, no accessor for a value. No monitor is
 * taken: each entry has a try-lock taken by compare-and-set with a token of its own, held only for bounded JDK string
 * work, never while application code runs, and released only by its holder; a check that finds its entry busy is
 * skipped and counted rather than waiting. JDK types only;
 * every entry point catches everything.
 */
public final class RequestValues {

    /** Sink kinds, for {@link #match}'s repeat detection and the records the sinks publish. */
    public static final int SINK_SQL = 1;

    public static final int SINK_COMMAND = 2;
    public static final int SINK_FILE = 3;
    public static final int SINK_URL = 4;

    /** The shortest value held (PLAN-v2 §5.16). */
    public static final int MIN_LENGTH = 4;

    /** The longest value held; a longer one is skipped and counted. */
    public static final int MAX_LENGTH = 256;

    /** Values held per request at most. */
    public static final int MAX_VALUES = 32;

    /** Requests holding values at once at most. */
    public static final int ENTRIES = 128;

    /** Sink checks per request at most. */
    public static final int MAX_CHECKS = 256;

    /** Characters of sink text scanned per check at most. */
    public static final int MAX_SCAN = 16 * 1024;

    /**
     * Character comparisons per request at most: the sum over its checks of scanned length times the held values' total
     * length.
     */
    public static final long MAX_COMPARISONS = 4L * 1024L * 1024L;

    /** Sink-text hashes remembered per request, so a repeated text is not checked again. */
    public static final int HASHES = 32;

    /** Spans {@link #match} reports at most; more mark the check {@link #F_OVERFLOW}. */
    public static final int MAX_SPANS = 8;

    /** The longest parameter name kept. */
    public static final int MAX_NAME = 64;

    /** How long an entry may live without its {@link #end}. */
    static final int DEADLINE_SECONDS = 60;

    static final long DEADLINE_NANOS = DEADLINE_SECONDS * 1_000_000_000L;

    /** {@link #match}'s {@code spans} layout: the spans written, the flags, then {@code (index, start, end)} triples. */
    public static final int S_COUNT = 0;

    public static final int S_FLAGS = 1;
    public static final int S_FIRST = 2;

    /** The length {@link #match}'s {@code spans} needs. */
    public static final int SPANS_LENGTH = S_FIRST + 3 * MAX_SPANS;

    /** Only the first {@value #MAX_SCAN} characters were scanned. */
    public static final int F_PARTIAL = 1;

    /** More than {@value #MAX_SPANS} spans matched: only the first ones are reported. */
    public static final int F_OVERFLOW = 2;

    /** The request's matching budget is spent: nothing was compared. */
    public static final int F_STOPPED = 4;

    /** The request already checked this text for this kind of sink: nothing was compared. */
    public static final int F_REPEATED = 8;

    /** The request's entry was busy with another check: nothing was compared. */
    public static final int F_BUSY = 16;

    /**
     * The request matched this same text before: compared again, so its spans redact it as before (never spans kept from
     * an earlier text, which a hash collision could misplace), and not published again or counted as a check; its
     * comparisons still count toward the request's comparison budget.
     */
    public static final int F_SEEN = 32;

    /** Matched sink-text hashes remembered per request, so a repeated matched text is not published again. */
    public static final int MATCHED_HASHES = 8;

    /**
     * Where a matched value sat in its sink, in a {@code security-sinks} record's outcome byte: inside a string or
     * numeric literal of an SQL text, or outside one (an identifier or keyword position); 0 when the sink has no
     * literals.
     */
    public static final int POSITION_IN_LITERAL = 1;

    public static final int POSITION_OUTSIDE_LITERAL = 2;

    /** Where a matched value sat is not known: its span was past the spans reported. */
    public static final int POSITION_UNKNOWN = 3;

    /** In a record's outcome byte: the matched value is made of digits only. */
    public static final int FLAG_NUMERIC = 4;

    /** In a record's outcome byte: the matched value crossed an SQL literal's or comment's bounds. */
    public static final int FLAG_CROSSES_LITERAL = 8;

    /** In a record's outcome byte: the matched value sat inside an unquoted SQL literal (a number, true, or false). */
    public static final int FLAG_BARE_LITERAL = 16;

    /** What a process start's or a file operation's own record names when its executable or path held request input
     * whose redaction could not cover every occurrence. */
    public static final String FROM_REQUEST_INPUT = "(not kept: it held request input)";

    /**
     * What a process start's or a file operation's own record names when the request holding values could not have its
     * executable or path checked (its matching stopped, its entry busy, the text past the scan): never the raw text.
     */
    public static final String NOT_CHECKED = "(not kept: not checked for request input)";

    /** Command arguments checked per process start at most. */
    static final int MAX_ARGUMENTS = 32;

    /** Distinct sink targets and parameter names interned per claim generation at most; past it, none is kept. */
    static final int INTERN_QUOTA = 2_000;

    /** The key of the per-process keyed hashes of raw sink texts (PLAN-v2 M5-6 design Important 11). */
    private static final long HASH_KEY = ThreadLocalRandom.current().nextLong() | 1L;

    private static final long FREE = 0L;

    /** The threads' ids in lock tokens, drawn once per thread, never 0. */
    private static final AtomicInteger TOKENS = new AtomicInteger();

    /**
     * The calling thread's id and its acquisition sequence, {@code {id, sequence}}: each acquisition's token is new, the
     * id in its high bits and the sequence in its low ones, so a waiter sees a lock that changed hands, even to the same
     * thread, and no counter is shared per acquisition.
     */
    static final ThreadLocal<long[]> THREAD_TOKEN = new ThreadLocal<long[]>();

    /** Each entry's request id, 0 when free; written only under the entry's lock, read without it to find an entry. */
    private static final AtomicLongArray REQUESTS = new AtomicLongArray(ENTRIES);

    /** Each entry's try-lock: {@link #FREE}, or its holder's token for this acquisition. */
    private static final AtomicLongArray LOCKS = new AtomicLongArray(ENTRIES);

    /**
     * Each slot's entry, {@code null} when free. A wipe detaches the entry object by compare-and-set: a holder a takeover
     * overtook keeps writing into an object the table no longer reaches, never into the next request's.
     */
    private static final AtomicReferenceArray<Entry> TABLE = new AtomicReferenceArray<Entry>(ENTRIES);

    /** Entries holding a request, so a check returns at once while none does. */
    private static final AtomicInteger LIVE = new AtomicInteger();

    /**
     * The claim generation the {@code security-sinks} sensor was enabled for, or {@link Long#MIN_VALUE}: tied to a
     * generation, so a later claim that does not ask for the sensor never finds it on.
     */
    private static volatile long sensorGeneration = Long.MIN_VALUE;

    /** How long {@link #lock} waits for an entry before taking it over, as from a thread that died holding it. */
    static final long LOCK_WAIT_NANOS = 100_000_000L;

    /** The claim generation the table's entries belong to. */
    private static volatile long tableGeneration = Long.MIN_VALUE;

    private static final LongAdder BEGUN = new LongAdder();
    private static final LongAdder TABLE_FULL = new LongAdder();
    private static final LongAdder KEPT = new LongAdder();
    private static final LongAdder TOO_SHORT = new LongAdder();
    private static final LongAdder TOO_LONG = new LongAdder();
    private static final LongAdder OVER_COUNT = new LongAdder();
    private static final LongAdder ENDED = new LongAdder();
    private static final LongAdder EXPIRED = new LongAdder();
    private static final LongAdder WIPED = new LongAdder();
    private static final LongAdder CHECKS = new LongAdder();
    private static final LongAdder MATCHED = new LongAdder();
    private static final LongAdder PARTIAL = new LongAdder();
    private static final LongAdder OVERFLOW = new LongAdder();
    private static final LongAdder STOPPED = new LongAdder();
    private static final LongAdder REPEATED = new LongAdder();
    private static final LongAdder BUSY = new LongAdder();
    private static final LongAdder REFUSED_HANDOFF = new LongAdder();
    private static final LongAdder REFUSED_EXECUTION = new LongAdder();
    private static final LongAdder FORCED = new LongAdder();
    private static final LongAdder PUBLISHED = new LongAdder();
    private static final LongAdder DROPPED = new LongAdder();
    private static final LongAdder NOT_KEPT = new LongAdder();
    private static final AtomicInteger INTERNED = new AtomicInteger();
    private static final LongAdder ERRORS = new LongAdder();

    private RequestValues() {}

    /**
     * One request's values, created when the request takes a slot and detached when it is wiped: every mutable field is
     * guarded by the slot's lock. No {@code toString}, by design.
     */
    private static final class Entry {
        final long request;
        final long generation;
        final String[] values = new String[MAX_VALUES];
        final String[] names = new String[MAX_VALUES];
        final long[] hashes = new long[HASHES];
        final long[] matchedHashes = new long[MATCHED_HASHES];
        int matchedCount;
        int count;
        /**
         * Bumped by every value added, on a push or a late pull: a text remembered under an earlier epoch is checked
         * again, as it may hold a value added since.
         */
        long epoch;
        /** The held values' lengths summed: a check's comparisons are its scanned length times this. */
        int valueChars;

        int hashCount;
        /** Written under the lock; read without it by {@link #sweep}, which then checks again under the lock. */
        volatile long begun;

        int checks;
        long comparisons;
        boolean stopped;
        /** Where values appear later, as WebFlux's path variables once a handler mapping set them, and their keys. */
        Map<?, ?> late;

        String[] lateKeys;

        Entry(long request, long generation, long begun) {
            this.request = request;
            this.generation = generation;
            this.begun = begun;
        }
    }

    // ---- the gate --------------------------------------------------------------------------------------------------

    /**
     * Whether an adapter should parse and push a request's values: the {@code security-sinks} sensor is installed and
     * enabled, and a claim is armed. A volatile read and the claim's; never throws.
     */
    public static boolean active() {
        long sensor = sensorGeneration;
        if (sensor == Long.MIN_VALUE) {
            return false;
        }
        Claim claim = AgentBridge.current();
        return claim != null && claim.armed && claim.generation == sensor && claim.generation == tableGeneration;
    }

    /** The claim generation the sensor is on for, or {@link Long#MIN_VALUE}: what {@link SideEffects} last set. */
    static long sensorGeneration() {
        return sensorGeneration;
    }

    /**
     * The {@code security-sinks} sensor was enabled for the claim of {@code generation}, or disabled: going off wipes
     * every entry. Never throws.
     */
    static void sensor(boolean on, long generation) {
        try {
            sensorGeneration = on ? generation : Long.MIN_VALUE;
            if (!on) {
                wipeAll();
            }
            refresh();
        } catch (Throwable ex) {
            ERRORS.increment();
        }
    }

    /**
     * The claim changed (claimed, disarmed, released): the table follows the current armed claim's generation, and any
     * entry of another generation, or every entry when no claim is armed, is wiped. Never throws.
     */
    static void refresh() {
        try {
            Claim claim = AgentBridge.current();
            long now = claim != null && claim.armed ? claim.generation : Long.MIN_VALUE;
            if (now != tableGeneration) {
                tableGeneration = now;
                INTERNED.set(0);
                wipeAll();
            }
        } catch (Throwable ex) {
            ERRORS.increment();
        }
    }

    // ---- lifetime --------------------------------------------------------------------------------------------------

    /**
     * Holds the values of the request {@code requestId}, a 16-digit hexadecimal id, with their parameter names, adding
     * them to its entry when it already holds some; {@code late}, when not {@code null}, is a map read at the request's
     * first check, whose values under each of {@code lateKeys} that are maps of names to values are added then (WebFlux's
     * path variables, which a handler mapping sets after this push). Nothing is held when {@link #active()} is false, on
     * BootUI's own work, or without room. Returns the values the entry holds, or -1 when nothing is held. Never throws.
     */
    public static int begin(String requestId, String[] names, String[] values, Map<?, ?> late, String[] lateKeys) {
        try {
            if (!active() || Reentrancy.sideEffectsSkipped()) {
                return -1;
            }
            long request = CodeInventory.parseRequestId(requestId);
            if (request == 0L) {
                return -1;
            }
            if (late != null && !jdkType(late)) {
                // Only a JDK map is ever read later, on a sink's thread: never an application's implementation.
                late = null;
            }
            long now = System.nanoTime();
            sweep(now);
            long generation = tableGeneration;
            int index = find(request);
            long token;
            if (index < 0) {
                index = claimFree(request, generation, now);
                if (index < 0) {
                    TABLE_FULL.increment();
                    return -1;
                }
                token = currentToken();
                BEGUN.increment();
            } else {
                token = lock(index);
            }
            try {
                Entry entry = entry(index, request);
                if (entry == null) {
                    return -1;
                }
                if (values != null) {
                    for (int i = 0; i < values.length; i++) {
                        add(entry, names != null && i < names.length ? names[i] : null, values[i]);
                    }
                }
                if (late != null && entry.late == null) {
                    entry.late = late;
                    entry.lateKeys = lateKeys;
                }
                if (TABLE.get(index) != entry) {
                    // Wiped while adding, after a takeover: the object is detached, and cleared again here.
                    clear(entry);
                    return -1;
                }
                if (entry.generation != tableGeneration || entry.generation != sensorGeneration) {
                    // A claim change or the sensor going off raced this push: its wipe may have passed this entry.
                    wipe(index, entry);
                    return -1;
                }
                return entry.count;
            } finally {
                unlock(index, token);
            }
        } catch (Throwable ex) {
            ERRORS.increment();
            return -1;
        }
    }

    /**
     * The request {@code requestId} completed: its entries are wiped, so no value outlives the response. Cheap when
     * nothing is held. Never throws.
     */
    public static void end(String requestId) {
        try {
            if (LIVE.get() == 0) {
                return;
            }
            long request = CodeInventory.parseRequestId(requestId);
            if (request == 0L) {
                return;
            }
            sweep(System.nanoTime());
            for (int i = 0; i < ENTRIES; i++) {
                if (REQUESTS.get(i) == request) {
                    long token = lock(i);
                    try {
                        if (wipe(i, entry(i, request))) {
                            ENDED.increment();
                        }
                    } finally {
                        unlock(i, token);
                    }
                }
            }
        } catch (Throwable ex) {
            ERRORS.increment();
        }
    }

    /** Wipes the entries older than the deadline, without waiting on a busy one. */
    private static void sweep(long now) {
        if (LIVE.get() == 0) {
            return;
        }
        for (int i = 0; i < ENTRIES; i++) {
            Entry candidate = TABLE.get(i);
            long token;
            // The deadline first, read without the lock: a sweep takes no lock, and touches no shared counter, unless
            // an
            // entry looks expired.
            if (candidate != null && now - candidate.begun > DEADLINE_NANOS && (token = tryLock(i)) != FREE) {
                try {
                    Entry entry = TABLE.get(i);
                    if (entry != null && now - entry.begun > DEADLINE_NANOS && wipe(i, entry)) {
                        EXPIRED.increment();
                    }
                } finally {
                    unlock(i, token);
                }
            }
        }
    }

    /** Wipes every entry, waiting for each one's lock. */
    static void wipeAll() {
        for (int i = 0; i < ENTRIES; i++) {
            if (REQUESTS.get(i) != 0L || TABLE.get(i) != null) {
                long token = lock(i);
                try {
                    if (wipe(i, TABLE.get(i))) {
                        WIPED.increment();
                    }
                } finally {
                    unlock(i, token);
                }
            }
        }
    }

    /** Slot {@code index}'s entry when it holds {@code request}, or {@code null}. */
    private static Entry entry(int index, long request) {
        Entry entry = TABLE.get(index);
        return entry != null && entry.request == request ? entry : null;
    }

    /**
     * Detaches {@code entry} from slot {@code index}, clears it, and frees the slot: only when the slot still holds this
     * very object, so a holder a takeover overtook never frees another request's slot. Returns whether this call
     * detached it, so {@link #LIVE} counts each entry once.
     */
    private static boolean wipe(int index, Entry entry) {
        if (entry == null || !TABLE.compareAndSet(index, entry, null)) {
            return false;
        }
        REQUESTS.compareAndSet(index, entry.request, 0L);
        LIVE.decrementAndGet();
        clear(entry);
        return true;
    }

    /** Clears every field of {@code entry}. */
    private static void clear(Entry entry) {
        for (int i = 0; i < MAX_VALUES; i++) {
            entry.values[i] = null;
            entry.names[i] = null;
        }
        for (int i = 0; i < HASHES; i++) {
            entry.hashes[i] = 0L;
        }
        for (int i = 0; i < MATCHED_HASHES; i++) {
            entry.matchedHashes[i] = 0L;
        }
        entry.matchedCount = 0;
        entry.epoch = 0L;
        entry.count = 0;
        entry.valueChars = 0;
        entry.hashCount = 0;
        entry.checks = 0;
        entry.comparisons = 0L;
        entry.stopped = false;
        entry.late = null;
        entry.lateKeys = null;
    }

    /** Adds one value to {@code entry}, under its lock, if it is within the caps and not already held. */
    private static void add(Entry entry, String name, String value) {
        if (value == null) {
            return;
        }
        int length = value.length();
        if (length < MIN_LENGTH) {
            TOO_SHORT.increment();
            return;
        }
        if (length > MAX_LENGTH) {
            TOO_LONG.increment();
            return;
        }
        for (int i = 0; i < entry.count; i++) {
            if (value.equals(entry.values[i])) {
                return;
            }
        }
        if (entry.count >= MAX_VALUES) {
            OVER_COUNT.increment();
            return;
        }
        entry.values[entry.count] = value;
        entry.names[entry.count] = validName(name) ? name : fallbackName(name);
        entry.count++;
        entry.epoch++;
        entry.valueChars += value.length();
        KEPT.increment();
    }

    /**
     * The name shown for a parameter whose own name is not kept: {@code param#} and four hexadecimal digits of the
     * name's hash, so the same parameter gets the same name in every request without showing it; {@code param#0000}
     * for a value without a name.
     */
    static String fallbackName(String name) {
        int hash = name == null ? 0 : (name.hashCode() & 0xFFFF);
        String hex = Integer.toHexString(hash);
        return "param#" + "0000".substring(hex.length()) + hex;
    }

    /** Whether {@code name} is kept for display: 1 to {@value #MAX_NAME} characters of {@code [A-Za-z0-9_.\-\[\]]}. */
    static boolean validName(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_NAME) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '_'
                    || c == '.'
                    || c == '-'
                    || c == '['
                    || c == ']';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    // ---- matching --------------------------------------------------------------------------------------------------

    /**
     * Checks whether a value of the calling thread's request appears verbatim in {@code text}, a sink of kind
     * {@code kind} ({@link #SINK_SQL}, {@link #SINK_COMMAND}, {@link #SINK_FILE}, {@link #SINK_URL}). Returns the bitmask
     * of the matched values' indices, 0 when none matched or nothing was compared. When {@code spans} (at least
     * {@link #SPANS_LENGTH} long) is given, writes the spans reported ({@link #S_COUNT}), the flags ({@link #S_FLAGS}:
     * {@link #F_PARTIAL}, {@link #F_OVERFLOW}, {@link #F_STOPPED}, {@link #F_REPEATED}, {@link #F_BUSY}), then up to
     * {@value #MAX_SPANS} {@code (index, start, end)} triples, non-overlapping per value, in value order. When
     * {@code names} (at least {@value #MAX_VALUES} long) is given, writes each matched index's parameter name, or
     * {@code param#n}. Never a value. Never throws.
     */
    public static int match(String text, int kind, int[] spans, String[] names) {
        if (spans != null && spans.length > S_FLAGS) {
            spans[S_COUNT] = 0;
            spans[S_FLAGS] = 0;
        }
        if (LIVE.get() == 0 || text == null || sensorGeneration == Long.MIN_VALUE) {
            return 0;
        }
        try {
            if (Reentrancy.sideEffectsSkipped()) {
                return 0;
            }
            Claim claim = AgentBridge.current();
            if (claim == null
                    || !claim.armed
                    || claim.generation != tableGeneration
                    || claim.generation != sensorGeneration) {
                return 0;
            }
            return matchFor(claim, callerRequest(claim), text, kind, spans, names);
        } catch (Throwable ex) {
            ERRORS.increment();
            return 0;
        }
    }

    /** {@link #match} for the request {@code request} the caller already resolved. */
    private static int matchFor(Claim claim, long request, String text, int kind, int[] spans, String[] names) {
        if (request == 0L || text == null) {
            return 0;
        }
        int index = find(request);
        if (index < 0) {
            return 0;
        }
        return compare(index, request, claim.generation, text, kind, spans, names);
    }

    // ---- the sinks -------------------------------------------------------------------------------------------------

    /**
     * An engine-side sink (an SQL statement, a REST client URL) matched the calling thread's request's value named
     * {@code name}: publishes one {@code security-sinks} record of kind {@code kind} with {@code target}, the sink's
     * text already redacted and normalized by the caller ({@code null} when it keeps none), {@code flags} ({@link
     * #POSITION_IN_LITERAL}, {@link #POSITION_OUTSIDE_LITERAL}, {@link #FLAG_NUMERIC}), {@code rawHash} and {@code
     * redactedHash}, the caller's keyed hashes of the raw text and of the redacted text before any masking or
     * normalization, and the code-paths {@code stamp}. Its call site is the first application frame, never
     * the recorder's. Never a value. Never throws.
     */
    public static void sinkMatched(
            int kind, String name, int flags, String target, long rawHash, long redactedHash, long stamp) {
        try {
            Claim claim = AgentBridge.current();
            if (claim == null || !claim.armed || claim.generation != sensorGeneration || name == null) {
                return;
            }
            long request = callerRequest(claim);
            if (request == 0L) {
                return;
            }
            // The application frame only: the first frame outside the JDK is the recorder's, BootUI's own.
            long frames = SideEffects.frames(claim) & 0xFFFFFFFFL;
            publish(
                    claim,
                    request,
                    kind,
                    flags,
                    target,
                    name,
                    rawHash & Long.MAX_VALUE,
                    redactedHash & Long.MAX_VALUE,
                    stamp,
                    frames);
        } catch (Throwable ex) {
            ERRORS.increment();
        }
    }

    /**
     * The processes sensor's {@code ProcessBuilder.start} hook started {@code command}, whose file name is
     * {@code commandName}: each of its first {@value #MAX_ARGUMENTS} elements is checked, and a match publishes the
     * command's file name and the argument's index, never an argument. Returns what the processes sensor's own record
     * names instead of the executable: {@link #FROM_REQUEST_INPUT} when it held a value, {@link #NOT_CHECKED} when the
     * request holds values but the executable could not be checked, or {@code null} to name it. Never throws.
     */
    static String commandStarted(Claim claim, List<String> command, String commandName, long stamp, long frames) {
        String executable = null;
        boolean checking = false;
        try {
            if (LIVE.get() == 0 || command == null || claim.generation != sensorGeneration) {
                return null;
            }
            long request = callerRequest(claim);
            if (request == 0L) {
                return null;
            }
            checking = true;
            int[] spans = new int[SPANS_LENGTH];
            String[] names = new String[MAX_VALUES];
            int size = Math.min(command.size(), MAX_ARGUMENTS);
            for (int i = 0; i < size; i++) {
                String argument = command.get(i);
                spans[S_COUNT] = 0;
                spans[S_FLAGS] = 0;
                int mask = matchFor(claim, request, argument, SINK_COMMAND, spans, names);
                if (mask == 0) {
                    if (i == 0 && unchecked(spans)) {
                        executable = NOT_CHECKED;
                    }
                    continue;
                }
                if (i == 0) {
                    executable = FROM_REQUEST_INPUT;
                }
                String target = i == 0
                        ? "(the executable)"
                        : executable != null ? "(the executable), argument " + i : commandName + ", argument " + i;
                if ((spans[S_FLAGS] & F_SEEN) == 0) {
                    publishEach(claim, request, SINK_COMMAND, 0, target, argument, mask, spans, names, stamp, frames);
                }
            }
        } catch (Throwable ex) {
            ERRORS.increment();
            if (checking && executable == null) {
                // Whether the executable held a value is not known: it is not named.
                executable = NOT_CHECKED;
            }
        }
        return executable;
    }

    /** Whether a check that matched nothing compared nothing, or not the whole text: its text may hold a value. */
    private static boolean unchecked(int[] spans) {
        return (spans[S_FLAGS] & (F_STOPPED | F_BUSY | F_PARTIAL)) != 0;
    }

    /**
     * The files sensor recorded an operation on {@code text}, a path as the application passed it: a match publishes the
     * path pattern of the <b>redacted</b> path, {@code ../{file}} and never the value, or no target when redaction
     * could not cover every occurrence. Returns the pattern the files sensor's own record must name instead of the
     * path's (that redacted pattern, {@link #FROM_REQUEST_INPUT}, or {@link #NOT_CHECKED} when the request holds values
     * but the path could not be checked), or {@code null} when nothing matched. Never throws.
     */
    static String fileUsed(Claim claim, String text, SideEffects.Places where, long stamp, long frames) {
        boolean checking = false;
        try {
            if (LIVE.get() == 0 || text == null || claim.generation != sensorGeneration) {
                return null;
            }
            long request = callerRequest(claim);
            if (request == 0L) {
                return null;
            }
            checking = true;
            int[] spans = new int[SPANS_LENGTH];
            String[] names = new String[MAX_VALUES];
            int mask = matchFor(claim, request, text, SINK_FILE, spans, names);
            if (mask == 0) {
                return unchecked(spans) ? NOT_CHECKED : null;
            }
            String redacted = redact(text, spans, names);
            String target = redacted == null ? null : SideEffects.pattern(SideEffects.absolute(redacted, where), where);
            if ((spans[S_FLAGS] & F_SEEN) == 0) {
                // A path this request already used with the same value: its records already say so.
                publishEach(claim, request, SINK_FILE, 0, target, text, mask, spans, names, stamp, frames);
            }
            return target == null ? FROM_REQUEST_INPUT : target;
        } catch (Throwable ex) {
            ERRORS.increment();
            // Whether the path held a value is not known: it is not named.
            return checking ? NOT_CHECKED : null;
        }
    }

    /** One record per matched parameter of a bridge-side sink, its value's digits-only flag read from its span. */
    private static void publishEach(
            Claim claim,
            long request,
            int kind,
            int position,
            String target,
            String text,
            int mask,
            int[] spans,
            String[] names,
            long stamp,
            long frames) {
        long rawHash = keyedHash(text);
        String redacted = redact(text, spans, names);
        long redactedHash = redacted == null ? 0L : keyedHash(redacted);
        for (int i = 0; i < MAX_VALUES; i++) {
            if ((mask & (1 << i)) == 0) {
                continue;
            }
            int flags = position | numericFlag(text, spans, i);
            if (!reported(spans, i)) {
                flags |= POSITION_UNKNOWN;
            }
            publish(claim, request, kind, flags, target, names[i], rawHash, redactedHash, stamp, frames);
        }
    }

    /** Whether a span of value {@code index} was reported: one past {@value #MAX_SPANS} spans is not. */
    static boolean reported(int[] spans, int index) {
        int count = Math.min(spans[S_COUNT], MAX_SPANS);
        for (int s = 0; s < count; s++) {
            if (spans[S_FIRST + 3 * s] == index) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@link #FLAG_NUMERIC} when the first span of value {@code index} is a number: digits, with an optional leading
     * sign and at most one decimal point, else 0.
     */
    static int numericFlag(String text, int[] spans, int index) {
        int count = Math.min(spans[S_COUNT], MAX_SPANS);
        for (int s = 0; s < count; s++) {
            int slot = S_FIRST + 3 * s;
            if (spans[slot] != index) {
                continue;
            }
            int start = spans[slot + 1];
            int end = spans[slot + 2];
            if (start < 0 || end > text.length() || start >= end) {
                return 0;
            }
            return number(text, start, end) ? FLAG_NUMERIC : 0;
        }
        return 0;
    }

    /**
     * A per-process keyed 64-bit hash of a raw sink text, at most its first {@value #MAX_SCAN} characters, never
     * negative: the engine compares such hashes to tell a text varying with a value from a text that always contains
     * it, and never sees the text.
     */
    public static long keyedHash(String text) {
        long hash = HASH_KEY;
        int length = Math.min(text.length(), MAX_SCAN);
        for (int i = 0; i < length; i++) {
            hash ^= text.charAt(i);
            hash *= 0x100000001B3L;
        }
        hash ^= hash >>> 29;
        hash *= 0xBF58476D1CE4E5B9L;
        hash ^= hash >>> 32;
        return hash & Long.MAX_VALUE;
    }

    /** Publishes one {@code security-sinks} record on the side-effect sensors' ring. */
    private static void publish(
            Claim claim,
            long request,
            int kind,
            int flags,
            String target,
            String name,
            long rawHash,
            long redactedHash,
            long stamp,
            long frames) {
        SideEffects.Owner owner = new SideEffects.Owner();
        owner.generation = claim.generation;
        owner.request = request;
        owner.threadKind = ThreadPropagation.isVirtual(Thread.currentThread())
                ? SideEffects.THREAD_VIRTUAL
                : SideEffects.THREAD_PLATFORM;
        long now = System.currentTimeMillis();
        long[] values = new long[SideEffects.RECORD];
        values[SideEffects.R_SENSOR] = SideEffects.SENSOR_SECURITY_SINKS;
        values[SideEffects.R_KIND] = kind;
        values[SideEffects.R_GENERATION] = claim.generation;
        values[SideEffects.R_FIRST_MILLIS] = now;
        values[SideEffects.R_LAST_MILLIS] = now;
        values[SideEffects.R_REQUEST] = request;
        values[SideEffects.R_STAMP] = stamp;
        values[SideEffects.R_TARGET] = internQuota(target);
        values[SideEffects.R_FLAGS] = SideEffects.flags(flags & 0xFF, owner, internQuota(name));
        values[SideEffects.R_COUNT] = 1L;
        values[SideEffects.R_NANOS] = rawHash;
        values[SideEffects.R_MAX_NANOS] = redactedHash;
        values[SideEffects.R_FRAMES] = frames;
        if (SideEffects.publish(SideEffects.SENSOR_SECURITY_SINKS, values)) {
            PUBLISHED.increment();
        } else {
            DROPPED.increment();
        }
    }

    /** {@code text}'s id among the side-effect sensors' strings, within this holder's quota; 0 past it, counted. */
    private static int internQuota(String text) {
        if (text == null) {
            return 0;
        }
        int known = SideEffects.internedId(text);
        if (known > 0) {
            return known;
        }
        if (INTERNED.get() >= INTERN_QUOTA) {
            NOT_KEPT.increment();
            return 0;
        }
        int id = SideEffects.intern(text);
        if (id != 0) {
            INTERNED.incrementAndGet();
        } else {
            NOT_KEPT.increment();
        }
        return id;
    }

    private static int compare(
            int index, long request, long generation, String text, int kind, int[] spans, String[] names) {
        boolean partial = text.length() > MAX_SCAN;
        String scanned = partial ? text.substring(0, MAX_SCAN) : text;
        long hash = textHash(text, scanned, kind);
        boolean spansFit = spans == null || spans.length >= SPANS_LENGTH;
        long token = tryLock(index);
        if (token == FREE) {
            return busy(spans);
        }
        Map<?, ?> late;
        String[] lateKeys;
        Entry held;
        try {
            held = entry(index, request);
            if (held == null || held.generation != generation) {
                return 0;
            }
            late = held.late;
            lateKeys = held.lateKeys;
        } finally {
            unlock(index, token);
        }
        Object[] pulled = late == null ? null : pull(late, lateKeys);
        token = tryLock(index);
        if (token == FREE) {
            return busy(spans);
        }
        String[] found = null;
        int mask;
        try {
            Entry entry = TABLE.get(index);
            if (entry != held) {
                // The request's entry was wiped between the two locks, and the slot perhaps taken by another request.
                return 0;
            }
            if (late != null && entry.late == late && pulled != null) {
                for (int i = 0; i + 1 < pulled.length; i += 2) {
                    add(entry, (String) pulled[i], (String) pulled[i + 1]);
                }
                // A text that matched none of the earlier values may hold a new one, and one that matched, another.
                entry.hashCount = 0;
                entry.matchedCount = 0;
                entry.late = null;
                entry.lateKeys = null;
            }
            if (entry.count == 0) {
                return 0;
            }
            if (entry.stopped) {
                return flag(spans, F_STOPPED);
            }
            // The text's key under the values held now: one remembered before a value was added is checked again.
            long key = hash ^ (entry.epoch * 0x9E3779B97F4A7C15L);
            // Before the budgets: a statement repeated in a loop costs neither a check nor comparisons. Only texts that
            // matched nothing are remembered, so a repeated text holding a value is compared again and redacted again.
            for (int i = 0; i < entry.hashCount && i < HASHES; i++) {
                if (entry.hashes[i] == key) {
                    REPEATED.increment();
                    return flag(spans, F_REPEATED);
                }
            }
            if (!owns(index, token, entry)) {
                return busy(spans);
            }
            boolean seen = false;
            for (int i = 0; i < entry.matchedCount && i < MATCHED_HASHES; i++) {
                seen |= entry.matchedHashes[i] == key;
            }
            if (!seen && ++entry.checks > MAX_CHECKS) {
                entry.stopped = true;
                STOPPED.increment();
                return flag(spans, F_STOPPED);
            }
            // Each value is searched for across the whole scanned text: the work grows with every value's length.
            long cost = (long) scanned.length() * entry.valueChars;
            if (entry.comparisons + cost > MAX_COMPARISONS) {
                entry.stopped = true;
                STOPPED.increment();
                return flag(spans, F_STOPPED);
            }
            entry.comparisons += cost;
            CHECKS.increment();
            mask = 0;
            int written = 0;
            int flags = (partial ? F_PARTIAL : 0) | (spansFit ? 0 : F_OVERFLOW) | (seen ? F_SEEN : 0);
            for (int i = 0; i < entry.count; i++) {
                String value = entry.values[i];
                int from = 0;
                int at;
                while ((at = scanned.indexOf(value, from)) >= 0) {
                    mask |= 1 << i;
                    if (written < MAX_SPANS) {
                        if (spans != null && spans.length >= S_FIRST + 3 * (written + 1)) {
                            int slot = S_FIRST + 3 * written;
                            spans[slot] = i;
                            spans[slot + 1] = at;
                            spans[slot + 2] = at + value.length();
                        }
                        written++;
                    } else {
                        flags |= F_OVERFLOW;
                    }
                    from = at + value.length();
                }
            }
            if (mask != 0 && names != null) {
                found = new String[MAX_VALUES];
                for (int i = 0; i < entry.count; i++) {
                    if ((mask & (1 << i)) != 0) {
                        found[i] = entry.names[i];
                    }
                }
            }
            if (mask == 0 && !partial && owns(index, token, entry)) {
                // Only a text scanned whole is remembered as holding nothing: a partly scanned one is checked again,
                // and
                // reported partial again, each time, so a repeat never reads as checked.
                entry.hashes[entry.hashCount % HASHES] = key;
                entry.hashCount++;
            } else if (mask != 0 && !seen && owns(index, token, entry)) {
                entry.matchedHashes[entry.matchedCount % MATCHED_HASHES] = key;
                entry.matchedCount++;
            }
            if (spans != null && spans.length > S_FLAGS) {
                spans[S_COUNT] = written;
                spans[S_FLAGS] = flags;
            }
            if (partial) {
                PARTIAL.increment();
            }
            if ((flags & F_OVERFLOW) != 0) {
                OVERFLOW.increment();
            }
        } finally {
            unlock(index, token);
        }
        if (mask != 0) {
            MATCHED.increment();
            if (found != null) {
                for (int i = 0; i < MAX_VALUES && i < names.length; i++) {
                    if ((mask & (1 << i)) != 0) {
                        names[i] = found[i] != null ? found[i] : fallbackName(null);
                    }
                }
            }
        }
        return mask;
    }

    /** Whether {@code text}'s characters {@code start} to {@code end} are a number, as {@code 42}, {@code -33.8688}. */
    static boolean number(CharSequence text, int start, int end) {
        int c = start;
        if (c < end && (text.charAt(c) == '-' || text.charAt(c) == '+')) {
            c++;
        }
        boolean digit = false;
        boolean point = false;
        for (; c < end; c++) {
            char ch = text.charAt(c);
            if (ch >= '0' && ch <= '9') {
                digit = true;
            } else if (ch == '.' && !point) {
                point = true;
            } else {
                return false;
            }
        }
        return digit;
    }

    /**
     * A sink text's repeat key: its kind, its length, and a 64-bit hash of its scanned prefix, computed outside any
     * lock.
     */
    static long textHash(String text, String scanned, int kind) {
        // FNV-1a over 64 bits: a text holding a value colliding with one that held none would be skipped unchecked.
        long hash = 0xcbf29ce484222325L ^ ((long) kind << 56) ^ text.length();
        for (int i = 0; i < scanned.length(); i++) {
            hash = (hash ^ scanned.charAt(i)) * 0x100000001b3L;
        }
        return hash;
    }

    /** Whether {@code value}'s class is the JDK's (loaded by the bootstrap class loader). */
    private static boolean jdkType(Object value) {
        return value.getClass().getClassLoader() == null;
    }

    private static int busy(int[] spans) {
        BUSY.increment();
        return flag(spans, F_BUSY);
    }

    private static int flag(int[] spans, int flag) {
        if (spans != null && spans.length > S_FLAGS) {
            spans[S_FLAGS] |= flag;
        }
        return 0;
    }

    /**
     * Reads the values a {@code late} map holds under {@code keys}, outside any lock: {@code {name, value, ...}} for
     * each string-to-string entry of a map found there, at most {@value #MAX_VALUES} pairs, or {@code null} when no key
     * holds a map yet.
     */
    private static Object[] pull(Map<?, ?> late, String[] keys) {
        if (keys == null || !Reentrancy.enter()) {
            return null;
        }
        try {
            return pullGuarded(late, keys);
        } finally {
            Reentrancy.exit();
        }
    }

    private static Object[] pullGuarded(Map<?, ?> late, String[] keys) {
        Object[] pairs = null;
        int pairCount = 0;
        for (int k = 0; k < keys.length; k++) {
            Object found = keys[k] == null ? null : late.get(keys[k]);
            if (!(found instanceof Map) || !jdkType(found)) {
                continue;
            }
            if (pairs == null) {
                pairs = new Object[2 * MAX_VALUES];
            }
            Iterator<?> entries = ((Map<?, ?>) found).entrySet().iterator();
            while (entries.hasNext() && pairCount < MAX_VALUES) {
                Map.Entry<?, ?> pair = (Map.Entry<?, ?>) entries.next();
                if (pair.getKey() instanceof String && pair.getValue() instanceof String) {
                    pairs[2 * pairCount] = pair.getKey();
                    pairs[2 * pairCount + 1] = pair.getValue();
                    pairCount++;
                }
            }
        }
        if (pairs == null) {
            return null;
        }
        Object[] trimmed = new Object[2 * pairCount];
        System.arraycopy(pairs, 0, trimmed, 0, trimmed.length);
        return trimmed;
    }

    /**
     * The request whose values the calling thread may be checked against, 0 when none: the top owner slot's request when
     * it is a scope naming one with no child execution; nothing when it is a handoff or a scope of an execution; the
     * claim's capture when the thread has no slot or a scope that captured no owner, refused when the capture names a
     * child execution ({@code async-}, {@code task-}, or an execution of its own).
     */
    static long callerRequest(Claim claim) {
        CodePaths.Frame frame = CodePaths.FRAME.get();
        if (frame != null && frame.slots > 0) {
            int index = frame.slots - 1;
            if (index >= SideEffects.SLOTS || frame.slotSource == null) {
                REFUSED_HANDOFF.increment();
                return 0L;
            }
            if (frame.slotSource[index] != SideEffects.SLOT_SCOPE) {
                REFUSED_HANDOFF.increment();
                return 0L;
            }
            if (frame.slotGeneration[index] == claim.generation
                    && (frame.slotRequest[index] != 0L || frame.slotExecution[index] != 0L)) {
                if (frame.slotExecution[index] != 0L) {
                    REFUSED_EXECUTION.increment();
                    return 0L;
                }
                return frame.slotRequest[index];
            }
        }
        Object payload = CodePaths.capture(claim);
        if (!(payload instanceof Object[])) {
            return 0L;
        }
        Object[] values = (Object[]) payload;
        if (values.length > 1 && values[1] != null) {
            REFUSED_EXECUTION.increment();
            return 0L;
        }
        return values.length > 0 && values[0] instanceof String ? CodeInventory.parseRequestId((String) values[0]) : 0L;
    }

    /**
     * {@code text} with every span {@link #match} reported replaced by {@code {name}}, overlapping spans merged under
     * the first one's name; {@code null} when the check overflowed or was partial, whose spans do not cover the text, so
     * the caller keeps no text at all (fails closed). {@code spans} and {@code names} are what {@link #match} wrote.
     */
    public static String redact(String text, int[] spans, String[] names) {
        try {
            return redactChecked(text, spans, names);
        } catch (Throwable ex) {
            ERRORS.increment();
            return null;
        }
    }

    private static String redactChecked(String text, int[] spans, String[] names) {
        if (text == null || spans == null || spans.length < SPANS_LENGTH) {
            return null;
        }
        if ((spans[S_FLAGS] & ~F_SEEN) != 0) {
            // Partial, overflowed, or not compared at all (stopped, repeated, busy): the spans may not cover every
            // occurrence, so no text is kept. A text seen before was compared in full.
            return null;
        }
        int count = spans[S_COUNT];
        if (count <= 0 || count > MAX_SPANS) {
            return null;
        }
        int[] order = new int[count];
        for (int i = 0; i < count; i++) {
            order[i] = i;
        }
        // Insertion sort by start, the longer span first at one start, at most eight spans.
        for (int i = 1; i < count; i++) {
            int current = order[i];
            int j = i - 1;
            while (j >= 0 && after(spans, order[j], current)) {
                order[j + 1] = order[j];
                j--;
            }
            order[j + 1] = current;
        }
        StringBuilder out = new StringBuilder(text.length());
        int position = 0;
        for (int i = 0; i < count; i++) {
            int slot = S_FIRST + 3 * order[i];
            int index = spans[slot];
            int start = spans[slot + 1];
            int end = spans[slot + 2];
            if (start < 0 || end > text.length() || start > end) {
                return null;
            }
            if (end <= position) {
                continue;
            }
            if (start >= position) {
                out.append(text, position, start);
                String name = names != null && index >= 0 && index < names.length ? names[index] : null;
                if (name == null) {
                    return null;
                }
                out.append('{').append(name).append('}');
            }
            position = end;
        }
        out.append(text, position, text.length());
        return out.toString();
    }

    /** Whether span {@code a} sorts after span {@code b}: a later start, or the same start and a shorter span. */
    private static boolean after(int[] spans, int a, int b) {
        int startA = spans[S_FIRST + 3 * a + 1];
        int startB = spans[S_FIRST + 3 * b + 1];
        return startA > startB || (startA == startB && spans[S_FIRST + 3 * a + 2] < spans[S_FIRST + 3 * b + 2]);
    }

    // ---- the table -------------------------------------------------------------------------------------------------

    /** The entry holding {@code request}, or -1, without a lock: probed from the request's slot. */
    private static int find(long request) {
        int start = slot(request);
        for (int i = 0; i < ENTRIES; i++) {
            int index = (start + i) & (ENTRIES - 1);
            if (REQUESTS.get(index) == request) {
                return index;
            }
        }
        return -1;
    }

    /**
     * Takes a free slot for {@code request} with a new entry, and returns its index, locked under the calling thread's
     * {@link #currentToken()}, or -1 when none is free. The entry is a new object, so nothing a holder overtaken by a
     * takeover wrote into the slot's previous one is ever reachable from it.
     */
    private static int claimFree(long request, long generation, long now) {
        int start = slot(request);
        for (int i = 0; i < ENTRIES; i++) {
            int index = (start + i) & (ENTRIES - 1);
            long token;
            if (REQUESTS.get(index) == 0L && (token = tryLock(index)) != FREE) {
                if (REQUESTS.compareAndSet(index, 0L, request)) {
                    if (TABLE.compareAndSet(index, null, new Entry(request, generation, now))) {
                        LIVE.incrementAndGet();
                        return index;
                    }
                    // A wipe still detaching the slot's previous entry: the slot is not free yet.
                    REQUESTS.compareAndSet(index, request, 0L);
                }
                unlock(index, token);
            }
        }
        return -1;
    }

    private static int slot(long request) {
        long mixed = request * 0x9E3779B97F4A7C15L;
        return (int) (mixed >>> 57) & (ENTRIES - 1);
    }

    /** A new lock token for the calling thread, never {@link #FREE}: its id, then the next of its own sequence. */
    private static long token() {
        long[] state = THREAD_TOKEN.get();
        if (state == null) {
            int id;
            do {
                id = TOKENS.incrementAndGet();
            } while (id == 0);
            state = new long[] {id, 0L};
            THREAD_TOKEN.set(state);
        }
        state[1] = (state[1] + 1L) & 0xFFFFFFFFL;
        return (state[0] << 32) | state[1];
    }

    /** The calling thread's last lock token, as {@link #token()} returned it. */
    private static long currentToken() {
        long[] state = THREAD_TOKEN.get();
        return state == null ? FREE : (state[0] << 32) | state[1];
    }

    /** The entry's lock, or {@link #FREE} when another thread holds it. */
    private static long tryLock(int index) {
        if (LOCKS.get(index) != FREE) {
            return FREE;
        }
        long token = token();
        return LOCKS.compareAndSet(index, FREE, token) ? token : FREE;
    }

    /**
     * Spins for the entry's lock, held only for bounded JDK string work by another thread, and returns its token. One
     * held past {@link #LOCK_WAIT_NANOS} is taken over, as from a thread that died inside, so a request's end or a claim
     * is never blocked: the overtaken holder, if it still runs, sees the lock is no longer its own before each write and
     * stops, and its unlock releases nothing.
     */
    private static long lock(int index) {
        long token = token();
        if (LOCKS.compareAndSet(index, FREE, token)) {
            return token;
        }
        long deadline = System.nanoTime() + LOCK_WAIT_NANOS;
        long seen = FREE;
        int spins = 0;
        while (true) {
            long held = LOCKS.get(index);
            if (held != seen) {
                // Another holder took the lock: its own wait starts now, so a lock is taken over only from a holder
                // that kept it the whole time, never from one that just took it.
                seen = held;
                deadline = System.nanoTime() + LOCK_WAIT_NANOS;
            }
            if (held == FREE) {
                if (LOCKS.compareAndSet(index, FREE, token)) {
                    return token;
                }
            } else if (System.nanoTime() - deadline > 0L && LOCKS.compareAndSet(index, held, token)) {
                FORCED.increment();
                return token;
            }
            if (++spins < 64) {
                Thread.onSpinWait();
            } else {
                Thread.yield();
            }
        }
    }

    /** Whether the caller still holds slot {@code index}, and the slot still holds {@code entry}: never after a takeover. */
    private static boolean owns(int index, long token, Entry entry) {
        return LOCKS.get(index) == token && TABLE.get(index) == entry;
    }

    /** Releases the entry's lock if {@code token} still holds it: an overtaken holder releases nothing. */
    private static void unlock(int index, long token) {
        LOCKS.compareAndSet(index, token, FREE);
    }

    // ---- status ----------------------------------------------------------------------------------------------------

    /** The holder's counters, never a value or a name. */
    public static Map<String, Object> status() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        try {
            map.put("active", Boolean.valueOf(active()));
            map.put("live", Integer.valueOf(LIVE.get()));
            map.put("requests", Long.valueOf(BEGUN.sum()));
            map.put("tableFull", Long.valueOf(TABLE_FULL.sum()));
            map.put("values", Long.valueOf(KEPT.sum()));
            map.put("valuesTooShort", Long.valueOf(TOO_SHORT.sum()));
            map.put("valuesTooLong", Long.valueOf(TOO_LONG.sum()));
            map.put("valuesOverCount", Long.valueOf(OVER_COUNT.sum()));
            map.put("ended", Long.valueOf(ENDED.sum()));
            map.put("expired", Long.valueOf(EXPIRED.sum()));
            map.put("wiped", Long.valueOf(WIPED.sum()));
            map.put("checks", Long.valueOf(CHECKS.sum()));
            map.put("matched", Long.valueOf(MATCHED.sum()));
            map.put("partial", Long.valueOf(PARTIAL.sum()));
            map.put("overflow", Long.valueOf(OVERFLOW.sum()));
            map.put("stopped", Long.valueOf(STOPPED.sum()));
            map.put("repeated", Long.valueOf(REPEATED.sum()));
            map.put("busy", Long.valueOf(BUSY.sum()));
            map.put("refusedHandoff", Long.valueOf(REFUSED_HANDOFF.sum()));
            map.put("refusedExecution", Long.valueOf(REFUSED_EXECUTION.sum()));
            map.put("lockTakeovers", Long.valueOf(FORCED.sum()));
            map.put("published", Long.valueOf(PUBLISHED.sum()));
            map.put("dropped", Long.valueOf(DROPPED.sum()));
            map.put("notKept", Long.valueOf(NOT_KEPT.sum()));
            map.put("errors", Long.valueOf(ERRORS.sum()));
        } catch (Throwable ex) {
            ERRORS.increment();
        }
        return map;
    }

    /** Tests only: the entries holding a request. */
    static int live() {
        return LIVE.get();
    }

    /** Tests only: every entry wiped, the gate closed, and the counters reset. */
    static void reset() {
        sensorGeneration = Long.MIN_VALUE;
        wipeAll();
        tableGeneration = Long.MIN_VALUE;
        INTERNED.set(0);
        LongAdder[] adders = {
            BEGUN,
            TABLE_FULL,
            KEPT,
            TOO_SHORT,
            TOO_LONG,
            OVER_COUNT,
            ENDED,
            EXPIRED,
            WIPED,
            CHECKS,
            MATCHED,
            PARTIAL,
            OVERFLOW,
            STOPPED,
            REPEATED,
            BUSY,
            REFUSED_HANDOFF,
            REFUSED_EXECUTION,
            FORCED,
            PUBLISHED,
            DROPPED,
            NOT_KEPT,
            ERRORS
        };
        for (int i = 0; i < adders.length; i++) {
            adders[i].reset();
        }
    }

    /** Tests only: ages every entry past the deadline. */
    static void expireAll() {
        for (int i = 0; i < ENTRIES; i++) {
            Entry entry = TABLE.get(i);
            if (entry != null) {
                long token = lock(i);
                try {
                    entry.begun -= DEADLINE_NANOS + 1L;
                } finally {
                    unlock(i, token);
                }
            }
        }
    }
}
