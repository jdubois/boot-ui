package io.github.jdubois.bootui.agent;

import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import io.github.jdubois.bootui.agent.bridge.SideEffects;
import io.github.jdubois.bootui.agent.bridge.ThreadLocals;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@value SideEffects#THREAD_LOCALS} sensor's agent side (PLAN-v2 §5.16, M5-5f): no transformer and no hook on
 * {@code ThreadLocal}. Its install grants the agent's own module, alone, the access its scanner and resolver need, then
 * hands both to the bridge ({@link ThreadLocals#install}) and self-tests them before the sensor records.
 *
 * <p><b>Access</b> ({@link #grant}), through {@link Instrumentation#redefineModule}, never {@code --add-opens} on the
 * application: {@code java.base} opens {@code java.lang} to the agent's module (the unnamed module of its isolated
 * class loader), so the scanner reads {@code Thread.threadLocals}, {@code inheritableThreadLocals}, a map's table, and
 * an entry's value's nullness; and exports {@code jdk.internal.misc} to it, so the resolver asks {@code
 * Unsafe.shouldBeInitialized} before it reads a static field, never initializing a class. Without the export, or with
 * no such method on this JDK, the resolver falls back to Code Inventory's executed classes, and says so; without the
 * opening, the sensor reports itself unavailable and never fails the claim.
 *
 * <p><b>Self-test</b> on its worker: a scope sees exactly what a request leaving thread locals set would leave (a plain
 * one, an inheritable one, and a {@code withInitial} one read), never one set before it, one removed, or one set to
 * {@code null}; then the resolver names the plain one's static field, unless it fell back to Code Inventory.
 */
final class ThreadLocalsSensor {

    /** The test-only hook id that forces the resolver's Code Inventory fallback, as the mutation tests omit hooks. */
    static final String UNSAFE_CHECK = "ThreadLocal.unsafeCheck";

    static final String SCAN = "ThreadLocalMap.scan";
    static final String HOLDER = "ThreadLocal.holder";

    private final Instrumentation instrumentation;
    private final boolean privileged;
    private final Set<String> omitted;

    private volatile String state = "off";
    private volatile long installMillis = -1;
    private volatile long selfTestMillis = -1;
    private volatile boolean selfTestPassed;
    private volatile String selfTestError;
    private volatile String initializationCheck;
    private volatile Map<String, String> selfTest = new LinkedHashMap<String, String>();
    private volatile Map<String, String> steps = new LinkedHashMap<String, String>();
    private volatile boolean reported;
    private volatile boolean granted;
    private volatile ThreadLocalResolver resolver;
    private boolean wanted;
    private Thread worker;
    private boolean pending;

    ThreadLocalsSensor(Instrumentation instrumentation, boolean privileged, Set<String> omitted) {
        this.instrumentation = instrumentation;
        this.privileged = privileged;
        this.omitted = omitted == null ? Collections.<String>emptySet() : omitted;
    }

    /** A claim asks for the sensor: installed and self-tested once, then enabled for each claim. */
    synchronized void claimed() {
        wanted = true;
        reported = true;
        if (selfTestPassed && worker == null) {
            SideEffects.enable(SideEffects.MASK_THREAD_LOCALS);
            return;
        }
        pending = true;
        if (worker == null) {
            worker = AgentThreads.newThread("bootui-agent-thread-locals", new Worker(), privileged);
            worker.start();
        }
    }

    /** A claim without the sensor, or a release: it stops recording; the grant, which cannot be undone, stays. */
    synchronized void release() {
        wanted = false;
        SideEffects.disable(SideEffects.MASK_THREAD_LOCALS, null);
    }

    /** Whether the worker has no job left. */
    synchronized boolean idle() {
        return worker == null && !pending;
    }

    private synchronized boolean nextJob() {
        if (!pending) {
            worker = null;
            return false;
        }
        pending = false;
        return true;
    }

    private synchronized boolean wanted() {
        return wanted;
    }

    final class Worker implements Runnable {

        @Override
        public void run() {
            while (nextJob()) {
                try {
                    if (!selfTestPassed) {
                        install();
                    }
                    if (selfTestPassed && wanted()) {
                        SideEffects.enable(SideEffects.MASK_THREAD_LOCALS);
                    }
                } catch (Throwable ex) {
                    fail("thread-locals sensor error: " + ex);
                }
            }
        }
    }

    void install() {
        long started = System.nanoTime();
        state = "installing";
        Map<String, String> results = new LinkedHashMap<String, String>();
        Map<String, String> outcome = new LinkedHashMap<String, String>();
        try {
            String access = granted ? "ok" : grant();
            outcome.put("access", access);
            if (!"ok".equals(access)) {
                results.put(SCAN, "failed");
                results.put(HOLDER, "not-run");
                finish(results, outcome, started, "the agent could not read thread-local maps: " + access);
                return;
            }
            granted = true;
            ThreadLocalScanner scanner = new ThreadLocalScanner();
            ThreadLocalResolver.InitializationCheck check =
                    omitted.contains(UNSAFE_CHECK) ? null : ThreadLocalResolver.UnsafeCheck.locate();
            if (check == null) {
                ThreadLocalResolver.InventoryCheck inventory = new ThreadLocalResolver.InventoryCheck();
                ThreadLocalResolver fallback = new ThreadLocalResolver(instrumentation, inventory);
                inventory.resolver(fallback);
                resolver = fallback;
            } else {
                resolver = new ThreadLocalResolver(instrumentation, check);
            }
            initializationCheck = resolver.initializationCheck();
            outcome.put("initializationCheck", initializationCheck);
            ThreadLocals.warm();
            ThreadLocals.install(scanner, resolver);
            installMillis = (System.nanoTime() - started) / 1_000_000L;
            state = "testing";
            long testStarted = System.nanoTime();
            String scan = scanStep();
            outcome.put(SCAN, scan);
            results.put(SCAN, "ok".equals(scan) ? "passed" : "failed");
            String holder = holderStep(resolver);
            outcome.put(HOLDER, holder);
            results.put(HOLDER, holder.startsWith("ok") ? "passed" : "failed");
            selfTestMillis = (System.nanoTime() - testStarted) / 1_000_000L;
            String error = null;
            if (!"passed".equals(results.get(SCAN)) || !"passed".equals(results.get(HOLDER))) {
                error = "self-test failed " + outcome;
                ThreadLocals.unavailable(error);
            }
            finish(results, outcome, started, error);
        } catch (Throwable ex) {
            ThreadLocals.unavailable("thread-locals sensor error: " + ex);
            results.put(SCAN, "failed");
            outcome.put("error", String.valueOf(ex));
            finish(results, outcome, started, "thread-locals sensor error: " + ex);
        }
    }

    private void finish(Map<String, String> results, Map<String, String> outcome, long started, String error) {
        if (installMillis < 0) {
            installMillis = (System.nanoTime() - started) / 1_000_000L;
        }
        selfTest = results;
        steps = outcome;
        if (error == null) {
            selfTestPassed = true;
            selfTestError = null;
            state = "installed";
            return;
        }
        fail(error);
    }

    private void fail(String error) {
        selfTestPassed = false;
        selfTestError = error;
        state = "self-test-failed";
        SideEffects.disable(SideEffects.MASK_THREAD_LOCALS, error);
        AgentBridge.message("the BootUI agent's thread-locals sensor is off: " + error);
    }

    /**
     * Opens {@code java.lang} to the agent's own module, which the scanner needs, then exports {@code
     * jdk.internal.misc} to it, which the resolver's initialization check uses when present: {@code ok}, or why the
     * opening failed. Never to another module, never to every unnamed module.
     */
    String grant() {
        try {
            Module base = Object.class.getModule();
            if (!(ThreadLocalsSensor.class.getClassLoader() instanceof AgentClassLoader)) {
                // Never to another module: outside its isolated class loader, the agent's would be the application's.
                return "the agent is not loaded by its own class loader";
            }
            Module agent = ThreadLocalsSensor.class.getModule();
            if (!instrumentation.isModifiableModule(base)) {
                return "java.base cannot be modified";
            }
            instrumentation.redefineModule(
                    base,
                    Collections.<Module>emptySet(),
                    Collections.<String, Set<Module>>emptyMap(),
                    Collections.singletonMap("java.lang", Collections.singleton(agent)),
                    Collections.<Class<?>>emptySet(),
                    Collections.<Class<?>, List<Class<?>>>emptyMap());
            try {
                instrumentation.redefineModule(
                        base,
                        Collections.<Module>emptySet(),
                        Collections.singletonMap("jdk.internal.misc", Collections.singleton(agent)),
                        Collections.<String, Set<Module>>emptyMap(),
                        Collections.<Class<?>>emptySet(),
                        Collections.<Class<?>, List<Class<?>>>emptyMap());
            } catch (Throwable ex) {
                // The resolver falls back to Code Inventory's executed classes.
            }
            return base.isOpen("java.lang", agent) ? "ok" : "java.lang was not opened";
        } catch (Throwable ex) {
            return "error: " + ex;
        }
    }

    /** The thread locals the scan self-test sets, held in static fields so the resolver can name one. */
    static final class Probe {
        static final ThreadLocal<Object> LEFT = new ThreadLocal<Object>();
        static final InheritableThreadLocal<Object> INHERITED = new InheritableThreadLocal<Object>();
        static final ThreadLocal<Object> CACHE = ThreadLocal.withInitial(new Initial());
        static final ThreadLocal<Object> BEFORE = new ThreadLocal<Object>();
        static final ThreadLocal<Object> REMOVED = new ThreadLocal<Object>();
        static final ThreadLocal<Object> NULLED = new ThreadLocal<Object>();
    }

    static final class Initial implements java.util.function.Supplier<Object> {
        @Override
        public Object get() {
            return Boolean.TRUE;
        }
    }

    /**
     * A scope leaves a plain, an inheritable, and a read {@code withInitial} thread local set: exactly those three are
     * found, never one set before it, removed, or set to {@code null}. Everything is cleared afterwards.
     */
    static String scanStep() {
        Probe.BEFORE.set(Boolean.TRUE);
        ThreadLocals.beginSelfTest();
        int[] found;
        try {
            long scope = ThreadLocals.open();
            if (scope == 0L) {
                return "error: the scope did not open";
            }
            Probe.LEFT.set(Boolean.TRUE);
            Probe.INHERITED.set(Boolean.TRUE);
            Probe.CACHE.get();
            Probe.REMOVED.set(Boolean.TRUE);
            Probe.REMOVED.remove();
            Probe.NULLED.set(Boolean.TRUE);
            Probe.NULLED.set(null);
            ThreadLocals.close(scope);
        } finally {
            found = ThreadLocals.endSelfTest();
            Probe.BEFORE.remove();
            Probe.LEFT.remove();
            Probe.INHERITED.remove();
            Probe.CACHE.remove();
            Probe.REMOVED.remove();
            Probe.NULLED.remove();
        }
        List<Integer> expected = new ArrayList<Integer>();
        expected.add(Integer.valueOf(ThreadLocalScanner.hash(Probe.LEFT)));
        expected.add(Integer.valueOf(ThreadLocalScanner.hash(Probe.INHERITED)));
        expected.add(Integer.valueOf(ThreadLocalScanner.hash(Probe.CACHE)));
        List<Integer> actual = new ArrayList<Integer>();
        for (int hash : found) {
            actual.add(Integer.valueOf(hash));
        }
        if (actual.size() != expected.size() || !actual.containsAll(expected)) {
            return "error: found " + actual.size() + " leftovers, expected the 3 the scope left set";
        }
        return "ok";
    }

    /** The resolver names {@link Probe#LEFT}'s static field; with the Code Inventory fallback it is not checked. */
    static String holderStep(ThreadLocalResolver resolver) {
        if (!"unsafe".equals(resolver.initializationCheck())) {
            return "ok (not checked: " + resolver.initializationCheck() + ")";
        }
        String[] holder =
                resolver.resolve(Probe.LEFT, new String[] {Probe.class.getName()}, new String[0], 5_000_000_000L);
        String expected = Probe.class.getName() + ".LEFT";
        if (holder == null || !expected.equals(holder[0])) {
            return "error: resolved " + (holder == null ? "nothing" : holder[0]);
        }
        String[] cache =
                resolver.resolve(Probe.CACHE, new String[] {Probe.class.getName()}, new String[0], 5_000_000_000L);
        if (cache == null || !"true".equals(cache[1])) {
            return "error: the withInitial thread local had no initial value";
        }
        return "ok";
    }

    /** The sensor's status row, once a claim asked for it. */
    Map<String, Object> status() {
        if (!reported) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("id", SideEffects.THREAD_LOCALS);
        map.put("state", state);
        map.put("idle", Boolean.valueOf(idle()));
        map.put("durationMillis", Long.valueOf(ExecutorSensor.durationMillis(installMillis, selfTestMillis)));
        map.put("installMillis", Long.valueOf(installMillis));
        map.put("selfTestMillis", Long.valueOf(selfTestMillis));
        map.put("selfTestPassed", Boolean.valueOf(selfTestPassed));
        map.put("selfTestError", selfTestError);
        map.put("selfTestSteps", new LinkedHashMap<String, String>(steps));
        map.put("initializationCheck", initializationCheck);
        List<Object> hooks = new ArrayList<Object>();
        Map<String, String> results = selfTest;
        hooks.add(hook(SCAN, "java.lang.ThreadLocal$ThreadLocalMap", results));
        hooks.add(hook(HOLDER, "java.lang.ThreadLocal", results));
        map.put("hooks", hooks);
        return map;
    }

    private Map<String, Object> hook(String id, String type, Map<String, String> results) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("id", id);
        row.put("kind", "record");
        row.put("type", type);
        row.put("present", Boolean.TRUE);
        // No class is transformed: the scan reads the maps through the access the sensor was granted.
        row.put("transformed", Boolean.valueOf(granted));
        row.put("selfTest", results.getOrDefault(id, "not-run"));
        return row;
    }
}
