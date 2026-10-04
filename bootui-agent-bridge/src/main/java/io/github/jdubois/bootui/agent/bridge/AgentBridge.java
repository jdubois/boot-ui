package io.github.jdubois.bootui.agent.bridge;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The contract between the BootUI engine and the BootUI Java agent (PLAN-v2 §5.13, D32–D34). The agent's launcher
 * appends the agent jar to the bootstrap class path, so this class is defined once per JVM by the bootstrap class loader
 * and outlives every DevTools restart and Quarkus live reload. The engine finds it with
 * {@code Class.forName(name, false, null)} and calls it reflectively; nothing here references the engine, the agent's
 * implementation, or any library.
 *
 * <p>One application at a time holds the claim. Its slot is {@code mode:application}: a claim in the same slot always
 * replaces the previous one (a DevTools restart or a Quarkus reload, even after a run that failed without disarming); a
 * claim in another slot is held while the current claim is armed and not abandoned, except that a {@code dev} claim takes
 * over a {@code test} claim. Disarming ends a run but keeps the agent's transformers (D34); releasing removes them, and
 * is used when BootUI is disabled.
 *
 * <p>Every result is a {@code Map} of JDK types with a {@code status} of {@value #ARMED}, {@value #HELD},
 * {@value #DISARMED}, {@value #RELEASED}, {@value #STALE}, {@value #FAILED}, or {@value #UNAVAILABLE}.
 */
public final class AgentBridge {

    /** Bumped whenever the engine-facing contract of this class changes; the engine refuses another protocol. */
    public static final int PROTOCOL = 1;

    public static final String ARMED = "armed";
    public static final String HELD = "held";
    public static final String DISARMED = "disarmed";
    public static final String RELEASED = "released";
    public static final String STALE = "stale";
    public static final String FAILED = "failed";
    public static final String UNAVAILABLE = "unavailable";

    public static final String DEV = "dev";
    public static final String TEST = "test";

    private static final int MESSAGES = 32;

    private static final AtomicReference<Function<Map<String, Object>, Map<String, Object>>> AGENT =
            new AtomicReference<Function<Map<String, Object>, Map<String, Object>>>();
    private static final AtomicReference<Claim> CLAIM = new AtomicReference<Claim>();
    private static final AtomicLong TOKENS = new AtomicLong();
    /** One sequence for every transition the agent sees, so it can order calls it receives out of order. */
    private static final AtomicLong GENERATIONS = new AtomicLong();

    private static final AtomicLong MESSAGE_COUNT = new AtomicLong();
    private static final AtomicReferenceArray<String> MESSAGE_RING = new AtomicReferenceArray<String>(MESSAGES);
    private static final LongAdder CLAIMS = new LongAdder();
    private static final LongAdder TAKEOVERS = new LongAdder();
    private static final LongAdder HOLDS = new LongAdder();
    private static final LongAdder STALE_TOKENS = new LongAdder();
    private static final LongAdder ERRORS = new LongAdder();
    private static final LongAdder PROBE_HITS = new LongAdder();

    /** Whether a claim ever asked for the inventory sensor: until then its state is never initialized. */
    private static volatile boolean inventoryClaimed;

    private AgentBridge() {}

    /**
     * Called once by the agent's {@code premain}: the handler the bridge calls on every transition, with an {@code op} of
     * {@code claim}, {@code refine}, {@code disarm}, {@code release}, or {@code status}. Returns false when a handler is
     * already installed (a second agent copy), which then stays dormant.
     */
    public static boolean install(Function<Map<String, Object>, Map<String, Object>> agent) {
        return agent != null && AGENT.compareAndSet(null, agent);
    }

    /** Whether an agent installed its handler: false when the bridge is present but the agent failed to start. */
    public static boolean attached() {
        return AGENT.get() != null;
    }

    /**
     * Records a message from the agent (start-up, failures) for the engine to show and log through the application's own
     * logging, since the agent writes nothing but {@code System.err} itself. Keeps the last {@value #MESSAGES}.
     */
    public static void message(String text) {
        if (text == null) {
            return;
        }
        long index = MESSAGE_COUNT.getAndIncrement();
        MESSAGE_RING.set((int) (index % MESSAGES), text);
    }

    /**
     * Claims the agent for one application run. The request carries {@code application}, {@code owner}, {@code mode}
     * ({@value #DEV} or {@value #TEST}), and {@code packages} (a collection of package names); every value is copied. The
     * engine must keep {@code capture} and {@code reopen} strongly reachable for the run, and pass fresh objects to each
     * claim: the bridge holds them weakly.
     */
    public static Map<String, Object> claim(
            Map<String, ?> request, Supplier<Object> capture, Function<Object, AutoCloseable> reopen) {
        Function<Map<String, Object>, Map<String, Object>> agent = AGENT.get();
        if (agent == null) {
            return result(UNAVAILABLE, "the BootUI agent did not start" + lastMessageSuffix(), null);
        }
        if (request == null || capture == null || reopen == null) {
            return result(FAILED, "a claim needs a request, a capture, and a reopen", null);
        }
        String application = text(request.get("application"), "application");
        String owner = text(request.get("owner"), application);
        String mode = TEST.equals(request.get("mode")) ? TEST : DEV;
        List<String> packages = strings(request.get("packages"));
        List<String> sensors = strings(request.get("sensors"));
        Object options = request.get("executors");
        String[] skipTasks = array(options instanceof Map ? ((Map<?, ?>) options).get("skipTasks") : null);
        String[] skipThreads = array(options instanceof Map ? ((Map<?, ?>) options).get("skipThreads") : null);
        Object capacity = request.get("ringCapacity");
        int ringCapacity = capacity instanceof Number ? ((Number) capacity).intValue() : AgentRing.DEFAULT_CAPACITY;
        List<String> beanClasses = beanClasses(request.get("beanClasses"));
        Object codePathsOptions = request.get("codePaths");
        Object pool = codePathsOptions instanceof Map ? ((Map<?, ?>) codePathsOptions).get("poolSize") : null;
        Object queue = codePathsOptions instanceof Map ? ((Map<?, ?>) codePathsOptions).get("queueBytes") : null;
        int codePathsPool = pool instanceof Number ? ((Number) pool).intValue() : 0;
        long codePathsQueueBytes = queue instanceof Number ? ((Number) queue).longValue() : 0L;
        String slot = Claim.slot(mode, application);
        while (true) {
            Claim current = CLAIM.get();
            if (current != null
                    && current.armed
                    && !current.slot.equals(slot)
                    && !current.abandoned()
                    && !(DEV.equals(mode) && TEST.equals(current.mode))) {
                HOLDS.increment();
                return result(HELD, "held by " + current.owner + " (" + current.mode + ")", current);
            }
            Claim next = new Claim(
                    GENERATIONS.incrementAndGet(),
                    TOKENS.incrementAndGet(),
                    owner,
                    application,
                    mode,
                    packages,
                    sensors,
                    skipTasks,
                    skipThreads,
                    ringCapacity,
                    beanClasses,
                    codePathsPool,
                    codePathsQueueBytes,
                    System.currentTimeMillis(),
                    true,
                    new WeakReference<Supplier<Object>>(capture),
                    new WeakReference<Function<Object, AutoCloseable>>(reopen));
            if (CLAIM.compareAndSet(current, next)) {
                CLAIMS.increment();
                if (current != null && current.armed && !current.slot.equals(slot)) {
                    TAKEOVERS.increment();
                }
                if (next.hasSensor(CodeInventory.SENSOR)) {
                    // Before the agent hears of the claim, so its self-test and advice see the new run's epoch.
                    inventoryClaimed = true;
                    try {
                        CodeInventory.claimed(next);
                    } catch (Throwable ex) {
                        // Even the inventory state failing to initialize must not fail the claim.
                        error(ex);
                    }
                }
                if (next.hasSensor(CodePaths.SENSOR)) {
                    // Before the agent hears of the claim, so the sensor records for the new run as soon as it can.
                    CodePaths.claimed(next);
                }
                CodePaths.refresh();
                return transition(agent, "claim", next, ARMED);
            }
        }
    }

    /**
     * Adds {@code packages} and {@code beanClasses} (binary names, for the code-paths sensor) to the current claim, as
     * known once the application context started.
     */
    public static Map<String, Object> refine(long token, Map<String, ?> request) {
        Function<Map<String, Object>, Map<String, Object>> agent = AGENT.get();
        if (agent == null) {
            return result(UNAVAILABLE, "the BootUI agent did not start", null);
        }
        List<String> packages = request == null ? Collections.<String>emptyList() : strings(request.get("packages"));
        List<String> beanClasses =
                request == null ? Collections.<String>emptyList() : beanClasses(request.get("beanClasses"));
        while (true) {
            Claim current = CLAIM.get();
            if (current == null || current.token != token || !current.armed) {
                STALE_TOKENS.increment();
                return result(STALE, "this claim was replaced or ended", current);
            }
            Claim next = current.refined(packages, beanClasses);
            if (CLAIM.compareAndSet(current, next)) {
                return transition(agent, "refine", next, ARMED);
            }
        }
    }

    /** Ends a run (context closed, Quarkus shutdown): recording stops, the agent's transformers stay installed (D34). */
    public static Map<String, Object> disarm(long token) {
        Function<Map<String, Object>, Map<String, Object>> agent = AGENT.get();
        if (agent == null) {
            return result(UNAVAILABLE, "the BootUI agent did not start", null);
        }
        while (true) {
            Claim current = CLAIM.get();
            if (current == null || current.token != token || !current.armed) {
                STALE_TOKENS.increment();
                return result(STALE, "this claim was replaced or ended", current);
            }
            Claim next = current.disarmed();
            if (CLAIM.compareAndSet(current, next)) {
                CodePaths.refresh();
                return transition(agent, "disarm", next, DISARMED);
            }
        }
    }

    /**
     * Removes the agent's transformers because BootUI is disabled for this application: called without a token, by a run
     * that never claimed. Succeeds when the current claim is in the same slot, disarmed, or abandoned; an armed claim of
     * another application is left alone.
     */
    public static Map<String, Object> release(String application, String mode) {
        Function<Map<String, Object>, Map<String, Object>> agent = AGENT.get();
        if (agent == null) {
            return result(UNAVAILABLE, "the BootUI agent did not start", null);
        }
        String slot = Claim.slot(TEST.equals(mode) ? TEST : DEV, text(application, "application"));
        while (true) {
            Claim current = CLAIM.get();
            if (current != null && current.armed && !current.slot.equals(slot) && !current.abandoned()) {
                HOLDS.increment();
                return result(HELD, "held by " + current.owner + " (" + current.mode + ")", current);
            }
            // A release takes its own generation, so the agent never applies it over a newer claim it saw first.
            long generation = GENERATIONS.incrementAndGet();
            if (CLAIM.compareAndSet(current, null)) {
                CodePaths.refresh();
                Map<String, Object> request = new LinkedHashMap<String, Object>();
                request.put("op", "release");
                request.put("generation", Long.valueOf(generation));
                return answered(agent, request, null, RELEASED);
            }
        }
    }

    /** Whether an application holds an armed claim: advice records nothing otherwise. A volatile read. */
    public static boolean recording() {
        Claim current = CLAIM.get();
        return current != null && current.armed;
    }

    /**
     * Marks or unmarks the calling thread's current work as BootUI's own, so the inventory sensor does not count the
     * classes it loads, nor capture for the methods it runs (PLAN-v2 M5-3): the engine sets it around its scans, its
     * drainer, and its class-presence checks, and restores the previous value it returns. Never throws.
     */
    public static boolean bootUiWork(boolean on) {
        try {
            return Reentrancy.bootUiWork(on);
        } catch (Throwable ex) {
            error(ex);
            return false;
        }
    }

    /** Counts an error of an advice entry point, which never throws to the application. */
    static void error(Throwable ex) {
        ERRORS.increment();
        if (ERRORS.sum() <= 5) {
            message("advice error: " + ex);
        }
    }

    /**
     * Called from the agent's diagnostic probe advice (no production sensor uses it): counts every probed method entry,
     * claimed or not, so a test can tell the advice was removed from a class.
     */
    public static void probe() {
        PROBE_HITS.increment();
    }

    /** The current claim, the counters, the agent's own status, and the recent messages; JDK types only. */
    public static Map<String, Object> status() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("protocol", Integer.valueOf(PROTOCOL));
        Function<Map<String, Object>, Map<String, Object>> agent = AGENT.get();
        map.put("attached", Boolean.valueOf(agent != null));
        Claim current = CLAIM.get();
        map.put("claim", current == null ? null : current.describe());
        Map<String, Object> counters = new LinkedHashMap<String, Object>();
        counters.put("claims", Long.valueOf(CLAIMS.sum()));
        counters.put("takeovers", Long.valueOf(TAKEOVERS.sum()));
        counters.put("holds", Long.valueOf(HOLDS.sum()));
        counters.put("staleTokens", Long.valueOf(STALE_TOKENS.sum()));
        counters.put("errors", Long.valueOf(ERRORS.sum()));
        counters.put("probeHits", Long.valueOf(PROBE_HITS.sum()));
        map.put("executors", TaskPropagation.status());
        map.put("threads", ThreadPropagation.status());
        try {
            if (inventoryClaimed) {
                map.put(CodeInventory.SENSOR, CodeInventory.status());
            }
            if (CodePaths.claimedOnce) {
                map.put(CodePaths.SENSOR, CodePaths.status());
            }
            map.put("ring", AgentRing.status());
        } catch (Throwable ex) {
            error(ex);
        }
        map.put("counters", counters);
        if (agent != null) {
            Map<String, Object> request = new LinkedHashMap<String, Object>();
            request.put("op", "status");
            map.put("agent", call(agent, request));
        }
        map.put("messages", messages());
        return map;
    }

    /** The recent agent messages, oldest first. */
    public static List<String> messages() {
        long count = MESSAGE_COUNT.get();
        long first = Math.max(0L, count - MESSAGES);
        List<String> list = new ArrayList<String>();
        for (long i = first; i < count; i++) {
            String text = MESSAGE_RING.get((int) (i % MESSAGES));
            if (text != null) {
                list.add(text);
            }
        }
        return list;
    }

    private static Map<String, Object> transition(
            Function<Map<String, Object>, Map<String, Object>> agent, String op, Claim claim, String status) {
        Map<String, Object> request = claim.describe();
        request.put("op", op);
        // The names only for the agent: status and answers carry their count.
        request.put("beanClasses", new ArrayList<String>(claim.beanClasses));
        return answered(agent, request, claim, status);
    }

    private static Map<String, Object> answered(
            Function<Map<String, Object>, Map<String, Object>> agent,
            Map<String, Object> request,
            Claim claim,
            String status) {
        String op = String.valueOf(request.get("op"));
        Map<String, Object> answer = call(agent, request);
        Map<String, Object> result;
        if (answer != null && FAILED.equals(answer.get("status"))) {
            result = result(FAILED, String.valueOf(answer.get("reason")), claim);
        } else if (answer == null) {
            result = result(FAILED, "the BootUI agent failed on " + op + lastMessageSuffix(), claim);
        } else {
            result = result(status, null, claim);
        }
        if (claim != null && ARMED.equals(status)) {
            result.put("token", Long.valueOf(claim.token));
        }
        result.put("agent", answer);
        return result;
    }

    private static Map<String, Object> call(
            Function<Map<String, Object>, Map<String, Object>> agent, Map<String, Object> request) {
        try {
            return agent.apply(request);
        } catch (Throwable ex) {
            ERRORS.increment();
            message("agent " + request.get("op") + " failed: " + ex);
            return null;
        }
    }

    private static Map<String, Object> result(String status, String reason, Claim claim) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("status", status);
        map.put("reason", reason);
        map.put("generation", claim == null ? null : Long.valueOf(claim.generation));
        map.put("claim", claim == null ? null : claim.describe());
        return map;
    }

    private static String lastMessageSuffix() {
        long count = MESSAGE_COUNT.get();
        if (count == 0) {
            return "";
        }
        String last = MESSAGE_RING.get((int) ((count - 1) % MESSAGES));
        return last == null ? "" : ": " + last;
    }

    private static String text(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value);
        return text.isEmpty() ? fallback : text;
    }

    private static String[] array(Object value) {
        List<String> list = strings(value);
        return list.toArray(new String[0]);
    }

    /** The current claim, for the bridge's advice entry points. */
    static Claim current() {
        return CLAIM.get();
    }

    /** Bean class names, at most {@link Claim#MAX_BEAN_CLASSES}. */
    private static List<String> beanClasses(Object value) {
        if (!(value instanceof Collection)) {
            return Collections.<String>emptyList();
        }
        LinkedHashSet<String> names = new LinkedHashSet<String>();
        Object[] items = ((Collection<?>) value).toArray();
        for (int i = 0; i < items.length && names.size() < Claim.MAX_BEAN_CLASSES; i++) {
            if (items[i] != null) {
                String name = String.valueOf(items[i]);
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
        }
        return Collections.unmodifiableList(new ArrayList<String>(names));
    }

    private static List<String> strings(Object value) {
        List<String> list = new ArrayList<String>();
        if (value instanceof Collection) {
            Object[] items = ((Collection<?>) value).toArray();
            for (int i = 0; i < items.length; i++) {
                if (items[i] != null) {
                    String name = String.valueOf(items[i]);
                    if (!name.isEmpty() && !list.contains(name)) {
                        list.add(name);
                    }
                }
            }
        }
        return Collections.unmodifiableList(list);
    }

    /** Tests only: forgets the agent, the claim, the messages, and the counters. */
    static void reset() {
        AGENT.set(null);
        CLAIM.set(null);
        for (int i = 0; i < MESSAGES; i++) {
            MESSAGE_RING.set(i, null);
        }
        MESSAGE_COUNT.set(0);
        GENERATIONS.set(0);
        CLAIMS.reset();
        TAKEOVERS.reset();
        HOLDS.reset();
        STALE_TOKENS.reset();
        ERRORS.reset();
        PROBE_HITS.reset();
        TaskPropagation.reset();
        ThreadPropagation.reset();
        CodeInventory.reset();
        CodePaths.reset();
        AgentRing.reset();
        inventoryClaimed = false;
    }
}
