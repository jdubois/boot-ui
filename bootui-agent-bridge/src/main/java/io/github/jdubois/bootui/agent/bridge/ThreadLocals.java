package io.github.jdubois.bootui.agent.bridge;

import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * The {@value SideEffects#THREAD_LOCALS} sensor's bridge side (PLAN-v2 §5.16, M5-5 item 3, M5-5f): thread locals a
 * request or a job left set on a pooled platform thread, found by scanning the thread's {@code ThreadLocalMap}s when
 * its scope closes rather than by advising {@code ThreadLocal.set}. Never a value: the agent's scanner reads each map
 * entry's key and whether its value is {@code null}, nothing else, and nothing here keeps a key past the scope's close.
 *
 * <p><b>Scopes.</b> A scope is a snapshot of the thread's thread locals with a value, taken when it opens, and a diff
 * when it closes, both on the thread that owns the maps, which are never read by another thread. Scopes open where a
 * request or a job starts on a pooled thread and close where it ends there: an adapter's request scope, where the
 * engine asks for it ({@link #configure}: Spring MVC; never an event loop's assembly scope); a request's pool task the
 * executors sensor reopened ({@link SideEffects#handoff(Object[], long, boolean)}, a {@code ThreadPoolExecutor} worker
 * or a {@code ForkJoinWorkerThread}, never a thread the application started); and an explicit pair the engine opens
 * itself ({@link #open()}, {@link #close(long)}): a Quarkus blocking resource method on its worker, a scheduled run, a
 * Quarkus managed executor's task. Only the outermost scope on a thread scans; virtual threads, which are not pooled,
 * event loops, BootUI's work, and scopes with no owner never do.
 *
 * <p><b>Leftovers.</b> A key with a value at the close that had none, or was absent, at the open; a {@code null} value
 * counts as cleared. Skipped without reflection: the bridge's own thread locals, by identity; a thread local whose class
 * the JDK defines (the bootstrap or platform loader) other than {@code ThreadLocal}, {@code InheritableThreadLocal}, and
 * {@code withInitial}'s; and BootUI's own, by class ({@code BootUiThreadLocal} and the agent's). Each other leftover is
 * one record, published at once, at most {@value #MAX_REPORTED} a scope, its key named by its runtime class and an id
 * in a registry of weak references per claim generation ({@value #REGISTRY} slots), whose holder the engine resolves
 * later on its drain thread ({@link #holder}), never on a request thread; a key the engine found excluded is skipped
 * from then on ({@link #exclude}), so it never takes another's place under the cap.
 *
 * <p>JDK types only; no lambda, no monitor; every entry point catches everything.
 */
public final class ThreadLocals {

    /**
     * A table longer than this is not scanned: the scope is skipped, counted. Instrumentation agents, as
     * OpenTelemetry's, keep hundreds of thread locals on a thread.
     */
    public static final int MAX_TABLE = 16_384;

    /** Live keys with a value a snapshot starts with room for, growing by doubling. */
    static final int FIRST_LIVE = 128;

    /** Live keys with a value kept in a snapshot at most: more, and the scope is skipped, counted. */
    public static final int MAX_LIVE = 4_096;

    /** Leftovers a scan returns at most. */
    public static final int MAX_FOUND = 64;

    /** Leftovers a scope reports at most. */
    public static final int MAX_REPORTED = 16;

    /** Keys remembered per claim generation. */
    public static final int REGISTRY = 1_024;

    /** A scan's results. */
    public static final int TOO_LARGE = -1;

    public static final int OVERFLOW = -2;

    /** A leftover's detail, in its record's {@code R_FLAGS} bits 32–39; its registry id is in bits 40–55. */
    public static final int DETAIL_INHERITABLE = 1;

    public static final int DETAIL_SUPPLIED = 2;
    public static final int DETAIL_SUBCLASS = 4;

    /** The runtime class of {@code ThreadLocal.withInitial}'s thread locals. */
    static final String SUPPLIED = "java.lang.ThreadLocal$SuppliedThreadLocal";

    /** BootUI's modules, whose thread locals are never reported; never the sample applications' packages. */
    private static final String[] BOOTUI = {
        "io.github.jdubois.bootui.agent.",
        "io.github.jdubois.bootui.engine.",
        "io.github.jdubois.bootui.core.",
        "io.github.jdubois.bootui.spi.",
        "io.github.jdubois.bootui.autoconfigure.",
        "io.github.jdubois.bootui.quarkus."
    };

    /** A scope's depth when the engine opened it explicitly. */
    static final int EXPLICIT = -1;

    /**
     * Reads the calling thread's thread-local maps: implemented by the agent, which alone was granted access to {@code
     * java.lang}'s private fields. Never given a thread: a map is only ever read by the thread that owns it.
     */
    public abstract static class Scanner {

        /**
         * Writes into {@code hashes} the hash code of every key of the calling thread's maps whose value is not {@code
         * null}: their count, {@link #TOO_LARGE}, or {@link #OVERFLOW} past {@code hashes.length}.
         */
        public abstract int snapshot(int[] hashes);

        /**
         * Writes into {@code keys}, {@code hashes}, and {@code inheritable} the keys of the calling thread's maps whose
         * value is not {@code null} and whose hash code is not among {@code open}'s first {@code openCount}, sorted: how
         * many there are, which may exceed {@code keys.length}, or {@link #TOO_LARGE}.
         */
        public abstract int leftovers(int[] open, int openCount, Object[] keys, int[] hashes, boolean[] inheritable);
    }

    /** Names a thread local's holder: implemented by the agent, called on the engine's drain thread only. */
    public abstract static class Resolver {

        /**
         * {@code threadLocal}'s holder: {@code {holder or null, "true" when it has an initial value, "true" when the
         * holder is in {@code packages}, a hint or null}}, within about {@code budgetNanos}; {@code null} when out of
         * time, to be asked again.
         */
        public abstract String[] resolve(Object threadLocal, String[] packages, String[] holders, long budgetNanos);

        /** How the resolver tells an initialized class: {@code unsafe} or {@code inventory}. */
        public abstract String initializationCheck();
    }

    private static final ClassLoader PLATFORM = platformLoader();
    private static final AtomicReference<Registry> REGISTRY_REF = new AtomicReference<Registry>();
    private static final AtomicLong TOKENS = new AtomicLong();

    private static final LongAdder SCOPES = new LongAdder();
    private static final LongAdder SCANS = new LongAdder();
    private static final LongAdder LEFTOVERS = new LongAdder();
    private static final LongAdder REPORTED = new LongAdder();
    private static final LongAdder BRIDGE_KEYS = new LongAdder();
    private static final LongAdder JDK_KEYS = new LongAdder();
    private static final LongAdder BOOTUI_KEYS = new LongAdder();
    private static final LongAdder EXCLUDED_KEYS = new LongAdder();
    private static final LongAdder TOO_LARGE_SCOPES = new LongAdder();
    private static final LongAdder OVERFLOW_SCOPES = new LongAdder();
    private static final LongAdder CAPPED = new LongAdder();
    private static final LongAdder NESTED = new LongAdder();
    private static final LongAdder VIRTUAL_SKIPPED = new LongAdder();
    private static final LongAdder EVENT_LOOP_SKIPPED = new LongAdder();
    private static final LongAdder UNOWNED = new LongAdder();
    private static final LongAdder STALE_SCOPES = new LongAdder();
    private static final LongAdder FOREIGN_CLOSES = new LongAdder();
    private static final LongAdder REGISTRY_FULL = new LongAdder();
    private static final LongAdder RESOLUTIONS = new LongAdder();
    private static final LongAdder SELF_TEST_SCANS = new LongAdder();

    private static volatile Scanner scanner;
    private static volatile Resolver resolver;
    private static volatile boolean adapterScopes;
    private static volatile Thread selfTestThread;
    private static volatile String accessError;

    /** The self-test's leftovers' hash codes: only ever written by the self-test's thread. */
    private static final int[] SELF_TEST_FOUND = new int[MAX_FOUND];

    private static volatile int selfTestCount;

    private ThreadLocals() {}

    // ---- the agent's side ----------------------------------------------------------------------------------------

    /** The agent installs its scanner and resolver once the module grant succeeded. Never throws. */
    public static void install(Scanner installedScanner, Resolver installedResolver) {
        scanner = installedScanner;
        resolver = installedResolver;
        accessError = null;
    }

    /** The agent could not read the maps: no scope scans, and status says why. Never throws. */
    public static void unavailable(String reason) {
        scanner = null;
        resolver = null;
        accessError = reason;
    }

    /** Starts the self-test on the calling thread: its scopes scan with no owner, and record nothing. */
    public static void beginSelfTest() {
        selfTestCount = 0;
        selfTestThread = Thread.currentThread();
    }

    /** Ends the self-test: the hash codes of the leftovers its scopes found, after the exclusions. */
    public static int[] endSelfTest() {
        selfTestThread = null;
        int count = Math.min(selfTestCount, SELF_TEST_FOUND.length);
        int[] found = new int[count];
        System.arraycopy(SELF_TEST_FOUND, 0, found, 0, count);
        return found;
    }

    // ---- the engine's side ---------------------------------------------------------------------------------------

    /**
     * Whether an adapter's request scope ({@code CodePaths.begin()}) is a thread-locals scope: true on Spring MVC,
     * whose scope is the request's whole handling on its pooled worker; false where it is an event loop's assembly,
     * as on Spring WebFlux and Quarkus. Never throws.
     */
    public static void configure(boolean scopes) {
        adapterScopes = scopes;
    }

    /**
     * The engine opens a scope on the calling thread, as a Quarkus blocking resource method's or a scheduled run's,
     * owned by what BootUI's context names here: a token for {@link #close(long)}, 0 when the thread is not scanned.
     * Never throws.
     */
    public static long open() {
        return open(true);
    }

    /**
     * The engine opens a scope on the calling thread before what will own it is known, as around a Reactor scheduler's
     * task, inside which Reactor's context propagation makes a request's context current: {@link #own()} names its
     * owner then, and a scope never owned reports nothing. Inside another scope it is nested and returns 0. Never throws.
     */
    public static long openUnowned() {
        return open(false);
    }

    /**
     * The calling thread's open scope, if {@link #openUnowned()} opened it and nothing owns it yet, is owned by what
     * BootUI's context names now. Never throws.
     */
    public static void own() {
        try {
            if ((SideEffects.mask & SideEffects.MASK_THREAD_LOCALS) == 0) {
                return;
            }
            CodePaths.Frame frame = CodePaths.FRAME.get();
            Scope scope = frame == null ? null : frame.threadLocals;
            if (scope == null || scope.depth != EXPLICIT || scope.request != 0L || scope.execution != 0L) {
                return;
            }
            Claim claim = AgentBridge.current();
            SideEffects.Owner owner = new SideEffects.Owner();
            if (claim != null
                    && claim.armed
                    && claim.generation == scope.generation
                    && SideEffects.ownerOf(CodePaths.capture(claim), owner)) {
                scope.request = owner.request;
                scope.execution = owner.execution;
                scope.executionKind = owner.executionKind;
            }
        } catch (Throwable ex) {
            SideEffects.failed(SideEffects.SENSOR_THREAD_LOCALS, ex);
        }
    }

    private static long open(boolean owned) {
        try {
            Thread self = Thread.currentThread();
            boolean testing = self == selfTestThread;
            if (!testing && ((SideEffects.mask & SideEffects.MASK_THREAD_LOCALS) == 0 || scanner == null)) {
                return 0L;
            }
            CodePaths.Frame frame = CodePaths.frame();
            Scope scope = frame.threadLocals;
            if (scope != null && scope.depth > 0) {
                NESTED.increment();
                return 0L;
            }
            if (!testing && !scanned(self)) {
                return 0L;
            }
            long generation = SideEffects.generation;
            SideEffects.Owner owner = new SideEffects.Owner();
            if (!owned) {
                if (scope != null && scope.depth == EXPLICIT) {
                    NESTED.increment();
                    return 0L;
                }
            } else if (!testing) {
                Claim claim = AgentBridge.current();
                if (claim == null
                        || !claim.armed
                        || claim.generation != generation
                        || !SideEffects.ownerOf(CodePaths.capture(claim), owner)) {
                    UNOWNED.increment();
                    return 0L;
                }
            }
            if (owned && scope != null && scope.depth == EXPLICIT && scope.request == 0L && scope.execution == 0L) {
                // A live unowned scope, as a scheduler task's: this work owns it, and is nested in it.
                scope.request = owner.request;
                scope.execution = owner.execution;
                scope.executionKind = owner.executionKind;
                NESTED.increment();
                return 0L;
            }
            if (owned && scope != null && scope.depth == EXPLICIT) {
                boolean same = scope.generation == generation
                        && (owner.request != 0L
                                ? scope.request == owner.request
                                : scope.request == 0L && scope.execution == owner.execution);
                if (same) {
                    // The same request's or job's work nested in its own scope, as a managed task run inline.
                    NESTED.increment();
                    return 0L;
                }
                // A scope of another request or job never closed on this thread, as after an unmapped failure: it is
                // dropped, never reported, and this one replaces it.
                STALE_SCOPES.increment();
                scope.depth = 0;
            }
            long token = TOKENS.incrementAndGet();
            if (!snapshot(frame, EXPLICIT, generation, owner.request, owner.execution, owner.executionKind)) {
                return 0L;
            }
            frame.threadLocals.token = token;
            return token;
        } catch (Throwable ex) {
            SideEffects.failed(SideEffects.SENSOR_THREAD_LOCALS, ex);
            return 0L;
        }
    }

    /**
     * The scope {@link #open()} returned {@code token} for ends: its leftovers are recorded. A token of another thread,
     * or of a scope already over, does nothing, counted. Never throws.
     */
    public static void close(long token) {
        if (token == 0L) {
            return;
        }
        try {
            CodePaths.Frame frame = CodePaths.FRAME.get();
            Scope scope = frame == null ? null : frame.threadLocals;
            if (scope == null || scope.depth != EXPLICIT || scope.token != token) {
                FOREIGN_CLOSES.increment();
                return;
            }
            diff(frame, scope);
        } catch (Throwable ex) {
            SideEffects.failed(SideEffects.SENSOR_THREAD_LOCALS, ex);
        }
    }

    /**
     * The holder of the thread local registry slot {@code id} of claim generation {@code generation}, whose hash code
     * is {@code hash}, resolved by the agent within about {@code budgetNanos}, for the engine's drain thread: {@code
     * {holder or null, initialValue, claimed, hint}}; {@code {null, …, "collected"}} when the thread local is gone or
     * the slot holds another, and {@code null} when out of time. Never throws.
     */
    public static String[] holder(
            long generation, int id, int hash, String[] packages, String[] holders, long budgetNanos) {
        try {
            Known known = known(generation, id, hash);
            Object key = known == null ? null : known.get();
            if (key == null) {
                return new String[] {null, "false", "false", "collected"};
            }
            Resolver current = resolver;
            if (current == null) {
                return new String[] {null, "false", "false", null};
            }
            RESOLUTIONS.increment();
            boolean previous = Reentrancy.bootUiWork(true);
            Reentrancy.agentWork(true);
            try {
                return current.resolve(key, packages, holders, budgetNanos);
            } finally {
                Reentrancy.agentWork(false);
                Reentrancy.bootUiWork(previous);
            }
        } catch (Throwable ex) {
            SideEffects.failed(SideEffects.SENSOR_THREAD_LOCALS, ex);
            return new String[] {null, "false", "false", null};
        }
    }

    /**
     * The engine found the thread local of registry slot {@code id} excluded (a framework's, or a per-thread cache): it
     * is skipped from now on. Never throws.
     */
    public static void exclude(long generation, int id, int hash) {
        try {
            Known known = known(generation, id, hash);
            if (known != null) {
                known.excluded = true;
            }
        } catch (Throwable ex) {
            SideEffects.failed(SideEffects.SENSOR_THREAD_LOCALS, ex);
        }
    }

    // ---- the owner slots' scopes -----------------------------------------------------------------------------------

    /** Whether an adapter's request scope is a thread-locals scope on this stack. */
    static boolean adapterScopes() {
        return adapterScopes;
    }

    /**
     * An owner slot was pushed on {@code frame}, an adapter's request scope or a pool task's handoff: a scope opens when
     * it is the thread's outermost, names an owner, and the thread is scanned. Never throws.
     */
    static void pushed(CodePaths.Frame frame) {
        try {
            if ((SideEffects.mask & SideEffects.MASK_THREAD_LOCALS) == 0 || scanner == null) {
                return;
            }
            int index = frame.slots - 1;
            if (index < 0 || index >= SideEffects.SLOTS) {
                return;
            }
            Scope scope = frame.threadLocals;
            if (scope != null && scope.depth != 0) {
                NESTED.increment();
                return;
            }
            long generation = SideEffects.generation;
            if (frame.slotGeneration[index] != generation
                    || (frame.slotRequest[index] == 0L && frame.slotExecution[index] == 0L)) {
                UNOWNED.increment();
                return;
            }
            if (!scanned(Thread.currentThread())) {
                return;
            }
            snapshot(
                    frame,
                    index + 1,
                    generation,
                    frame.slotRequest[index],
                    frame.slotExecution[index],
                    frame.slotKind[index]);
        } catch (Throwable ex) {
            SideEffects.failed(SideEffects.SENSOR_THREAD_LOCALS, ex);
        }
    }

    /** The owner slot at {@code index} is about to be popped: the scope it opened, if any, closes. Never throws. */
    static void popping(CodePaths.Frame frame, int index) {
        try {
            Scope scope = frame.threadLocals;
            if (scope != null && scope.depth == index + 1) {
                diff(frame, scope);
            }
        } catch (Throwable ex) {
            SideEffects.failed(SideEffects.SENSOR_THREAD_LOCALS, ex);
        }
    }

    /** Whether a pool task's handoff on the calling thread is a pooled worker's: a pool's own thread, never one-off. */
    static boolean pooled(int applyHook) {
        return applyHook == TaskPropagation.APPLY_RUN_WORKER || Thread.currentThread() instanceof ForkJoinWorkerThread;
    }

    // ---- scanning --------------------------------------------------------------------------------------------------

    /** Whether the calling thread's scopes scan: a platform thread, not an event loop, not doing BootUI's work. */
    private static boolean scanned(Thread thread) {
        if (ThreadPropagation.isVirtual(thread)) {
            VIRTUAL_SKIPPED.increment();
            return false;
        }
        if (SideEffects.eventLoop(thread, thread.getName())) {
            EVENT_LOOP_SKIPPED.increment();
            return false;
        }
        return !Reentrancy.sideEffectsSkipped();
    }

    /** Takes the scope's snapshot; false when the maps are too large to scan. */
    private static boolean snapshot(
            CodePaths.Frame frame, int depth, long generation, long request, long execution, int executionKind) {
        Scanner current = scanner;
        if (current == null) {
            return false;
        }
        Scope scope = frame.threadLocals;
        if (scope == null) {
            scope = new Scope();
            frame.threadLocals = scope;
        }
        int count = current.snapshot(scope.open);
        while (count == OVERFLOW && scope.open.length < MAX_LIVE) {
            scope.open = new int[Math.min(MAX_LIVE, scope.open.length * 2)];
            count = current.snapshot(scope.open);
        }
        SCANS.increment();
        if (count < 0) {
            (count == TOO_LARGE ? TOO_LARGE_SCOPES : OVERFLOW_SCOPES).increment();
            return false;
        }
        sort(scope.open, count);
        scope.openCount = count;
        scope.depth = depth;
        scope.generation = generation;
        scope.request = request;
        scope.execution = execution;
        scope.executionKind = executionKind;
        scope.openedMillis = System.currentTimeMillis();
        SCOPES.increment();
        return true;
    }

    /** Closes the scope: its leftovers are recorded, and no key stays in the frame afterwards. */
    private static void diff(CodePaths.Frame frame, Scope scope) {
        Object[] keys = scope.keys;
        int found = 0;
        try {
            Scanner current = scanner;
            if (current == null) {
                return;
            }
            if (scope.request == 0L && scope.execution == 0L && Thread.currentThread() != selfTestThread) {
                // An unowned scope nothing claimed, as a scheduler's task no request's context reached: not scanned.
                UNOWNED.increment();
                return;
            }
            found = current.leftovers(scope.open, scope.openCount, keys, scope.hashes, scope.inheritable);
            SCANS.increment();
            if (found == TOO_LARGE) {
                TOO_LARGE_SCOPES.increment();
                return;
            }
            if (found > keys.length) {
                OVERFLOW_SCOPES.increment();
            }
            report(frame, scope, Math.min(found, keys.length));
        } finally {
            for (int i = 0; i < keys.length; i++) {
                keys[i] = null;
            }
            scope.depth = 0;
            scope.token = 0L;
        }
    }

    private static void report(CodePaths.Frame frame, Scope scope, int found) {
        boolean testing = Thread.currentThread() == selfTestThread;
        if (testing) {
            SELF_TEST_SCANS.increment();
        }
        Claim claim = AgentBridge.current();
        if (!testing
                && (claim == null
                        || !claim.armed
                        || claim.generation != scope.generation
                        || (SideEffects.mask & SideEffects.MASK_THREAD_LOCALS) == 0)) {
            return;
        }
        Registry registry = testing ? null : registry(scope.generation);
        int reported = 0;
        for (int i = 0; i < found; i++) {
            Object key = scope.keys[i];
            if (key == null || skipped(key)) {
                continue;
            }
            LEFTOVERS.increment();
            int hash = scope.hashes[i];
            if (testing) {
                int at = selfTestCount;
                if (at < SELF_TEST_FOUND.length) {
                    SELF_TEST_FOUND[at] = hash;
                    selfTestCount = at + 1;
                }
                continue;
            }
            int id = registry == null ? 0 : id(registry, key, hash);
            if (id < 0) {
                EXCLUDED_KEYS.increment();
                continue;
            }
            if (reported == MAX_REPORTED) {
                CAPPED.increment();
                continue;
            }
            reported++;
            int detail = detail(key, scope.inheritable[i]);
            SideEffects.Owner owner = new SideEffects.Owner();
            owner.generation = scope.generation;
            owner.request = scope.request;
            owner.execution = scope.execution;
            owner.executionKind = scope.executionKind;
            owner.threadKind = SideEffects.THREAD_PLATFORM;
            owner.threadName = SideEffects.threadFamilyId(frame, scope.generation);
            int target = SideEffects.intern(hint(key.getClass().getName()));
            SideEffects.publishOne(
                    owner,
                    SideEffects.SENSOR_THREAD_LOCALS,
                    SideEffects.KIND_THREAD_LOCAL_LEFT_SET,
                    target,
                    SideEffects.OUTCOME_DONE,
                    (detail & 0xFF) | ((id & 0xFFFF) << 8),
                    0L,
                    0L,
                    hash & 0xFFFFFFFFL,
                    scope.openedMillis);
            REPORTED.increment();
        }
    }

    /**
     * Whether a leftover is never reported, without reflection: the bridge's own thread locals; a JDK-defined subclass,
     * such as a lock's hold counter; BootUI's own.
     */
    static boolean skipped(Object key) {
        if (key == CodePaths.FRAME
                || key == Reentrancy.STATE
                || key == TaskPropagation.ACTIVE
                || key == CaughtExceptions.COUNTS
                || key == CaughtExceptions.SCRATCH) {
            BRIDGE_KEYS.increment();
            return true;
        }
        Class<?> type = key.getClass();
        ClassLoader loader = type.getClassLoader();
        String name = type.getName();
        if ((loader == null || loader == PLATFORM)
                && type != ThreadLocal.class
                && type != InheritableThreadLocal.class
                && !SUPPLIED.equals(name)) {
            JDK_KEYS.increment();
            return true;
        }
        if (bootUi(name)) {
            BOOTUI_KEYS.increment();
            return true;
        }
        return false;
    }

    /** Whether {@code className} is in one of BootUI's modules, never the sample applications'. */
    static boolean bootUi(String className) {
        if (!className.startsWith("io.github.jdubois.bootui.")) {
            return false;
        }
        for (String prefix : BOOTUI) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** A leftover's detail bits. */
    static int detail(Object key, boolean inheritable) {
        Class<?> type = key.getClass();
        int detail = inheritable ? DETAIL_INHERITABLE : 0;
        if (SUPPLIED.equals(type.getName())) {
            detail |= DETAIL_SUPPLIED;
        } else if (type != ThreadLocal.class && type != InheritableThreadLocal.class) {
            detail |= DETAIL_SUBCLASS;
        }
        return detail;
    }

    /** A runtime class's name without a hidden class's or a lambda's suffix. */
    static String hint(String className) {
        String name = className;
        int slash = name.indexOf('/');
        if (slash > 0) {
            name = name.substring(0, slash);
        }
        int lambda = name.indexOf("$$Lambda");
        if (lambda > 0) {
            name = name.substring(0, lambda);
        }
        return name.length() > SideEffects.MAX_TARGET ? name.substring(0, SideEffects.MAX_TARGET) : name;
    }

    /** Sorts the first {@code count} values, ascending: by insertion for the few dozen most snapshots hold. */
    static void sort(int[] values, int count) {
        if (count > 32) {
            java.util.Arrays.sort(values, 0, count);
            return;
        }
        for (int i = 1; i < count; i++) {
            int value = values[i];
            int j = i - 1;
            while (j >= 0 && values[j] > value) {
                values[j + 1] = values[j];
                j--;
            }
            values[j + 1] = value;
        }
    }

    /** Whether {@code value} is among the first {@code count} sorted values. */
    public static boolean contains(int[] sorted, int count, int value) {
        int low = 0;
        int high = count - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int at = sorted[middle];
            if (at < value) {
                low = middle + 1;
            } else if (at > value) {
                high = middle - 1;
            } else {
                return true;
            }
        }
        return false;
    }

    // ---- the registry
    // ------------------------------------------------------------------------------------------------

    /** One known thread local: weakly held, with its hash code, and whether the engine excluded it. */
    static final class Known extends WeakReference<Object> {

        final int hash;
        volatile boolean excluded;

        Known(Object key, int hash) {
            super(key);
            this.hash = hash;
        }
    }

    /** The thread locals of one claim generation, by open addressing on their hash code. */
    static final class Registry {

        final long generation;
        final AtomicReferenceArray<Known> slots = new AtomicReferenceArray<Known>(REGISTRY);

        Registry(long generation) {
            this.generation = generation;
        }
    }

    private static Registry registry(long generation) {
        while (true) {
            Registry current = REGISTRY_REF.get();
            if (current != null && current.generation == generation) {
                return current;
            }
            if (current != null && current.generation > generation) {
                return null;
            }
            if (REGISTRY_REF.compareAndSet(current, new Registry(generation))) {
                return REGISTRY_REF.get();
            }
        }
    }

    /**
     * {@code key}'s slot in {@code registry}, from 1; its negation when the engine excluded it; 0 when the registry is
     * full. A slot whose thread local was collected is reused; no slot is ever emptied, so probe chains stay intact.
     */
    static int id(Registry registry, Object key, int hash) {
        int start = hash & (REGISTRY - 1);
        for (int attempt = 0; attempt < 4; attempt++) {
            int collected = -1;
            Known collectedKnown = null;
            int empty = -1;
            for (int i = 0; i < REGISTRY; i++) {
                int slot = (start + i) & (REGISTRY - 1);
                Known known = registry.slots.get(slot);
                if (known == null) {
                    empty = slot;
                    break;
                }
                Object held = known.get();
                if (held == key) {
                    return known.excluded ? -(slot + 1) : slot + 1;
                }
                if (held == null && collected < 0) {
                    collected = slot;
                    collectedKnown = known;
                }
            }
            if (collected >= 0) {
                if (registry.slots.compareAndSet(collected, collectedKnown, new Known(key, hash))) {
                    return collected + 1;
                }
            } else if (empty >= 0) {
                if (registry.slots.compareAndSet(empty, null, new Known(key, hash))) {
                    return empty + 1;
                }
            } else {
                break;
            }
            // Another thread took the slot meanwhile: probed again.
        }
        REGISTRY_FULL.increment();
        return 0;
    }

    /** The registry entry of {@code id} in {@code generation}, when it still holds the thread local of {@code hash}. */
    private static Known known(long generation, int id, int hash) {
        Registry registry = REGISTRY_REF.get();
        if (registry == null || registry.generation != generation || id <= 0 || id > REGISTRY) {
            return null;
        }
        Known known = registry.slots.get(id - 1);
        return known != null && known.hash == hash ? known : null;
    }

    // ---- per-thread state
    // --------------------------------------------------------------------------------------------

    /** A thread's thread-locals scope: its snapshot and owner, and the buffers its close reuses. */
    static final class Scope {

        /** 0 when no scope is open; the owner slot's index plus one; or {@link #EXPLICIT}. */
        int depth;

        long token;
        long generation;
        long request;
        long execution;
        int executionKind;
        long openedMillis;
        int[] open = new int[FIRST_LIVE];
        int openCount;
        final Object[] keys = new Object[MAX_FOUND];
        final int[] hashes = new int[MAX_FOUND];
        final boolean[] inheritable = new boolean[MAX_FOUND];
    }

    // ---- status
    // ------------------------------------------------------------------------------------------------------

    /** Loads and links what scopes use, before the sensor is enabled. */
    public static void warm() {
        try {
            Scope scope = new Scope();
            sort(scope.open, 0);
            contains(scope.open, 0, 1);
            hint("warm$$Lambda/0x1");
            bootUi("warm");
            ThreadLocal<Object> probe = new ThreadLocal<Object>();
            skipped(probe);
            detail(probe, false);
            Registry registry = new Registry(-2L);
            id(registry, probe, 1);
            new SideEffects.Owner().getClass();
            pooled(-1);
            scanned(Thread.currentThread());
        } catch (Throwable ex) {
            AgentBridge.error(ex);
        }
    }

    /** The sensor's counters, for its status. */
    static void putStatus(Map<String, Object> map) {
        Map<String, Object> recorded = new LinkedHashMap<String, Object>();
        recorded.put("ThreadLocalMap.scan", Long.valueOf(SCANS.sum()));
        recorded.put("ThreadLocal.holder", Long.valueOf(RESOLUTIONS.sum()));
        map.put("recorded", recorded);
        map.put("scannerInstalled", Boolean.valueOf(scanner != null));
        Resolver current = resolver;
        map.put("initializationCheck", current == null ? null : current.initializationCheck());
        map.put("accessError", accessError);
        map.put("adapterScopes", Boolean.valueOf(adapterScopes));
        map.put("scopes", Long.valueOf(SCOPES.sum()));
        map.put("scans", Long.valueOf(SCANS.sum()));
        map.put("leftovers", Long.valueOf(LEFTOVERS.sum()));
        map.put("reported", Long.valueOf(REPORTED.sum()));
        map.put("bridgeKeys", Long.valueOf(BRIDGE_KEYS.sum()));
        map.put("jdkKeys", Long.valueOf(JDK_KEYS.sum()));
        map.put("bootUiKeys", Long.valueOf(BOOTUI_KEYS.sum()));
        map.put("excludedKeys", Long.valueOf(EXCLUDED_KEYS.sum()));
        map.put("tooLarge", Long.valueOf(TOO_LARGE_SCOPES.sum()));
        map.put("overflow", Long.valueOf(OVERFLOW_SCOPES.sum()));
        map.put("capped", Long.valueOf(CAPPED.sum()));
        map.put("nested", Long.valueOf(NESTED.sum()));
        map.put("virtualSkipped", Long.valueOf(VIRTUAL_SKIPPED.sum()));
        map.put("eventLoopSkipped", Long.valueOf(EVENT_LOOP_SKIPPED.sum()));
        map.put("unowned", Long.valueOf(UNOWNED.sum()));
        map.put("staleScopes", Long.valueOf(STALE_SCOPES.sum()));
        map.put("foreignCloses", Long.valueOf(FOREIGN_CLOSES.sum()));
        map.put("registryFull", Long.valueOf(REGISTRY_FULL.sum()));
        map.put("resolutions", Long.valueOf(RESOLUTIONS.sum()));
        map.put("selfTestScans", Long.valueOf(SELF_TEST_SCANS.sum()));
    }

    /** Tests only: forgets the scanner, the registry, and the counters. */
    static void reset() {
        scanner = null;
        resolver = null;
        adapterScopes = false;
        selfTestThread = null;
        selfTestCount = 0;
        accessError = null;
        REGISTRY_REF.set(null);
        LongAdder[] adders = {
            SCOPES,
            SCANS,
            LEFTOVERS,
            REPORTED,
            BRIDGE_KEYS,
            JDK_KEYS,
            BOOTUI_KEYS,
            EXCLUDED_KEYS,
            TOO_LARGE_SCOPES,
            OVERFLOW_SCOPES,
            CAPPED,
            NESTED,
            VIRTUAL_SKIPPED,
            EVENT_LOOP_SKIPPED,
            UNOWNED,
            STALE_SCOPES,
            FOREIGN_CLOSES,
            REGISTRY_FULL,
            RESOLUTIONS,
            SELF_TEST_SCANS
        };
        for (LongAdder adder : adders) {
            adder.reset();
        }
        CodePaths.Frame frame = CodePaths.FRAME.get();
        if (frame != null) {
            frame.threadLocals = null;
        }
    }

    /** Tests only: a counter's value by status name. */
    static long counter(String name) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        putStatus(map);
        Object value = map.get(name);
        return value instanceof Long ? ((Long) value).longValue() : -1L;
    }

    private static ClassLoader platformLoader() {
        try {
            return ClassLoader.getPlatformClassLoader();
        } catch (Throwable ex) {
            return null;
        }
    }
}
