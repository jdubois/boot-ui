package io.github.jdubois.bootui.agent;

import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import io.github.jdubois.bootui.agent.bridge.SideEffects;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatchers;

/**
 * The side-effect sensors (PLAN-v2 §5.16, M5-5): one transformer for every JDK side-effect hook, installed with the
 * hooks of the sensors the claim asks for, each hook delegating to the bridge ({@link SideEffectsAdvice}), and one mask
 * in the bridge saying which sensors record ({@link SideEffects#enable}). M5-5a ships {@value SideEffects#PROCESSES}:
 * {@code ProcessBuilder.start(Redirect[])}; M5-5c the JDK hook of {@value SideEffects#BLOCKING}: every public
 * {@code LockSupport.park*} method, whose advice returns at entry off event loops (its call-site hooks on
 * {@code Thread.sleep} and {@code Object.wait} are a visit of {@link ApplicationMethodsSensor}). A self-test per hook
 * runs it on the sensor's worker thread, which the bridge counts and never records: the processes hook starts a command
 * holding a NUL character, which {@code ProcessBuilder} refuses before spawning anything, and the park hook parks with
 * its permit already given. A sensor whose hook fails its self-test is disabled alone: the transformer is removed, then
 * installed again with the other sensors' hooks and self-tested; the bridge stops the failing sensor for the claim at
 * once. A claim asking for another set of side-effect sensors reinstalls the transformer with that set's hooks; a claim
 * asking for none, or a release, removes it.
 */
final class SideEffectsSensor {

    static final String PROCESS_BUILDER = "java.lang.ProcessBuilder";

    static final String LOCK_SUPPORT = "java.util.concurrent.locks.LockSupport";

    /** Every hook: its id, the type it transforms, its kind, and its sensor. */
    static final String[][] HOOKS = {
        {"ProcessBuilder.start", PROCESS_BUILDER, "record", SideEffects.PROCESSES},
        {"LockSupport.park", LOCK_SUPPORT, "record", SideEffects.BLOCKING}
    };

    /** The side-effect sensors this transformer carries hooks for, in status order. */
    static final String[] SENSORS = {SideEffects.PROCESSES, SideEffects.BLOCKING};

    /** The command the processes self-test starts: {@code ProcessBuilder} refuses a NUL before spawning. */
    static final String SELF_TEST_COMMAND = "bootui-agent-self-test\u0000";

    private static final int INSTALL = 1;
    private static final int RELEASE = 2;

    private final Instrumentation instrumentation;
    private final boolean privileged;
    /** Hooks left out of the transformer: only ever non-empty in BootUI's own mutation tests. */
    private final Set<String> omitted;

    private final TransformStats stats = new TransformStats();
    private volatile ResettableClassFileTransformer transformer;
    /** The sensors the installed transformer carries the hooks of. */
    private volatile int installedMask;
    /** The sensors the current claim asks for. */
    private volatile int wantedMask;
    /** The sensors of {@link #wantedMask} whose hooks failed their self-test: left out until another set is claimed. */
    private volatile int failedMask;
    /** Every sensor a claim ever asked for: reported, with its state, until the JVM ends. */
    private volatile int reportedMask;

    private volatile String state = "off";
    private volatile long installMillis = -1;
    private volatile long selfTestMillis = -1;
    private volatile boolean selfTestPassed;
    private volatile String selfTestError;
    private volatile Map<String, String> selfTest = new LinkedHashMap<String, String>();
    private volatile Map<String, String> selfTestSteps = new LinkedHashMap<String, String>();
    /** The sensors whose hooks failed their self-test for the current claim, with the error: removed alone. */
    private final Map<String, String> sensorFailures = new java.util.concurrent.ConcurrentHashMap<String, String>();

    private volatile boolean stuck;
    private Thread worker;
    private int pending;
    private boolean exitWorkerStarted;

    SideEffectsSensor(Instrumentation instrumentation, boolean privileged, Set<String> omitted) {
        this.instrumentation = instrumentation;
        this.privileged = privileged;
        this.omitted = omitted;
    }

    /** A claim asking for the side-effect sensors of {@code mask}: installs their hooks and self-tests them. */
    synchronized void claimed(int mask) {
        if (mask != wantedMask) {
            // Another set of sensors: each is self-tested again. The same set keeps its failures, so a reload never
            // retransforms the JDK's classes again for a hook known to fail.
            sensorFailures.clear();
            failedMask = 0;
        }
        wantedMask = mask;
        reportedMask |= mask;
        if (stuck) {
            return;
        }
        // Only while no job runs: a release the worker is running would remove the hooks after this enabled them.
        if (transformer != null && installedMask == (mask & ~failedMask) && selfTestPassed && worker == null) {
            SideEffects.enable(mask & ~failedMask);
            return;
        }
        schedule(INSTALL);
    }

    /** Removes the transformer and restores every transformed class, off the caller's thread. */
    synchronized void release() {
        wantedMask = 0;
        schedule(RELEASE);
    }

    private void schedule(int job) {
        pending = job;
        if (worker == null) {
            worker = AgentThreads.newThread("bootui-agent-side-effects", new Worker(), privileged);
            worker.start();
        }
    }

    private synchronized int nextJob() {
        int job = pending;
        pending = 0;
        if (job == 0) {
            worker = null;
        }
        return job;
    }

    /** Whether the worker has no job left. */
    synchronized boolean idle() {
        return worker == null && pending == 0;
    }

    /** Starts the one thread process exits complete on, once per JVM, through the agent's own thread factory. */
    private synchronized void startExitWorker() {
        if (exitWorkerStarted) {
            return;
        }
        exitWorkerStarted = true;
        Runnable exits = SideEffects.exitWorker();
        if (exits != null) {
            try {
                AgentThreads.newThread("bootui-agent-process-exits", exits, privileged)
                        .start();
            } catch (Throwable ex) {
                // The sensor still records starts; exits are counted as not watched.
                stats.failure("process exits thread: " + ex);
                AgentBridge.message("the BootUI agent could not start its process exits thread: " + ex);
            }
        }
    }

    final class Worker implements Runnable {

        @Override
        public void run() {
            int job;
            while ((job = nextJob()) != 0) {
                int mask = wantedMask & ~failedMask;
                try {
                    if ((job & RELEASE) != 0 || (job & INSTALL) != 0 && transformer != null && installedMask != mask) {
                        SideEffects.disable(installedMask, null);
                        reset();
                    }
                    if ((job & INSTALL) != 0 && !stuck && mask != 0) {
                        if (transformer == null) {
                            install(mask);
                        }
                        selfTest(mask);
                    }
                } catch (Throwable ex) {
                    selfTestPassed = false;
                    selfTestError = "side-effect sensors error: " + ex;
                    SideEffects.disable(mask, selfTestError);
                    state = "failed";
                    stats.failure("side effects: " + ex);
                    AgentBridge.message("the BootUI agent could not install its side-effect sensors: " + ex);
                }
            }
        }
    }

    void install(int mask) {
        long started = System.nanoTime();
        state = "installing";
        selfTestMillis = -1;
        SideEffects.warm();
        startExitWorker();
        InstallAction action = new InstallAction(mask);
        try {
            transformer = privileged ? (ResettableClassFileTransformer) AgentThreads.privileged(action) : action.run();
            installedMask = mask;
        } finally {
            long elapsed = System.nanoTime() - started;
            stats.retransformedFor(elapsed);
            installMillis = elapsed / 1_000_000L;
        }
        state = "testing";
    }

    void reset() {
        ResettableClassFileTransformer installed;
        synchronized (this) {
            installed = transformer;
            transformer = null;
            installedMask = 0;
            selfTestPassed = false;
        }
        if (installed == null) {
            state = stuck ? "release-failed" : "released";
            return;
        }
        long started = System.nanoTime();
        boolean restored;
        try {
            restored = installed.reset(
                    instrumentation,
                    AgentBuilder.RedefinitionStrategy.RETRANSFORMATION,
                    AgentBuilder.RedefinitionStrategy.BatchAllocator.ForFixedSize.ofSize(64),
                    new AgentBuilder.RedefinitionStrategy.Listener.Compound(
                            AgentBuilder.RedefinitionStrategy.Listener.BatchReallocator.splitting(),
                            stats.redefinitionFailures()));
        } finally {
            stats.retransformedFor(System.nanoTime() - started);
        }
        if (!restored) {
            // The hooks stay in the JDK's classes: the bridge keeps their sensors off for good.
            stuck = true;
            SideEffects.disable(-1, "the side-effect sensors' transformer could not be removed");
        }
        state = restored ? "released" : "release-failed";
    }

    final class InstallAction implements PrivilegedAction<ResettableClassFileTransformer> {

        private final int mask;

        InstallAction(int mask) {
            this.mask = mask;
        }

        @Override
        public ResettableClassFileTransformer run() {
            return builder(mask).installOn(instrumentation);
        }
    }

    private AgentBuilder builder(int mask) {
        ExecutorSensor.Visit processBuilder = new ExecutorSensor.Visit(omitted);
        ExecutorSensor.Visit lockSupport = new ExecutorSensor.Visit(omitted);
        if ((mask & SideEffects.MASK_BLOCKING) != 0) {
            // Every public park method: park, parkNanos, and parkUntil, with and without a blocker.
            lockSupport.and(
                    "LockSupport.park",
                    Advice.to(SideEffectsAdvice.Park.class)
                            .on(ElementMatchers.nameStartsWith("park")
                                    .and(ElementMatchers.isPublic())
                                    .and(ElementMatchers.isStatic())));
        }
        if ((mask & SideEffects.MASK_PROCESSES) != 0) {
            processBuilder.and(
                    "ProcessBuilder.start",
                    Advice.to(SideEffectsAdvice.ProcessStart.class)
                            .on(ElementMatchers.named("start")
                                    .and(ElementMatchers.isPrivate())
                                    .and(ElementMatchers.takesArguments(1))
                                    .and(ElementMatchers.takesArgument(0, ProcessBuilder.Redirect[].class))));
        }
        return stats.configure(new AgentBuilder.Default())
                .assureReadEdgeTo(instrumentation, SideEffects.class)
                .ignore(ElementMatchers.not(ElementMatchers.<TypeDescription>namedOneOf(PROCESS_BUILDER, LOCK_SUPPORT)))
                .type(ElementMatchers.named(PROCESS_BUILDER))
                .transform(processBuilder)
                .type(ElementMatchers.named(LOCK_SUPPORT))
                .transform(lockSupport);
    }

    // ---- self-test -----------------------------------------------------------------------------------------------

    void selfTest(int mask) {
        selfTestPassed = false;
        selfTestError = null;
        state = "testing";
        long started = System.nanoTime();
        Map<String, String> steps = new LinkedHashMap<String, String>();
        Map<String, Object> hits;
        SideEffects.beginSelfTest();
        try {
            if ((mask & SideEffects.MASK_PROCESSES) != 0) {
                steps.put("processes", processStep());
            }
            if ((mask & SideEffects.MASK_BLOCKING) != 0) {
                steps.put(SideEffects.BLOCKING, parkStep());
            }
        } finally {
            hits = SideEffects.endSelfTest();
        }
        selfTestMillis = (System.nanoTime() - started) / 1_000_000L;
        Map<String, String> results = evaluate(mask, hits, steps);
        // A sensor failing earlier in this claim keeps its hooks' verdicts beside the others' new ones.
        Map<String, String> merged = new LinkedHashMap<String, String>(selfTest);
        merged.putAll(results);
        for (String[] hook : HOOKS) {
            if ((mask & SideEffects.bit(hook[3])) == 0 && sensorFailures.containsKey(hook[3])) {
                merged.put(hook[0], selfTest.getOrDefault(hook[0], "failed"));
            }
        }
        selfTestSteps = steps;
        List<String> failed = new ArrayList<String>();
        int failedMask = 0;
        for (String[] hook : HOOKS) {
            if ((mask & SideEffects.bit(hook[3])) != 0 && !"passed".equals(results.get(hook[0]))) {
                failed.add(hook[0]);
                failedMask |= SideEffects.bit(hook[3]);
            }
        }
        if (failed.isEmpty()) {
            SideEffects.enable(mask);
            state = "installed";
            selfTestPassed = true;
            selfTest = merged;
            return;
        }
        String error = "self-test failed for " + failed + " " + steps;
        SideEffects.disable(failedMask, error);
        for (String id : SENSORS) {
            if ((failedMask & SideEffects.bit(id)) != 0) {
                sensorFailures.put(id, error);
                failedMask |= SideEffects.bit(id);
            }
        }
        AgentBridge.message("the BootUI agent's side-effect sensors failed their self-test and were removed: " + error);
        selfTest = merged;
        int remaining = mask & ~failedMask;
        // The verdict before the transformer's removal, which takes a while: status reports it at once.
        state = remaining == 0 ? "self-test-failed" : "installing";
        selfTestError = error;
        reset();
        if (remaining != 0 && !stuck) {
            // Only the failing sensors are removed: the others are installed again without their hooks.
            install(remaining);
            selfTest(remaining);
            return;
        }
        state = stuck ? "self-test-failed (release-failed)" : "self-test-failed";
    }

    /**
     * Parks this agent thread with its permit already given, so it returns at once, then for one nanosecond: the hook
     * counts both, records neither.
     */
    static String parkStep() {
        try {
            java.util.concurrent.locks.LockSupport.unpark(Thread.currentThread());
            java.util.concurrent.locks.LockSupport.park(SideEffectsSensor.class);
            java.util.concurrent.locks.LockSupport.parkNanos(1L);
            return "ok";
        } catch (Throwable ex) {
            return "error: " + ex;
        }
    }

    /** Starts a command {@code ProcessBuilder} refuses before spawning anything: the hook runs, nothing starts. */
    static String processStep() {
        try {
            Process process = new ProcessBuilder(SELF_TEST_COMMAND).start();
            process.destroyForcibly();
            return "error: a command holding a NUL character started";
        } catch (IOException expected) {
            return "ok";
        } catch (Throwable ex) {
            return "error: " + ex;
        }
    }

    static Map<String, String> evaluate(int mask, Map<String, Object> hits, Map<String, String> steps) {
        Map<String, String> results = new LinkedHashMap<String, String>();
        for (String[] hook : HOOKS) {
            if ((mask & SideEffects.bit(hook[3])) == 0) {
                results.put(hook[0], "not-installed");
                continue;
            }
            Object count = hits == null ? null : hits.get(hook[0]);
            String outcome = steps.get(hook[3]);
            if (count instanceof Long && (Long) count > 0) {
                results.put(hook[0], "passed");
            } else {
                results.put(hook[0], "ok".equals(outcome) ? "failed" : "not-exercised (" + outcome + ")");
            }
        }
        return results;
    }

    // ---- status ----------------------------------------------------------------------------------------------------

    /** One status row per side-effect sensor a claim asked for since the JVM started. */
    List<Map<String, Object>> status() {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (String id : SENSORS) {
            int bit = SideEffects.bit(id);
            if ((reportedMask & bit) == 0) {
                continue;
            }
            String failure = sensorFailures.get(id);
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("id", id);
            map.put(
                    "state",
                    failure != null ? (stuck ? "self-test-failed (release-failed)" : "self-test-failed") : state);
            map.put("idle", Boolean.valueOf(idle()));
            map.put("durationMillis", Long.valueOf(ExecutorSensor.durationMillis(installMillis, selfTestMillis)));
            map.put("installMillis", Long.valueOf(installMillis));
            map.put("selfTestMillis", Long.valueOf(selfTestMillis));
            map.put("selfTestPassed", Boolean.valueOf(failure == null && selfTestPassed && (installedMask & bit) != 0));
            map.put("selfTestError", failure != null ? failure : selfTestError);
            map.put("selfTestSteps", new LinkedHashMap<String, String>(selfTestSteps));
            List<Object> hooks = new ArrayList<Object>();
            Map<String, String> results = selfTest;
            for (String[] hook : HOOKS) {
                if (!id.equals(hook[3])) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("id", hook[0]);
                row.put("kind", hook[2]);
                row.put("type", hook[1]);
                row.put("present", Boolean.valueOf(ExecutorSensor.present(hook[1])));
                row.put("transformed", Boolean.valueOf((installedMask & bit) != 0 && stats.transformed(hook[1])));
                row.put("selfTest", results.getOrDefault(hook[0], "not-run"));
                hooks.add(row);
            }
            map.put("hooks", hooks);
            stats.putInto(map);
            rows.add(map);
        }
        return rows;
    }
}
