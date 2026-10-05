package io.github.jdubois.bootui.agent.bridge;

import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * The side-effect sensors' bridge side (PLAN-v2 §5.16, M5-5): what the application does to the world outside the JVM,
 * recorded by delegating advice on JDK classes, one static call per hook. M5-5a ships the shared base and its first
 * sensor, {@value #PROCESSES} ({@code ProcessBuilder.start}, which {@code Runtime.exec} delegates to): the command's
 * file name, never an argument or the environment, whether it started, and its exit status and duration.
 *
 * <p><b>Switches.</b> One {@code static volatile int} mask, a bit per sensor, is read first by every hook: a sensor
 * records only while an armed claim asks for it and the agent enabled it after its hooks passed their self-test. An
 * internal error budget of {@value #MAX_ERRORS} switches every sensor off for the JVM's life.
 *
 * <p><b>Exclusions.</b> Only the outermost hook on a thread records, so a sensor's own work and a hook nested in another
 * are never recorded; nor is what a thread does while it captures its owner, while BootUI marks its work as its own,
 * while the agent transforms a class, or on BootUI's and the agent's own threads, marked once when they start.
 *
 * <p><b>Owner.</b> The record carries its owner: the request and execution ids, read from the thread's owner slots, a
 * small stack each adapter scope ({@link CodePaths#begin()}) and each propagation handoff pushes as it opens a
 * request's scope or reopens its context and pops as it closes it ({@link #scopeBegin}, {@link #handoff}), so a task of
 * another request run inline on a request's thread is that other request's; a hook whose top slot names no owner, as
 * when the code-paths sensor did not capture one, captures it through the claim's {@code capture}. It also carries the
 * code-paths stamp of the innermost instrumented call ({@link CodePaths#stamp()}), and on rare hooks a frame summary:
 * the first frame outside the JDK and the first in the claimed packages, from a bounded {@code StackWalker}. A record
 * without request or execution names the thread, which the engine groups into a thread family.
 *
 * <p><b>Transport.</b> Records are {@value #RECORD} longs on this class's own ring, with its own table of interned
 * strings scoped to the claim generation, both allocated by the first claim asking for a side-effect sensor. A rare
 * hook, as {@code processes}, publishes each occurrence at once; a hot hook of a later sensor aggregates in the thread's
 * table by {@code (sensor, kind, target, outcome, stamp, frames)} while its owner comes from a slot, flushed to the ring
 * when a slot is pushed or popped, when the table is full, or when its oldest entry is older than {@value
 * #FLUSH_MILLIS} ms at the next record ({@link #record}). A full ring drops the record, counted per sensor, and never
 * blocks.
 *
 * <p><b>Processes.</b> A started process's exit is watched through {@code Process.onExit()}, completed on this class's
 * executor, whose one thread the agent starts through its own thread factory, so nothing of the application is pinned;
 * at most {@value #MAX_PENDING_EXITS} exits are watched at once, the others counted as not watched.
 *
 * <p>JDK types only; every entry point catches everything.
 */
public final class SideEffects {

    /** The processes sensor's id, as {@code bootui.agent.sensors} names it. */
    public static final String PROCESSES = "processes";

    /** The files sensor's id (M5-5d). */
    public static final String FILES = "files";

    /** The environment sensor's id (M5-5d), opt-in. */
    public static final String ENVIRONMENT = "environment";

    /** Sensor ids in records and bit positions in the mask: 0 is unused. */
    public static final int SENSOR_PROCESSES = 1;

    public static final int SENSOR_FILES = 2;
    public static final int SENSOR_ENVIRONMENT = 3;

    static final String[] SENSOR_NAMES = {"other", PROCESSES, FILES, ENVIRONMENT};

    public static final int MASK_PROCESSES = 1 << SENSOR_PROCESSES;
    public static final int MASK_FILES = 1 << SENSOR_FILES;
    public static final int MASK_ENVIRONMENT = 1 << SENSOR_ENVIRONMENT;

    /** Hooks, by index: their ids, as the agent reports them, and their sensors. */
    public static final int HOOK_PROCESS_START = 0;

    public static final int HOOK_FILE_INPUT_STREAM = 1;
    public static final int HOOK_FILE_OUTPUT_STREAM = 2;
    public static final int HOOK_RANDOM_ACCESS_FILE = 3;
    public static final int HOOK_NEW_BYTE_CHANNEL = 4;
    public static final int HOOK_NEW_INPUT_STREAM = 5;
    public static final int HOOK_NEW_OUTPUT_STREAM = 6;
    public static final int HOOK_DELETE = 7;
    public static final int HOOK_DELETE_IF_EXISTS = 8;
    public static final int HOOK_MOVE = 9;
    public static final int HOOK_COPY = 10;
    public static final int HOOK_FILE_CHANNEL = 11;
    public static final int HOOK_GETENV = 12;
    public static final int HOOK_GET_PROPERTY = 13;

    static final String[] HOOKS = {
        "ProcessBuilder.start",
        "FileInputStream.open",
        "FileOutputStream.open",
        "RandomAccessFile.open",
        "Files.newByteChannel",
        "Files.newInputStream",
        "Files.newOutputStream",
        "Files.delete",
        "Files.deleteIfExists",
        "Files.move",
        "Files.copy",
        "FileChannel.open",
        "System.getenv",
        "System.getProperty"
    };
    static final int[] HOOK_SENSORS = {
        SENSOR_PROCESSES,
        SENSOR_FILES,
        SENSOR_FILES,
        SENSOR_FILES,
        SENSOR_FILES,
        SENSOR_FILES,
        SENSOR_FILES,
        SENSOR_FILES,
        SENSOR_FILES,
        SENSOR_FILES,
        SENSOR_FILES,
        SENSOR_FILES,
        SENSOR_ENVIRONMENT,
        SENSOR_ENVIRONMENT
    };

    /** Record kinds. */
    public static final int KIND_PROCESS_START = 1;

    public static final int KIND_PROCESS_EXIT = 2;

    /** A file opened for reading. */
    public static final int KIND_FILE_READ = 3;

    /** A file opened for writing, created, truncated, or appended to. */
    public static final int KIND_FILE_WRITE = 4;

    public static final int KIND_FILE_DELETE = 5;
    public static final int KIND_FILE_MOVE_FROM = 6;
    public static final int KIND_FILE_MOVE_TO = 7;
    public static final int KIND_FILE_COPY_FROM = 8;
    public static final int KIND_FILE_COPY_TO = 9;

    /** An environment variable read by name, or every variable through {@code System.getenv()}. */
    public static final int KIND_ENVIRONMENT_VARIABLE = 10;

    public static final int KIND_SYSTEM_PROPERTY = 11;

    /** Outcomes. */
    public static final int OUTCOME_STARTED = 1;

    public static final int OUTCOME_IO_ERROR = 2;
    public static final int OUTCOME_ERROR = 3;
    public static final int OUTCOME_EXITED = 4;

    /** A file operation or an environment read that returned normally. */
    public static final int OUTCOME_DONE = 5;

    /**
     * The JDK context of a file operation or an environment read, from its frame summary, in {@link #R_FLAGS} bits
     * 32–39: none, the JDK's own logging ({@code java.util.logging}), class loading (a class loader, a module, a service
     * loader, a {@code jar:} URL), or only JDK frames within {@value #MAX_FRAMES}. The engine classifies the first frame
     * outside the JDK itself.
     */
    public static final int CONTEXT_NONE = 0;

    public static final int CONTEXT_JDK_LOGGING = 1;
    public static final int CONTEXT_CLASS_LOADING = 2;
    public static final int CONTEXT_JDK_ONLY = 3;

    /** The files sensor's buckets: counted, never recorded, interned, or walked (PLAN-v2 M5-5 design B2). */
    public static final int BUCKET_CLASS_FILES = 0;

    public static final int BUCKET_ARCHIVES = 1;
    public static final int BUCKET_ARCHIVE_FILE_SYSTEMS = 2;
    public static final int BUCKET_JAVA_HOME = 3;
    public static final int BUCKET_CLASS_PATH_DIRECTORIES = 4;

    static final String[] BUCKETS = {"classFiles", "archives", "archiveFileSystems", "javaHome", "classPathDirectories"
    };

    /** Interned targets per claim generation, at most, for the files and environment sensors. */
    public static final int FILES_INTERN_QUOTA = 3_000;

    public static final int ENVIRONMENT_INTERN_QUOTA = 1_000;

    /** The longest path pattern or name kept, in characters. */
    static final int MAX_PATTERN = 200;

    /** An unowned thread's environment reads are recorded again after this long. */
    static final long UNOWNED_SEEN_MILLIS = 1_000L;

    /** Thread kinds. */
    public static final int THREAD_PLATFORM = 1;

    public static final int THREAD_VIRTUAL = 2;

    /**
     * Execution kinds, as records carry them: those of the code-paths sensor ({@link CodePaths#EXECUTION_ASYNC},
     * {@link CodePaths#EXECUTION_TASK}), and an execution no request owns, as a scheduled run, a consumed message, or a
     * WebSocket message, whose id names it in the runtime journal.
     */
    public static final int EXECUTION_OWN = 3;

    /** What filled a thread's owner slot. */
    static final int SLOT_SCOPE = 1;

    static final int SLOT_HANDOFF = 2;

    /** Longs per record. */
    public static final int RECORD = 14;

    /** Record layout: the sensor id. */
    public static final int R_SENSOR = AgentRing.SENSOR;

    public static final int R_KIND = AgentRing.TYPE;

    /** The claim generation, where every ring keeps it. */
    public static final int R_GENERATION = AgentRing.GENERATION;

    public static final int R_FIRST_MILLIS = 3;
    public static final int R_LAST_MILLIS = 4;
    public static final int R_REQUEST = 5;
    public static final int R_EXECUTION = 6;
    public static final int R_STAMP = 7;

    /** The interned target: for processes, the command's file name. */
    public static final int R_TARGET = 8;

    /**
     * Outcome (bits 0–7), thread kind (8–11), execution kind (12–15), the interned thread name of an unowned record
     * (16–31), and a process's exit status (32–63), or a file operation's or an environment read's JDK context
     * ({@link #CONTEXT_NONE}, 32–39).
     */
    public static final int R_FLAGS = 9;

    public static final int R_COUNT = 10;
    public static final int R_NANOS = 11;
    public static final int R_MAX_NANOS = 12;

    /** The interned first frame outside the JDK (bits 32–63) and first frame in the claimed packages (bits 0–31). */
    public static final int R_FRAMES = 13;

    public static final int DEFAULT_CAPACITY = 1 << 10;

    /** Interned strings per claim generation; id 0 is "unknown", returned on overflow. */
    public static final int DEFAULT_INTERNS = 1 << 13;

    /** Aggregated entries per thread before the table is flushed. */
    public static final int TABLE = 32;

    public static final long FLUSH_MILLIS = 1_000L;

    public static final int MAX_ERRORS = 100;

    public static final int MAX_PENDING_EXITS = 1_024;

    static final int EXIT_QUEUE = 256;

    /** Frames walked for a frame summary at most. */
    static final int MAX_FRAMES = 64;

    /** The longest target kept, in characters. */
    static final int MAX_TARGET = 128;

    private static final long FLUSH_NANOS = FLUSH_MILLIS * 1_000_000L;

    private static final AgentRing.Counters COUNTERS = new AgentRing.Counters();
    private static final AtomicReference<AgentRing.Ring> RING = new AtomicReference<AgentRing.Ring>();
    private static final AtomicReference<AgentRing.Interns> INTERNS = new AtomicReference<AgentRing.Interns>();
    private static final StackWalker WALKER = StackWalker.getInstance();
    /**
     * The files and environment sensors' walker, which tells a JDK module's frames by their class: created by {@link
     * #warm()} on the agent's own thread, as a security manager may require a permission for it; until then, or
     * without it, the plain walker, by class name only.
     */
    private static volatile StackWalker classWalker;

    private static final ExitQueue EXITS = new ExitQueue();
    private static final AtomicBoolean EXIT_WORKER_HANDED_OUT = new AtomicBoolean();

    private static final LongAdder[] RECORDED = adders(HOOKS.length);
    private static final LongAdder[] SELF_TEST_HITS = adders(HOOKS.length);
    private static final LongAdder[] DROPPED = adders(SENSOR_NAMES.length);
    private static final LongAdder[] PUBLISHED = adders(SENSOR_NAMES.length);
    private static final LongAdder STALE_DRAINS = new LongAdder();
    private static final LongAdder BUSY_DRAINS = new LongAdder();
    private static final LongAdder TABLE_FLUSHES = new LongAdder();
    private static final LongAdder SKIPPED = new LongAdder();
    private static final LongAdder EXITS_WATCHED = new LongAdder();
    private static final LongAdder EXITS_RECORDED = new LongAdder();
    private static final LongAdder EXITS_UNWATCHED = new LongAdder();
    private static final LongAdder EXITS_DROPPED = new LongAdder();
    private static final LongAdder APPLICATION_ERRORS = new LongAdder();
    private static final LongAdder SLOT_MISMATCHES = new LongAdder();
    private static final AtomicInteger PENDING_EXITS = new AtomicInteger();
    private static final AtomicLong ERROR_COUNT = new AtomicLong();
    private static final String[] DISABLED_REASONS = new String[SENSOR_NAMES.length];

    private static final LongAdder[] BUCKET_COUNTS = adders(BUCKETS.length);
    private static final LongAdder[] QUOTA_EXCEEDED = adders(SENSOR_NAMES.length);
    private static final LongAdder WALKS = new LongAdder();
    private static final LongAdder SIGHTINGS_FULL = new LongAdder();
    private static final LongAdder JDK_READS = new LongAdder();
    private static final LongAdder FRAMEWORK_READS = new LongAdder();

    /** The context of an environment read whose immediate caller is a configuration framework: not recorded. */
    static final int INDIRECT_FRAMEWORK = -2;

    private static final Sightings SIGHTINGS = new Sightings();
    /** The interned targets of the files and environment sensors in the current intern table's generation. */
    private static final AtomicInteger[] INTERNED = {
        new AtomicInteger(), new AtomicInteger(), new AtomicInteger(), new AtomicInteger()
    };

    /** The directories path patterns are relative to, read by {@link #warm()} on the agent's own thread. */
    private static volatile Places places = Places.NONE;

    /**
     * {@link #mask}, with every bit set while a self-test runs: what the inlined advice of a hot hook reads before it
     * calls the bridge at all ({@link #gate()}).
     */
    private static volatile int gate;

    /** The sensors recording now, a bit per sensor id: read first by every hook. */
    static volatile int mask;

    /** The sensors the agent enabled once their hooks passed their self-test. */
    private static volatile int enabled;

    private static volatile long generation = -1L;
    private static volatile boolean off;
    private static volatile String offReason;
    private static volatile Thread selfTestThread;
    private static volatile boolean exitWorkerRunning;

    /** Whether a claim ever asked for a side-effect sensor: until then its status is not reported. */
    static volatile boolean claimedOnce;

    private SideEffects() {}

    // ---- the processes sensor's advice ---------------------------------------------------------------------------

    /**
     * {@code ProcessBuilder.start(Redirect[])} entry: a token for {@link #processStarted}, 0 when nothing is recorded
     * for the call (the sensor is off, the thread's work is skipped, or a side-effect hook is already open on the
     * thread). Never throws.
     */
    public static long processStarting() {
        try {
            Thread self = selfTestThread;
            if (self != null && self == Thread.currentThread()) {
                SELF_TEST_HITS[HOOK_PROCESS_START].increment();
                return 0L;
            }
            if ((mask & MASK_PROCESSES) == 0) {
                return 0L;
            }
            if (Reentrancy.sideEffectsSkipped()
                    || Thread.currentThread().getName().startsWith("bootui-")) {
                SKIPPED.increment();
                return 0L;
            }
            CodePaths.Frame frame = CodePaths.frame();
            if (frame.sideEffectDepth != 0) {
                return 0L;
            }
            frame.sideEffectDepth = 1;
            long now = System.nanoTime();
            return now == 0L ? 1L : now;
        } catch (Throwable ex) {
            failed(ex);
            return 0L;
        }
    }

    /**
     * {@code ProcessBuilder.start(Redirect[])} exit, normal or not, with the token its entry returned: records the
     * command's file name and whether the process started, and watches its exit. Never reads an argument or the
     * environment. Never throws.
     */
    public static void processStarted(long token, List<String> command, Process process, Throwable thrown) {
        if (token == 0L) {
            return;
        }
        CodePaths.Frame frame = null;
        try {
            frame = CodePaths.FRAME.get();
            long nanos = System.nanoTime() - token;
            Claim claim = AgentBridge.current();
            if (claim == null || !claim.armed || claim.generation != generation || (mask & MASK_PROCESSES) == 0) {
                return;
            }
            RECORDED[HOOK_PROCESS_START].increment();
            int target = intern(commandName(command, process != null));
            int outcome = process != null
                    ? OUTCOME_STARTED
                    : thrown instanceof java.io.IOException ? OUTCOME_IO_ERROR : OUTCOME_ERROR;
            long stamp = CodePaths.stamp();
            long frames = frames(claim);
            Owner owner = owner(frame, claim);
            long startMillis = System.currentTimeMillis();
            // A rare hook: published at once, never held in the thread's table.
            publishOne(
                    owner, SENSOR_PROCESSES, KIND_PROCESS_START, target, outcome, 0, stamp, frames, nanos, startMillis);
            if (process != null) {
                watchExit(process, owner, target, stamp, frames, token, startMillis);
            }
        } catch (Throwable ex) {
            failed(ex);
        } finally {
            if (frame != null) {
                frame.sideEffectDepth = 0;
            }
        }
    }

    /**
     * The command's file name, at most {@value #MAX_TARGET} characters, every character but letters, digits, and
     * {@code . _ + -} replaced by {@code ?}: for a process that {@code started}, the first element of the command after
     * its last path separator, an executable's path, spaces included, as {@code C:\Program Files\Java\bin\java.exe}
     * gives {@code java.exe}, and an element that opens with a quote, as Windows passes {@code "C:\Tools\app.exe" --token
     * x} verbatim to {@code CreateProcess}, only its quoted text, and in every case never past an inner quote, as Windows
     * starts {@code C:\Tools\app.exe" --token "x}; for a start that failed, which may have been given a command and its arguments, or an
     * environment assignment, as one element, that element up to its first whitespace and its first {@code =} first, so
     * never an argument or a value.
     */
    static String commandName(List<String> command, boolean started) {
        if (command == null) {
            return "(unknown)";
        }
        String first = command.isEmpty() ? null : command.get(0);
        if (first == null) {
            return "(empty)";
        }
        String program = first;
        if (first.startsWith("\"")) {
            // On Windows, one element such as "C:\Tools\app.exe" --token "abc" is passed verbatim to CreateProcess
            // and starts: only the quoted executable, never what follows its closing quote.
            int close = first.indexOf('"', 1);
            if (close > 0) {
                program = first.substring(1, close);
            } else {
                // An unclosed quote: never past the first whitespace either.
                int space = 1;
                while (space < first.length() && !Character.isWhitespace(first.charAt(space))) {
                    space++;
                }
                program = first.substring(1, space);
            }
            if (!started) {
                int equals = program.indexOf('=');
                if (equals >= 0) {
                    program = program.substring(0, equals);
                }
            }
        } else if (!started) {
            // A command and its arguments passed as one element never start, but are still recorded: never past the
            // first whitespace, so an argument cannot reach the name.
            int space = 0;
            while (space < first.length() && !Character.isWhitespace(first.charAt(space))) {
                space++;
            }
            program = first.substring(0, space);
            // Before the path split: a value after the '=' may hold a '/', whose tail must never become the name.
            int equals = program.indexOf('=');
            if (equals >= 0) {
                program = program.substring(0, equals);
            }
        }
        // Before the path split: no file name holds a '"', and on Windows the JDK wraps an element with an inner
        // unpaired
        // quote and a space in quotes, so C:\Tools\app.exe" --token "abc starts app.exe with the rest as arguments.
        int quote = program.indexOf('"');
        if (quote >= 0) {
            program = program.substring(0, quote);
        }
        int slash = Math.max(program.lastIndexOf('/'), program.lastIndexOf('\\'));
        String name = slash >= 0 ? program.substring(slash + 1) : program;
        if (name.isEmpty()) {
            return "(empty)";
        }
        int length = Math.min(name.length(), MAX_TARGET);
        StringBuilder text = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            char c = name.charAt(i);
            boolean kept = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '.'
                    || c == '_'
                    || c == '+'
                    || c == '-';
            text.append(kept ? c : '?');
        }
        return text.toString();
    }

    private static void watchExit(
            Process process, Owner owner, int target, long stamp, long frames, long startNanos, long startMillis) {
        if (!exitWorkerRunning) {
            EXITS_UNWATCHED.increment();
            return;
        }
        if (PENDING_EXITS.incrementAndGet() > MAX_PENDING_EXITS) {
            PENDING_EXITS.decrementAndGet();
            EXITS_UNWATCHED.increment();
            return;
        }
        // Process.onExit() completes through a JDK stage on the common ForkJoin pool, then whenCompleteAsync hands the
        // exit to this class's own executor. BootUI's work: a thread the JDK starts for that stage, as with a common
        // pool of one, is never recorded.
        boolean previous = Reentrancy.bootUiWork(true);
        try {
            CompletableFuture<Process> exit = process.onExit();
            exit.whenCompleteAsync(new ExitCallback(owner, target, stamp, frames, startNanos, startMillis), EXITS);
            EXITS_WATCHED.increment();
        } catch (Throwable ex) {
            PENDING_EXITS.decrementAndGet();
            EXITS_UNWATCHED.increment();
            throw ex;
        } finally {
            Reentrancy.bootUiWork(previous);
        }
    }

    // ---- the files and environment sensors' advice (M5-5d) ------------------------------------------------------

    /**
     * What the inlined advice of a file or environment hook reads before it calls the bridge: the bits of the sensors
     * recording now, every bit while a self-test runs. One volatile read; a hook whose sensor's bit is clear costs
     * nothing more.
     */
    public static int gate() {
        return gate;
    }

    /**
     * A file hook's entry: a token for its exit, 0 when nothing is recorded for the call (the sensor is off, the
     * thread's work is skipped, or a side-effect hook is already open on the thread, so only the outermost of nested
     * file operations records). The self-test's thread is counted per hook and records nothing. Never throws.
     */
    public static long fileOpening(int hook) {
        try {
            Thread current = Thread.currentThread();
            Thread self = selfTestThread;
            if (self != null && self == current) {
                SELF_TEST_HITS[hook].increment();
                return 0L;
            }
            if ((mask & MASK_FILES) == 0) {
                return 0L;
            }
            if (Reentrancy.sideEffectsSkipped() || current.getName().startsWith("bootui-")) {
                SKIPPED.increment();
                return 0L;
            }
            CodePaths.Frame frame = CodePaths.frame();
            if (frame.sideEffectDepth != 0) {
                return 0L;
            }
            frame.sideEffectDepth = 1;
            long now = System.nanoTime();
            return now == 0L ? 1L : now;
        } catch (Throwable ex) {
            failed(ex);
            return 0L;
        }
    }

    /**
     * {@code FileInputStream.open(String)} ({@link #KIND_FILE_READ}) or {@code FileOutputStream.open(String, boolean)}
     * ({@link #KIND_FILE_WRITE}) exit, normal or not: records the file's path pattern, never its contents. Never throws.
     */
    public static void fileOpened(long token, int hook, int kind, String name, Throwable thrown) {
        if (token == 0L) {
            return;
        }
        files(token, hook, kind, name, 0, null, thrown);
    }

    /**
     * {@code RandomAccessFile.open(String, int)} exit: read when the mode holds the JDK's {@code O_RDONLY} bit (1), as
     * {@code "r"} gives, else write ({@code "rw"}, {@code "rws"}, {@code "rwd"}). Never throws.
     */
    public static void randomAccessOpened(long token, String name, int mode, Throwable thrown) {
        if (token == 0L) {
            return;
        }
        files(
                token,
                HOOK_RANDOM_ACCESS_FILE,
                (mode & 1) != 0 ? KIND_FILE_READ : KIND_FILE_WRITE,
                name,
                0,
                null,
                thrown);
    }

    /**
     * {@code Files.newByteChannel(Path, Set, FileAttribute[])} or {@code FileChannel.open(Path, Set,
     * FileAttribute[])} exit: write when the options hold {@code WRITE} or {@code APPEND}, else read. Never throws.
     */
    public static void channelOpened(long token, int hook, Object path, Set<?> options, Throwable thrown) {
        if (token == 0L) {
            return;
        }
        int kind = KIND_FILE_READ;
        try {
            if (options != null
                    && (options.contains(StandardOpenOption.WRITE) || options.contains(StandardOpenOption.APPEND))) {
                kind = KIND_FILE_WRITE;
            }
        } catch (Throwable ex) {
            // An application's own Set: read, as the JDK would for an option set it cannot read.
        }
        files(token, hook, kind, path, 0, null, thrown);
    }

    /**
     * {@code Files.newInputStream}, {@code newOutputStream}, {@code delete}, or {@code deleteIfExists} exit, with the
     * kind the hook records. Never throws.
     */
    public static void pathUsed(long token, int hook, int kind, Object path, Throwable thrown) {
        if (token == 0L) {
            return;
        }
        files(token, hook, kind, path, 0, null, thrown);
    }

    /**
     * {@code Files.move(Path, Path, CopyOption[])} or a {@code Files.copy} overload exit: each {@code Path} argument
     * is one observation, the source's kind {@code fromKind} and the destination's {@code toKind}; a stream argument
     * of {@code copy} records nothing. Never throws.
     */
    public static void pathsUsed(
            long token, int hook, int fromKind, Object from, int toKind, Object to, Throwable thrown) {
        if (token == 0L) {
            return;
        }
        files(
                token,
                hook,
                fromKind,
                from instanceof Path ? from : null,
                toKind,
                to instanceof Path ? to : null,
                thrown);
    }

    private static void files(
            long token, int hook, int kind, Object target, int secondKind, Object second, Throwable thrown) {
        CodePaths.Frame frame = null;
        try {
            frame = CodePaths.FRAME.get();
            long nanos = System.nanoTime() - token;
            Claim claim = AgentBridge.current();
            if (claim == null || !claim.armed || claim.generation != generation || (mask & MASK_FILES) == 0) {
                return;
            }
            int outcome = thrown == null
                    ? OUTCOME_DONE
                    : thrown instanceof java.io.IOException ? OUTCOME_IO_ERROR : OUTCOME_ERROR;
            if (target != null) {
                recordFile(frame, claim, hook, kind, target, outcome, nanos);
            }
            if (second != null) {
                recordFile(frame, claim, hook, secondKind, second, outcome, nanos);
            }
        } catch (Throwable ex) {
            failed(ex);
        } finally {
            if (frame != null) {
                frame.sideEffectDepth = 0;
            }
        }
    }

    /** One file operation: counted in its bucket, or recorded with its path pattern, owner, and frame summary. */
    private static void recordFile(
            CodePaths.Frame frame, Claim claim, int hook, int kind, Object target, int outcome, long nanos) {
        Places where = places;
        String text;
        if (target instanceof Path) {
            Path path = (Path) target;
            if (where.fileSystem == null || path.getFileSystem() != where.fileSystem) {
                // A zip, jar:, nested:, or jrt: file system's path: its toString() is never called.
                BUCKET_COUNTS[BUCKET_ARCHIVE_FILE_SYSTEMS].increment();
                return;
            }
            text = path.toString();
        } else if (target instanceof String) {
            text = (String) target;
        } else {
            return;
        }
        if (text.isEmpty()) {
            return;
        }
        int bucket = bucketOfName(text);
        if (bucket >= 0) {
            BUCKET_COUNTS[bucket].increment();
            return;
        }
        String absolute = absolute(text, where);
        bucket = bucketOfPath(absolute, where);
        if (bucket >= 0) {
            BUCKET_COUNTS[bucket].increment();
            return;
        }
        RECORDED[hook].increment();
        int id = internQuota(pattern(absolute, where), SENSOR_FILES);
        long stamp = CodePaths.stamp();
        Owner owner = owner(frame, claim);
        long[] summary = summary(frame, claim, owner, hook, id, stamp, false);
        record(frame, owner, SENSOR_FILES, kind, id, outcome, (int) summary[1], stamp, summary[0], nanos);
    }

    /**
     * {@code System.getenv(String)} ({@link #KIND_ENVIRONMENT_VARIABLE}, {@code name} {@code null} for {@code
     * getenv()}) or {@code System.getProperty} ({@link #KIND_SYSTEM_PROPERTY}) entry: records the name, never the value,
     * which the advice never sees, the first time the thread reads it for its current owner, and only for a direct
     * call, not the JDK's own reads. Never throws.
     */
    public static void environmentRead(int hook, int kind, String name) {
        CodePaths.Frame frame = null;
        try {
            Thread current = Thread.currentThread();
            Thread self = selfTestThread;
            if (self != null && self == current) {
                SELF_TEST_HITS[hook].increment();
                return;
            }
            if ((mask & MASK_ENVIRONMENT) == 0
                    || (name != null && name.isEmpty())
                    || Reentrancy.sideEffectsSkipped()
                    || current.getName().startsWith("bootui-")) {
                return;
            }
            CodePaths.Frame candidate = CodePaths.frame();
            if (candidate.sideEffectDepth != 0) {
                return;
            }
            frame = candidate;
            frame.sideEffectDepth = 1;
            long now = generation;
            boolean slotted = slotted(frame, now);
            int top = frame.slots - 1;
            Seen seen = frame.environmentSeen;
            if (seen == null) {
                seen = new Seen(true);
                frame.environmentSeen = seen;
            }
            seen.own(
                    now,
                    slotted ? frame.slotRequest[top] : 0L,
                    slotted ? frame.slotExecution[top] : 0L,
                    slotted,
                    slotted ? 0L : System.currentTimeMillis());
            long key = Seen.key(hook, kind, name == null ? 0 : name.hashCode());
            if (seen.find(key, name) >= 0) {
                return;
            }
            seen.put(key, name, 0L, 0);
            Claim claim = AgentBridge.current();
            if (claim == null || !claim.armed || claim.generation != now) {
                return;
            }
            String target = name == null
                    ? "(all variables)"
                    : name.length() > MAX_PATTERN ? name.substring(0, MAX_PATTERN) : name;
            long stamp = CodePaths.stamp();
            // Walked before the name is interned, its cache keyed by the name's hash: an indirect read, never
            // recorded, takes nothing of the generation's table.
            long[] summary = summary(frame, claim, null, hook, target.hashCode() | 1, stamp, true);
            if (summary[1] == INDIRECT_FRAMEWORK) {
                // A configuration framework resolving its own property, as SmallRye Config or Spring's Environment.
                FRAMEWORK_READS.increment();
                return;
            }
            if (summary[1] < 0) {
                // A read the JDK made for itself, as a property lookup inside an XML or SSL factory: not direct.
                JDK_READS.increment();
                return;
            }
            int id = internQuota(target, SENSOR_ENVIRONMENT);
            RECORDED[hook].increment();
            Owner owner = owner(frame, claim);
            record(frame, owner, SENSOR_ENVIRONMENT, kind, id, OUTCOME_DONE, (int) summary[1], stamp, summary[0], 0L);
        } catch (Throwable ex) {
            failed(ex);
        } finally {
            if (frame != null) {
                frame.sideEffectDepth = 0;
            }
        }
    }

    // ---- the owner slots ---------------------------------------------------------------------------------------

    /** Owner slots kept per thread; deeper ones name no owner, and a hook captures its owner instead. */
    static final int SLOTS = 8;

    /**
     * An adapter opened a request's scope on this thread ({@link CodePaths#begin()}), with the owner the code-paths
     * sensor captured there, or {@code null}: a slot is pushed for the scope, naming that owner, or no owner, so that a
     * hook inside captures its own. When the code-paths sensor made no capture ({@code attempted} false, as when it is
     * not claimed) and the files or environment sensor records, whose hooks read only the slot on their hot path, the
     * owner is captured here, once per scope (PLAN-v2 M5-5 design B1). Never throws.
     */
    static void scopeBegin(long[] captured, boolean attempted) {
        try {
            CodePaths.Frame frame;
            if (mask == 0) {
                // A stack in use stays balanced while a sensor is off, as between a claim and its self-test.
                frame = CodePaths.FRAME.get();
                if (frame == null || frame.slots == 0) {
                    return;
                }
            } else {
                frame = CodePaths.frame();
            }
            if (captured == null && !attempted && (mask & (MASK_FILES | MASK_ENVIRONMENT)) != 0) {
                Claim claim = AgentBridge.current();
                Owner owner = new Owner();
                boolean owned = false;
                try {
                    owned = claim != null
                            && claim.armed
                            && claim.generation == generation
                            && !Reentrancy.sideEffectsSkipped()
                            && ownerOf(CodePaths.capture(claim), owner);
                } catch (Throwable ex) {
                    // The slot is pushed all the same, naming no owner, so the scope's end stays balanced.
                    failed(ex);
                }
                if (owned) {
                    push(frame, SLOT_SCOPE, generation, owner.request, owner.execution, owner.executionKind);
                    return;
                }
            }
            if (captured == null) {
                push(frame, SLOT_SCOPE, 0L, 0L, 0L, 0);
            } else {
                push(frame, SLOT_SCOPE, generation, captured[0], captured[1], (int) captured[2]);
            }
        } catch (Throwable ex) {
            failed(ex);
        }
    }

    /** The scope {@link #scopeBegin} opened ends: its slot is popped and the thread's table flushed. Never throws. */
    static void scopeEnd() {
        try {
            CodePaths.Frame frame = CodePaths.FRAME.get();
            if (frame != null) {
                pop(frame, SLOT_SCOPE);
            }
        } catch (Throwable ex) {
            failed(ex);
        }
    }

    /**
     * A propagation sensor reopened, on this thread, the context of work its submitter's request owns, with the
     * submitter's capture: a slot naming that request is pushed, over whatever this thread was doing, as when an executor
     * runs another request's task on a request's own thread. Never throws.
     */
    static void handoff(Object[] payload, long snapshotGeneration) {
        try {
            CodePaths.Frame frame;
            if (mask == 0) {
                frame = CodePaths.FRAME.get();
                if (frame == null || frame.slots == 0) {
                    return;
                }
            } else {
                frame = CodePaths.frame();
            }
            long current = generation;
            long request = snapshotGeneration == current
                            && payload != null
                            && payload.length > 0
                            && payload[0] instanceof String
                    ? CodeInventory.parseRequestId((String) payload[0])
                    : 0L;
            if (request == 0L) {
                push(frame, SLOT_HANDOFF, 0L, 0L, 0L, 0);
            } else {
                // The reopened execution's own id is the engine's: the request is what attributes the work.
                push(frame, SLOT_HANDOFF, current, request, 0L, CodePaths.EXECUTION_NONE);
            }
        } catch (Throwable ex) {
            failed(ex);
        }
    }

    /** The work {@link #handoff} reopened is over: its slot is popped and the thread's table flushed. Never throws. */
    static void handoffDone() {
        try {
            CodePaths.Frame frame = CodePaths.FRAME.get();
            if (frame != null) {
                pop(frame, SLOT_HANDOFF);
            }
        } catch (Throwable ex) {
            failed(ex);
        }
    }

    private static void push(
            CodePaths.Frame frame, int source, long slotGeneration, long request, long execution, int kind) {
        flush(frame);
        if (frame.slotSource == null) {
            frame.slotGeneration = new long[SLOTS];
            frame.slotRequest = new long[SLOTS];
            frame.slotExecution = new long[SLOTS];
            frame.slotKind = new int[SLOTS];
            frame.slotSource = new int[SLOTS];
        }
        int index = frame.slots;
        if (index < SLOTS) {
            frame.slotGeneration[index] = slotGeneration;
            frame.slotRequest[index] = request;
            frame.slotExecution[index] = execution;
            frame.slotKind[index] = kind;
            frame.slotSource[index] = source;
        }
        frame.slots = index + 1;
    }

    /** Pops the top slot when {@code source} pushed it; an unmatched end, as after a sensor switched on mid-scope, pops nothing. */
    private static void pop(CodePaths.Frame frame, int source) {
        int index = frame.slots - 1;
        if (index < 0) {
            return;
        }
        if (index < SLOTS && frame.slotSource[index] != source) {
            SLOT_MISMATCHES.increment();
            return;
        }
        flush(frame);
        frame.slots = index;
    }

    /** Whether the thread's top slot names an owner of generation {@code current}. */
    private static boolean slotted(CodePaths.Frame frame, long current) {
        int index = frame.slots - 1;
        return index >= 0
                && index < SLOTS
                && frame.slotGeneration[index] == current
                && (frame.slotRequest[index] != 0L || frame.slotExecution[index] != 0L);
    }

    /** The record's owner: the slot's, or, for a rare hook with an empty slot, captured. */
    private static Owner owner(CodePaths.Frame frame, Claim claim) {
        Owner owner = new Owner();
        Thread thread = Thread.currentThread();
        owner.threadKind = ThreadPropagation.isVirtual(thread) ? THREAD_VIRTUAL : THREAD_PLATFORM;
        owner.generation = claim.generation;
        if (frame != null && slotted(frame, claim.generation)) {
            int index = frame.slots - 1;
            owner.request = frame.slotRequest[index];
            owner.execution = frame.slotExecution[index];
            owner.executionKind = frame.slotKind[index];
            owner.slot = true;
            return owner;
        }
        if (!ownerOf(CodePaths.capture(claim), owner)) {
            owner.threadName = intern(thread.getName());
        }
        return owner;
    }

    /**
     * Reads a capture's request and execution into {@code owner}: an execution id prefixed {@code async-} or
     * {@code task-} is a request's ({@link CodePaths#EXECUTION_ASYNC}, {@link CodePaths#EXECUTION_TASK}), a bare one an
     * execution no request owns ({@link #EXECUTION_OWN}). Returns whether the capture named either.
     */
    static boolean ownerOf(Object payload, Owner owner) {
        if (!(payload instanceof Object[])) {
            return false;
        }
        Object[] values = (Object[]) payload;
        owner.request = values.length > 0 && values[0] instanceof String
                ? CodeInventory.parseRequestId((String) values[0])
                : 0L;
        if (values.length > 1 && values[1] instanceof String) {
            String id = (String) values[1];
            if (id.startsWith("async-")) {
                owner.execution = CodeInventory.parseRequestId(id.substring(6));
                owner.executionKind = CodePaths.EXECUTION_ASYNC;
            } else if (id.startsWith("task-")) {
                owner.execution = CodeInventory.parseRequestId(id.substring(5));
                owner.executionKind = CodePaths.EXECUTION_TASK;
            } else {
                owner.execution = CodeInventory.parseRequestId(id);
                owner.executionKind = EXECUTION_OWN;
            }
            if (owner.execution == 0L) {
                owner.executionKind = CodePaths.EXECUTION_NONE;
            }
        }
        return owner.request != 0L || owner.execution != 0L;
    }

    // ---- frames ----------------------------------------------------------------------------------------------------

    /** The first frame outside the JDK and the first in the claimed packages, interned, packed; 0 when none. */
    static long frames(Claim claim) {
        long[] found = WALKER.walk(new FrameSummary(claim));
        return found == null ? 0L : found[0];
    }

    /** The JDK, this bridge, and the agent: never a frame of the summary. */
    static boolean jdkOrAgent(String className) {
        return className.startsWith("java.")
                || className.startsWith("jdk.")
                || className.startsWith("sun.")
                || className.startsWith("com.sun.")
                || className.startsWith("io.github.jdubois.bootui.agent.");
    }

    /** Walks at most {@value #MAX_FRAMES} frames for the summary: the packed interned ids. */
    static final class FrameSummary implements Function<Stream<StackWalker.StackFrame>, long[]> {

        private final Claim claim;

        FrameSummary(Claim claim) {
            this.claim = claim;
        }

        @Override
        public long[] apply(Stream<StackWalker.StackFrame> frames) {
            Iterator<StackWalker.StackFrame> iterator = frames.iterator();
            int outside = 0;
            int application = 0;
            for (int i = 0; i < MAX_FRAMES && iterator.hasNext() && application == 0; i++) {
                StackWalker.StackFrame frame = iterator.next();
                String className = frame.getClassName();
                if (jdkOrAgent(className)) {
                    continue;
                }
                if (outside == 0) {
                    outside = intern(className + "#" + frame.getMethodName());
                }
                if (claim != null && ThreadPropagation.inPackages(className, claim)) {
                    application = intern(className + "#" + frame.getMethodName());
                }
            }
            return new long[] {((long) outside << 32) | (application & 0xFFFFFFFFL)};
        }
    }

    // ---- the files and environment sensors' frame summaries, caches, and path patterns (M5-5d) ------------------

    /**
     * The frame summary and JDK context of an operation, {@code {frames, context}}, the context -1 for an environment
     * read whose immediate caller is the JDK's: walked once per {@code (hook, target, method)} in the generation when a
     * code-paths stamp names the method ({@code stamp > 0}, PLAN-v2 M5-5 design B3), else once per {@code (hook,
     * target)} for the thread's current owner for a file operation, and for every first read of an owner for an
     * environment read, whose own per-thread cache bounds it. Without room in the generation's cache, nothing is walked.
     */
    static long[] summary(CodePaths.Frame frame, Claim claim, Owner owner, int hook, int id, long stamp, boolean env) {
        int method = stamp > 0L ? CodePaths.stampMethod(stamp) : -1;
        if (method >= 0 && (env ? id != 0 : id > 0)) {
            long key = ((long) (hook + 1) << 56) | ((long) (id & 0xFFFFFF) << 32) | (method & 0xFFFFFFFFL);
            long[] found = new long[2];
            int result = SIGHTINGS.find(generation, key, found);
            if (result == Sightings.FOUND) {
                return found;
            }
            if (result == Sightings.FULL) {
                SIGHTINGS_FULL.increment();
                return new long[] {0L, CONTEXT_NONE};
            }
            long[] walked = walk(claim, env);
            SIGHTINGS.put(generation, key, walked[0], (int) walked[1]);
            return walked;
        }
        if (!env && id <= 0) {
            // Past the quota: no pattern to cache the summary under, so none is walked, as with a full cache.
            return new long[] {0L, CONTEXT_NONE};
        }
        if (env || frame == null || owner == null) {
            return walk(claim, env);
        }
        Seen seen = frame.fileSightings;
        if (seen == null) {
            seen = new Seen(false);
            frame.fileSightings = seen;
        }
        seen.own(
                owner.generation,
                owner.request,
                owner.execution,
                owner.slot,
                owner.slot ? 0L : System.currentTimeMillis());
        long key = Seen.key(hook, 0, id);
        int index = seen.find(key, null);
        if (index >= 0) {
            return new long[] {seen.frames[index], seen.contexts[index]};
        }
        long[] walked = walk(claim, false);
        seen.put(key, null, walked[0], (int) walked[1]);
        return walked;
    }

    private static long[] walk(Claim claim, boolean environment) {
        WALKS.increment();
        StackWalker walker = classWalker;
        long[] walked = (walker == null ? WALKER : walker).walk(new ContextSummary(claim, environment));
        return walked == null ? new long[] {0L, CONTEXT_NONE} : walked;
    }

    /** JDK classes whose frames make an operation class loading's. */
    static boolean classLoading(String className) {
        return className.startsWith("java.lang.ClassLoader")
                || className.equals("java.lang.Class")
                || className.equals("java.lang.Module")
                || className.equals("java.net.URLClassLoader")
                || className.equals("java.security.SecureClassLoader")
                || className.equals("java.util.ServiceLoader")
                || className.startsWith("java.util.ServiceLoader$")
                || className.startsWith("java.lang.module.")
                || className.startsWith("jdk.internal.loader.")
                || className.startsWith("jdk.internal.module.")
                || className.startsWith("jdk.internal.jimage.")
                || className.startsWith("sun.net.www.protocol.jar.");
    }

    /**
     * JDK frames a direct read passes through, as {@code Optional.map(System::getenv)}, a stream's {@code
     * map(System::getProperty)}, or a reflective call: an environment read's immediate caller is looked for past them.
     */
    static boolean transparent(String className) {
        return className.startsWith("java.util.Optional")
                || className.startsWith("java.util.stream.")
                || className.startsWith("java.util.function.")
                || className.startsWith("java.util.Spliterator")
                || className.startsWith("java.util.ArrayList")
                || className.startsWith("java.util.Iterator")
                || className.startsWith("java.lang.Iterable")
                || className.startsWith("jdk.internal.reflect.")
                || className.startsWith("java.lang.reflect.")
                || className.startsWith("java.lang.invoke.");
    }

    /**
     * Configuration frameworks, which read system properties and environment variables to resolve their own
     * properties: such a read is the framework's, not a direct one (PLAN-v2 M5-5 design I4).
     */
    static boolean configuration(String className) {
        return className.startsWith("io.smallrye.config.")
                || className.startsWith("org.eclipse.microprofile.config.")
                || className.startsWith("io.quarkus.runtime.configuration.")
                || className.startsWith("org.springframework.core.env.")
                || className.startsWith("org.springframework.boot.env.")
                || className.startsWith("org.springframework.boot.context.config.")
                || className.equals("org.springframework.core.SpringProperties");
    }

    /** Whether {@code className} is the JDK's by its name. */
    static boolean jdk(String className) {
        return className.startsWith("java.")
                || className.startsWith("jdk.")
                || className.startsWith("sun.")
                || className.startsWith("com.sun.");
    }

    /**
     * Whether a frame is the JDK's: by its class's name, or by its module, a {@code java.} or {@code jdk.} module of
     * the boot layer, as {@code javax.xml.parsers} in {@code java.xml} or {@code org.w3c.dom}.
     */
    static boolean jdk(StackWalker.StackFrame frame) {
        if (jdk(frame.getClassName())) {
            return true;
        }
        Module module;
        try {
            module = frame.getDeclaringClass().getModule();
        } catch (UnsupportedOperationException ex) {
            // The plain walker, when the agent could not get one retaining classes: by name only.
            return false;
        }
        String name = module.getName();
        return module.isNamed()
                && name != null
                && (name.startsWith("java.") || name.startsWith("jdk."))
                && module.getLayer() == ModuleLayer.boot();
    }

    /**
     * Walks at most {@value #MAX_FRAMES} frames, skipping the agent's, for a file operation's or an environment read's
     * summary: a JDK logging or class-loading frame before any frame outside the JDK gives that context and stops the
     * walk; otherwise the first frame outside the JDK and the first in the claimed packages, interned, packed as {@link
     * #R_FRAMES}, and the context {@link #CONTEXT_JDK_ONLY} when no frame is outside the JDK. For an environment read,
     * the immediate caller, past {@code System} and the {@code Boolean}, {@code Integer}, and {@code Long} lookups,
     * decides first: a JDK class's read gives context -1, not recorded.
     */
    static final class ContextSummary implements Function<Stream<StackWalker.StackFrame>, long[]> {

        private final Claim claim;
        private final boolean environment;

        ContextSummary(Claim claim, boolean environment) {
            this.claim = claim;
            this.environment = environment;
        }

        @Override
        public long[] apply(Stream<StackWalker.StackFrame> frames) {
            Iterator<StackWalker.StackFrame> iterator = frames.iterator();
            int outside = 0;
            int application = 0;
            boolean immediate = environment;
            for (int i = 0; i < MAX_FRAMES && iterator.hasNext() && application == 0; i++) {
                StackWalker.StackFrame frame = iterator.next();
                String className = frame.getClassName();
                if (className.startsWith("io.github.jdubois.bootui.agent.")) {
                    continue;
                }
                if (className.indexOf("$$Lambda") >= 0) {
                    // A lambda or method reference's hidden class, shown by the class walker: its outer class, the
                    // JDK's or not, decides whether an environment read is direct; it is never a summary frame.
                    if (immediate) {
                        immediate = false;
                        if (jdk(className)) {
                            return new long[] {0L, -1L};
                        }
                    }
                    continue;
                }
                if (immediate) {
                    if (className.equals("java.lang.System")
                            || className.equals("java.lang.Boolean")
                            || className.equals("java.lang.Integer")
                            || className.equals("java.lang.Long")
                            || transparent(className)) {
                        continue;
                    }
                    immediate = false;
                    if (jdk(frame)) {
                        return new long[] {0L, -1L};
                    }
                    if (configuration(className)) {
                        return new long[] {0L, INDIRECT_FRAMEWORK};
                    }
                }
                if (jdk(frame)) {
                    if (outside == 0) {
                        if (className.startsWith("jdk.internal.platform.") || className.startsWith("sun.net.dns.")) {
                            // The JDK's own container and resolver reads, as a metrics scrape's processor count.
                            return new long[] {0L, CONTEXT_JDK_ONLY};
                        }
                        if (className.startsWith("java.util.logging.")) {
                            return new long[] {0L, CONTEXT_JDK_LOGGING};
                        }
                        if (classLoading(className)) {
                            return new long[] {0L, CONTEXT_CLASS_LOADING};
                        }
                    }
                    continue;
                }
                if (outside == 0) {
                    outside = intern(className + "#" + frame.getMethodName());
                }
                if (claim != null && ThreadPropagation.inPackages(className, claim)) {
                    application = intern(className + "#" + frame.getMethodName());
                }
            }
            long packed = ((long) outside << 32) | (application & 0xFFFFFFFFL);
            return new long[] {packed, outside == 0 ? CONTEXT_JDK_ONLY : CONTEXT_NONE};
        }
    }

    /**
     * The generation's cache of frame summaries by {@code (hook, target, method)}: {@value #SIZE} open-addressed entries,
     * at most {@value #PROBES} probes, lock-free, as the bridge takes no monitor. A writer claims a free slot by
     * compare-and-set to {@value #RESERVED}, writes the summary, then publishes the key; a reader skips a reserved slot.
     * A new generation replaces the whole table; full, it stores nothing more.
     */
    static final class Sightings {

        static final int SIZE = 4_096;
        static final int PROBES = 8;
        static final long RESERVED = -1L;

        static final int FOUND = 1;
        static final int MISSING = 0;
        static final int FULL = -1;

        private final AtomicReference<Table> table = new AtomicReference<Table>();

        static final class Table {
            final long generation;
            final AtomicLongArray keys = new AtomicLongArray(SIZE);
            final AtomicLongArray frames = new AtomicLongArray(SIZE);
            final AtomicIntegerArray contexts = new AtomicIntegerArray(SIZE);

            Table(long generation) {
                this.generation = generation;
            }
        }

        private static int hash(long key) {
            long mixed = key * 0x9E3779B97F4A7C15L;
            return (int) (mixed ^ (mixed >>> 32));
        }

        private Table of(long generation, boolean create) {
            while (true) {
                Table current = table.get();
                if (current != null && current.generation == generation) {
                    return current;
                }
                if (!create) {
                    return null;
                }
                Table fresh = new Table(generation);
                if (table.compareAndSet(current, fresh)) {
                    return fresh;
                }
            }
        }

        /** {@link #FOUND} with {@code out} filled, {@link #MISSING} with room to put, or {@link #FULL}. */
        int find(long generation, long key, long[] out) {
            Table current = of(generation, false);
            if (current == null) {
                return MISSING;
            }
            int hash = hash(key);
            for (int probe = 0; probe < PROBES; probe++) {
                int slot = (hash + probe) & (SIZE - 1);
                long known = current.keys.get(slot);
                if (known == key) {
                    out[0] = current.frames.get(slot);
                    out[1] = current.contexts.get(slot);
                    return FOUND;
                }
                if (known == 0L) {
                    return MISSING;
                }
            }
            return FULL;
        }

        void put(long generation, long key, long frame, int context) {
            Table current = of(generation, true);
            int hash = hash(key);
            for (int probe = 0; probe < PROBES; probe++) {
                int slot = (hash + probe) & (SIZE - 1);
                long known = current.keys.get(slot);
                if (known == key) {
                    return;
                }
                if (known == 0L && current.keys.compareAndSet(slot, 0L, RESERVED)) {
                    current.frames.set(slot, frame);
                    current.contexts.set(slot, context);
                    current.keys.set(slot, key);
                    return;
                }
            }
        }

        void clear() {
            table.set(null);
        }
    }

    /**
     * A thread's small cache for one owner: the environment names it recorded ({@code named}), or the frame summaries of
     * its file operations no method stamps. {@value #SIZE} entries, at most {@value #PROBES} probes, then the home entry
     * is overwritten, so a lookup is bounded. Valid for the owner it was filled for, and, for a thread no slot owns,
     * {@value #UNOWNED_SEEN_MILLIS} ms; any other owner clears it.
     */
    static final class Seen {

        static final int SIZE = 32;
        static final int PROBES = 4;

        final boolean named;
        final long[] keys = new long[SIZE];
        final String[] names;
        final long[] frames;
        final int[] contexts;
        long generation = Long.MIN_VALUE;
        long request;
        long execution;
        boolean slot;
        long sinceMillis;

        Seen(boolean named) {
            this.named = named;
            this.names = named ? new String[SIZE] : null;
            this.frames = named ? null : new long[SIZE];
            this.contexts = named ? null : new int[SIZE];
        }

        /** A key, never 0. */
        static long key(int hook, int kind, int value) {
            return ((long) (hook + 1) << 48) | ((long) (kind & 0xFFFF) << 32) | (value & 0xFFFFFFFFL);
        }

        /** Makes the cache the given owner's, clearing it when it was another's or an unowned one's that expired. */
        void own(long ownerGeneration, long ownerRequest, long ownerExecution, boolean ownerSlot, long nowMillis) {
            boolean same = generation == ownerGeneration
                    && slot == ownerSlot
                    && request == ownerRequest
                    && execution == ownerExecution
                    && (ownerSlot || nowMillis - sinceMillis < UNOWNED_SEEN_MILLIS);
            if (same) {
                return;
            }
            java.util.Arrays.fill(keys, 0L);
            if (names != null) {
                java.util.Arrays.fill(names, null);
            }
            generation = ownerGeneration;
            request = ownerRequest;
            execution = ownerExecution;
            slot = ownerSlot;
            sinceMillis = nowMillis;
        }

        int find(long key, String name) {
            int home = (int) (key ^ (key >>> 32)) & (SIZE - 1);
            for (int probe = 0; probe < PROBES; probe++) {
                int index = (home + probe) & (SIZE - 1);
                long known = keys[index];
                if (known == 0L) {
                    return -1;
                }
                if (known == key && (!named || equal(names[index], name))) {
                    return index;
                }
            }
            return -1;
        }

        void put(long key, String name, long frame, int context) {
            int home = (int) (key ^ (key >>> 32)) & (SIZE - 1);
            int index = home;
            for (int probe = 0; probe < PROBES; probe++) {
                int candidate = (home + probe) & (SIZE - 1);
                if (keys[candidate] == 0L) {
                    index = candidate;
                    break;
                }
            }
            keys[index] = key;
            if (named) {
                names[index] = name;
            } else {
                frames[index] = frame;
                contexts[index] = context;
            }
        }

        private static boolean equal(String a, String b) {
            return a == null ? b == null : a.equals(b);
        }
    }

    /** The id of {@code text} in the generation's table, within {@code sensor}'s quota; 0 past it, counted. */
    static int internQuota(String text, int sensor) {
        AgentRing.Interns interns = INTERNS.get();
        if (text == null || interns == null) {
            return 0;
        }
        Integer known = interns.ids.get(text);
        if (known != null) {
            return known.intValue();
        }
        AtomicInteger count = INTERNED[sensor];
        int quota = sensor == SENSOR_ENVIRONMENT ? ENVIRONMENT_INTERN_QUOTA : FILES_INTERN_QUOTA;
        if (count.get() >= quota) {
            QUOTA_EXCEEDED[sensor].increment();
            return 0;
        }
        int id = interns.intern(text);
        if (id != 0) {
            count.incrementAndGet();
        }
        return id;
    }

    /**
     * The directories path patterns are relative to: the working directory ({@code .}), the temporary directory
     * ({@code $TMPDIR}), the user's home ({@code ~}), each as the JVM names it and canonical, without a trailing
     * separator; Java's home and the class path's directories, whose files are counted in buckets; and the default
     * file system, the only one whose paths are turned into text.
     */
    static final class Places {

        static final Places NONE = new Places(
                null, null, new String[0], new String[0], new String[0], new String[0], new String[0], false);

        final java.nio.file.FileSystem fileSystem;
        final String workingDirectory;
        final String[] workingDirectories;
        final String[] temporaryDirectories;
        final String[] homes;
        final String[] javaHomes;
        final String[] classPathDirectories;
        final boolean windows;

        Places(
                java.nio.file.FileSystem fileSystem,
                String workingDirectory,
                String[] workingDirectories,
                String[] temporaryDirectories,
                String[] homes,
                String[] javaHomes,
                String[] classPathDirectories,
                boolean windows) {
            this.fileSystem = fileSystem;
            this.workingDirectory = workingDirectory;
            this.workingDirectories = workingDirectories;
            this.temporaryDirectories = temporaryDirectories;
            this.homes = homes;
            this.javaHomes = javaHomes;
            this.classPathDirectories = classPathDirectories;
            this.windows = windows;
        }

        /** Read from the system properties and the file system: only on the agent's own thread, never in a hook. */
        static Places read() {
            boolean windows = java.io.File.separatorChar == '\\';
            String cwd = System.getProperty("user.dir");
            String[] working = forms(cwd, windows);
            String workingDirectory = working.length == 0 ? null : working[0];
            List<String> classPath = new java.util.ArrayList<String>();
            String path = System.getProperty("java.class.path");
            if (path != null) {
                for (String entry : path.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                    if (entry.isEmpty() || !new java.io.File(entry).isDirectory()) {
                        continue;
                    }
                    for (String form : forms(new java.io.File(entry).getAbsolutePath(), windows)) {
                        // Never a directory holding the working directory: its files would all become buckets.
                        if (workingDirectory != null && (under(workingDirectory, form, windows))) {
                            continue;
                        }
                        classPath.add(form);
                    }
                }
            }
            return new Places(
                    java.nio.file.FileSystems.getDefault(),
                    workingDirectory,
                    working,
                    forms(System.getProperty("java.io.tmpdir"), windows),
                    forms(System.getProperty("user.home"), windows),
                    forms(System.getProperty("java.home"), windows),
                    classPath.toArray(new String[0]),
                    windows);
        }

        /** {@code directory} as given and canonical, '/'-separated, without a trailing separator; none for a root. */
        static String[] forms(String directory, boolean windows) {
            if (directory == null || directory.isEmpty()) {
                return new String[0];
            }
            List<String> forms = new java.util.ArrayList<String>(2);
            add(forms, directory);
            try {
                add(forms, new java.io.File(directory).getCanonicalPath());
            } catch (Throwable ex) {
                // The literal form only.
            }
            return forms.toArray(new String[0]);
        }

        private static void add(List<String> forms, String directory) {
            String form = clean(directory.replace('\\', '/'));
            while (form.length() > 1 && form.endsWith("/")) {
                form = form.substring(0, form.length() - 1);
            }
            boolean root = form.equals("/") || (form.length() <= 3 && form.length() >= 2 && form.charAt(1) == ':');
            if (!root && !forms.contains(form)) {
                forms.add(form);
            }
        }
    }

    /** The directories path patterns are relative to, now: tests set them. */
    static void places(Places where) {
        places = where == null ? Places.NONE : where;
    }

    /** A bucket by the file's name alone, before any allocation: a class file or an archive; -1 for none. */
    static int bucketOfName(String text) {
        int length = text.length();
        if (endsWith(text, length, ".class")) {
            return BUCKET_CLASS_FILES;
        }
        if (endsWith(text, length, ".jar") || endsWith(text, length, ".war") || endsWith(text, length, ".jmod")) {
            return BUCKET_ARCHIVES;
        }
        return -1;
    }

    private static boolean endsWith(String text, int length, String suffix) {
        int from = length - suffix.length();
        return from >= 0 && text.regionMatches(true, from, suffix, 0, suffix.length());
    }

    /** A bucket by the file's absolute path: under Java's home, or a class path directory; -1 for none. */
    static int bucketOfPath(String absolute, Places where) {
        for (String home : where.javaHomes) {
            if (under(absolute, home, where.windows)) {
                return BUCKET_JAVA_HOME;
            }
        }
        for (String directory : where.classPathDirectories) {
            if (under(absolute, directory, where.windows)) {
                return BUCKET_CLASS_PATH_DIRECTORIES;
            }
        }
        return -1;
    }

    /** Whether {@code path} is {@code directory} or inside it, case-insensitively on Windows. */
    static boolean under(String path, String directory, boolean windows) {
        int length = directory.length();
        return path.length() >= length
                && path.regionMatches(windows, 0, directory, 0, length)
                && (path.length() == length || path.charAt(length) == '/');
    }

    /**
     * {@code text}, a path as the application named it, made absolute against the working directory, '/'-separated,
     * with its {@code .} and {@code ..} segments and repeated separators collapsed, lexically: no file-system call.
     */
    static String absolute(String text, Places where) {
        String path = text.indexOf('\\') >= 0 ? text.replace('\\', '/') : text;
        boolean absolute = path.startsWith("/")
                || (path.length() >= 2 && path.charAt(1) == ':' && Character.isLetter(path.charAt(0)));
        if (!absolute && where.workingDirectory != null) {
            path = where.workingDirectory + "/" + path;
        }
        return clean(path);
    }

    /** {@code path} with its {@code .} and {@code ..} segments and repeated separators collapsed. */
    static String clean(String path) {
        if (path.indexOf("/.") < 0 && path.indexOf("//") < 0 && !path.startsWith(".")) {
            return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        }
        String[] segments = path.split("/", -1);
        String[] kept = new String[segments.length];
        int size = 0;
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (i == 0) {
                kept[size++] = segment;
                continue;
            }
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (size > 1) {
                    size--;
                }
                continue;
            }
            kept[size++] = segment;
        }
        StringBuilder cleaned = new StringBuilder(path.length());
        for (int i = 0; i < size; i++) {
            if (i > 0) {
                cleaned.append('/');
            }
            cleaned.append(kept[i]);
        }
        return cleaned.length() == 0 ? "/" : cleaned.toString();
    }

    /**
     * The pattern of an absolute path: the longest of the temporary directory ({@code $TMPDIR}), the working
     * directory ({@code .}), and the home ({@code ~}) replaced; another user's home collapsed to {@code /Users/*},
     * {@code /home/*}, or {@code C:/Users/*}; ids collapsed ({@link #collapse}); at most {@value #MAX_PATTERN}
     * characters. A raw path, and the user name in it, never reaches the intern table.
     */
    static String pattern(String absolute, Places where) {
        String prefix = null;
        int length = -1;
        for (String directory : where.temporaryDirectories) {
            if (directory.length() > length && under(absolute, directory, where.windows)) {
                prefix = "$TMPDIR";
                length = directory.length();
            }
        }
        for (String directory : where.workingDirectories) {
            if (directory.length() > length && under(absolute, directory, where.windows)) {
                prefix = ".";
                length = directory.length();
            }
        }
        for (String directory : where.homes) {
            if (directory.length() > length && under(absolute, directory, where.windows)) {
                prefix = "~";
                length = directory.length();
            }
        }
        String pattern;
        if (prefix != null) {
            pattern = prefix + collapse(absolute.substring(length));
        } else {
            int user = otherHome(absolute);
            pattern = user > 0
                    ? absolute.substring(0, absolute.lastIndexOf('/', user - 1) + 1) + "*"
                            + collapse(absolute.substring(user))
                    : collapse(absolute);
        }
        return pattern.length() > MAX_PATTERN ? pattern.substring(0, MAX_PATTERN) : pattern;
    }

    /** The end of the user name in another user's home directory, {@code /Users/x}, {@code /home/x}, or {@code C:/Users/x}; -1. */
    static int otherHome(String absolute) {
        int start;
        if (absolute.startsWith("/Users/")) {
            start = 7;
        } else if (absolute.startsWith("/home/")) {
            start = 6;
        } else if (absolute.length() > 9
                && absolute.charAt(1) == ':'
                && absolute.regionMatches(true, 2, "/Users/", 0, 7)) {
            start = 9;
        } else {
            return -1;
        }
        int end = absolute.indexOf('/', start);
        end = end < 0 ? absolute.length() : end;
        return end > start ? end : -1;
    }

    /**
     * Ids collapsed in {@code text}: a JWT-like segment ({@code eyJ…} with a dot) becomes {@code {token}}, a UUID
     * {@code {uuid}}, an alphanumeric run of 20 or more characters holding letters and digits {@code {id}}, a run of 8
     * or more hexadecimal characters holding a digit {@code {hex}}, and any other run of digits {@code {n}}.
     */
    static String collapse(String text) {
        int length = text.length();
        boolean digit = false;
        for (int i = 0; i < length && !digit; i++) {
            char c = text.charAt(i);
            digit = (c >= '0' && c <= '9') || c == '.';
        }
        if (!digit) {
            return text;
        }
        StringBuilder out = new StringBuilder(length);
        int i = 0;
        while (i < length) {
            char c = text.charAt(i);
            if (c == '/') {
                out.append(c);
                i++;
                continue;
            }
            int segmentEnd = text.indexOf('/', i);
            segmentEnd = segmentEnd < 0 ? length : segmentEnd;
            if (text.startsWith("eyJ", i) && text.indexOf('.', i) > 0 && text.indexOf('.', i) < segmentEnd) {
                out.append("{token}");
                i = segmentEnd;
                continue;
            }
            if (alphanumeric(c)) {
                if ((i == 0 || !alphanumeric(text.charAt(i - 1))) && uuidAt(text, i)) {
                    out.append("{uuid}");
                    i += 36;
                    continue;
                }
                int end = i;
                boolean letters = false;
                boolean digits = false;
                boolean hex = true;
                while (end < length && alphanumeric(text.charAt(end))) {
                    char d = text.charAt(end);
                    if (d >= '0' && d <= '9') {
                        digits = true;
                    } else {
                        letters = true;
                        hex &= (d >= 'a' && d <= 'f') || (d >= 'A' && d <= 'F');
                    }
                    end++;
                }
                int run = end - i;
                if (digits && !letters) {
                    out.append("{n}");
                } else if (digits && run >= 20) {
                    out.append("{id}");
                } else if (digits && hex && run >= 8) {
                    out.append("{hex}");
                } else if (digits) {
                    for (int j = i; j < end; j++) {
                        char d = text.charAt(j);
                        if (d >= '0' && d <= '9') {
                            if (j == i || !(text.charAt(j - 1) >= '0' && text.charAt(j - 1) <= '9')) {
                                out.append("{n}");
                            }
                        } else {
                            out.append(d);
                        }
                    }
                } else {
                    out.append(text, i, end);
                }
                i = end;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static boolean alphanumeric(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    /** Whether a UUID, 8-4-4-4-12 hexadecimal characters, starts at {@code from} and ends at a boundary. */
    private static boolean uuidAt(String text, int from) {
        if (text.length() - from < 36) {
            return false;
        }
        for (int i = 0; i < 36; i++) {
            char c = text.charAt(from + i);
            boolean dash = i == 8 || i == 13 || i == 18 || i == 23;
            if (dash ? c != '-' : !((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return from + 36 == text.length() || !alphanumeric(text.charAt(from + 36));
    }

    // ---- aggregation and the ring -----------------------------------------------------------------------------------

    /**
     * Records one occurrence of a hot hook: aggregated in the thread's table while its owner comes from the slot, else
     * published at once; {@code exitStatus} carries a file operation's or an environment read's JDK context, which the
     * table keys too. {@code processes} publishes every start at once, never through here.
     */
    static void record(
            CodePaths.Frame frame,
            Owner owner,
            int sensor,
            int kind,
            int target,
            int outcome,
            int exitStatus,
            long stamp,
            long frames,
            long nanos) {
        long now = System.currentTimeMillis();
        if (owner.slot && frame != null) {
            Table table = frame.sideEffects;
            if (table == null) {
                table = new Table();
                frame.sideEffects = table;
            }
            table.add(owner, sensor, kind, target, outcome, exitStatus, stamp, frames, nanos, now);
            return;
        }
        publishOne(owner, sensor, kind, target, outcome, exitStatus, stamp, frames, nanos, now);
    }

    /** Publishes one occurrence at once, first seen at {@code firstMillis}. */
    private static void publishOne(
            Owner owner,
            int sensor,
            int kind,
            int target,
            int outcome,
            int exitStatus,
            long stamp,
            long frames,
            long nanos,
            long firstMillis) {
        long now = System.currentTimeMillis();
        long[] values = new long[RECORD];
        values[R_SENSOR] = sensor;
        values[R_KIND] = kind;
        values[R_GENERATION] = owner.generation;
        values[R_FIRST_MILLIS] = firstMillis;
        values[R_LAST_MILLIS] = now;
        values[R_REQUEST] = owner.request;
        values[R_EXECUTION] = owner.execution;
        values[R_STAMP] = stamp;
        values[R_TARGET] = target;
        values[R_FLAGS] = flags(outcome, owner, exitStatus);
        values[R_COUNT] = 1L;
        values[R_NANOS] = nanos;
        values[R_MAX_NANOS] = nanos;
        values[R_FRAMES] = frames;
        publish(sensor, values);
    }

    static long flags(int outcome, Owner owner, int exitStatus) {
        return (outcome & 0xFFL)
                | ((long) (owner.threadKind & 0xF) << 8)
                | ((long) (owner.executionKind & 0xF) << 12)
                | ((long) (owner.threadName & 0xFFFF) << 16)
                | ((long) exitStatus << 32);
    }

    /** Publishes one record, or drops it, counted per sensor, when the ring is full or not allocated. */
    static boolean publish(int sensor, long[] values) {
        int index = sensor > 0 && sensor < SENSOR_NAMES.length ? sensor : 0;
        AgentRing.Ring ring = RING.get();
        if (ring == null) {
            DROPPED[index].increment();
            return false;
        }
        long position = ring.claim();
        if (position < 0) {
            DROPPED[index].increment();
            return false;
        }
        boolean written = ring.write(position, values);
        if (written) {
            PUBLISHED[index].increment();
        }
        return written;
    }

    /** Flushes the thread's table, if any, to the ring. */
    private static void flush(CodePaths.Frame frame) {
        Table table = frame.sideEffects;
        if (table != null && table.size > 0) {
            table.flush();
        }
    }

    /** The calling thread's table, flushed now: tests, and the agent's self-test. Never throws. */
    public static void flushThread() {
        try {
            CodePaths.Frame frame = CodePaths.FRAME.get();
            if (frame != null) {
                flush(frame);
            }
        } catch (Throwable ex) {
            failed(ex);
        }
    }

    /** One record's owner and thread. */
    static final class Owner {
        long generation;
        long request;
        long execution;
        int executionKind;
        int threadKind;
        int threadName;
        boolean slot;
    }

    /**
     * A thread's aggregation table: up to {@value #TABLE} entries of one owner, keyed by {@code (sensor, kind, target,
     * outcome, extra, stamp, frames)}, {@code extra} being the JDK context of {@link #R_FLAGS} bits 32 onwards, with
     * their count, total and longest durations, and first and last times.
     */
    static final class Table {

        long generation;
        long request;
        long execution;
        int executionKind;
        int threadKind;
        int size;
        long oldestNanos;

        final int[] sensor = new int[TABLE];
        final int[] kind = new int[TABLE];
        final int[] target = new int[TABLE];
        final int[] outcome = new int[TABLE];
        final int[] extra = new int[TABLE];
        final long[] stamp = new long[TABLE];
        final long[] frames = new long[TABLE];
        final long[] count = new long[TABLE];
        final long[] nanos = new long[TABLE];
        final long[] maxNanos = new long[TABLE];
        final long[] firstMillis = new long[TABLE];
        final long[] lastMillis = new long[TABLE];
        final long[] scratch = new long[RECORD];

        void add(
                Owner owner,
                int entrySensor,
                int entryKind,
                int entryTarget,
                int entryOutcome,
                int entryExtra,
                long entryStamp,
                long entryFrames,
                long entryNanos,
                long now) {
            long nowNanos = System.nanoTime();
            if (size > 0
                    && (generation != owner.generation
                            || request != owner.request
                            || execution != owner.execution
                            || nowNanos - oldestNanos > FLUSH_NANOS)) {
                if (generation == owner.generation) {
                    flush();
                } else {
                    // A previous run's entries: its drainer is gone, so they would only be dropped as stale.
                    size = 0;
                }
            }
            if (size == 0) {
                generation = owner.generation;
                request = owner.request;
                execution = owner.execution;
                executionKind = owner.executionKind;
                threadKind = owner.threadKind;
                oldestNanos = nowNanos;
            }
            for (int i = 0; i < size; i++) {
                if (sensor[i] == entrySensor
                        && kind[i] == entryKind
                        && target[i] == entryTarget
                        && outcome[i] == entryOutcome
                        && extra[i] == entryExtra
                        && stamp[i] == entryStamp
                        && frames[i] == entryFrames) {
                    count[i]++;
                    nanos[i] += entryNanos;
                    maxNanos[i] = Math.max(maxNanos[i], entryNanos);
                    lastMillis[i] = now;
                    return;
                }
            }
            if (size == TABLE) {
                flush();
                generation = owner.generation;
                request = owner.request;
                execution = owner.execution;
                executionKind = owner.executionKind;
                threadKind = owner.threadKind;
                oldestNanos = nowNanos;
            }
            int i = size++;
            sensor[i] = entrySensor;
            kind[i] = entryKind;
            target[i] = entryTarget;
            outcome[i] = entryOutcome;
            extra[i] = entryExtra;
            stamp[i] = entryStamp;
            frames[i] = entryFrames;
            count[i] = 1L;
            nanos[i] = entryNanos;
            maxNanos[i] = entryNanos;
            firstMillis[i] = now;
            lastMillis[i] = now;
        }

        void flush() {
            TABLE_FLUSHES.increment();
            int flushed = size;
            size = 0;
            for (int i = 0; i < flushed; i++) {
                long[] values = scratch;
                values[R_SENSOR] = sensor[i];
                values[R_KIND] = kind[i];
                values[R_GENERATION] = generation;
                values[R_FIRST_MILLIS] = firstMillis[i];
                values[R_LAST_MILLIS] = lastMillis[i];
                values[R_REQUEST] = request;
                values[R_EXECUTION] = execution;
                values[R_STAMP] = stamp[i];
                values[R_TARGET] = target[i];
                values[R_FLAGS] = (outcome[i] & 0xFFL)
                        | ((long) (threadKind & 0xF) << 8)
                        | ((long) (executionKind & 0xF) << 12)
                        | ((long) extra[i] << 32);
                values[R_COUNT] = count[i];
                values[R_NANOS] = nanos[i];
                values[R_MAX_NANOS] = maxNanos[i];
                values[R_FRAMES] = frames[i];
                publish(sensor[i], values);
            }
        }
    }

    // ---- process exits
    // -----------------------------------------------------------------------------------------------

    /** A watched process's exit: published as one record carrying its start's owner, target, stamp, and frames. */
    static final class ExitCallback implements BiConsumer<Object, Throwable> {

        private final long generation;
        private final long request;
        private final long execution;
        private final int executionKind;
        private final int threadKind;
        private final int threadName;
        private final int target;
        private final long stamp;
        private final long frames;
        private final long startNanos;
        private final long startMillis;

        ExitCallback(Owner owner, int target, long stamp, long frames, long startNanos, long startMillis) {
            this.generation = owner.generation;
            this.request = owner.request;
            this.execution = owner.execution;
            this.executionKind = owner.executionKind;
            this.threadKind = owner.threadKind;
            this.threadName = owner.threadName;
            this.target = target;
            this.stamp = stamp;
            this.frames = frames;
            this.startNanos = startNanos;
            this.startMillis = startMillis;
        }

        @Override
        public void accept(Object process, Throwable thrown) {
            try {
                PENDING_EXITS.decrementAndGet();
                long nanos = System.nanoTime() - startNanos;
                int status = 0;
                int outcome = OUTCOME_ERROR;
                if (process instanceof Process) {
                    try {
                        status = ((Process) process).exitValue();
                        outcome = OUTCOME_EXITED;
                    } catch (Throwable notExited) {
                        // Left as an error: its exit status is unknown.
                    }
                }
                long now = System.currentTimeMillis();
                Owner owner = new Owner();
                owner.generation = generation;
                owner.request = request;
                owner.execution = execution;
                owner.executionKind = executionKind;
                owner.threadKind = threadKind;
                owner.threadName = threadName;
                long[] values = new long[RECORD];
                values[R_SENSOR] = SENSOR_PROCESSES;
                values[R_KIND] = KIND_PROCESS_EXIT;
                values[R_GENERATION] = generation;
                // The start's time: the engine attributes an exit as it attributed its start.
                values[R_FIRST_MILLIS] = startMillis;
                values[R_LAST_MILLIS] = now;
                values[R_REQUEST] = request;
                values[R_EXECUTION] = execution;
                values[R_STAMP] = stamp;
                values[R_TARGET] = target;
                values[R_FLAGS] = flags(outcome, owner, status);
                values[R_COUNT] = 1L;
                values[R_NANOS] = nanos;
                values[R_MAX_NANOS] = nanos;
                values[R_FRAMES] = frames;
                publish(SENSOR_PROCESSES, values);
                EXITS_RECORDED.increment();
            } catch (Throwable ex) {
                failed(ex);
            }
        }
    }

    /** The executor process exits complete on: a bounded queue its one worker thread, which the agent starts, runs. */
    static final class ExitQueue implements Executor {

        final ArrayBlockingQueue<Runnable> queue = new ArrayBlockingQueue<Runnable>(EXIT_QUEUE);

        @Override
        public void execute(Runnable task) {
            if (task == null || !queue.offer(task)) {
                EXITS_DROPPED.increment();
                PENDING_EXITS.decrementAndGet();
            }
        }
    }

    /** The worker running {@link ExitQueue}: marks its thread as the agent's, then runs exits until interrupted. */
    static final class ExitWorker implements Runnable {

        @Override
        public void run() {
            Reentrancy.markBootUiThread();
            exitWorkerRunning = true;
            try {
                while (true) {
                    Runnable task;
                    try {
                        task = EXITS.queue.take();
                    } catch (InterruptedException ex) {
                        // Nothing legitimate stops it: it is handed out once per JVM.
                        continue;
                    }
                    try {
                        task.run();
                    } catch (Throwable ex) {
                        failed(ex);
                    }
                }
            } finally {
                exitWorkerRunning = false;
            }
        }
    }

    /**
     * The exits' worker, for the agent to run on a thread of its own: handed out once per JVM, {@code null} after. The
     * agent creates its thread without the claiming application's context class loader, inherited thread-locals, or
     * access-control context.
     */
    public static Runnable exitWorker() {
        return EXIT_WORKER_HANDED_OUT.compareAndSet(false, true) ? new ExitWorker() : null;
    }

    // ---- the agent's threads and transformations ------------------------------------------------------------------

    /** Marks the calling thread, for its life, as one of BootUI's or the agent's own: never recorded. Never throws. */
    public static void markBootUiThread() {
        try {
            Reentrancy.markBootUiThread();
        } catch (Throwable ex) {
            AgentBridge.error(ex);
        }
    }

    /**
     * The agent starts (true) or ends (false) transforming a class on the calling thread: what it does meanwhile, such
     * as reading class files, is never recorded. Never throws.
     */
    public static void agentWork(boolean on) {
        try {
            Reentrancy.agentWork(on);
        } catch (Throwable ex) {
            AgentBridge.error(ex);
        }
    }

    // ---- strings and the drain ----------------------------------------------------------------------------------

    /** The id of {@code text} in the current generation's table, from 1; 0 for {@code null} or a full table. */
    static int intern(String text) {
        AgentRing.Interns interns = INTERNS.get();
        if (text == null || interns == null) {
            return 0;
        }
        return interns.intern(text);
    }

    /**
     * The interned strings of {@code generation} with ids {@code from} onwards, index 0 being id {@code from}; {@code
     * null} when the current table belongs to another generation. Never throws.
     */
    public static String[] interned(long requested, int from) {
        try {
            AgentRing.Interns interns = INTERNS.get();
            if (interns == null || interns.generation != requested) {
                return null;
            }
            return interns.copy(Math.max(1, from));
        } catch (Throwable ex) {
            AgentBridge.error(ex);
            return null;
        }
    }

    /**
     * Drains the published records in order, at most one ring's worth, into {@code sink}, which receives one reused
     * {@code long[]} of {@value #RECORD} longs per record and must copy what it keeps. Only the current claim's token
     * drains, one caller at a time; the drain stops at the first record of a newer claim generation. Never throws.
     */
    public static int drain(long token, Consumer<long[]> sink) {
        try {
            AgentRing.Ring ring = RING.get();
            if (ring == null || sink == null) {
                return 0;
            }
            Claim claim = AgentBridge.current();
            if (claim == null || claim.token != token) {
                STALE_DRAINS.increment();
                return 0;
            }
            if (!ring.draining.compareAndSet(false, true)) {
                BUSY_DRAINS.increment();
                return 0;
            }
            try {
                return ring.drain(claim.generation, sink);
            } finally {
                ring.draining.set(false);
            }
        } catch (Throwable ex) {
            AgentBridge.error(ex);
            return 0;
        }
    }

    // ---- lifecycle -----------------------------------------------------------------------------------------------

    /** Whether {@code claim} asks for a side-effect sensor. */
    static boolean claims(Claim claim) {
        return claimedMask(claim) != 0;
    }

    private static int claimedMask(Claim claim) {
        int bits = 0;
        for (int i = 1; i < SENSOR_NAMES.length; i++) {
            if (claim.hasSensor(SENSOR_NAMES[i])) {
                bits |= 1 << i;
            }
        }
        return bits;
    }

    /**
     * A claim asking for a side-effect sensor was recorded: the ring is allocated once, and a new intern table starts
     * for its generation. Never throws.
     */
    static void claimed(Claim claim) {
        try {
            claimedOnce = true;
            if (RING.get() == null) {
                RING.compareAndSet(null, new AgentRing.Ring(DEFAULT_CAPACITY, RECORD, COUNTERS));
            }
            while (true) {
                AgentRing.Interns current = INTERNS.get();
                if (current != null && current.generation >= claim.generation) {
                    break;
                }
                if (INTERNS.compareAndSet(
                        current,
                        new AgentRing.Interns(claim.generation, DEFAULT_INTERNS, COUNTERS.internOverflow, true))) {
                    for (AtomicInteger interned : INTERNED) {
                        interned.set(0);
                    }
                    // A new run: its buckets count from its claim.
                    resetAll(BUCKET_COUNTS);
                    break;
                }
            }
            if (claim.generation > generation) {
                generation = claim.generation;
            }
        } catch (Throwable ex) {
            AgentBridge.error(ex);
        }
    }

    /** Recomputes which sensors record, after any transition of the claim or a sensor. Never throws. */
    static void refresh() {
        try {
            Claim claim = AgentBridge.current();
            mask = claim != null && claim.armed && claim.generation == generation && !off
                    ? claimedMask(claim) & enabled
                    : 0;
            updateGate();
        } catch (Throwable ex) {
            AgentBridge.error(ex);
        }
    }

    /**
     * Recomputes {@link #gate} after {@link #mask} or the self-test's thread changed, without a monitor, which the
     * bridge never takes: each writer writes the gate, then checks it against the state again and rewrites it when
     * another writer changed the state meanwhile, so once every writer returns the gate matches the state.
     */
    private static void updateGate() {
        int value;
        do {
            value = mask | (selfTestThread != null ? -1 : 0);
            gate = value;
        } while ((mask | (selfTestThread != null ? -1 : 0)) != value);
    }

    /** The agent enables the sensors of {@code bits} once their hooks passed their self-test. */
    public static void enable(int bits) {
        enabled |= bits;
        for (int i = 1; i < SENSOR_NAMES.length; i++) {
            if ((bits & (1 << i)) != 0) {
                DISABLED_REASONS[i] = null;
            }
        }
        refresh();
    }

    /** The agent disables the sensors of {@code bits}: their self-test failed, or their transformer was removed. */
    public static void disable(int bits, String reason) {
        enabled &= ~bits;
        for (int i = 1; i < SENSOR_NAMES.length; i++) {
            if ((bits & (1 << i)) != 0) {
                DISABLED_REASONS[i] = reason;
            }
        }
        refresh();
        if (reason != null) {
            AgentBridge.message("side-effect sensors disabled: " + reason);
        }
    }

    /** The mask bit of sensor {@code id}, 0 when no side-effect sensor has that id. */
    public static int bit(String id) {
        for (int i = 1; i < SENSOR_NAMES.length; i++) {
            if (SENSOR_NAMES[i].equals(id)) {
                return 1 << i;
            }
        }
        return 0;
    }

    /** Loads and links what the advice and recording use, before the transformer instruments anything. */
    public static void warm() {
        try {
            commandName(new ProcessBuilder("warm").command(), false);
            ownerOf(null, new Owner());
            agentWork(true);
            agentWork(false);
            Table table = new Table();
            Owner owner = new Owner();
            table.add(owner, 0, 0, 0, 0, 0, 0L, 0L, 0L, 0L);
            table.size = 0;
            flags(0, owner, 0);
            jdkOrAgent("warm");
            WALKER.walk(new FrameSummary(null)).getClass();
            new ExitCallback(owner, 0, 0L, 0L, 0L, 0L).getClass();
            Reentrancy.sideEffectsSkipped();
            new AgentRing.Interns(-1L, 1, new LongAdder()).intern("warm");
            CompletableFuture.completedFuture(null).getClass();
            status(PROCESSES);
            // The files and environment sensors: their places, read here, on the agent's own thread, never in a hook,
            // and every class their hooks link, so a hook inside class loading loads nothing.
            places = Places.read();
            try {
                classWalker = StackWalker.getInstance(java.util.EnumSet.of(
                        StackWalker.Option.RETAIN_CLASS_REFERENCE, StackWalker.Option.SHOW_HIDDEN_FRAMES));
            } catch (Throwable ex) {
                // Class names only.
            }
            Places where = places;
            String probe = absolute("warm/./1/../report-2026.csv", where);
            pattern(probe, where);
            collapse("/a/0123456789abcdef/550e8400-e29b-41d4-a716-446655440000/eyJx.y/abcdefghij0123456789");
            bucketOfName(probe);
            bucketOfPath(probe, where);
            otherHome("/home/warm/x");
            Seen seen = new Seen(true);
            seen.own(0L, 0L, 0L, false, 0L);
            seen.put(Seen.key(0, 0, 1), "warm", 0L, 0);
            seen.find(Seen.key(0, 0, 1), "warm");
            Seen sightings = new Seen(false);
            sightings.put(Seen.key(0, 0, 1), null, 0L, 0);
            sightings.find(Seen.key(0, 0, 1), null);
            long[] found = new long[2];
            new Sightings().find(0L, 1L, found);
            walk(null, true);
            walk(null, false);
            transparent("warm");
            configuration("warm");
            classLoading("warm");
            jdk("warm");
            StandardOpenOption.WRITE.getClass();
            java.util.Collections.emptySet().contains(StandardOpenOption.APPEND);
            java.nio.file.FileSystems.getDefault()
                    .getPath("warm")
                    .getFileSystem()
                    .getClass();
            new java.io.IOException("warm").getClass();
            status(FILES);
            status(ENVIRONMENT);
        } catch (Throwable ex) {
            AgentBridge.error(ex);
        }
    }

    /** Starts the self-test on the calling thread: hooks it runs are counted per hook, and record nothing. */
    public static void beginSelfTest() {
        for (int i = 0; i < SELF_TEST_HITS.length; i++) {
            SELF_TEST_HITS[i].reset();
        }
        selfTestThread = Thread.currentThread();
        updateGate();
    }

    /** Ends the self-test: the hooks it ran, by hook id, with how often each fired. */
    public static Map<String, Object> endSelfTest() {
        selfTestThread = null;
        updateGate();
        Map<String, Object> hits = new LinkedHashMap<String, Object>();
        for (int i = 0; i < HOOKS.length; i++) {
            hits.put(HOOKS[i], Long.valueOf(SELF_TEST_HITS[i].sum()));
        }
        return hits;
    }

    // ---- errors and status ---------------------------------------------------------------------------------------

    private static void failed(Throwable ex) {
        try {
            if (ex instanceof VirtualMachineError) {
                APPLICATION_ERRORS.increment();
                return;
            }
            long errors = ERROR_COUNT.incrementAndGet();
            AgentBridge.error(ex);
            if (errors >= MAX_ERRORS && !off) {
                off = true;
                offReason = "switched off after " + MAX_ERRORS + " internal errors, the last: " + ex;
                mask = 0;
                updateGate();
                AgentBridge.message("the side-effect sensors were " + offReason);
            }
        } catch (Throwable ignored) {
            // Never throw from the error path.
        }
    }

    /** One side-effect sensor's counters, for the agent's status and the engine; JDK types only. */
    static Map<String, Object> status(String id) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        try {
            int sensor = 0;
            for (int i = 1; i < SENSOR_NAMES.length; i++) {
                if (SENSOR_NAMES[i].equals(id)) {
                    sensor = i;
                }
            }
            map.put("generation", Long.valueOf(generation));
            map.put("active", Boolean.valueOf(sensor != 0 && (mask & (1 << sensor)) != 0));
            Map<String, Object> recorded = new LinkedHashMap<String, Object>();
            for (int i = 0; i < HOOKS.length; i++) {
                if (HOOK_SENSORS[i] == sensor) {
                    recorded.put(HOOKS[i], Long.valueOf(RECORDED[i].sum()));
                }
            }
            map.put("recorded", recorded);
            map.put("published", Long.valueOf(PUBLISHED[sensor].sum()));
            map.put("dropped", Long.valueOf(DROPPED[sensor].sum()));
            map.put("skipped", Long.valueOf(SKIPPED.sum()));
            map.put("tableFlushes", Long.valueOf(TABLE_FLUSHES.sum()));
            map.put("slotMismatches", Long.valueOf(SLOT_MISMATCHES.sum()));
            if (sensor == SENSOR_FILES) {
                Map<String, Object> buckets = new LinkedHashMap<String, Object>();
                for (int i = 0; i < BUCKETS.length; i++) {
                    buckets.put(BUCKETS[i], Long.valueOf(BUCKET_COUNTS[i].sum()));
                }
                map.put("buckets", buckets);
            }
            if (sensor == SENSOR_FILES || sensor == SENSOR_ENVIRONMENT) {
                map.put(
                        "internQuota",
                        Integer.valueOf(sensor == SENSOR_FILES ? FILES_INTERN_QUOTA : ENVIRONMENT_INTERN_QUOTA));
                map.put("internQuotaExceeded", Long.valueOf(QUOTA_EXCEEDED[sensor].sum()));
                map.put("walks", Long.valueOf(WALKS.sum()));
                map.put("sightingsFull", Long.valueOf(SIGHTINGS_FULL.sum()));
            }
            if (sensor == SENSOR_ENVIRONMENT) {
                map.put("jdkReads", Long.valueOf(JDK_READS.sum()));
                map.put("frameworkReads", Long.valueOf(FRAMEWORK_READS.sum()));
            }
            if (sensor == SENSOR_PROCESSES) {
                map.put("exitsWatched", Long.valueOf(EXITS_WATCHED.sum()));
                map.put("exitsRecorded", Long.valueOf(EXITS_RECORDED.sum()));
                map.put("exitsUnwatched", Long.valueOf(EXITS_UNWATCHED.sum()));
                map.put("exitsDropped", Long.valueOf(EXITS_DROPPED.sum()));
                map.put("exitsPending", Integer.valueOf(Math.max(0, PENDING_EXITS.get())));
                map.put("exitWorkerRunning", Boolean.valueOf(exitWorkerRunning));
            }
            AgentRing.Ring ring = RING.get();
            map.put("ringCapacity", Integer.valueOf(ring == null ? 0 : ring.capacity));
            map.put("ringSize", Long.valueOf(ring == null ? 0L : ring.size()));
            map.put("lost", Long.valueOf(COUNTERS.lost.sum()));
            map.put("drained", Long.valueOf(COUNTERS.drained.sum()));
            map.put("staleDrains", Long.valueOf(STALE_DRAINS.sum()));
            map.put("busyDrains", Long.valueOf(BUSY_DRAINS.sum()));
            AgentRing.Interns interns = INTERNS.get();
            map.put("interned", Integer.valueOf(interns == null ? 0 : interns.size()));
            map.put("internOverflow", Long.valueOf(COUNTERS.internOverflow.sum()));
            map.put("errors", Long.valueOf(ERROR_COUNT.get()));
            map.put("applicationErrors", Long.valueOf(APPLICATION_ERRORS.sum()));
            map.put("off", Boolean.valueOf(off));
            map.put("disabledReason", off ? offReason : DISABLED_REASONS[sensor]);
        } catch (Throwable ex) {
            AgentBridge.error(ex);
        }
        return map;
    }

    /** The ids of every side-effect sensor this bridge knows. */
    static String[] sensorIds() {
        String[] ids = new String[SENSOR_NAMES.length - 1];
        for (int i = 1; i < SENSOR_NAMES.length; i++) {
            ids[i - 1] = SENSOR_NAMES[i];
        }
        return ids;
    }

    /** Tests only: forgets the ring, the table, the switches, and the counters; the exit worker stays handed out. */
    static void reset() {
        RING.set(null);
        INTERNS.set(null);
        COUNTERS.reset();
        resetAll(RECORDED);
        resetAll(SELF_TEST_HITS);
        resetAll(DROPPED);
        resetAll(PUBLISHED);
        STALE_DRAINS.reset();
        BUSY_DRAINS.reset();
        TABLE_FLUSHES.reset();
        SKIPPED.reset();
        EXITS_WATCHED.reset();
        EXITS_RECORDED.reset();
        EXITS_UNWATCHED.reset();
        EXITS_DROPPED.reset();
        APPLICATION_ERRORS.reset();
        SLOT_MISMATCHES.reset();
        PENDING_EXITS.set(0);
        ERROR_COUNT.set(0);
        resetAll(BUCKET_COUNTS);
        resetAll(QUOTA_EXCEEDED);
        WALKS.reset();
        SIGHTINGS_FULL.reset();
        JDK_READS.reset();
        FRAMEWORK_READS.reset();
        SIGHTINGS.clear();
        for (AtomicInteger interned : INTERNED) {
            interned.set(0);
        }
        for (int i = 0; i < DISABLED_REASONS.length; i++) {
            DISABLED_REASONS[i] = null;
        }
        mask = 0;
        gate = 0;
        enabled = 0;
        generation = -1L;
        off = false;
        offReason = null;
        selfTestThread = null;
        claimedOnce = false;
        CodePaths.Frame frame = CodePaths.FRAME.get();
        if (frame != null) {
            frame.slots = 0;
            frame.sideEffects = null;
            frame.sideEffectDepth = 0;
            frame.environmentSeen = null;
            frame.fileSightings = null;
        }
    }

    /** Tests only: the exits' queue, which a test runs by hand when no worker thread was started. */
    static ArrayBlockingQueue<Runnable> exitQueue() {
        return EXITS.queue;
    }

    /** Tests only: whether the exits' worker is marked running, as a test that runs exits by hand sets. */
    static void exitWorkerRunning(boolean running) {
        exitWorkerRunning = running;
    }

    private static LongAdder[] adders(int count) {
        LongAdder[] adders = new LongAdder[count];
        for (int i = 0; i < count; i++) {
            adders[i] = new LongAdder();
        }
        return adders;
    }

    private static void resetAll(LongAdder[] adders) {
        for (int i = 0; i < adders.length; i++) {
            adders[i].reset();
        }
    }
}
