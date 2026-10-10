package io.github.jdubois.bootui.agent.bridge;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.Socket;
import java.nio.channels.Channel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * The {@value SideEffects#RESOURCES} sensor's bridge side (PLAN-v2 §5.16, M5-5g, D46): the streams, channels, and
 * sockets a request's or a job's work opened, reported when a request ended with one still open, and when the
 * collector reclaimed one never closed. Never a byte read or written, never a path or an address beyond what the
 * {@code files} and {@code network} sensors record.
 *
 * <p><b>Opens.</b> No hook of its own: the {@code files} and {@code network} sensors' hooks hand it the object they
 * opened once they recorded it ({@link #opened}), so it sees only what they record, and only while they are on.
 * Tracked only when the work belongs to a request or an execution, a frame of the claimed packages is on the stack (an
 * application's call into a library that opens for it included, its origin {@link #ORIGIN_LIBRARY}), and the
 * resource's close hook passed its self-test ({@link #enableKinds}). Channels and sockets are tracked only of the JDK's
 * exact classes, whose every close reaches a hook: {@code sun.nio.ch.FileChannelImpl}, {@code SocketChannelImpl},
 * {@code java.net.Socket}, and {@code sun.security.ssl.SSLSocketImpl}. {@code Files.newInputStream} and {@code
 * newOutputStream} return a stream over a channel the provider opens itself: its {@code setUninterruptible} hook hands
 * that channel to the outer files hook ({@link #uninterruptible}).
 *
 * <p><b>Closes.</b> The agent's own transformer advises {@code close()} of {@code FileInputStream}, {@code
 * FileOutputStream}, {@code RandomAccessFile}, and {@code Socket}, and {@code implCloseChannel()} of {@code
 * FileChannelImpl} and {@code AbstractSelectableChannel}, which a close and a thread's interruption both reach, at exit:
 * {@link #closed}, lock-free, reading one counter first ({@link ResourceTracker}).
 *
 * <p><b>Reports</b>, on the drain thread ({@link #sweep}): a resource of a request still open {@value #GRACE_NANOS} ns
 * after its response completed, checked through the JDK's own final methods ({@link #closedNow}); one cleared by the
 * collector while never closed; and one reported open, then closed, as handed off. A resource whose close a hook missed
 * (its own state closed for 30 seconds while its entry was not marked) is counted, and the collector's
 * reclaims of its kind are no longer reported for the claim generation: a close the hooks miss systematically is
 * detected and stops that kind's reclaims, though a single missed close before the canary sees it can still read as one.
 *
 * <p>JDK types only; every entry point catches everything.
 */
public final class Resources {

    /** Resource kinds, in a record's detail bits 4–7. */
    public static final int KIND_FILE_INPUT_STREAM = 1;

    public static final int KIND_FILE_OUTPUT_STREAM = 2;
    public static final int KIND_RANDOM_ACCESS_FILE = 3;
    public static final int KIND_FILE_CHANNEL = 4;
    public static final int KIND_SOCKET = 5;
    public static final int KIND_SOCKET_CHANNEL = 6;

    /** Every kind's bit. */
    public static final int ALL_KINDS = (1 << KIND_FILE_INPUT_STREAM)
            | (1 << KIND_FILE_OUTPUT_STREAM)
            | (1 << KIND_RANDOM_ACCESS_FILE)
            | (1 << KIND_FILE_CHANNEL)
            | (1 << KIND_SOCKET)
            | (1 << KIND_SOCKET_CHANNEL);

    /** A record's detail: its origin, bits 0–1. */
    public static final int ORIGIN_APPLICATION = 1;

    public static final int ORIGIN_LIBRARY = 2;

    /** The record is the resource's first report: the engine counts the resource once. */
    public static final int DETAIL_FIRST = 1 << 8;

    /** The close hooks, by index, and the setUninterruptible hook. */
    public static final int HOOK_FILE_INPUT_STREAM_CLOSE = 0;

    public static final int HOOK_FILE_OUTPUT_STREAM_CLOSE = 1;
    public static final int HOOK_RANDOM_ACCESS_FILE_CLOSE = 2;
    public static final int HOOK_FILE_CHANNEL_CLOSE = 3;
    public static final int HOOK_SOCKET_CLOSE = 4;
    public static final int HOOK_SELECTABLE_CHANNEL_CLOSE = 5;
    public static final int HOOK_UNINTERRUPTIBLE = 6;

    public static final String[] HOOKS = {
        "FileInputStream.close",
        "FileOutputStream.close",
        "RandomAccessFile.close",
        "FileChannelImpl.implCloseChannel",
        "Socket.close",
        "AbstractSelectableChannel.implCloseChannel",
        "FileChannelImpl.setUninterruptible"
    };

    /** The kind each hook closes, 0 for the setUninterruptible hook. */
    static final int[] HOOK_KINDS = {
        KIND_FILE_INPUT_STREAM,
        KIND_FILE_OUTPUT_STREAM,
        KIND_RANDOM_ACCESS_FILE,
        KIND_FILE_CHANNEL,
        KIND_SOCKET,
        KIND_SOCKET_CHANNEL,
        0
    };

    /** What a resource's own state says. */
    static final int STATE_OPEN = 0;

    static final int STATE_CLOSED = 1;
    static final int STATE_UNKNOWN = 2;

    /** How long after its end a request's resources are checked: one closed as the response completes is not. */
    static final long GRACE_NANOS = 250_000_000L;

    static final ResourceTracker TRACKER = new ResourceTracker();

    /** The kinds whose close hook passed its self-test: only they are tracked. */
    static volatile int kinds;

    static volatile Thread selfTestThread;

    /** The kinds whose close a hook missed in {@link #noReclaimsGeneration}: their reclaims are not reported. */
    private static volatile int noReclaims;

    private static volatile long noReclaimsGeneration = Long.MIN_VALUE;

    static final LongAdder[] SELF_TEST_HITS = adders(HOOKS.length);
    /** Per close hook, the closes of a tracked resource; for the hand-off hook, the channels handed over. */
    private static final LongAdder[] CLOSES = adders(HOOKS.length);

    private static final LongAdder TRACKED = new LongAdder();
    private static final LongAdder LEFT_OPEN = new LongAdder();
    private static final LongAdder CLOSED_LATE = new LongAdder();
    private static final LongAdder RECLAIMED = new LongAdder();
    private static final LongAdder UNOWNED = new LongAdder();
    private static final LongAdder NO_APPLICATION_FRAME = new LongAdder();
    private static final LongAdder NOT_JDK_CLASS = new LongAdder();
    private static final LongAdder KIND_OFF = new LongAdder();
    private static final LongAdder STASHED = new LongAdder();

    private Resources() {}

    // ---- hooks -------------------------------------------------------------------------------------------------

    /**
     * A close hook's exit, normal or not, {@code hook} saying which: the resource's entry, if any, is marked closed. On
     * the self-test's thread, counted and nothing else. Lock-free; never throws.
     */
    public static void closed(Object resource, int hook) {
        try {
            Thread self = selfTestThread;
            if (self != null && self == Thread.currentThread()) {
                SELF_TEST_HITS[hook].increment();
                return;
            }
            int kind = HOOK_KINDS[hook];
            if (resource != null && TRACKER.tracking(kind) && TRACKER.closed(resource, kind)) {
                CLOSES[hook].increment();
            }
        } catch (Throwable ex) {
            SideEffects.failed(SideEffects.SENSOR_RESOURCES, ex);
        }
    }

    /**
     * {@code FileChannelImpl.setUninterruptible()} entry: the channel a provider's {@code newInputStream} or {@code
     * newOutputStream} opened, handed to the files hook open on the thread, which tracks it at its exit. Never throws.
     */
    public static void uninterruptible(Object channel) {
        try {
            Thread self = selfTestThread;
            if (self != null && self == Thread.currentThread()) {
                SELF_TEST_HITS[HOOK_UNINTERRUPTIBLE].increment();
                return;
            }
            if ((SideEffects.mask & SideEffects.MASK_RESOURCES) == 0) {
                return;
            }
            CodePaths.Frame frame = CodePaths.FRAME.get();
            if (frame != null && (frame.sideEffectOpen & SideEffects.MASK_FILES) != 0) {
                frame.resourcePending = channel;
                STASHED.increment();
                CLOSES[HOOK_UNINTERRUPTIBLE].increment();
            }
        } catch (Throwable ex) {
            SideEffects.failed(SideEffects.SENSOR_RESOURCES, ex);
        }
    }

    /**
     * A {@code files} or {@code network} hook recorded opening {@code resource} of {@code kind}, with the record's
     * owner, target, stamp, and frames, and {@code found} the summary's {@link SideEffects#FOUND_APPLICATION} and {@link
     * SideEffects#FOUND_OUTSIDE_APPLICATION} bits: tracked when it qualifies. Never throws.
     */
    static void opened(
            Claim claim,
            SideEffects.Owner owner,
            Object resource,
            int kind,
            int target,
            long stamp,
            long frames,
            int found) {
        try {
            if ((SideEffects.mask & SideEffects.MASK_RESOURCES) == 0 || resource == null || claim == null) {
                return;
            }
            if ((kinds & (1 << kind)) == 0) {
                KIND_OFF.increment();
                return;
            }
            if (owner.request == 0L && owner.execution == 0L) {
                UNOWNED.increment();
                return;
            }
            if ((found & SideEffects.FOUND_APPLICATION) == 0) {
                NO_APPLICATION_FRAME.increment();
                return;
            }
            if (!exactClass(resource, kind)) {
                NOT_JDK_CLASS.increment();
                return;
            }
            int origin = (found & SideEffects.FOUND_OUTSIDE_APPLICATION) != 0 ? ORIGIN_APPLICATION : ORIGIN_LIBRARY;
            ResourceTracker.Entry entry = TRACKER.track(
                    resource,
                    kind,
                    claim.generation,
                    owner.request,
                    owner.execution,
                    owner.executionKind,
                    owner.threadKind,
                    owner.threadName,
                    target,
                    stamp,
                    frames,
                    origin | (kind << 4),
                    System.currentTimeMillis());
            if (entry != null) {
                TRACKED.increment();
            }
        } catch (Throwable ex) {
            SideEffects.failed(SideEffects.SENSOR_RESOURCES, ex);
        }
    }

    /** The resource kind of a files hook's open, 0 when the hook opens none. */
    static int fileKind(int hook) {
        switch (hook) {
            case SideEffects.HOOK_FILE_INPUT_STREAM:
                return KIND_FILE_INPUT_STREAM;
            case SideEffects.HOOK_FILE_OUTPUT_STREAM:
                return KIND_FILE_OUTPUT_STREAM;
            case SideEffects.HOOK_RANDOM_ACCESS_FILE:
                return KIND_RANDOM_ACCESS_FILE;
            case SideEffects.HOOK_NEW_BYTE_CHANNEL:
            case SideEffects.HOOK_FILE_CHANNEL:
            case SideEffects.HOOK_NEW_INPUT_STREAM:
            case SideEffects.HOOK_NEW_OUTPUT_STREAM:
                return KIND_FILE_CHANNEL;
            default:
                return 0;
        }
    }

    /** The resource kind of a connect's resource, 0 for none. */
    static int networkKind(Object resource) {
        if (resource instanceof Socket) {
            return KIND_SOCKET;
        }
        return resource instanceof java.nio.channels.SocketChannel ? KIND_SOCKET_CHANNEL : 0;
    }

    /**
     * Whether every close of {@code resource} reaches a hook: any {@code FileInputStream}, {@code FileOutputStream}, or
     * {@code RandomAccessFile}, whose descriptor only their own {@code close()} releases; channels and sockets of the
     * JDK's exact classes only, never a custom file system provider's channel or a {@code Socket} subclass, which may
     * close their descriptor elsewhere.
     */
    static boolean exactClass(Object resource, int kind) {
        String type = resource.getClass().getName();
        switch (kind) {
            case KIND_FILE_INPUT_STREAM:
                return resource instanceof FileInputStream;
            case KIND_FILE_OUTPUT_STREAM:
                return resource instanceof FileOutputStream;
            case KIND_RANDOM_ACCESS_FILE:
                return resource instanceof RandomAccessFile;
            case KIND_FILE_CHANNEL:
                return "sun.nio.ch.FileChannelImpl".equals(type);
            case KIND_SOCKET:
                return resource.getClass() == Socket.class || "sun.security.ssl.SSLSocketImpl".equals(type);
            case KIND_SOCKET_CHANNEL:
                return "sun.nio.ch.SocketChannelImpl".equals(type);
            default:
                return false;
        }
    }

    /**
     * What {@code resource}'s own state says, through the JDK's final methods only, never application code: a stream's
     * descriptor, a channel's {@code isOpen()}, a plain socket's {@code isClosed()}; {@link #STATE_UNKNOWN} for a TLS
     * socket, whose {@code isClosed()} reads its TLS state. Never throws.
     */
    static int closedNow(Object resource, int kind) {
        try {
            switch (kind) {
                case KIND_FILE_INPUT_STREAM:
                    return ((FileInputStream) resource).getFD().valid() ? STATE_OPEN : STATE_CLOSED;
                case KIND_FILE_OUTPUT_STREAM:
                    return ((FileOutputStream) resource).getFD().valid() ? STATE_OPEN : STATE_CLOSED;
                case KIND_RANDOM_ACCESS_FILE:
                    return ((RandomAccessFile) resource).getFD().valid() ? STATE_OPEN : STATE_CLOSED;
                case KIND_FILE_CHANNEL:
                case KIND_SOCKET_CHANNEL:
                    return ((Channel) resource).isOpen() ? STATE_OPEN : STATE_CLOSED;
                case KIND_SOCKET:
                    if (resource.getClass() != Socket.class) {
                        return STATE_UNKNOWN;
                    }
                    return ((Socket) resource).isClosed() ? STATE_CLOSED : STATE_OPEN;
                default:
                    return STATE_UNKNOWN;
            }
        } catch (IOException ex) {
            // No descriptor: never opened, or closed.
            return STATE_CLOSED;
        } catch (Throwable ex) {
            return STATE_UNKNOWN;
        }
    }

    // ---- request ends and the drain thread ---------------------------------------------------------------------

    /**
     * A request of claim generation {@code requested} ended, its response complete: noted without a lock, while the
     * sensor records, for the drain thread. Fed by the same adapter calls as the thread-activity sensor's ({@link
     * ThreadActivity#requestEnded}), whichever of the two is on. Never throws.
     */
    static void requestEnded(long requested, long request) {
        try {
            if ((SideEffects.mask & SideEffects.MASK_RESOURCES) == 0
                    || requested != SideEffects.generation()
                    || request == 0L) {
                return;
            }
            TRACKER.ended(request);
        } catch (Throwable ex) {
            SideEffects.failed(SideEffects.SENSOR_RESOURCES, ex);
        }
    }

    /** On the drain thread, before it drains: what requests left open, what was reclaimed, what closed late. */
    static void sweep(Claim claim) {
        try {
            if (claim == null || claim.generation != SideEffects.generation() || TRACKER.size() == 0) {
                return;
            }
            if (kinds == 0 || (SideEffects.mask & SideEffects.MASK_RESOURCES) == 0) {
                // Switched off: its close hooks may be gone, so nothing it still holds is ever reported.
                TRACKER.clear();
                return;
            }
            if (noReclaimsGeneration != claim.generation) {
                noReclaims = 0;
                noReclaimsGeneration = claim.generation;
            }
            List<ResourceTracker.Entry> reports = new ArrayList<ResourceTracker.Entry>();
            int missed = TRACKER.sweep(claim.generation, System.nanoTime(), GRACE_NANOS, noReclaims, reports);
            if (missed != 0) {
                noReclaims |= missed;
                AgentBridge.message("the BootUI agent's resources sensor saw a close its hooks missed: reclaims of"
                        + " those resources are no longer reported in this run");
            }
            publish(reports);
        } catch (Throwable ex) {
            SideEffects.failed(SideEffects.SENSOR_RESOURCES, ex);
        }
    }

    private static void publish(List<ResourceTracker.Entry> reports) {
        for (int i = 0; i < reports.size(); i++) {
            ResourceTracker.Entry entry = reports.get(i);
            int kind;
            switch (entry.reported) {
                case ResourceTracker.LEFT_OPEN:
                    kind = SideEffects.KIND_RESOURCE_LEFT_OPEN;
                    LEFT_OPEN.increment();
                    break;
                case ResourceTracker.CLOSED_LATE:
                    kind = SideEffects.KIND_RESOURCE_CLOSED_LATE;
                    CLOSED_LATE.increment();
                    break;
                default:
                    kind = SideEffects.KIND_RESOURCE_RECLAIMED;
                    RECLAIMED.increment();
                    break;
            }
            long nanos = Math.max(0L, entry.reportedNanos - entry.createdNanos);
            int detail = entry.detail | (entry.first ? DETAIL_FIRST : 0);
            long[] values = new long[SideEffects.RECORD];
            values[SideEffects.R_SENSOR] = SideEffects.SENSOR_RESOURCES;
            values[SideEffects.R_KIND] = kind;
            values[SideEffects.R_GENERATION] = entry.generation;
            // The open's time: the engine attributes a report as it would have attributed the open.
            values[SideEffects.R_FIRST_MILLIS] = entry.createdMillis;
            values[SideEffects.R_LAST_MILLIS] = System.currentTimeMillis();
            values[SideEffects.R_REQUEST] = entry.request;
            values[SideEffects.R_EXECUTION] = entry.execution;
            values[SideEffects.R_STAMP] = entry.stamp;
            values[SideEffects.R_TARGET] = entry.target;
            values[SideEffects.R_FLAGS] = (SideEffects.OUTCOME_DONE & 0xFFL)
                    | ((long) (entry.threadKind & 0xF) << 8)
                    | ((long) (entry.executionKind & 0xF) << 12)
                    | ((long) (entry.threadName & 0xFFFF) << 16)
                    | ((long) detail << 32);
            values[SideEffects.R_COUNT] = 1L;
            values[SideEffects.R_NANOS] = nanos;
            values[SideEffects.R_MAX_NANOS] = nanos;
            values[SideEffects.R_FRAMES] = entry.frames;
            SideEffects.publish(SideEffects.SENSOR_RESOURCES, values);
        }
    }

    // ---- lifecycle and status -----------------------------------------------------------------------------------

    /** The agent tracks the kinds of {@code bits} ({@code 1 << KIND_*}) once their close hooks passed their self-test. */
    public static void enableKinds(int bits) {
        kinds = bits & ALL_KINDS;
        TRACKER.accept(kinds != 0);
    }

    /** Starts the self-test on the calling thread: hooks it runs are counted per hook, and record nothing. */
    public static void beginSelfTest() {
        for (LongAdder hits : SELF_TEST_HITS) {
            hits.reset();
        }
        selfTestThread = Thread.currentThread();
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

    /** Loads and links what the hooks use, on the agent's own thread, before any is installed. */
    public static void warm() {
        try {
            TRACKER.size();
            TRACKER.tracking(KIND_SOCKET);
            Object probe = new Object();
            TRACKER.closed(probe, KIND_SOCKET);
            exactClass(probe, KIND_FILE_CHANNEL);
            closedNow(probe, 0);
            fileKind(0);
            networkKind(probe);
            new ResourceTracker.Entry(null, null, 1, 0L, 0L, 0L, 0, 0, 0, 0, 0L, 0L, 0, 0L, 0L)
                    .report(0, 0L, false)
                    .getClass();
            new ArrayList<ResourceTracker.Entry>(0).size();
        } catch (Throwable ex) {
            AgentBridge.error(ex);
        }
    }

    /** The sensor was disabled or released: nothing is kept past its life. */
    static void disabled() {
        TRACKER.clear();
    }

    /** What each hook recorded: closes of a tracked resource, and channels handed over. */
    static void putRecorded(Map<String, Object> recorded) {
        for (int i = 0; i < HOOKS.length; i++) {
            recorded.put(HOOKS[i], Long.valueOf(CLOSES[i].sum()));
        }
    }

    static void putStatus(Map<String, Object> map) {
        map.put("kinds", Integer.valueOf(kinds));
        map.put("tracked", Long.valueOf(TRACKED.sum()));
        map.put("tracking", Integer.valueOf(TRACKER.size()));
        map.put("waitingForRequestEnd", Integer.valueOf(TRACKER.waitingCount()));
        map.put("untracked", Long.valueOf(TRACKER.untracked.sum()));
        map.put("unowned", Long.valueOf(UNOWNED.sum()));
        map.put("noApplicationFrame", Long.valueOf(NO_APPLICATION_FRAME.sum()));
        map.put("notJdkClass", Long.valueOf(NOT_JDK_CLASS.sum()));
        map.put("kindOff", Long.valueOf(KIND_OFF.sum()));
        map.put("streamChannels", Long.valueOf(STASHED.sum()));
        map.put("duplicates", Long.valueOf(TRACKER.duplicates.sum()));
        map.put("closedBeforeTracked", Long.valueOf(TRACKER.closedBeforeTracked.sum()));
        map.put("requestEndsChecked", Long.valueOf(TRACKER.endsChecked.sum()));
        map.put("requestEndsLost", Long.valueOf(TRACKER.endsLost.sum()));
        map.put("unresolved", Long.valueOf(TRACKER.unresolved.sum()));
        map.put("evicted", Long.valueOf(TRACKER.evicted.sum()));
        map.put("closeMissed", Long.valueOf(TRACKER.closeMissed.sum()));
        map.put("reclaimsSuppressedKinds", Integer.valueOf(noReclaims));
        map.put("dropped", Long.valueOf(TRACKER.dropped.sum()));
        map.put("leftOpen", Long.valueOf(LEFT_OPEN.sum()));
        map.put("closedLate", Long.valueOf(CLOSED_LATE.sum()));
        map.put("reclaimed", Long.valueOf(RECLAIMED.sum()));
        map.put("filesSensorOn", Boolean.valueOf((SideEffects.claimedBits & SideEffects.MASK_FILES) != 0));
    }

    /** Tests only: forgets everything. */
    static void reset() {
        TRACKER.clear();
        TRACKER.untracked.reset();
        TRACKER.duplicates.reset();
        TRACKER.closedBeforeTracked.reset();
        TRACKER.unresolved.reset();
        TRACKER.endsLost.reset();
        TRACKER.endsChecked.reset();
        TRACKER.evicted.reset();
        TRACKER.closeMissed.reset();
        TRACKER.dropped.reset();
        kinds = 0;
        selfTestThread = null;
        noReclaims = 0;
        noReclaimsGeneration = Long.MIN_VALUE;
        for (LongAdder adder : new LongAdder[] {
            TRACKED, LEFT_OPEN, CLOSED_LATE, RECLAIMED, UNOWNED, NO_APPLICATION_FRAME, NOT_JDK_CLASS, KIND_OFF, STASHED
        }) {
            adder.reset();
        }
        for (LongAdder hits : SELF_TEST_HITS) {
            hits.reset();
        }
        for (LongAdder closes : CLOSES) {
            closes.reset();
        }
    }

    private static LongAdder[] adders(int count) {
        LongAdder[] adders = new LongAdder[count];
        for (int i = 0; i < count; i++) {
            adders[i] = new LongAdder();
        }
        return adders;
    }
}
