package io.github.jdubois.bootui.agent.bridge;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Method probes' bridge side (PLAN-v2 §5.14, M5-8): user-triggered, bounded recordings of one application method's next
 * invocations: each invocation's duration, thread kind, request id, outcome (returned, or the thrown exception's type
 * name), and calling frame; never an argument or a return value. A probe started with {@code shapes} also records the
 * <b>shapes</b> of its first {@value #MAX_SHAPED_ARGUMENTS} arguments, at entry, and of its return value
 * ({@link ProbeShapes}, D37, D44): class names and, for an allowlist of JDK types, a size, a length, a presence, or an
 * enum constant's name, read without running any application code; never the values themselves.
 *
 * <p><b>Bounds.</b> A probe records at most {@value #MAX_INVOCATIONS} invocations, during at most
 * {@value #MAX_WINDOW_MILLIS} ms from the moment the agent activates it, and at most {@value #SLOTS} probes exist at
 * once. The bounds are enforced here, in the advice's calls, by the probe's state, a counter, and a deadline: they hold
 * whether or not the agent manages to remove the advice afterwards. A request may ask for less, never more. A probe ends
 * on its invocations, its window, an explicit stop, or the end of the run that started it (a new claim generation, a
 * disarm, or a release), and its advice is then a no-op at once, before the agent removes it.
 *
 * <p><b>Advice.</b> The agent inlines {@code long started = MethodProbes.enter(slot, id)} at the probed method's entry
 * and {@code MethodProbes.exit(slot, id, started, thrown)} at its exits, the slot and the probe id bound as constants.
 * Ids are never reused, so a slot reused by a later probe, or advice the agent failed to remove, records nothing for a
 * probe that ended. Entry allocates nothing; exit, at most {@value #MAX_INVOCATIONS} times per probe, captures the
 * request id through the claim's capture under the re-entrancy guard, walks at most {@value #MAX_FRAMES} frames for the
 * calling frame, and publishes one record on the transport ring ({@link AgentRing#SENSOR_METHOD_PROBES}).
 *
 * <p><b>Shapes.</b> A probe started with shapes is installed with another advice, which passes its arguments to
 * {@link #arguments} and its return value to {@link #exit(int, long, long, Throwable, Object)} only once {@link #enter}
 * returned a start, so that advice allocates its argument array and boxes only for a recorded invocation. {@link #enter}
 * then carries the invocation's index in the low five bits of the start; each shapes record ({@link #PROBE_SHAPES}) and
 * the invocation's record carry that index, so the engine joins them. At most four shapes records an invocation.
 *
 * <p><b>Slots.</b> A slot is freed when the agent reports the probe removed or failed, and also, without the agent, when
 * a probe stays starting or ending longer than {@value #STUCK_MILLIS} ms, so a stuck or dead agent thread can never
 * hold slots for the JVM's life. JDK types and this package's classes only; every entry point catches everything.
 */
public final class MethodProbes {

    /** The id of method probes in the ring's sensor names and in status. */
    public static final String SENSOR = "method-probes";

    /** Probes at once. */
    public static final int SLOTS = 5;

    /** Invocations a probe records at most. */
    public static final int MAX_INVOCATIONS = 20;

    /** A probe's longest window, from its activation. */
    public static final long MAX_WINDOW_MILLIS = 60_000L;

    /** Ended probes kept for the engine to read their final state. */
    public static final int HISTORY = 16;

    /** Frames walked at most for the calling frame. */
    public static final int MAX_FRAMES = 32;

    /** How long a probe may stay starting, or ending, before the bridge frees its slot itself. */
    public static final long STUCK_MILLIS = 10_000L;

    /** The ring record type of one recorded invocation. */
    public static final int PROBE_HIT = 1;

    /**
     * The ring record type of up to three shapes of one invocation: {@code a} is {@code id << 8 | index << 3 | part},
     * parts 0 to 2 holding arguments {@code 3 * part} to {@code 3 * part + 2} in {@code b}, {@code c}, and {@code d},
     * part {@value #RETURN_PART} the return value's shape in {@code b}.
     */
    public static final int PROBE_SHAPES = 2;

    /** The shapes record part of a return value. */
    public static final int RETURN_PART = 3;

    /** Arguments whose shapes a probe records at most: the first ones, in three records. */
    public static final int MAX_SHAPED_ARGUMENTS = 9;

    /** Whether this bridge records shapes, for the engine to read before starting a probe with them. */
    public static final int SHAPES_PROTOCOL = 1;

    /** A shapes probe's invocation index, in the low bits of what {@link #enter} returns. */
    static final long INDEX_MASK = 31L;

    /** Probe states. */
    public static final int STARTING = 0;

    public static final int ACTIVE = 1;
    public static final int ENDING = 2;
    public static final int ENDED = 3;
    public static final int FAILED = 4;

    static final String[] STATE_NAMES = {"starting", "active", "ending", "ended", "failed"};

    /** Record payload: the outcome bits, above the thread kind's. */
    public static final int OUTCOME_RETURNED = 0;

    public static final int OUTCOME_THREW = 1;

    /** Record payload: the thread kind. */
    public static final int THREAD_PLATFORM = 1;

    public static final int THREAD_VIRTUAL = 2;

    /** End reasons. */
    public static final String END_INVOCATIONS = "invocations";

    public static final String END_WINDOW = "window";
    public static final String END_STOPPED = "stopped";
    public static final String END_RUN = "run-ended";

    /** Answers to the engine. */
    public static final String STARTED = "started";

    public static final String REFUSED = "refused";
    public static final String INVALID = "invalid";

    private static final String BRIDGE_PACKAGE = "io.github.jdubois.bootui.agent.bridge.";

    private static final AtomicReferenceArray<Probe> SLOT = new AtomicReferenceArray<Probe>(SLOTS);
    private static final AtomicReferenceArray<Probe> ENDED_PROBES = new AtomicReferenceArray<Probe>(HISTORY);
    private static final AtomicLong ENDED_COUNT = new AtomicLong();
    private static final AtomicLong IDS = new AtomicLong();
    private static final AtomicBoolean PLACING = new AtomicBoolean();

    private static final LongAdder STARTS = new LongAdder();
    private static final LongAdder REFUSALS = new LongAdder();
    private static final LongAdder HITS = new LongAdder();
    private static final LongAdder DROPPED = new LongAdder();
    private static final LongAdder REAPED = new LongAdder();
    /** Every call of an installed probe's entry advice, recorded or not: proves the advice is gone once removed. */
    private static final LongAdder ADVICE_CALLS = new LongAdder();

    /** Tests shorten it. */
    static volatile long stuckNanos = STUCK_MILLIS * 1_000_000L;

    private MethodProbes() {}

    // ---- advice ----------------------------------------------------------------------------------------------------

    /**
     * Called at the probed method's entry: the invocation's start in nanoseconds, or 0 when it is not recorded (the probe
     * is not active, its bound is reached, or the thread is doing BootUI's own work or a capture). Allocates nothing.
     * Never throws.
     */
    public static long enter(int slot, long id) {
        try {
            ADVICE_CALLS.increment();
            if (slot < 0 || slot >= SLOTS) {
                return 0L;
            }
            Probe probe = SLOT.get(slot);
            if (probe == null || probe.id != id || probe.state.get() != ACTIVE) {
                return 0L;
            }
            if (Reentrancy.guarded() || Reentrancy.bootUiWork()) {
                return 0L;
            }
            long now = System.nanoTime();
            if (now - probe.deadlineNanos >= 0L) {
                end(probe, END_WINDOW);
                return 0L;
            }
            int entered = probe.entered.incrementAndGet();
            if (entered > probe.max) {
                end(probe, END_INVOCATIONS);
                return 0L;
            }
            if (probe.shapes) {
                // The invocation's index in the low bits, at the cost of at most 31 ns of its duration.
                long started = (now & ~INDEX_MASK) | (entered - 1);
                return started == 0L ? INDEX_MASK + 1L : started;
            }
            return now == 0L ? 1L : now;
        } catch (Throwable ex) {
            AgentBridge.error(ex);
            return 0L;
        }
    }

    /**
     * Called at every exit of the probed method with what {@link #enter} returned: records the invocation, even when its
     * probe ended meanwhile, since it started within the bound. Never throws.
     */
    public static void exit(int slot, long id, long started, Throwable thrown) {
        record(slot, id, started, thrown, null, false);
    }

    /**
     * Called at every exit of a probed method whose probe records shapes, with what {@link #enter} returned and the
     * returned value ({@code null} when it threw or returns nothing): records the return value's shape, then the
     * invocation. Never throws.
     */
    public static void exit(int slot, long id, long started, Throwable thrown, Object returned) {
        record(slot, id, started, thrown, returned, true);
    }

    /**
     * Called at the entry of a probed method whose probe records shapes, once {@link #enter} returned {@code started},
     * not 0: publishes the shapes of its first {@value #MAX_SHAPED_ARGUMENTS} arguments. Never throws.
     */
    public static void arguments(int slot, long id, long started, Object[] arguments) {
        if (started == 0L || arguments == null || arguments.length == 0) {
            return;
        }
        try {
            Probe probe = slot(slot, id);
            if (probe == null || !probe.shapes) {
                return;
            }
            long index = started & INDEX_MASK;
            int count = Math.min(arguments.length, MAX_SHAPED_ARGUMENTS);
            for (int part = 0; part * 3 < count; part++) {
                int first = part * 3;
                publishShapes(
                        probe,
                        index,
                        part,
                        ProbeShapes.shape(arguments[first]),
                        first + 1 < count ? ProbeShapes.shape(arguments[first + 1]) : ProbeShapes.ABSENT,
                        first + 2 < count ? ProbeShapes.shape(arguments[first + 2]) : ProbeShapes.ABSENT);
            }
        } catch (Throwable ex) {
            AgentBridge.error(ex);
        }
    }

    private static void publishShapes(Probe probe, long index, int part, long first, long second, long third) {
        boolean published = AgentRing.publish(
                AgentRing.SENSOR_METHOD_PROBES,
                PROBE_SHAPES,
                probe.generation,
                System.currentTimeMillis(),
                (probe.id << 8) | (index << 3) | part,
                first,
                second,
                third);
        if (!published) {
            probe.shapesDropped.incrementAndGet();
        }
    }

    private static void record(int slot, long id, long started, Throwable thrown, Object returned, boolean withReturn) {
        if (started == 0L) {
            return;
        }
        try {
            long now = System.nanoTime();
            Probe probe = find(slot, id);
            if (probe == null) {
                return;
            }
            long index = probe.shapes ? started & INDEX_MASK : 0L;
            long duration = Math.max(0L, now - (probe.shapes ? started & ~INDEX_MASK : started));
            if (probe.shapes && withReturn && thrown == null && !returnsVoid(probe)) {
                publishShapes(
                        probe, index, RETURN_PART, ProbeShapes.shape(returned), ProbeShapes.ABSENT, ProbeShapes.ABSENT);
            }
            int kind = Thread.currentThread().getClass().getName().endsWith("VirtualThread")
                    ? THREAD_VIRTUAL
                    : THREAD_PLATFORM;
            int outcome = thrown == null ? OUTCOME_RETURNED : OUTCOME_THREW;
            long request = requestId(probe);
            int exception =
                    thrown == null ? 0 : AgentRing.intern(thrown.getClass().getName());
            int caller = AgentRing.intern(caller(probe));
            boolean published = AgentRing.publish(
                    AgentRing.SENSOR_METHOD_PROBES,
                    PROBE_HIT,
                    probe.generation,
                    System.currentTimeMillis(),
                    (probe.id << 8) | (index << 3) | ((long) outcome << 2) | kind,
                    duration,
                    request,
                    ((long) caller << 32) | (exception & 0xFFFFFFFFL));
            if (published) {
                probe.recorded.incrementAndGet();
                HITS.increment();
            } else {
                probe.dropped.incrementAndGet();
                DROPPED.increment();
            }
            if (probe.recorded.get() + probe.dropped.get() >= probe.max) {
                end(probe, END_INVOCATIONS);
            }
        } catch (Throwable ex) {
            AgentBridge.error(ex);
        }
    }

    /** The request id's bits through the run's capture, under the re-entrancy guard; 0 when unknown. */
    private static long requestId(Probe probe) {
        if (!Reentrancy.enter()) {
            return 0L;
        }
        try {
            Claim claim = AgentBridge.current();
            if (claim == null || !claim.armed || claim.generation != probe.generation) {
                return 0L;
            }
            Supplier<Object> capture = claim.capture.get();
            Object payload = capture == null ? null : capture.get();
            if (payload instanceof Object[]) {
                Object[] values = (Object[]) payload;
                if (values.length > 0 && values[0] instanceof String) {
                    return CodeInventory.parseRequestId((String) values[0]);
                }
            }
            return 0L;
        } finally {
            Reentrancy.exit();
        }
    }

    /**
     * The calling frame, {@code class#method:line}: past the probed method's frame, the first frame of a class in the
     * claimed packages that the agent never excludes (so a proxy, an interceptor, or reflection between the caller and
     * the method is skipped), else the frame right above the probed method; {@code null} when neither is found within
     * {@value #MAX_FRAMES} frames.
     */
    static String caller(Probe probe) {
        try {
            return StackWalker.getInstance().walk(new CallerWalk(probe));
        } catch (Throwable ex) {
            return null;
        }
    }

    /** Walks the frames for {@link #caller}. */
    static final class CallerWalk implements Function<Stream<StackWalker.StackFrame>, String> {

        private final Probe probe;

        CallerWalk(Probe probe) {
            this.probe = probe;
        }

        @Override
        public String apply(Stream<StackWalker.StackFrame> frames) {
            Iterator<StackWalker.StackFrame> iterator = frames.limit(MAX_FRAMES).iterator();
            boolean past = false;
            String immediate = null;
            while (iterator.hasNext()) {
                StackWalker.StackFrame frame = iterator.next();
                String type = frame.getClassName();
                if (type.startsWith(BRIDGE_PACKAGE)) {
                    continue;
                }
                if (!past) {
                    past = type.equals(probe.className) && frame.getMethodName().equals(probe.methodName);
                    continue;
                }
                if (immediate == null) {
                    immediate = describe(frame);
                }
                if (Claim.startsWithAny(type, probe.packagePrefixes) && !Exclusions.excluded(type)) {
                    return describe(frame);
                }
            }
            return immediate;
        }

        private static String describe(StackWalker.StackFrame frame) {
            int line = frame.getLineNumber();
            String text = frame.getClassName() + "#" + frame.getMethodName();
            return line > 0 ? text + ":" + line : text;
        }
    }

    // ---- the engine ------------------------------------------------------------------------------------------------

    /**
     * Starts a probe for the claim {@code token}: {@code method} is {@code binary.Class#name} with, for an overloaded
     * method, its descriptor ({@code binary.Class#name(I)J}); {@code maxInvocations} and {@code windowMillis} may lower
     * the bounds, and {@code shapes} ({@code Boolean.TRUE}) asks for argument and return shapes. The answer's {@code status} is {@value #STARTED} with the {@code probe}, or {@value #INVALID},
     * {@value #REFUSED}, {@code stale}, {@code unavailable}, or {@code failed} with a {@code reason}. The probe starts
     * {@code starting}: the agent installs it off the caller's thread. Never throws.
     */
    public static Map<String, Object> start(long token, Map<String, ?> request) {
        try {
            Claim claim = AgentBridge.current();
            if (claim == null || claim.token != token || !claim.armed) {
                return answer(AgentBridge.STALE, "this claim was replaced or ended", null);
            }
            if (!AgentBridge.attached()) {
                return answer(AgentBridge.UNAVAILABLE, "the BootUI agent did not start", null);
            }
            Object method = request == null ? null : request.get("method");
            String key = method == null ? "" : String.valueOf(method).trim();
            String problem = invalid(key);
            if (problem != null) {
                REFUSALS.increment();
                return answer(INVALID, problem, null);
            }
            int hash = key.indexOf('#');
            int open = key.indexOf('(', hash);
            String className = key.substring(0, hash);
            String methodName = open < 0 ? key.substring(hash + 1) : key.substring(hash + 1, open);
            String descriptor = open < 0 ? null : key.substring(open);
            if (!inPackages(className, claim.packages)) {
                REFUSALS.increment();
                return answer(INVALID, className + " is not in the application's packages " + claim.packages, null);
            }
            if (Exclusions.excluded(className)) {
                REFUSALS.increment();
                return answer(INVALID, "the BootUI agent never instruments " + className, null);
            }
            if (!CodeInventory.knows(className, methodName, descriptor)) {
                REFUSALS.increment();
                return answer(
                        INVALID,
                        "neither Code Inventory nor Code Paths knows " + key
                                + ": a probe takes a method the agent instrumented, so run code that loads its class first",
                        null);
            }
            int max = bound(request.get("maxInvocations"), MAX_INVOCATIONS);
            boolean shapes = Boolean.TRUE.equals(request.get("shapes"));
            long windowMillis = bound(request.get("windowMillis"), MAX_WINDOW_MILLIS);
            Probe probe;
            String refusal = null;
            if (!lock()) {
                REFUSALS.increment();
                return answer(REFUSED, "another probe is being started; try again", null);
            }
            try {
                reap(System.nanoTime());
                probe = null;
                int free = -1;
                for (int i = 0; i < SLOTS; i++) {
                    Probe current = SLOT.get(i);
                    if (current == null) {
                        if (free < 0) {
                            free = i;
                        }
                    } else if (current.className.equals(className)
                            && current.methodName.equals(methodName)
                            && sameOverload(current, descriptor)
                            && current.generation == claim.generation
                            && current.state.get() <= ACTIVE) {
                        refusal = "probe " + current.id + " is already probing " + key;
                    }
                }
                if (refusal == null && free < 0) {
                    refusal = "five probes are running: stop one, or wait for one to end";
                }
                if (refusal == null) {
                    probe = new Probe(
                            IDS.incrementAndGet(),
                            free,
                            claim.generation,
                            key,
                            className,
                            methodName,
                            descriptor,
                            max,
                            windowMillis,
                            prefixes(claim.packages),
                            shapes);
                    SLOT.set(free, probe);
                }
            } finally {
                PLACING.set(false);
            }
            if (refusal != null) {
                REFUSALS.increment();
                return answer(REFUSED, refusal, null);
            }
            STARTS.increment();
            // The ring and the intern table, for a claim that did not ask for the inventory sensor.
            AgentRing.newGeneration(claim.generation, claim.ringCapacity);
            Map<String, Object> call = new LinkedHashMap<String, Object>();
            call.put("op", "method-probe");
            call.put("generation", Long.valueOf(claim.generation));
            call.put("slot", Integer.valueOf(probe.slot));
            call.put("id", Long.valueOf(probe.id));
            call.put("className", className);
            call.put("methodName", methodName);
            call.put("descriptor", descriptor);
            call.put("shapes", Boolean.valueOf(shapes));
            Map<String, Object> agent = AgentBridge.callAgent(call);
            if (agent == null || !"ok".equals(agent.get("status"))) {
                String reason = agent == null
                        ? "the BootUI agent failed to accept the probe"
                        : String.valueOf(agent.get("reason"));
                failed(probe.slot, probe.id, reason);
                return answer(AgentBridge.FAILED, reason, probe.describe());
            }
            return answer(STARTED, null, probe.describe());
        } catch (Throwable ex) {
            AgentBridge.error(ex);
            return answer(AgentBridge.FAILED, "the bridge failed to start the probe: " + ex, null);
        }
    }

    /**
     * Stops the probe {@code id} for the claim {@code token}: it ends at once ({@value #END_STOPPED}) and the agent
     * removes its advice. The answer's {@code status} is {@code stopped} with the {@code probe}, {@code unknown}, or
     * {@code stale}. Never throws.
     */
    public static Map<String, Object> stop(long token, long id) {
        try {
            Claim claim = AgentBridge.current();
            if (claim == null || claim.token != token) {
                return answer(AgentBridge.STALE, "this claim was replaced or ended", null);
            }
            for (int i = 0; i < SLOTS; i++) {
                Probe probe = SLOT.get(i);
                if (probe != null && probe.id == id) {
                    end(probe, END_STOPPED);
                    return answer("stopped", null, probe.describe());
                }
            }
            Probe ended = find(-1, id);
            if (ended != null) {
                return answer("stopped", null, ended.describe());
            }
            return answer("unknown", "no probe " + id, null);
        } catch (Throwable ex) {
            AgentBridge.error(ex);
            return answer(AgentBridge.FAILED, "the bridge failed to stop the probe: " + ex, null);
        }
    }

    /** Every probe in a slot, then the ended ones kept, newest first: copies of JDK types. Never throws. */
    public static List<Map<String, Object>> list() {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        try {
            reap(System.nanoTime());
            List<Long> seen = new ArrayList<Long>();
            for (int i = 0; i < SLOTS; i++) {
                Probe probe = SLOT.get(i);
                if (probe != null) {
                    seen.add(Long.valueOf(probe.id));
                    list.add(probe.describe());
                }
            }
            long count = ENDED_COUNT.get();
            for (long n = count - 1; n >= 0 && n >= count - HISTORY; n--) {
                Probe probe = ENDED_PROBES.get((int) (n % HISTORY));
                if (probe != null && !seen.contains(Long.valueOf(probe.id))) {
                    seen.add(Long.valueOf(probe.id));
                    list.add(probe.describe());
                }
            }
        } catch (Throwable ex) {
            AgentBridge.error(ex);
        }
        return list;
    }

    // ---- the agent -------------------------------------------------------------------------------------------------

    /**
     * The agent is about to install the probe: starting becomes active and its window starts now. False, and the agent
     * installs nothing, when the probe ended, failed, or is not in this slot any more.
     */
    public static boolean activate(int slot, long id) {
        try {
            Probe probe = slot(slot, id);
            if (probe == null) {
                return false;
            }
            Claim claim = AgentBridge.current();
            if (claim == null || !claim.armed || claim.generation != probe.generation) {
                // Its run ended between the start and the install: never installed on that run's code.
                end(probe, END_RUN);
                return false;
            }
            probe.activatedMillis = System.currentTimeMillis();
            long now = System.nanoTime();
            probe.deadlineNanos = now + probe.windowNanos;
            probe.activatedNanos = now;
            return probe.state.compareAndSet(STARTING, ACTIVE);
        } catch (Throwable ex) {
            AgentBridge.error(ex);
            return false;
        }
    }

    /** The agent advised a loaded class of the current run: the method {@code descriptor}, resolved. */
    public static void advised(int slot, long id, String descriptor) {
        Probe probe = slot(slot, id);
        if (probe != null) {
            probe.resolvedDescriptor = descriptor;
            probe.advised = true;
        }
    }

    /**
     * The probe's state for the agent's worker, after ending it if its window passed: {@link #ACTIVE}, {@link #ENDING},
     * ..., or -1 when it is not in this slot any more.
     */
    public static int poll(int slot, long id) {
        Probe probe = slot(slot, id);
        if (probe == null) {
            return -1;
        }
        if (probe.state.get() == ACTIVE && System.nanoTime() - probe.deadlineNanos >= 0L) {
            end(probe, END_WINDOW);
        }
        return probe.state.get();
    }

    /** Why the probe in {@code slot} ended, or {@code null} while it has not or it is not there any more. */
    public static String endReason(int slot, long id) {
        Probe probe = slot(slot, id);
        return probe == null ? null : probe.endReason;
    }

    /** The probe could not be installed: it fails with {@code reason}, and its slot is freed. */
    public static void failed(int slot, long id, String reason) {
        Probe probe = slot(slot, id);
        if (probe == null) {
            return;
        }
        int state;
        while ((state = probe.state.get()) != ENDED && state != FAILED) {
            if (probe.state.compareAndSet(state, FAILED)) {
                break;
            }
        }
        if (state == ENDED) {
            return;
        }
        probe.failure = reason;
        if (probe.endedMillis == 0L) {
            probe.endedMillis = System.currentTimeMillis();
        }
        retire(probe);
        AgentBridge.message("method probe " + id + " on " + probe.key + " failed: " + reason);
    }

    /**
     * The agent removed the probe's advice ({@code removal} null), or could not ({@code removal} says why: the bound
     * still holds, by its state): it ends, and its slot is freed.
     */
    public static void removed(int slot, long id, String removal) {
        Probe probe = slot(slot, id);
        if (probe == null) {
            return;
        }
        probe.removal = removal == null ? "removed" : removal;
        int state;
        while ((state = probe.state.get()) != FAILED && state != ENDED) {
            if (probe.state.compareAndSet(state, ENDED)) {
                break;
            }
        }
        retire(probe);
    }

    // ---- the run ---------------------------------------------------------------------------------------------------

    /** A new claim or release: only probes preceding its captured generation end with their run. Never throws. */
    static void claimed(long generation) {
        try {
            for (int i = 0; i < SLOTS; i++) {
                Probe probe = SLOT.get(i);
                if (probe != null && probe.generation < generation) {
                    end(probe, END_RUN);
                }
            }
        } catch (Throwable ex) {
            AgentBridge.error(ex);
        }
    }

    /** A disarm ends only its own generation, even when a newer claim has already started a probe. Never throws. */
    static void disarmed(long generation) {
        try {
            for (int i = 0; i < SLOTS; i++) {
                Probe probe = SLOT.get(i);
                if (probe != null && probe.generation == generation) {
                    end(probe, END_RUN);
                }
            }
        } catch (Throwable ex) {
            AgentBridge.error(ex);
        }
    }

    // ---- internals -------------------------------------------------------------------------------------------------

    /** Starting or active becomes ending, for {@code reason}; the first reason wins. */
    static void end(Probe probe, String reason) {
        int state;
        while ((state = probe.state.get()) == STARTING || state == ACTIVE) {
            // Before the state moves, so a reaper that sees ending never reads a time of 0.
            long now = System.nanoTime();
            if (probe.endingNanos == 0L) {
                probe.endingNanos = now;
            }
            if (probe.state.compareAndSet(state, ENDING)) {
                probe.endReason = reason;
                probe.endedMillis = System.currentTimeMillis();
                return;
            }
        }
    }

    /**
     * Frees the slots the agent never answered for: a probe starting, or ending, longer than {@link #STUCK_MILLIS}.
     * Ending a probe past its window costs nothing more here.
     */
    static void reap(long now) {
        for (int i = 0; i < SLOTS; i++) {
            Probe probe = SLOT.get(i);
            if (probe == null) {
                continue;
            }
            int state = probe.state.get();
            if (state == ACTIVE && now - probe.deadlineNanos >= 0L) {
                end(probe, END_WINDOW);
                state = probe.state.get();
            }
            if (state == STARTING
                    && now - probe.requestedNanos >= stuckNanos
                    && probe.state.compareAndSet(STARTING, FAILED)) {
                REAPED.increment();
                probe.failure = "the BootUI agent did not install it within " + STUCK_MILLIS / 1000 + " s";
                probe.endedMillis = System.currentTimeMillis();
                retire(probe);
            } else if (state == ENDING
                    && probe.endingNanos != 0L
                    && now - probe.endingNanos >= stuckNanos
                    && probe.state.compareAndSet(ENDING, ENDED)) {
                REAPED.increment();
                probe.removal = "unknown: the BootUI agent did not report removing it; its advice may still be"
                        + " installed, and records nothing";
                retire(probe);
            }
        }
    }

    /** Moves the probe from its slot to the ended probes kept. */
    private static void retire(Probe probe) {
        if (SLOT.compareAndSet(probe.slot, probe, null)) {
            long n = ENDED_COUNT.getAndIncrement();
            ENDED_PROBES.set((int) (n % HISTORY), probe);
        }
    }

    /** The probe in {@code slot} if it is {@code id}. */
    private static Probe slot(int slot, long id) {
        if (slot < 0 || slot >= SLOTS) {
            return null;
        }
        Probe probe = SLOT.get(slot);
        return probe != null && probe.id == id ? probe : null;
    }

    /** The probe {@code id}, in its slot or among the ended ones kept. */
    private static Probe find(int slot, long id) {
        Probe probe = slot(slot, id);
        if (probe != null) {
            return probe;
        }
        for (int i = 0; i < HISTORY; i++) {
            Probe ended = ENDED_PROBES.get(i);
            if (ended != null && ended.id == id) {
                return ended;
            }
        }
        return null;
    }

    private static boolean lock() {
        for (int i = 0; i < 10_000; i++) {
            if (PLACING.compareAndSet(false, true)) {
                return true;
            }
            Thread.yield();
        }
        return false;
    }

    /** Whether the probed method is known to return nothing: its descriptor resolved, or named, ends with {@code V}. */
    private static boolean returnsVoid(Probe probe) {
        String known = probe.resolvedDescriptor != null ? probe.resolvedDescriptor : probe.descriptor;
        return known != null && known.endsWith(")V");
    }

    /**
     * Whether {@code probe} may be the overload {@code descriptor} names: the same descriptor, or either one unnamed, as
     * {@code Foo#bar} and {@code Foo#bar(I)J} may be the same method.
     */
    private static boolean sameOverload(Probe probe, String descriptor) {
        String known = probe.resolvedDescriptor != null ? probe.resolvedDescriptor : probe.descriptor;
        return known == null || descriptor == null || known.equals(descriptor);
    }

    /** Why {@code key} is not a method key a probe accepts, or {@code null}. */
    static String invalid(String key) {
        int hash = key.indexOf('#');
        if (hash <= 0 || hash != key.lastIndexOf('#') || hash == key.length() - 1) {
            return "a method is named class#method, with its descriptor for an overloaded one: " + key;
        }
        int open = key.indexOf('(', hash);
        String name = open < 0 ? key.substring(hash + 1) : key.substring(hash + 1, open);
        if (name.isEmpty() || name.indexOf('<') >= 0 || name.indexOf('/') >= 0 || name.indexOf('.') >= 0) {
            return "a probe takes a method, not a constructor or an initializer: " + key;
        }
        if (open >= 0 && key.indexOf(')', open) < 0) {
            return "a descriptor is (parameters)return, as in (I)J: " + key;
        }
        for (int i = 0; i < hash; i++) {
            char c = key.charAt(i);
            if (c == '/' || c == '(' || c == ' ' || c == ';') {
                return "a class is named by its binary name, as com.example.Outer$Inner: " + key;
            }
        }
        return null;
    }

    private static boolean inPackages(String className, List<String> packages) {
        for (int i = 0; i < packages.size(); i++) {
            if (className.startsWith(packages.get(i) + ".")) {
                return true;
            }
        }
        return false;
    }

    private static String[] prefixes(List<String> packages) {
        String[] prefixes = new String[packages.size()];
        for (int i = 0; i < prefixes.length; i++) {
            prefixes[i] = packages.get(i) + ".";
        }
        return prefixes;
    }

    private static int bound(Object requested, int max) {
        return requested instanceof Number && ((Number) requested).intValue() > 0
                ? Math.min(max, ((Number) requested).intValue())
                : max;
    }

    private static long bound(Object requested, long max) {
        return requested instanceof Number && ((Number) requested).longValue() > 0L
                ? Math.min(max, ((Number) requested).longValue())
                : max;
    }

    private static Map<String, Object> answer(String status, String reason, Map<String, Object> probe) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("status", status);
        map.put("reason", reason);
        map.put("probe", probe);
        return map;
    }

    /** Loads and links what the advice calls, on the agent's thread, before a probe is installed. */
    public static void warm() {
        try {
            Probe probe = new Probe(0L, -1, -1L, "warm.Up#warm", "warm.Up", "warm", null, 1, 1L, new String[0], false);
            caller(probe);
            probe.describe();
            Reentrancy.guarded();
            Reentrancy.bootUiWork();
            Thread.currentThread().getClass().getName().endsWith("VirtualThread");
            CodeInventory.parseRequestId("0000000000000000");
            Exclusions.excluded("warm.Up");
            returnsVoid(probe);
            ProbeShapes.warm();
        } catch (Throwable ex) {
            AgentBridge.error(ex);
        }
    }

    /** Counters and the probes in their slots; JDK types only. */
    public static Map<String, Object> status() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        try {
            int active = 0;
            for (int i = 0; i < SLOTS; i++) {
                if (SLOT.get(i) != null) {
                    active++;
                }
            }
            map.put("slots", Integer.valueOf(SLOTS));
            map.put("inUse", Integer.valueOf(active));
            map.put("started", Long.valueOf(STARTS.sum()));
            map.put("refused", Long.valueOf(REFUSALS.sum()));
            map.put("hits", Long.valueOf(HITS.sum()));
            map.put("dropped", Long.valueOf(DROPPED.sum()));
            map.put("reaped", Long.valueOf(REAPED.sum()));
            map.put("adviceCalls", Long.valueOf(ADVICE_CALLS.sum()));
        } catch (Throwable ex) {
            AgentBridge.error(ex);
        }
        return map;
    }

    /** Tests only: forgets every probe and counter. */
    static void reset() {
        for (int i = 0; i < SLOTS; i++) {
            SLOT.set(i, null);
        }
        for (int i = 0; i < HISTORY; i++) {
            ENDED_PROBES.set(i, null);
        }
        ENDED_COUNT.set(0L);
        PLACING.set(false);
        STARTS.reset();
        REFUSALS.reset();
        HITS.reset();
        DROPPED.reset();
        REAPED.reset();
        ADVICE_CALLS.reset();
        stuckNanos = STUCK_MILLIS * 1_000_000L;
    }

    /** One probe: immutable but for its state and counters. Strings and primitives only. */
    static final class Probe {

        final long id;
        final int slot;
        final long generation;
        final String key;
        final String className;
        final String methodName;
        final String descriptor;
        final int max;
        final long windowMillis;
        final long windowNanos;
        final String[] packagePrefixes;
        /** Whether it records argument and return shapes. */
        final boolean shapes;

        final long requestedMillis;
        final long requestedNanos;
        final AtomicInteger state = new AtomicInteger(STARTING);
        final AtomicInteger entered = new AtomicInteger();
        final AtomicInteger recorded = new AtomicInteger();
        final AtomicInteger dropped = new AtomicInteger();
        /** Shapes records the ring could not take. */
        final AtomicInteger shapesDropped = new AtomicInteger();

        volatile long activatedNanos;
        volatile long activatedMillis;
        /** Until activated, far enough ahead for no advice to see it passed. */
        volatile long deadlineNanos;

        volatile long endingNanos;
        volatile long endedMillis;
        volatile String endReason;
        volatile String failure;
        volatile String removal;
        volatile String resolvedDescriptor;
        volatile boolean advised;

        Probe(
                long id,
                int slot,
                long generation,
                String key,
                String className,
                String methodName,
                String descriptor,
                int max,
                long windowMillis,
                String[] packagePrefixes,
                boolean shapes) {
            this.id = id;
            this.slot = slot;
            this.generation = generation;
            this.key = key;
            this.className = className;
            this.methodName = methodName;
            this.descriptor = descriptor;
            this.max = max;
            this.windowMillis = windowMillis;
            this.windowNanos = windowMillis * 1_000_000L;
            this.packagePrefixes = packagePrefixes;
            this.shapes = shapes;
            this.requestedMillis = System.currentTimeMillis();
            this.requestedNanos = System.nanoTime();
            this.deadlineNanos = requestedNanos + windowNanos;
        }

        Map<String, Object> describe() {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            int current = state.get();
            map.put("id", Long.valueOf(id));
            map.put("generation", Long.valueOf(generation));
            map.put("method", key);
            map.put("className", className);
            map.put("methodName", methodName);
            map.put("descriptor", resolvedDescriptor != null ? resolvedDescriptor : descriptor);
            map.put("state", current >= 0 && current < STATE_NAMES.length ? STATE_NAMES[current] : "unknown");
            map.put("endReason", endReason);
            map.put("failure", failure);
            map.put("removal", removal);
            map.put("advised", Boolean.valueOf(advised));
            map.put("maxInvocations", Integer.valueOf(max));
            map.put("windowMillis", Long.valueOf(windowMillis));
            map.put("requestedAt", Long.valueOf(requestedMillis));
            long activated = activatedMillis;
            map.put("activatedAt", activated == 0L ? null : Long.valueOf(activated));
            map.put("endsAt", activated == 0L ? null : Long.valueOf(activated + windowMillis));
            long ended = endedMillis;
            map.put("endedAt", ended == 0L ? null : Long.valueOf(ended));
            map.put("invocations", Integer.valueOf(Math.min(entered.get(), max)));
            map.put("recorded", Integer.valueOf(recorded.get()));
            map.put("dropped", Integer.valueOf(dropped.get()));
            map.put("shapes", Boolean.valueOf(shapes));
            map.put("shapesDropped", Integer.valueOf(shapesDropped.get()));
            return map;
        }
    }
}
