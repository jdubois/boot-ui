package io.github.jdubois.bootui.agent.bridge;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLongArray;
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
 * (text length times values); past either budget matching stops for the request, counted. An identical sink text of
 * the same kind seen again by the request, as one SQL statement inside a loop, is not checked again (the last
 * {@value #HASHES} text hashes). Only parameter indices, their names, and span offsets leave it: <b>never a value</b>.
 * The caller redacts every matched span ({@link #redact}) before it builds any target, record, or log.
 *
 * <p><b>Never stored, logged, or displayed.</b> No {@code toString}, no logging, no accessor for a value. No monitor is
 * taken: each entry has a try-lock taken by compare-and-set, held only for bounded JDK string work, never while
 * application code runs; a check that finds its entry busy is skipped and counted rather than waiting. JDK types only;
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

    /** Character comparisons per request at most: the sum over its checks of scanned length times values. */
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

    private static final int FREE = 0;
    private static final int LOCKED = 1;

    /** Each entry's request id, 0 when free; written only under the entry's lock, read without it to find an entry. */
    private static final AtomicLongArray REQUESTS = new AtomicLongArray(ENTRIES);

    /** Each entry's try-lock. */
    private static final AtomicIntegerArray LOCKS = new AtomicIntegerArray(ENTRIES);

    private static final Entry[] TABLE = entries();

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
    private static final LongAdder ERRORS = new LongAdder();

    private RequestValues() {}

    private static Entry[] entries() {
        Entry[] entries = new Entry[ENTRIES];
        for (int i = 0; i < ENTRIES; i++) {
            entries[i] = new Entry();
        }
        return entries;
    }

    /** One request's values: every field is guarded by the entry's lock. No {@code toString}, by design. */
    private static final class Entry {
        final String[] values = new String[MAX_VALUES];
        final String[] names = new String[MAX_VALUES];
        final long[] hashes = new long[HASHES];
        int count;
        int hashCount;
        long generation;
        long begun;
        int checks;
        long comparisons;
        boolean stopped;
        /** Where values appear later, as WebFlux's path variables once a handler mapping set them, and their keys. */
        Map<?, ?> late;

        String[] lateKeys;
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
            boolean fresh = false;
            if (index < 0) {
                index = claimFree(request);
                if (index < 0) {
                    TABLE_FULL.increment();
                    return -1;
                }
                fresh = true;
            } else {
                lock(index);
                if (REQUESTS.get(index) != request) {
                    unlock(index);
                    return -1;
                }
            }
            try {
                Entry entry = TABLE[index];
                if (fresh) {
                    entry.generation = generation;
                    entry.begun = now;
                    BEGUN.increment();
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
                if (entry.generation != tableGeneration || entry.generation != sensorGeneration) {
                    // A claim change or the sensor going off raced this push: its wipe may have passed this entry.
                    wipe(index);
                    return -1;
                }
                return entry.count;
            } finally {
                unlock(index);
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
                    lock(i);
                    try {
                        if (REQUESTS.get(i) == request) {
                            wipe(i);
                            ENDED.increment();
                        }
                    } finally {
                        unlock(i);
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
            if (REQUESTS.get(i) != 0L && tryLock(i)) {
                try {
                    if (REQUESTS.get(i) != 0L && now - TABLE[i].begun > DEADLINE_NANOS) {
                        wipe(i);
                        EXPIRED.increment();
                    }
                } finally {
                    unlock(i);
                }
            }
        }
    }

    /** Wipes every entry, waiting for each one's lock. */
    static void wipeAll() {
        for (int i = 0; i < ENTRIES; i++) {
            if (REQUESTS.get(i) != 0L) {
                lock(i);
                try {
                    if (REQUESTS.get(i) != 0L) {
                        wipe(i);
                        WIPED.increment();
                    }
                } finally {
                    unlock(i);
                }
            }
        }
    }

    /** Clears entry {@code index}, under its lock, so it reaches no value. */
    private static void wipe(int index) {
        Entry entry = TABLE[index];
        for (int i = 0; i < MAX_VALUES; i++) {
            entry.values[i] = null;
            entry.names[i] = null;
        }
        for (int i = 0; i < HASHES; i++) {
            entry.hashes[i] = 0L;
        }
        entry.count = 0;
        entry.hashCount = 0;
        entry.generation = 0L;
        entry.begun = 0L;
        entry.checks = 0;
        entry.comparisons = 0L;
        entry.stopped = false;
        entry.late = null;
        entry.lateKeys = null;
        REQUESTS.set(index, 0L);
        LIVE.decrementAndGet();
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
            long request = callerRequest(claim);
            if (request == 0L) {
                return 0;
            }
            int index = find(request);
            if (index < 0) {
                return 0;
            }
            return compare(index, request, claim.generation, text, kind, spans, names);
        } catch (Throwable ex) {
            ERRORS.increment();
            return 0;
        }
    }

    private static int compare(
            int index, long request, long generation, String text, int kind, int[] spans, String[] names) {
        boolean partial = text.length() > MAX_SCAN;
        String scanned = partial ? text.substring(0, MAX_SCAN) : text;
        long hash = textHash(text, scanned, kind);
        boolean spansFit = spans == null || spans.length >= SPANS_LENGTH;
        if (!tryLock(index)) {
            return busy(spans);
        }
        Map<?, ?> late;
        String[] lateKeys;
        try {
            Entry entry = TABLE[index];
            if (REQUESTS.get(index) != request || entry.generation != generation) {
                return 0;
            }
            late = entry.late;
            lateKeys = entry.lateKeys;
        } finally {
            unlock(index);
        }
        Object[] pulled = late == null ? null : pull(late, lateKeys);
        if (!tryLock(index)) {
            return busy(spans);
        }
        String[] found = null;
        int mask;
        try {
            Entry entry = TABLE[index];
            if (REQUESTS.get(index) != request || entry.generation != generation) {
                return 0;
            }
            if (late != null && entry.late == late && pulled != null) {
                for (int i = 0; i + 1 < pulled.length; i += 2) {
                    add(entry, (String) pulled[i], (String) pulled[i + 1]);
                }
                entry.late = null;
                entry.lateKeys = null;
            }
            if (entry.count == 0) {
                return 0;
            }
            if (entry.stopped) {
                return flag(spans, F_STOPPED);
            }
            // Before the budgets: a statement repeated in a loop costs neither a check nor comparisons.
            for (int i = 0; i < entry.hashCount && i < HASHES; i++) {
                if (entry.hashes[i] == hash) {
                    REPEATED.increment();
                    return flag(spans, F_REPEATED);
                }
            }
            if (++entry.checks > MAX_CHECKS) {
                entry.stopped = true;
                STOPPED.increment();
                return flag(spans, F_STOPPED);
            }
            long cost = (long) scanned.length() * entry.count;
            if (entry.comparisons + cost > MAX_COMPARISONS) {
                entry.stopped = true;
                STOPPED.increment();
                return flag(spans, F_STOPPED);
            }
            entry.comparisons += cost;
            entry.hashes[entry.hashCount % HASHES] = hash;
            entry.hashCount++;
            CHECKS.increment();
            mask = 0;
            int written = 0;
            int flags = (partial ? F_PARTIAL : 0) | (spansFit ? 0 : F_OVERFLOW);
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
            unlock(index);
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

    /**
     * A sink text's repeat key: its kind, its length, and the hash of its scanned prefix (the {@code String}'s own
     * cached hash when the whole text is scanned), computed outside any lock.
     */
    static long textHash(String text, String scanned, int kind) {
        int hash = scanned.hashCode();
        return ((long) kind << 56) ^ ((long) text.length() << 32) ^ (hash & 0xFFFFFFFFL);
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
        if (spans[S_FLAGS] != 0) {
            // Partial, overflowed, or not compared at all (stopped, repeated, busy): the spans may not cover every
            // occurrence, so no text is kept.
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

    /** Takes a free entry for {@code request} and returns it locked, or -1 when none is free. */
    private static int claimFree(long request) {
        int start = slot(request);
        for (int i = 0; i < ENTRIES; i++) {
            int index = (start + i) & (ENTRIES - 1);
            if (REQUESTS.get(index) == 0L && tryLock(index)) {
                if (REQUESTS.get(index) == 0L) {
                    REQUESTS.set(index, request);
                    LIVE.incrementAndGet();
                    return index;
                }
                unlock(index);
            }
        }
        return -1;
    }

    private static int slot(long request) {
        long mixed = request * 0x9E3779B97F4A7C15L;
        return (int) (mixed >>> 57) & (ENTRIES - 1);
    }

    private static boolean tryLock(int index) {
        return LOCKS.compareAndSet(index, FREE, LOCKED);
    }

    /** Spins for the entry's lock, held only for bounded JDK string work by another thread. */
    private static void lock(int index) {
        if (LOCKS.compareAndSet(index, FREE, LOCKED)) {
            return;
        }
        long deadline = System.nanoTime() + LOCK_WAIT_NANOS;
        int spins = 0;
        while (!LOCKS.compareAndSet(index, FREE, LOCKED)) {
            if (System.nanoTime() - deadline > 0L) {
                // Its holder does bounded string work only: one that held it this long died inside, as of a stack
                // overflow in its unlock, so the entry is taken over rather than ever blocking a request or a claim.
                LOCKS.set(index, LOCKED);
                FORCED.increment();
                return;
            }
            if (++spins < 64) {
                Thread.onSpinWait();
            } else {
                Thread.yield();
            }
        }
    }

    private static void unlock(int index) {
        LOCKS.set(index, FREE);
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
            ERRORS
        };
        for (int i = 0; i < adders.length; i++) {
            adders[i].reset();
        }
    }

    /** Tests only: ages every entry past the deadline. */
    static void expireAll() {
        for (int i = 0; i < ENTRIES; i++) {
            if (REQUESTS.get(i) != 0L) {
                lock(i);
                try {
                    TABLE[i].begun -= DEADLINE_NANOS + 1L;
                } finally {
                    unlock(i);
                }
            }
        }
    }
}
