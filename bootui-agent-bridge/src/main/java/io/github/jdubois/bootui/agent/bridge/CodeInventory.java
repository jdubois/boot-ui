package io.github.jdubois.bootui.agent.bridge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The {@code inventory} sensor's bridge side (PLAN-v2 §5.15, M5-3): which application methods ran in this run, and which
 * code sources (jars and class directories) loaded classes. JDK types only: method keys, ids, flags, and location
 * strings, never a {@code Class}, {@code ClassLoader}, or {@code ProtectionDomain}.
 *
 * <p><b>Method ids.</b> {@link #methodId} gives each method key ({@code className#name+descriptor}) an id that is stable
 * for the agent's lifetime, so HotSwap, retransformation, restarts, and reloads reuse it. At most {@value #MAX_METHODS}
 * ids exist; past that a method gets no id and no advice, counted.
 *
 * <p><b>Hit flags.</b> The advice the agent inlines at every instrumented method's entry is
 * {@code if (CodeInventory.HITS[id] != CodeInventory.epoch) CodeInventory.hit(id);}: once a method ran in this run, a
 * call costs one array read and one volatile read. A claim asking for the sensor moves {@link #epoch} to its next value
 * (1 to 255, all flags cleared when it wraps), so "executed in this run" is {@code HITS[id] == epoch} and a new run
 * costs no clearing. Before any such claim the epoch is 0, which every flag already equals: advice costs a read and
 * records nothing. {@link #hit} sets the flag of a method's first call in a run whatever the claim's state, so the
 * advice takes the fast path from then on even while the claim is disarmed, taken over by a claim without the sensor,
 * or disabled; only recording is gated on an armed claim of the run's generation. It records a method's first call in
 * an armed run once: unless the thread is already inside a capture or doing BootUI's own work, it captures the
 * correlation once and publishes a {@link #FIRST_HIT} record when the call belongs to a request or an execution. A
 * first call with nothing to capture, as at startup, is marked by its flag alone.
 *
 * <p><b>Tracking.</b> The agent reports per method whether its class was instrumented ({@link #TRACKED}) or failed to
 * transform ({@link #TRANSFORM_FAILED}), so the engine counts executed and never-executed methods only among those it
 * could see. Advice is kept across claims (D34), so that state is too, until a release restores the classes. Each
 * instrumentation also marks its methods tracked in the current run ({@link #snapshot}'s {@code trackedThisRun}), so the
 * engine can tell a class instrumented in this run from one whose class loader only loaded it in an earlier run: a
 * DevTools restart's new class loader has not loaded a class until its methods are marked again. A class whose
 * transformation failed before any of its methods got an id, and a class some of whose methods got none past
 * {@link #MAX_METHODS}, are named ({@link #transformFailed(String, int[])}, {@link #overLimit}), so the engine can give
 * its methods the reason. A class the agent instrumented only after it was loaded (retransformed at the install or a
 * refine) may have run before, unseen: its methods are marked late for the run in which that happened, so the engine can
 * say they were not tracked from the run's start. {@link #snapshot} reads all of it at once for one claim generation,
 * and {@link #version()} changes whenever any of it does.
 *
 * <p><b>Class loads.</b> {@link #classLoaded} counts the classes each code source defines, per claim generation and in
 * total, with the first load's time; the first class of a code source in a generation publishes a {@link #CLASS_LOAD}
 * record with the route that loaded it, captured only when no capture is in progress on the thread. Loads the engine
 * marks as BootUI's own work ({@link AgentBridge#bootUiWork}) are skipped.
 */
public final class CodeInventory {

    public static final String SENSOR = "inventory";

    /** The most method ids, and the length of {@link #HITS}. */
    public static final int MAX_METHODS = 1 << 18;

    /** The most code sources counted. */
    public static final int MAX_CODE_SOURCES = 4096;

    /** Record types: a method's first call in a run, and a code source's first class in a run. */
    public static final int FIRST_HIT = 1;

    public static final int CLASS_LOAD = 2;

    /** Tracking states, per method id. */
    public static final byte UNKNOWN = 0;

    public static final byte TRACKED = 1;
    public static final byte TRANSFORM_FAILED = 2;

    private static final int PAGE_BITS = 12;
    private static final int PAGE_SIZE = 1 << PAGE_BITS;

    /** Per method id: the epoch of the run in which it last ran. Read by inlined advice: never replaced. */
    public static final byte[] HITS = new byte[MAX_METHODS];

    /** The current run's epoch, 1 to 255; 0 before any claim asked for the sensor. Read by inlined advice. */
    public static volatile byte epoch;

    private static final byte[] TRACKING = new byte[MAX_METHODS];

    /** Per method id: the epoch of the run in which its class was last instrumented, else 0. */
    private static final byte[] TRACKED_EPOCH = new byte[MAX_METHODS];

    /** The most class names kept as failed or over the limit; past it, the overflow is counted. */
    static final int MAX_NAMED_CLASSES = 4096;

    /** Classes whose transformation failed and that no later transformation instrumented, by binary name. */
    private static final ConcurrentHashMap<String, Boolean> FAILED_CLASSES = new ConcurrentHashMap<String, Boolean>();

    /** Classes some of whose methods got no id past {@link #MAX_METHODS}, by binary name. */
    private static final ConcurrentHashMap<String, Boolean> OVER_LIMIT_CLASSES =
            new ConcurrentHashMap<String, Boolean>();

    /** Changes whenever a flag, a tracking state, or the run does: a cheap fingerprint for readers. */
    private static final AtomicLong VERSION = new AtomicLong();

    /** Per method id: the epoch of the run in which its class was instrumented after it had loaded, else 0. */
    private static final byte[] LATE = new byte[MAX_METHODS];

    private static final ConcurrentHashMap<String, Integer> IDS = new ConcurrentHashMap<String, Integer>();
    private static final AtomicReferenceArray<String[]> KEYS =
            new AtomicReferenceArray<String[]>(MAX_METHODS / PAGE_SIZE);
    private static final AtomicInteger NEXT_ID = new AtomicInteger();
    private static final Function<String, Integer> ASSIGN = new Assign();

    private static final ConcurrentHashMap<String, CodeSource> SOURCES = new ConcurrentHashMap<String, CodeSource>();
    private static final AtomicReferenceArray<CodeSource> SOURCES_BY_ID =
            new AtomicReferenceArray<CodeSource>(MAX_CODE_SOURCES + 1);
    private static final AtomicInteger NEXT_SOURCE = new AtomicInteger();
    private static final Function<String, CodeSource> CREATE_SOURCE = new CreateSource();

    private static final AtomicReference<Run> RUN = new AtomicReference<Run>(new Run((byte) 0, -1L));

    /** No generation is disabled. */
    private static final long NONE = Long.MIN_VALUE;

    /** Every generation is disabled: the sensor failed its self-test and could not remove its transformer. */
    private static final long ALL = Long.MAX_VALUE;

    private static volatile long disabledGeneration = NONE;
    private static volatile String disabledReason;
    private static volatile Thread selfTestThread;

    private static final LongAdder METHOD_OVERFLOW = new LongAdder();
    private static final LongAdder SOURCE_OVERFLOW = new LongAdder();
    private static final LongAdder SLOW_PATH = new LongAdder();
    private static final LongAdder FIRST_HITS = new LongAdder();
    private static final LongAdder FIRST_HIT_RECORDS = new LongAdder();
    private static final LongAdder CLASS_LOADS = new LongAdder();
    private static final LongAdder CLASS_LOAD_RECORDS = new LongAdder();
    private static final LongAdder SKIPPED_BOOTUI_LOADS = new LongAdder();
    private static final LongAdder TRANSFORM_FAILURES = new LongAdder();
    private static final LongAdder NAMED_CLASS_OVERFLOW = new LongAdder();
    private static final LongAdder SELF_TEST_HITS = new LongAdder();

    private CodeInventory() {}

    // ---- method ids ------------------------------------------------------------------------------------------------

    /**
     * The id of {@code key}, assigning the next one on its first request: stable for the agent's lifetime. -1 once
     * {@value #MAX_METHODS} ids exist (counted), or for a {@code null} key. Never throws.
     */
    public static int methodId(String key) {
        try {
            if (key == null) {
                return -1;
            }
            Integer known = IDS.get(key);
            if (known == null) {
                known = IDS.computeIfAbsent(key, ASSIGN);
            }
            if (known == null) {
                METHOD_OVERFLOW.increment();
                return -1;
            }
            return known.intValue();
        } catch (Throwable ex) {
            AgentBridge.error(ex);
            return -1;
        }
    }

    /** The id of {@code key} if it has one, else -1; assigns nothing. */
    public static int idOf(String key) {
        try {
            Integer known = key == null ? null : IDS.get(key);
            return known == null ? -1 : known.intValue();
        } catch (Throwable ex) {
            return -1;
        }
    }

    /** How many method ids exist. */
    public static int methodCount() {
        return Math.min(NEXT_ID.get(), MAX_METHODS);
    }

    /** The keys of ids {@code from} to {@code from + max - 1}, as far as they exist: a copy, index 0 being {@code from}. */
    public static String[] methodKeys(int from, int max) {
        int count = methodCount();
        int start = Math.max(0, from);
        int end = (int) Math.min((long) count, (long) start + Math.max(0, max));
        if (start >= end) {
            return new String[0];
        }
        String[] keys = new String[end - start];
        for (int id = start; id < end; id++) {
            String[] page = KEYS.get(id >>> PAGE_BITS);
            keys[id - start] = page == null ? null : page[id & (PAGE_SIZE - 1)];
        }
        return keys;
    }

    // ---- tracking --------------------------------------------------------------------------------------------------

    /** The agent instrumented the methods {@code ids} of a class as it loaded. */
    public static void tracked(int[] ids) {
        tracked(null, ids, false);
    }

    /** {@link #tracked(String, int[], boolean)} without the class name. */
    public static void tracked(int[] ids, boolean late) {
        tracked(null, ids, late);
    }

    /**
     * The agent instrumented the methods {@code ids} of the class {@code className}: tracked, and tracked in the current
     * run; {@code late} when the class had loaded, uninstrumented, before (a retransformation at the install or a
     * refine), so the current run may have missed its earlier calls. A failure named for the class before is forgotten.
     */
    public static void tracked(String className, int[] ids, boolean late) {
        try {
            if (className != null) {
                FAILED_CLASSES.remove(className);
            }
            mark(ids, TRACKED);
            byte current = RUN.get().epoch;
            if (ids != null && current != 0) {
                for (int i = 0; i < ids.length; i++) {
                    int id = ids[i];
                    if (id >= 0 && id < MAX_METHODS) {
                        TRACKED_EPOCH[id] = current;
                        if (late) {
                            LATE[id] = current;
                        }
                    }
                }
            }
            VERSION.incrementAndGet();
        } catch (Throwable ex) {
            AgentBridge.error(ex);
        }
    }

    /** {@link #transformFailed(String, int[])} without the class name. */
    public static void transformFailed(int[] ids) {
        transformFailed(null, ids);
    }

    /**
     * The agent failed to transform the class {@code className}, whose methods are {@code ids}: those it assigned before
     * failing, or those of the class's last instrumentation, possibly none. The class is named, so the engine gives its
     * methods the reason even when none has an id.
     */
    public static void transformFailed(String className, int[] ids) {
        try {
            TRANSFORM_FAILURES.increment();
            mark(ids, TRANSFORM_FAILED);
            name(FAILED_CLASSES, className);
            VERSION.incrementAndGet();
        } catch (Throwable ex) {
            AgentBridge.error(ex);
        }
    }

    /** Some methods of the class {@code className} got no id past {@link #MAX_METHODS}, so no advice. */
    public static void overLimit(String className) {
        try {
            if (className != null && !OVER_LIMIT_CLASSES.containsKey(className)) {
                name(OVER_LIMIT_CLASSES, className);
                VERSION.incrementAndGet();
            }
        } catch (Throwable ex) {
            AgentBridge.error(ex);
        }
    }

    private static void name(ConcurrentHashMap<String, Boolean> names, String className) {
        if (className == null) {
            NAMED_CLASS_OVERFLOW.increment();
        } else if (names.size() < MAX_NAMED_CLASSES || names.containsKey(className)) {
            names.put(className, Boolean.TRUE);
        } else {
            NAMED_CLASS_OVERFLOW.increment();
        }
    }

    /** The agent restored every class it instrumented: no method is tracked any more. */
    public static void untrackAll() {
        Arrays.fill(TRACKING, UNKNOWN);
        Arrays.fill(LATE, (byte) 0);
        Arrays.fill(TRACKED_EPOCH, (byte) 0);
        FAILED_CLASSES.clear();
        OVER_LIMIT_CLASSES.clear();
        VERSION.incrementAndGet();
    }

    /**
     * A number that changes whenever what {@link #snapshot} reports may have: a method's first call in a run, a tracking
     * change, a new run, or recording stopped or resumed. Never throws.
     */
    public static long version() {
        return VERSION.get();
    }

    private static void mark(int[] ids, byte state) {
        if (ids == null) {
            return;
        }
        for (int i = 0; i < ids.length; i++) {
            int id = ids[i];
            if (id >= 0 && id < MAX_METHODS) {
                TRACKING[id] = state;
            }
        }
    }

    // ---- hits ------------------------------------------------------------------------------------------------------

    /**
     * Called by the inlined advice when a method's flag differs from the current epoch: marks it executed in this run
     * and records its first call once. Never throws.
     */
    public static void hit(int id) {
        try {
            Run run = RUN.get();
            byte current = run.epoch;
            if (current == 0 || HITS[id] == current) {
                return;
            }
            SLOW_PATH.increment();
            if (Thread.currentThread() == selfTestThread) {
                // The probe proves the advice reaches the bridge; it sets no flag, so every self-test reaches here.
                SELF_TEST_HITS.increment();
                return;
            }
            // Whatever the claim's state, so the method takes the fast path from now on: only recording is gated.
            HITS[id] = current;
            VERSION.incrementAndGet();
            Claim claim = AgentBridge.current();
            if (claim == null || !claim.armed || claim.generation != run.generation || disabled(run.generation)) {
                return;
            }
            if (!run.first(id)) {
                return;
            }
            FIRST_HITS.increment();
            if (Reentrancy.bootUiWork() || !Reentrancy.enter()) {
                return;
            }
            try {
                Object payload = capture(claim);
                if (payload != null) {
                    long[] correlation = correlation(payload);
                    if (AgentRing.publish(
                            AgentRing.SENSOR_INVENTORY,
                            FIRST_HIT,
                            run.generation,
                            System.currentTimeMillis(),
                            id,
                            correlation[0],
                            correlation[1],
                            0L)) {
                        FIRST_HIT_RECORDS.increment();
                    }
                }
            } finally {
                Reentrancy.exit();
            }
        } catch (Throwable ex) {
            AgentBridge.error(ex);
        }
    }

    /**
     * Everything the engine reads about the run of claim {@code generation}, read against that one run: {@code
     * generation} and {@code epoch}; {@code methods}, how many ids exist; {@code executed}, the ids that ran in the run,
     * and {@code late}, the ids whose class was instrumented only after it loaded during the run, as bitsets (bit
     * {@code id % 64} of word {@code id / 64}); {@code trackedThisRun}, the ids whose class was instrumented in the
     * run, as a bitset; {@code tracking}, per id {@link #UNKNOWN}, {@link #TRACKED}, or {@link #TRANSFORM_FAILED};
     * {@code failedClasses} and {@code overLimitClasses}, the classes named as failed or over the limit, sorted;
     * {@code methodOverflow}, {@code transformFailures}, and {@code namedClassOverflow}, the counters that say whether
     * those names are complete; {@code version}, as {@link #version()}; and {@code disabled}, whether recording is
     * stopped for the run (its flags may still be set). {@code null} when the current run belongs to another
     * generation, before any claim asked for the sensor, or when a new run started while it was read. A copy of JDK
     * types.
     */
    public static Map<String, Object> snapshot(long generation) {
        try {
            Run run = RUN.get();
            byte current = run.epoch;
            if (current == 0 || run.generation != generation) {
                return null;
            }
            long version = VERSION.get();
            int count = methodCount();
            long[] executed = new long[(count + 63) >>> 6];
            long[] late = new long[executed.length];
            long[] trackedThisRun = new long[executed.length];
            for (int id = 0; id < count; id++) {
                if (HITS[id] == current) {
                    executed[id >>> 6] |= 1L << (id & 63);
                }
                if (LATE[id] == current) {
                    late[id >>> 6] |= 1L << (id & 63);
                }
                if (TRACKED_EPOCH[id] == current) {
                    trackedThisRun[id >>> 6] |= 1L << (id & 63);
                }
            }
            byte[] tracking = Arrays.copyOf(TRACKING, count);
            String[] failedClasses = names(FAILED_CLASSES);
            String[] overLimitClasses = names(OVER_LIMIT_CLASSES);
            if (RUN.get() != run) {
                // A new run started while this one was read: its flags may already be cleared or reused.
                return null;
            }
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("generation", Long.valueOf(run.generation));
            map.put("epoch", Integer.valueOf(current & 0xFF));
            map.put("methods", Integer.valueOf(count));
            map.put("executed", executed);
            map.put("late", late);
            map.put("trackedThisRun", trackedThisRun);
            map.put("tracking", tracking);
            map.put("failedClasses", failedClasses);
            map.put("overLimitClasses", overLimitClasses);
            map.put("methodOverflow", Long.valueOf(METHOD_OVERFLOW.sum()));
            map.put("transformFailures", Long.valueOf(TRANSFORM_FAILURES.sum()));
            map.put("namedClassOverflow", Long.valueOf(NAMED_CLASS_OVERFLOW.sum()));
            map.put("version", Long.valueOf(version));
            map.put("disabled", Boolean.valueOf(disabled(run.generation)));
            return map;
        } catch (Throwable ex) {
            AgentBridge.error(ex);
            return null;
        }
    }

    private static String[] names(ConcurrentHashMap<String, Boolean> names) {
        String[] array = names.keySet().toArray(new String[0]);
        Arrays.sort(array);
        return array;
    }

    /** The current run's epoch, 0 before any claim asked for the sensor. */
    public static int currentEpoch() {
        return RUN.get().epoch & 0xFF;
    }

    /** The claim generation the current epoch belongs to, -1 before any. */
    public static long currentGeneration() {
        return RUN.get().generation;
    }

    // ---- class loads -----------------------------------------------------------------------------------------------

    /**
     * A class {@code className} defined from {@code location}, seen by the agent's class-load recorder (which ignores
     * redefinitions, classes without a code source, and the JDK's, BootUI's, and Byte Buddy's). Never throws.
     */
    public static void classLoaded(String location, String className) {
        try {
            if (location == null || Reentrancy.bootUiWork()) {
                if (location != null) {
                    SKIPPED_BOOTUI_LOADS.increment();
                }
                return;
            }
            CodeSource source = source(location);
            if (source == null) {
                return;
            }
            CLASS_LOADS.increment();
            source.total.incrementAndGet();
            Run run = RUN.get();
            Claim claim = AgentBridge.current();
            if (run.epoch == 0
                    || claim == null
                    || !claim.armed
                    || claim.generation != run.generation
                    || disabled(run.generation)) {
                return;
            }
            long now = System.currentTimeMillis();
            Loads loads = source.loads.get();
            boolean first = false;
            while (loads.generation != run.generation) {
                Loads fresh = new Loads(run.generation, now);
                if (source.loads.compareAndSet(loads, fresh)) {
                    first = true;
                    loads = fresh;
                } else {
                    loads = source.loads.get();
                }
            }
            loads.count.incrementAndGet();
            if (first) {
                publishClassLoad(claim, run, source.id, now);
            }
        } catch (Throwable ex) {
            AgentBridge.error(ex);
        }
    }

    /** A class already loaded when the sensor installed: counted once, apart, as loaded before the claim. */
    public static void loadedBeforeClaim(String location, String className) {
        try {
            if (location == null) {
                return;
            }
            CodeSource source = source(location);
            if (source != null) {
                source.beforeClaim.incrementAndGet();
            }
        } catch (Throwable ex) {
            AgentBridge.error(ex);
        }
    }

    private static void publishClassLoad(Claim claim, Run run, int sourceId, long now) {
        long request = 0L;
        long route = 0L;
        if (Reentrancy.enter()) {
            try {
                Object payload = capture(claim);
                if (payload != null) {
                    long[] correlation = correlation(payload);
                    request = correlation[0];
                    route = correlation[1];
                }
            } finally {
                Reentrancy.exit();
            }
        }
        if (AgentRing.publish(
                AgentRing.SENSOR_INVENTORY, CLASS_LOAD, run.generation, now, sourceId, request, route, 0L)) {
            CLASS_LOAD_RECORDS.increment();
        }
    }

    private static CodeSource source(String location) {
        CodeSource source = SOURCES.get(location);
        if (source == null) {
            source = SOURCES.computeIfAbsent(location, CREATE_SOURCE);
        }
        if (source == null) {
            SOURCE_OVERFLOW.increment();
        }
        return source;
    }

    /**
     * Every code source seen: {@code id}, {@code location}, {@code beforeClaim} (classes already loaded when the sensor
     * installed), {@code total} (classes loaded since), {@code generation}, {@code loaded} (classes loaded in that
     * generation), and {@code firstLoadMillis} (its first class in that generation), or null generation values when it
     * loaded nothing since the current claim. A copy of JDK types.
     */
    public static List<Map<String, Object>> codeSources() {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        long generation = RUN.get().generation;
        int count = Math.min(NEXT_SOURCE.get(), MAX_CODE_SOURCES);
        for (int id = 1; id <= count; id++) {
            CodeSource source = SOURCES_BY_ID.get(id);
            if (source == null) {
                continue;
            }
            Loads loads = source.loads.get();
            boolean current = loads.generation == generation && generation >= 0;
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("id", Integer.valueOf(source.id));
            map.put("location", source.location);
            map.put("beforeClaim", Long.valueOf(source.beforeClaim.get()));
            map.put("total", Long.valueOf(source.total.get()));
            map.put("generation", current ? Long.valueOf(generation) : null);
            map.put("loaded", Long.valueOf(current ? loads.count.get() : 0L));
            map.put("firstLoadMillis", current ? Long.valueOf(loads.firstMillis) : null);
            list.add(map);
        }
        return list;
    }

    // ---- correlation -----------------------------------------------------------------------------------------------

    /** The claim's capture, called by recording under the re-entrancy guard; {@code null} when it has nothing. */
    private static Object capture(Claim claim) {
        Supplier<Object> capture = claim.capture.get();
        return capture == null ? null : capture.get();
    }

    /**
     * The request id, parsed from the payload's 16-hex-digit request id (0 when absent or another format), and the
     * route template's intern id (0 when absent), from a capture payload ({@code requestId, executionId, traceId,
     * spanId, routeTemplate, ...}).
     */
    static long[] correlation(Object payload) {
        long[] correlation = new long[2];
        if (payload instanceof Object[]) {
            Object[] values = (Object[]) payload;
            if (values.length > 0 && values[0] instanceof String) {
                correlation[0] = parseRequestId((String) values[0]);
            }
            if (values.length > 4 && values[4] instanceof String) {
                correlation[1] = AgentRing.intern((String) values[4]);
            }
        }
        return correlation;
    }

    /** A 16-hex-digit request id as its 64 bits; 0 for anything else. */
    static long parseRequestId(String id) {
        if (id == null || id.length() != 16) {
            return 0L;
        }
        long value = 0L;
        for (int i = 0; i < 16; i++) {
            int digit = Character.digit(id.charAt(i), 16);
            if (digit < 0) {
                return 0L;
            }
            value = (value << 4) | digit;
        }
        return value;
    }

    // ---- lifecycle -------------------------------------------------------------------------------------------------

    /**
     * A claim asking for the sensor was recorded: a new run epoch and intern table, the ring allocated if needed, and
     * the claim's capture warmed once, so its classes are linked before recording calls it inside class loading.
     * Never throws.
     */
    static void claimed(Claim claim) {
        try {
            AgentRing.newGeneration(claim.generation, claim.ringCapacity);
            // Claims racing each other may get here out of order: the run only ever moves to a newer generation.
            while (true) {
                Run current = RUN.get();
                if (current.generation >= claim.generation) {
                    break;
                }
                int next = (current.epoch & 0xFF) + 1;
                if (next > 255) {
                    Arrays.fill(HITS, (byte) 0);
                    Arrays.fill(LATE, (byte) 0);
                    Arrays.fill(TRACKED_EPOCH, (byte) 0);
                    next = 1;
                }
                if (RUN.compareAndSet(current, new Run((byte) next, claim.generation))) {
                    VERSION.incrementAndGet();
                    break;
                }
            }
            byte published;
            do {
                published = RUN.get().epoch;
                epoch = published;
            } while (RUN.get().epoch != published);
            if (Reentrancy.enter()) {
                try {
                    capture(claim);
                } finally {
                    Reentrancy.exit();
                }
            }
        } catch (Throwable ex) {
            AgentBridge.error(ex);
        }
    }

    /** Loads and links what recording uses, before the agent's transformers can call it inside class loading. */
    public static void warm() {
        try {
            Reentrancy.guarded();
            Reentrancy.bootUiWork();
            new Loads(-1L, 0L).count.get();
            parseRequestId("0000000000000000");
            correlation(new Object[0]);
            AgentRing.status();
            status();
            codeSources();
        } catch (Throwable ex) {
            AgentBridge.error(ex);
        }
    }

    /**
     * Stops recording for {@code generation} whatever the advice still does, as when the self-test failed; with
     * {@code everyGeneration}, for good, as when the sensor also could not remove its transformer.
     */
    public static void disable(long generation, boolean everyGeneration, String reason) {
        disabledGeneration = everyGeneration ? ALL : generation;
        disabledReason = reason;
        VERSION.incrementAndGet();
        AgentBridge.message("inventory sensor disabled: " + reason);
    }

    /** Re-enables recording, after a later self-test passed. */
    public static void enable() {
        if (disabledGeneration != ALL) {
            disabledGeneration = NONE;
            disabledReason = null;
            VERSION.incrementAndGet();
        }
    }

    static boolean disabled(long generation) {
        long disabled = disabledGeneration;
        return disabled == ALL || disabled == generation;
    }

    /** Starts the self-test on the calling thread: its hits are counted, set no flag, and record nothing. */
    public static void beginSelfTest() {
        SELF_TEST_HITS.reset();
        selfTestThread = Thread.currentThread();
    }

    /** Ends the self-test: how many hits the self-test thread made. */
    public static long endSelfTest() {
        selfTestThread = null;
        return SELF_TEST_HITS.sum();
    }

    // ---- status ----------------------------------------------------------------------------------------------------

    /** Counters for the agent's status and the engine; JDK types only. */
    public static Map<String, Object> status() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        try {
            Run run = RUN.get();
            int count = methodCount();
            long tracked = 0L;
            long failed = 0L;
            long executed = 0L;
            long late = 0L;
            for (int id = 0; id < count; id++) {
                byte state = TRACKING[id];
                if (state == TRACKED) {
                    tracked++;
                    if (run.epoch != 0 && HITS[id] == run.epoch) {
                        executed++;
                    }
                    if (run.epoch != 0 && LATE[id] == run.epoch) {
                        late++;
                    }
                } else if (state == TRANSFORM_FAILED) {
                    failed++;
                }
            }
            map.put("epoch", Integer.valueOf(run.epoch & 0xFF));
            map.put("generation", Long.valueOf(run.generation));
            map.put("methods", Integer.valueOf(count));
            map.put("methodsTracked", Long.valueOf(tracked));
            map.put("methodsFailed", Long.valueOf(failed));
            map.put("executedThisRun", Long.valueOf(executed));
            map.put("methodsLate", Long.valueOf(late));
            map.put("classesFailed", Integer.valueOf(FAILED_CLASSES.size()));
            map.put("classesOverLimit", Integer.valueOf(OVER_LIMIT_CLASSES.size()));
            map.put("namedClassOverflow", Long.valueOf(NAMED_CLASS_OVERFLOW.sum()));
            map.put("methodOverflow", Long.valueOf(METHOD_OVERFLOW.sum()));
            map.put("transformFailures", Long.valueOf(TRANSFORM_FAILURES.sum()));
            map.put("firstHits", Long.valueOf(FIRST_HITS.sum()));
            map.put("firstHitRecords", Long.valueOf(FIRST_HIT_RECORDS.sum()));
            map.put("slowPathCalls", Long.valueOf(SLOW_PATH.sum()));
            map.put("codeSources", Integer.valueOf(Math.min(NEXT_SOURCE.get(), MAX_CODE_SOURCES)));
            map.put("codeSourceOverflow", Long.valueOf(SOURCE_OVERFLOW.sum()));
            map.put("classLoads", Long.valueOf(CLASS_LOADS.sum()));
            map.put("classLoadRecords", Long.valueOf(CLASS_LOAD_RECORDS.sum()));
            map.put("bootUiLoadsSkipped", Long.valueOf(SKIPPED_BOOTUI_LOADS.sum()));
            map.put("ringDropped", Long.valueOf(AgentRing.dropped(AgentRing.SENSOR_INVENTORY)));
            map.put("ringLost", Long.valueOf(AgentRing.lost()));
            map.put("internOverflow", Long.valueOf(AgentRing.internOverflow()));
            map.put("disabledReason", disabledReason);
            Map<String, Object> recorded = new LinkedHashMap<String, Object>();
            recorded.put("method entry", Long.valueOf(FIRST_HITS.sum()));
            recorded.put("class load", Long.valueOf(CLASS_LOADS.sum()));
            map.put("recorded", recorded);
        } catch (Throwable ex) {
            AgentBridge.error(ex);
        }
        return map;
    }

    /** Tests only: forgets every id, flag, code source, and counter. */
    static void reset() {
        IDS.clear();
        for (int i = 0; i < KEYS.length(); i++) {
            KEYS.set(i, null);
        }
        NEXT_ID.set(0);
        Arrays.fill(HITS, (byte) 0);
        Arrays.fill(TRACKING, UNKNOWN);
        Arrays.fill(LATE, (byte) 0);
        Arrays.fill(TRACKED_EPOCH, (byte) 0);
        FAILED_CLASSES.clear();
        OVER_LIMIT_CLASSES.clear();
        NAMED_CLASS_OVERFLOW.reset();
        VERSION.incrementAndGet();
        epoch = 0;
        RUN.set(new Run((byte) 0, -1L));
        SOURCES.clear();
        for (int i = 0; i < SOURCES_BY_ID.length(); i++) {
            SOURCES_BY_ID.set(i, null);
        }
        NEXT_SOURCE.set(0);
        disabledGeneration = NONE;
        disabledReason = null;
        selfTestThread = null;
        METHOD_OVERFLOW.reset();
        SOURCE_OVERFLOW.reset();
        SLOW_PATH.reset();
        FIRST_HITS.reset();
        FIRST_HIT_RECORDS.reset();
        CLASS_LOADS.reset();
        CLASS_LOAD_RECORDS.reset();
        SKIPPED_BOOTUI_LOADS.reset();
        TRANSFORM_FAILURES.reset();
        SELF_TEST_HITS.reset();
    }

    /** One run: its epoch, its claim generation, and which ids already recorded their first call in it. */
    static final class Run {

        final byte epoch;
        final long generation;
        private final AtomicIntegerArray firsts;

        Run(byte epoch, long generation) {
            this.epoch = epoch;
            this.generation = generation;
            this.firsts = new AtomicIntegerArray(epoch == 0 ? 0 : MAX_METHODS >>> 5);
        }

        /** Whether this caller is the first to record {@code id} in this run: exactly one caller is. */
        boolean first(int id) {
            int word = id >>> 5;
            int bit = 1 << (id & 31);
            while (true) {
                int value = firsts.get(word);
                if ((value & bit) != 0) {
                    return false;
                }
                if (firsts.compareAndSet(word, value, value | bit)) {
                    return true;
                }
            }
        }
    }

    /** A code source's classes loaded in one generation, and when the first one was. */
    static final class Loads {

        final long generation;
        final long firstMillis;
        final AtomicLong count = new AtomicLong();

        Loads(long generation, long firstMillis) {
            this.generation = generation;
            this.firstMillis = firstMillis;
        }
    }

    /** One code source: a location string, its counters, and its current generation's loads. */
    static final class CodeSource {

        final int id;
        final String location;
        final AtomicLong beforeClaim = new AtomicLong();
        final AtomicLong total = new AtomicLong();
        final AtomicReference<Loads> loads = new AtomicReference<Loads>(new Loads(-1L, 0L));

        CodeSource(int id, String location) {
            this.id = id;
            this.location = location;
        }
    }

    /** Assigns the next method id, or none past the limit; called inside {@code computeIfAbsent}. */
    static final class Assign implements Function<String, Integer> {

        @Override
        public Integer apply(String key) {
            if (NEXT_ID.get() >= MAX_METHODS) {
                return null;
            }
            int id = NEXT_ID.getAndIncrement();
            if (id >= MAX_METHODS) {
                return null;
            }
            int index = id >>> PAGE_BITS;
            String[] page = KEYS.get(index);
            if (page == null) {
                KEYS.compareAndSet(index, null, new String[PAGE_SIZE]);
                page = KEYS.get(index);
            }
            page[id & (PAGE_SIZE - 1)] = key;
            return Integer.valueOf(id);
        }
    }

    /** Creates the next code source, or none past the limit; called inside {@code computeIfAbsent}. */
    static final class CreateSource implements Function<String, CodeSource> {

        @Override
        public CodeSource apply(String location) {
            if (NEXT_SOURCE.get() >= MAX_CODE_SOURCES) {
                return null;
            }
            int id = NEXT_SOURCE.incrementAndGet();
            if (id > MAX_CODE_SOURCES) {
                return null;
            }
            CodeSource source = new CodeSource(id, location);
            SOURCES_BY_ID.set(id, source);
            return source;
        }
    }
}
