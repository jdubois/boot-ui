package io.github.jdubois.bootui.agent.bridge;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
 * <p><b>Network</b> (M5-5b). Connects ({@code Socket.connect}, {@code SocketChannelImpl.connect}, {@code
 * blockingConnect}, and a non-blocking connect's {@code finishConnect}) and name lookups the JVM's address cache
 * missed ({@code InetAddress.getAddressesFromNameService}) are rare hooks, published at once with the first frame
 * outside the socket plumbing (the client, which the engine recognizes), the summary's frames, and the thread's family,
 * digit runs folded. Datagram sends are hot: the first of a target from a call site is published at once and its frames
 * remembered, the next ones counted in the thread's table. A target is the remote's host string and port, never
 * resolved, never a byte of what is sent; at most {@value #MAX_NETWORK_TARGETS} distinct ones per generation. The JDK's
 * own loopback pair ({@code sun.nio.ch.PipeImpl}) is never recorded.
 *
 * <p>JDK types only; every entry point catches everything.
 */
public final class SideEffects {

    /** The processes sensor's id, as {@code bootui.agent.sensors} names it. */
    public static final String PROCESSES = "processes";

    /** The network sensor's id (M5-5b). */
    public static final String NETWORK = "network";

    /** Sensor ids in records and bit positions in the mask: 0 is unused. */
    public static final int SENSOR_PROCESSES = 1;

    public static final int SENSOR_NETWORK = 2;

    static final String[] SENSOR_NAMES = {"other", PROCESSES, NETWORK};

    public static final int MASK_PROCESSES = 1 << SENSOR_PROCESSES;
    public static final int MASK_NETWORK = 1 << SENSOR_NETWORK;

    /** Hooks, by index: their ids, as the agent reports them, and their sensors. */
    public static final int HOOK_PROCESS_START = 0;

    public static final int HOOK_SOCKET_CONNECT = 1;
    public static final int HOOK_CHANNEL_CONNECT = 2;
    public static final int HOOK_CHANNEL_BLOCKING_CONNECT = 3;
    public static final int HOOK_CHANNEL_FINISH_CONNECT = 4;
    public static final int HOOK_DATAGRAM_CHANNEL_SEND = 5;
    public static final int HOOK_DATAGRAM_SOCKET_SEND = 6;
    public static final int HOOK_LOOKUP = 7;

    static final String[] HOOKS = {
        "ProcessBuilder.start",
        "Socket.connect",
        "SocketChannel.connect",
        "SocketChannel.blockingConnect",
        "SocketChannel.finishConnect",
        "DatagramChannel.send",
        "DatagramSocket.send",
        "InetAddress.lookup"
    };
    static final int[] HOOK_SENSORS = {
        SENSOR_PROCESSES,
        SENSOR_NETWORK,
        SENSOR_NETWORK,
        SENSOR_NETWORK,
        SENSOR_NETWORK,
        SENSOR_NETWORK,
        SENSOR_NETWORK,
        SENSOR_NETWORK
    };

    /** Record kinds. */
    public static final int KIND_PROCESS_START = 1;

    public static final int KIND_PROCESS_EXIT = 2;

    /** A connect: blocking, with its time, or non-blocking, {@link #OUTCOME_PENDING} until its finish record. */
    public static final int KIND_CONNECT = 3;

    /** A non-blocking connect finished: its outcome and the time since the connect started, carrying its owner. */
    public static final int KIND_CONNECT_FINISH = 4;

    /** A datagram sent to an address. */
    public static final int KIND_DATAGRAM = 5;

    /** A host name the JVM resolved, its address cache missing it. */
    public static final int KIND_LOOKUP = 6;

    /** Outcomes. */
    public static final int OUTCOME_STARTED = 1;

    public static final int OUTCOME_IO_ERROR = 2;
    public static final int OUTCOME_ERROR = 3;
    public static final int OUTCOME_EXITED = 4;
    public static final int OUTCOME_CONNECTED = 5;
    public static final int OUTCOME_PENDING = 6;
    public static final int OUTCOME_SENT = 7;
    public static final int OUTCOME_RESOLVED = 8;
    public static final int OUTCOME_UNKNOWN_HOST = 9;

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
     * Outcome (bits 0–7), thread kind (8–11), execution kind (12–15), the interned thread name of an unowned record,
     * or of every network record, as its family, digit runs folded (16–31), and a process's exit status or a network
     * record's interned client frame (32–63).
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

    /** Frames walked for a network record at most: a Netty connect's stack is deep. */
    static final int MAX_NETWORK_FRAMES = 128;

    /** Distinct network targets interned per claim generation; the others share {@value #OTHER_HOSTS}. */
    static final int MAX_NETWORK_TARGETS = 1_024;

    static final String OTHER_HOSTS = "(other hosts)";

    /** Distinct datagram first sightings remembered per claim generation, with their frames. */
    static final int MAX_DATAGRAM_MEMO = 1_024;

    /** Non-blocking connects waiting for their finish at most, and how long one waits. */
    static final int MAX_PENDING_CONNECTS = 1_024;

    static final long PENDING_CONNECT_NANOS = 60_000_000_000L;

    /** An open side-effect hook older than this is stale: its exit never ran. */
    static final long STALE_DEPTH_NANOS = 600_000_000_000L;

    private static final long FLUSH_NANOS = FLUSH_MILLIS * 1_000_000L;

    private static final AgentRing.Counters COUNTERS = new AgentRing.Counters();
    private static final AtomicReference<AgentRing.Ring> RING = new AtomicReference<AgentRing.Ring>();
    private static final AtomicReference<AgentRing.Interns> INTERNS = new AtomicReference<AgentRing.Interns>();
    private static final StackWalker WALKER = StackWalker.getInstance();
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
    private static final LongAdder STALE_DEPTHS = new LongAdder();
    private static final LongAdder NETWORK_TARGETS_OVERFLOW = new LongAdder();
    private static final LongAdder CONNECTS_UNPAIRED = new LongAdder();
    private static final LongAdder JDK_LOOPBACK_SKIPPED = new LongAdder();
    private static final AtomicReference<Network> NETWORK_STATE = new AtomicReference<Network>();
    private static final AtomicInteger PENDING_EXITS = new AtomicInteger();
    private static final AtomicLong ERROR_COUNT = new AtomicLong();
    private static final String[] DISABLED_REASONS = new String[SENSOR_NAMES.length];

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
        return starting(HOOK_PROCESS_START, MASK_PROCESSES);
    }

    /**
     * A hook's entry: a token for its exit, from {@link System#nanoTime()}, 0 when nothing is recorded for the call (the
     * sensor is off, the thread's work is skipped, or a side-effect hook is already open on the thread). On the
     * self-test's thread, counts the hook and records nothing. Never throws.
     */
    private static long starting(int hook, int bit) {
        try {
            Thread current = Thread.currentThread();
            Thread self = selfTestThread;
            if (self != null && self == current) {
                SELF_TEST_HITS[hook].increment();
                return 0L;
            }
            if ((mask & bit) == 0) {
                return 0L;
            }
            if (Reentrancy.sideEffectsSkipped() || current.getName().startsWith("bootui-")) {
                SKIPPED.increment();
                return 0L;
            }
            CodePaths.Frame frame = CodePaths.frame();
            long now = System.nanoTime();
            if (frame.sideEffectDepth != 0) {
                if (now - frame.sideEffectSince < STALE_DEPTH_NANOS) {
                    return 0L;
                }
                // An exit that never ran, as when calling it overflowed the stack: the thread records again.
                STALE_DEPTHS.increment();
            }
            frame.sideEffectDepth = 1;
            frame.sideEffectSince = now;
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

    // ---- the network sensor's advice (M5-5b) -----------------------------------------------------------------------

    /** A network hook's entry, {@code hook} indexing {@link #HOOKS}: a token for its exit, 0 when nothing is recorded. */
    public static long networkStarting(int hook) {
        return starting(hook, MASK_NETWORK);
    }

    /**
     * A connect's exit, normal or not: {@code Socket.connect(SocketAddress,int)} and {@code
     * SocketChannelImpl.blockingConnect} with no channel and {@code finished} true unless it threw; {@code
     * SocketChannelImpl.connect(SocketAddress)} with its channel, used only as an identity key, never called, and its
     * result, false being a non-blocking connect started: recorded as pending, and paired with its {@link
     * #connectFinished finish}. Records the remote's host string and port, never resolving or reverse-resolving it, and
     * never a byte the connection carries. Never throws.
     */
    public static void connected(
            long token, int hook, Object channel, Object remote, boolean finished, Throwable thrown) {
        if (token == 0L) {
            return;
        }
        CodePaths.Frame frame = null;
        try {
            frame = CodePaths.FRAME.get();
            long nanos = System.nanoTime() - token;
            Claim claim = AgentBridge.current();
            if (!recording(claim, MASK_NETWORK)) {
                return;
            }
            RECORDED[hook].increment();
            long[] walked = networkFrames(claim);
            if (walked == null) {
                JDK_LOOPBACK_SKIPPED.increment();
                return;
            }
            int target = networkTarget(describe(remote));
            int outcome = thrown != null ? failure(thrown) : finished ? OUTCOME_CONNECTED : OUTCOME_PENDING;
            long stamp = CodePaths.stamp();
            Owner owner = networkOwner(frame, claim);
            long startMillis = System.currentTimeMillis() - nanos / 1_000_000L;
            int client = (int) walked[1];
            // A rare hook: published at once. A pending connect's time is its finish's.
            publishOne(
                    owner,
                    SENSOR_NETWORK,
                    KIND_CONNECT,
                    target,
                    outcome,
                    client,
                    stamp,
                    walked[0],
                    outcome == OUTCOME_PENDING ? 0L : nanos,
                    startMillis);
            if (outcome == OUTCOME_PENDING && channel != null) {
                pendingConnect(channel, owner, target, client, stamp, walked[0], token, startMillis);
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
     * {@code SocketChannelImpl.finishConnect()}'s exit: a non-blocking connect this sensor recorded as pending finished,
     * or failed, on whichever thread finishes it; published with the connect's owner, target, client, stamp, and
     * frames, and the time since it started. A finish still pending, or of a connect not recorded, records nothing.
     * Never throws.
     */
    public static void connectFinished(long token, Object channel, boolean finished, Throwable thrown) {
        if (token == 0L) {
            return;
        }
        CodePaths.Frame frame = null;
        try {
            frame = CodePaths.FRAME.get();
            Network state = NETWORK_STATE.get();
            if (state == null || channel == null || (!finished && thrown == null) || state.pending.isEmpty()) {
                return;
            }
            PendingConnect pending = state.pending.remove(Identity.of(channel));
            Claim claim = AgentBridge.current();
            if (pending == null || !recording(claim, MASK_NETWORK) || pending.owner.generation != claim.generation) {
                return;
            }
            RECORDED[HOOK_CHANNEL_FINISH_CONNECT].increment();
            long nanos = System.nanoTime() - pending.startNanos;
            int outcome = thrown == null ? OUTCOME_CONNECTED : failure(thrown);
            long[] values = new long[RECORD];
            values[R_SENSOR] = SENSOR_NETWORK;
            values[R_KIND] = KIND_CONNECT_FINISH;
            values[R_GENERATION] = pending.owner.generation;
            // The connect's time: the engine decides a finish as it decided its connect.
            values[R_FIRST_MILLIS] = pending.startMillis;
            values[R_LAST_MILLIS] = System.currentTimeMillis();
            values[R_REQUEST] = pending.owner.request;
            values[R_EXECUTION] = pending.owner.execution;
            values[R_STAMP] = pending.stamp;
            values[R_TARGET] = pending.target;
            values[R_FLAGS] = flags(outcome, pending.owner, pending.client);
            values[R_COUNT] = 1L;
            values[R_NANOS] = nanos;
            values[R_MAX_NANOS] = nanos;
            values[R_FRAMES] = pending.frames;
            publish(SENSOR_NETWORK, values);
        } catch (Throwable ex) {
            failed(ex);
        } finally {
            if (frame != null) {
                frame.sideEffectDepth = 0;
            }
        }
    }

    /**
     * A datagram send's exit: {@code DatagramChannelImpl.send(ByteBuffer,SocketAddress)} with its address, {@code
     * DatagramSocket.send(DatagramPacket)} with its packet, whose address and port only are read, never its data. A
     * hot hook: the first send of a target from a call site is published at once with its frames, which a bounded memo
     * keeps; the next ones reuse them and count in the thread's table. Never throws.
     */
    public static void datagramSent(long token, int hook, Object target, Throwable thrown) {
        if (token == 0L) {
            return;
        }
        CodePaths.Frame frame = null;
        try {
            frame = CodePaths.FRAME.get();
            long nanos = System.nanoTime() - token;
            Claim claim = AgentBridge.current();
            Network state = NETWORK_STATE.get();
            if (!recording(claim, MASK_NETWORK) || state == null || frame == null) {
                return;
            }
            RECORDED[hook].increment();
            Object address = target instanceof java.net.DatagramPacket
                    ? packetAddress((java.net.DatagramPacket) target)
                    : target;
            int targetId = networkTarget(describe(address));
            int outcome = thrown == null ? OUTCOME_SENT : failure(thrown);
            long stamp = CodePaths.stamp();
            Owner owner = networkOwner(frame, claim);
            long now = System.currentTimeMillis();
            // The call site without the request's fragment sequence, and the thread's family: the frames of a send
            // depend on where it is made, never on which request made it.
            Memo key = new Memo(targetId, stamp > 0L ? stamp & SITE_MASK : stamp, outcome, owner.threadName);
            long[] known = state.memo.get(key);
            if (known == null && frame.sideEffects != null) {
                // Past the memo's bound, the thread's own table still remembers this send's frames.
                known = frame.sideEffects.known(SENSOR_NETWORK, KIND_DATAGRAM, targetId, outcome, stamp);
            }
            if (known == null) {
                long[] walked = networkFrames(claim);
                if (walked == null) {
                    JDK_LOOPBACK_SKIPPED.increment();
                    return;
                }
                if (state.memo.size() < MAX_DATAGRAM_MEMO) {
                    state.memo.putIfAbsent(key, walked);
                }
                publishOne(
                        owner,
                        SENSOR_NETWORK,
                        KIND_DATAGRAM,
                        targetId,
                        outcome,
                        (int) walked[1],
                        stamp,
                        walked[0],
                        nanos,
                        now - nanos / 1_000_000L);
                return;
            }
            Table table = frame.sideEffects;
            if (table == null) {
                table = new Table();
                frame.sideEffects = table;
            }
            table.add(
                    owner,
                    SENSOR_NETWORK,
                    KIND_DATAGRAM,
                    targetId,
                    outcome,
                    (int) known[1],
                    stamp,
                    known[0],
                    nanos,
                    now);
        } catch (Throwable ex) {
            failed(ex);
        } finally {
            if (frame != null) {
                frame.sideEffectDepth = 0;
            }
        }
    }

    /**
     * {@code InetAddress.getAddressesFromNameService(String, …)}'s exit: the JVM resolved {@code host}, its address
     * cache missing it, so the time is the name service's. A cached answer never reaches it. Never throws.
     */
    public static void lookedUp(long token, String host, Object addresses, Throwable thrown) {
        if (token == 0L) {
            return;
        }
        CodePaths.Frame frame = null;
        try {
            frame = CodePaths.FRAME.get();
            long nanos = System.nanoTime() - token;
            Claim claim = AgentBridge.current();
            if (!recording(claim, MASK_NETWORK)) {
                return;
            }
            RECORDED[HOOK_LOOKUP].increment();
            long[] walked = networkFrames(claim);
            if (walked == null) {
                JDK_LOOPBACK_SKIPPED.increment();
                return;
            }
            int target = lookupTarget(hostName(host));
            int outcome;
            if (thrown == null) {
                // A statically mocked lookup may answer null.
                outcome = addresses == null ? OUTCOME_ERROR : OUTCOME_RESOLVED;
            } else {
                outcome = thrown instanceof java.net.UnknownHostException ? OUTCOME_UNKNOWN_HOST : failure(thrown);
            }
            long stamp = CodePaths.stamp();
            Owner owner = networkOwner(frame, claim);
            publishOne(
                    owner,
                    SENSOR_NETWORK,
                    KIND_LOOKUP,
                    target,
                    outcome,
                    (int) walked[1],
                    stamp,
                    walked[0],
                    nanos,
                    System.currentTimeMillis() - nanos / 1_000_000L);
        } catch (Throwable ex) {
            failed(ex);
        } finally {
            if (frame != null) {
                frame.sideEffectDepth = 0;
            }
        }
    }

    private static boolean recording(Claim claim, int bit) {
        return claim != null && claim.armed && claim.generation == generation && (mask & bit) != 0;
    }

    private static int failure(Throwable thrown) {
        return thrown instanceof java.io.IOException ? OUTCOME_IO_ERROR : OUTCOME_ERROR;
    }

    /** The record's owner, as {@link #owner}, with the thread's family interned on every network record. */
    private static Owner networkOwner(CodePaths.Frame frame, Claim claim) {
        Owner owner = owner(frame, claim, false);
        owner.threadName = threadFamilyId(frame, claim.generation);
        return owner;
    }

    /** The calling thread's family, digit runs folded, interned once per thread and generation. */
    private static int threadFamilyId(CodePaths.Frame frame, long current) {
        String name = Thread.currentThread().getName();
        if (frame != null && frame.sideEffectThreadGeneration == current && name.equals(frame.sideEffectThreadName)) {
            return frame.sideEffectThreadId;
        }
        int id = intern(threadFamily(name));
        if (frame != null) {
            frame.sideEffectThreadName = name;
            frame.sideEffectThreadId = id;
            frame.sideEffectThreadGeneration = current;
        }
        return id;
    }

    /** A thread name's family: each run of digits becomes {@code {n}}, so pools never fill the string table. */
    static String threadFamily(String name) {
        if (name == null) {
            return null;
        }
        int length = Math.min(name.length(), MAX_TARGET);
        StringBuilder family = new StringBuilder(length + 8);
        boolean digits = false;
        for (int i = 0; i < length; i++) {
            char c = name.charAt(i);
            if (c >= '0' && c <= '9') {
                if (!digits) {
                    family.append("{n}");
                    digits = true;
                }
            } else {
                family.append(c);
                digits = false;
            }
        }
        return family.toString();
    }

    /**
     * A socket address as a target: {@code host:port} from {@code InetSocketAddress.getHostString()}, which never
     * resolves, IPv6 bracketed, anything up to an {@code @} dropped; {@code unix:} and its path; a marker otherwise.
     */
    static String describe(Object address) {
        if (address instanceof java.net.InetSocketAddress) {
            java.net.InetSocketAddress socket = (java.net.InetSocketAddress) address;
            String host = hostName(socket.getHostString());
            if (host.indexOf(':') >= 0 && !host.startsWith("[")) {
                host = "[" + host + "]";
            }
            return host + ":" + socket.getPort();
        }
        if (address instanceof java.net.UnixDomainSocketAddress) {
            return "unix:"
                    + sanitize(((java.net.UnixDomainSocketAddress) address)
                            .getPath()
                            .toString());
        }
        if (address instanceof String) {
            return (String) address;
        }
        return address == null ? "(none)" : "(other)";
    }

    /** A host name, as a target: after its last {@code @}, so no user information, sanitized. */
    static String hostName(String host) {
        if (host == null || host.isEmpty()) {
            return "(unknown)";
        }
        int at = host.lastIndexOf('@');
        String name = at >= 0 ? host.substring(at + 1) : host;
        return name.isEmpty() ? "(unknown)" : sanitize(name);
    }

    /** At most {@value #MAX_TARGET} characters, every one but letters, digits, and {@code . _ - : [ ] / ~} as {@code ?}. */
    static String sanitize(String text) {
        int length = Math.min(text.length(), MAX_TARGET);
        StringBuilder kept = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '.'
                    || c == '_'
                    || c == '-'
                    || c == ':'
                    || c == '['
                    || c == ']'
                    || c == '/'
                    || c == '~';
            kept.append(ok ? c : '?');
        }
        return kept.toString();
    }

    /** A datagram packet's address, never its data: {@code (connected)} when it names none. */
    private static Object packetAddress(java.net.DatagramPacket packet) {
        java.net.InetAddress address = packet.getAddress();
        if (address == null) {
            return "(connected)";
        }
        int port = packet.getPort();
        // A packet given an address but no port: the send fails, and the target is no host and port.
        return port < 0 || port > 0xFFFF ? "(other)" : new java.net.InetSocketAddress(address, port);
    }

    /** A network target interned, at most {@value #MAX_NETWORK_TARGETS} distinct ones per generation. */
    static int networkTarget(String text) {
        Network state = NETWORK_STATE.get();
        return state == null ? intern(text) : quota(state.targets, text);
    }

    /** A looked-up name interned, at most {@value #MAX_NETWORK_TARGETS} distinct ones, apart from the targets. */
    static int lookupTarget(String text) {
        Network state = NETWORK_STATE.get();
        return state == null ? intern(text) : quota(state.lookups, text);
    }

    private static int quota(java.util.concurrent.ConcurrentHashMap<String, Integer> known, String text) {
        Integer id = known.get(text);
        if (id != null) {
            return id.intValue();
        }
        if (known.size() >= MAX_NETWORK_TARGETS) {
            NETWORK_TARGETS_OVERFLOW.increment();
            return intern(OTHER_HOSTS);
        }
        int interned = intern(text);
        if (interned != 0) {
            known.putIfAbsent(text, Integer.valueOf(interned));
        }
        return interned;
    }

    private static void pendingConnect(
            Object channel,
            Owner owner,
            int target,
            int client,
            long stamp,
            long frames,
            long startNanos,
            long startMillis) {
        Network state = NETWORK_STATE.get();
        if (state == null) {
            return;
        }
        if (state.pending.size() >= MAX_PENDING_CONNECTS || (state.puts.incrementAndGet() & 63) == 0) {
            // Connects closed or timed out without a finish are swept, never left to fill the map.
            state.sweep(System.nanoTime());
            if (state.pending.size() >= MAX_PENDING_CONNECTS) {
                CONNECTS_UNPAIRED.increment();
                return;
            }
        }
        state.pending.put(
                Identity.stored(channel),
                new PendingConnect(owner, target, client, stamp, frames, startNanos, startMillis));
    }

    /** The first frame outside the socket plumbing, then the summary's frames: {@code null} for the JDK's own loopback. */
    static long[] networkFrames(Claim claim) {
        return WALKER.walk(new NetworkFrames(claim));
    }

    /**
     * Socket plumbing: the JDK's socket and channel code, the agent, the shared transports (Netty, Vert.x's core, Reactor
     * and Reactor Netty's transport), and the frames between them and their caller, never a client. The JDK's own
     * clients, {@code jdk.internal.net.http} and {@code sun.net.www}, are not plumbing.
     */
    static boolean plumbing(String className) {
        return className.startsWith("java.")
                || className.startsWith("javax.net.")
                || className.startsWith("sun.nio.")
                || className.startsWith("sun.security.ssl.")
                || className.startsWith("jdk.internal.misc.")
                || className.startsWith("jdk.internal.reflect.")
                || className.startsWith("jdk.internal.loader.")
                || className.startsWith("jdk.jfr.")
                || className.startsWith("io.netty.")
                || className.startsWith("io.vertx.core.")
                || className.startsWith("reactor.core.")
                || className.startsWith("reactor.netty.transport.")
                || className.startsWith("reactor.netty.resources.")
                || className.startsWith("io.github.jdubois.bootui.agent.");
    }

    /**
     * Walks at most {@value #MAX_NETWORK_FRAMES} frames: {@code {packed outside and application frames, client
     * frame}}, or {@code null} on the JDK's own loopback pair ({@code sun.nio.ch.PipeImpl}, a pipe or selector
     * wake-up on Windows).
     */
    static final class NetworkFrames implements Function<Stream<StackWalker.StackFrame>, long[]> {

        private final Claim claim;

        NetworkFrames(Claim claim) {
            this.claim = claim;
        }

        @Override
        public long[] apply(Stream<StackWalker.StackFrame> frames) {
            Iterator<StackWalker.StackFrame> iterator = frames.iterator();
            int client = 0;
            int outside = 0;
            int application = 0;
            for (int i = 0; i < MAX_NETWORK_FRAMES && iterator.hasNext() && application == 0; i++) {
                StackWalker.StackFrame frame = iterator.next();
                String className = frame.getClassName();
                if (className.startsWith("sun.nio.ch.PipeImpl")) {
                    return null;
                }
                if (client == 0 && !plumbing(className)) {
                    client = intern(className + "#" + frame.getMethodName());
                }
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
            return new long[] {((long) outside << 32) | (application & 0xFFFFFFFFL), client};
        }
    }

    /** The network sensor's state for one claim generation: its targets, datagram memo, and pending connects. */
    static final class Network {

        final long generation;
        final java.util.concurrent.ConcurrentHashMap<String, Integer> targets =
                new java.util.concurrent.ConcurrentHashMap<String, Integer>();
        final java.util.concurrent.ConcurrentHashMap<Memo, long[]> memo =
                new java.util.concurrent.ConcurrentHashMap<Memo, long[]>();
        final java.util.concurrent.ConcurrentHashMap<Identity, PendingConnect> pending =
                new java.util.concurrent.ConcurrentHashMap<Identity, PendingConnect>();
        final java.util.concurrent.ConcurrentHashMap<String, Integer> lookups =
                new java.util.concurrent.ConcurrentHashMap<String, Integer>();
        final AtomicLong puts = new AtomicLong();

        Network(long generation) {
            this.generation = generation;
        }

        /** Drops the pending connects older than {@value #PENDING_CONNECT_NANOS} ns: their channel never finished. */
        void sweep(long now) {
            Iterator<Map.Entry<Identity, PendingConnect>> iterator =
                    pending.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Identity, PendingConnect> entry = iterator.next();
                if (entry.getKey().value() == null || now - entry.getValue().startNanos > PENDING_CONNECT_NANOS) {
                    iterator.remove();
                    CONNECTS_UNPAIRED.increment();
                }
            }
        }
    }

    /** A code-paths stamp's node and method bits, without the fragment's sequence. */
    static final long SITE_MASK = (1L << (CodePaths.STAMP_NODE_BITS + CodePaths.STAMP_METHOD_BITS)) - 1;

    /** A datagram first sighting's key: its target, call site, outcome, and thread family. */
    static final class Memo {

        final int target;
        final long site;
        final int outcome;
        final int thread;

        Memo(int target, long site, int outcome, int thread) {
            this.target = target;
            this.site = site;
            this.outcome = outcome;
            this.thread = thread;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Memo)) {
                return false;
            }
            Memo that = (Memo) other;
            return target == that.target && site == that.site && outcome == that.outcome && thread == that.thread;
        }

        @Override
        public int hashCode() {
            return ((31 * target + (int) (site ^ (site >>> 32))) * 31 + outcome) * 31 + thread;
        }
    }

    /**
     * A channel as a key by identity, held weakly once stored, so a channel the application closed without finishing
     * its connect is never kept: none of its methods, not even {@code hashCode}, is ever called.
     */
    static final class Identity {

        private final int hash;
        private final Object strong;
        private final java.lang.ref.WeakReference<Object> weak;

        private Identity(Object value, boolean stored) {
            this.hash = System.identityHashCode(value);
            this.strong = stored ? null : value;
            this.weak = stored ? new java.lang.ref.WeakReference<Object>(value) : null;
        }

        /** A key to look a channel up with. */
        static Identity of(Object value) {
            return new Identity(value, false);
        }

        /** A key to store, holding the channel weakly. */
        static Identity stored(Object value) {
            return new Identity(value, true);
        }

        Object value() {
            return strong != null ? strong : weak.get();
        }

        @Override
        public boolean equals(Object other) {
            if (other == this) {
                return true;
            }
            if (!(other instanceof Identity)) {
                return false;
            }
            Object mine = value();
            return mine != null && mine == ((Identity) other).value();
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    /** A non-blocking connect waiting for its finish. */
    static final class PendingConnect {

        final Owner owner;
        final int target;
        final int client;
        final long stamp;
        final long frames;
        final long startNanos;
        final long startMillis;

        PendingConnect(
                Owner owner, int target, int client, long stamp, long frames, long startNanos, long startMillis) {
            this.owner = owner;
            this.target = target;
            this.client = client;
            this.stamp = stamp;
            this.frames = frames;
            this.startNanos = startNanos;
            this.startMillis = startMillis;
        }
    }

    // ---- the owner slots ---------------------------------------------------------------------------------------

    /** Owner slots kept per thread; deeper ones name no owner, and a hook captures its owner instead. */
    static final int SLOTS = 8;

    /**
     * An adapter opened a request's scope on this thread ({@link CodePaths#begin()}), with the owner the code-paths
     * sensor captured there, or {@code null}: a slot is pushed for the scope, naming that owner, or no owner, so that a
     * hook inside captures its own. No capture is made here. Never throws.
     */
    static void scopeBegin(long[] captured) {
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
        return owner(frame, claim, true);
    }

    /** As {@link #owner(CodePaths.Frame, Claim)}, naming an unowned record's thread only when {@code threadName}. */
    private static Owner owner(CodePaths.Frame frame, Claim claim, boolean threadName) {
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
        if (!ownerOf(CodePaths.capture(claim), owner) && threadName) {
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

    // ---- aggregation and the ring -----------------------------------------------------------------------------------

    /**
     * Records one occurrence of a hot hook: aggregated in the thread's table while its owner comes from the slot, else
     * published at once. No M5-5a sensor is hot; {@code processes} publishes every start at once.
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
     * outcome, detail, stamp, frames)}, with their count, total and longest durations, and first and last times. The
     * owner includes the thread's family for a network record, and the detail is a network record's client frame.
     */
    static final class Table {

        long generation;
        long request;
        long execution;
        int executionKind;
        int threadKind;
        int threadName;
        int size;
        long oldestNanos;

        final int[] sensor = new int[TABLE];
        final int[] kind = new int[TABLE];
        final int[] target = new int[TABLE];
        final int[] outcome = new int[TABLE];
        final int[] detail = new int[TABLE];
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
                int entryDetail,
                long entryStamp,
                long entryFrames,
                long entryNanos,
                long now) {
            long nowNanos = System.nanoTime();
            if (size > 0
                    && (generation != owner.generation
                            || request != owner.request
                            || execution != owner.execution
                            || threadName != owner.threadName
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
                threadName = owner.threadName;
                oldestNanos = nowNanos;
            }
            for (int i = 0; i < size; i++) {
                if (sensor[i] == entrySensor
                        && kind[i] == entryKind
                        && target[i] == entryTarget
                        && outcome[i] == entryOutcome
                        && detail[i] == entryDetail
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
                threadName = owner.threadName;
                oldestNanos = nowNanos;
            }
            int i = size++;
            sensor[i] = entrySensor;
            kind[i] = entryKind;
            target[i] = entryTarget;
            outcome[i] = entryOutcome;
            detail[i] = entryDetail;
            stamp[i] = entryStamp;
            frames[i] = entryFrames;
            count[i] = 1L;
            nanos[i] = entryNanos;
            maxNanos[i] = entryNanos;
            firstMillis[i] = now;
            lastMillis[i] = now;
        }

        /** The {@code {frames, detail}} of an entry recorded for this key, or {@code null}. */
        long[] known(int entrySensor, int entryKind, int entryTarget, int entryOutcome, long entryStamp) {
            for (int i = 0; i < size; i++) {
                if (sensor[i] == entrySensor
                        && kind[i] == entryKind
                        && target[i] == entryTarget
                        && outcome[i] == entryOutcome
                        && stamp[i] == entryStamp) {
                    return new long[] {frames[i], detail[i]};
                }
            }
            return null;
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
                        | ((long) (threadName & 0xFFFF) << 16)
                        | ((long) detail[i] << 32);
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
                    break;
                }
            }
            if (claim.generation > generation) {
                generation = claim.generation;
            }
            while (true) {
                Network current = NETWORK_STATE.get();
                if (current != null && current.generation >= claim.generation) {
                    break;
                }
                if (NETWORK_STATE.compareAndSet(current, new Network(claim.generation))) {
                    break;
                }
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
        } catch (Throwable ex) {
            AgentBridge.error(ex);
        }
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
        if ((bits & MASK_NETWORK) != 0) {
            // Never kept past the sensor's life: the channels still waiting and the datagram frames remembered.
            Network network = NETWORK_STATE.get();
            if (network != null) {
                network.pending.clear();
                network.memo.clear();
            }
        }
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
            // The network hooks' path, never resolving a name: an unresolved address and the loopback literal.
            describe(java.net.InetSocketAddress.createUnresolved("warm.invalid", 1));
            describe(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 1));
            describe(new java.net.InetSocketAddress(java.net.InetAddress.getByAddress(new byte[16]), 1));
            describe(java.net.UnixDomainSocketAddress.of("warm"));
            describe("(connected)");
            describe(null);
            packetAddress(new java.net.DatagramPacket(new byte[0], 0));
            hostName("user@warm");
            threadFamily("warm-1");
            plumbing("warm");
            failure(new java.io.IOException("warm"));
            new java.net.UnknownHostException("warm").getClass();
            recording(null, 0);
            WALKER.walk(new NetworkFrames(null)).getClass();
            Network network = new Network(-1L);
            network.targets.putIfAbsent("warm", Integer.valueOf(0));
            network.targets.get("warm");
            network.memo.putIfAbsent(new Memo(0, 0L, 0, 0), new long[2]);
            network.memo.get(new Memo(0, 0L, 0, 0));
            network.pending.put(Identity.stored(network), new PendingConnect(owner, 0, 0, 0L, 0L, 0L, 0L));
            network.puts.incrementAndGet();
            network.sweep(Long.MAX_VALUE);
            network.pending.remove(Identity.of(network));
            network.pending.isEmpty();
            quota(network.lookups, "warm");
            table.known(0, 0, 0, 0, 0L);
            packetAddress(new java.net.DatagramPacket(new byte[0], 0, java.net.InetAddress.getLoopbackAddress(), 1));
            status(PROCESSES);
            status(NETWORK);
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
    }

    /**
     * Moves the self-test to {@code thread}, as the agent does for a step it runs on a helper thread of its own with a
     * bounded wait, such as a name lookup; {@code null} counts nothing until the next call. Never throws.
     */
    public static void selfTestOn(Thread thread) {
        selfTestThread = thread;
    }

    /** Ends the self-test: the hooks it ran, by hook id, with how often each fired. */
    public static Map<String, Object> endSelfTest() {
        selfTestThread = null;
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
            map.put("staleDepths", Long.valueOf(STALE_DEPTHS.sum()));
            if (sensor == SENSOR_NETWORK) {
                Network network = NETWORK_STATE.get();
                map.put("targets", Integer.valueOf(network == null ? 0 : network.targets.size()));
                map.put("targetsOverflow", Long.valueOf(NETWORK_TARGETS_OVERFLOW.sum()));
                map.put("pendingConnects", Integer.valueOf(network == null ? 0 : network.pending.size()));
                map.put("connectsUnpaired", Long.valueOf(CONNECTS_UNPAIRED.sum()));
                map.put("datagramMemo", Integer.valueOf(network == null ? 0 : network.memo.size()));
                map.put("jdkLoopbackSkipped", Long.valueOf(JDK_LOOPBACK_SKIPPED.sum()));
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
        STALE_DEPTHS.reset();
        NETWORK_TARGETS_OVERFLOW.reset();
        CONNECTS_UNPAIRED.reset();
        JDK_LOOPBACK_SKIPPED.reset();
        NETWORK_STATE.set(null);
        PENDING_EXITS.set(0);
        ERROR_COUNT.set(0);
        for (int i = 0; i < DISABLED_REASONS.length; i++) {
            DISABLED_REASONS[i] = null;
        }
        mask = 0;
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
            frame.sideEffectSince = 0L;
            frame.sideEffectThreadName = null;
            frame.sideEffectThreadGeneration = -1L;
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
