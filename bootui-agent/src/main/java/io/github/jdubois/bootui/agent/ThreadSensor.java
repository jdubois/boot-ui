package io.github.jdubois.bootui.agent;

import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import io.github.jdubois.bootui.agent.bridge.ThreadPropagation;
import java.lang.instrument.Instrumentation;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.MemberSubstitution;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

/**
 * The {@code threads} sensor (PLAN-v2 M5-2c): BootUI's context carried into threads started from owned work, and into
 * virtual threads. Kept apart from the executors sensor, with its own transformer, because {@code java.lang.Thread} is
 * the riskiest class to retransform: an application asks for it in {@code bootui.agent.sensors}. A behavioral
 * self-test starts a platform and, from JDK 21, a virtual thread; a failure of a present hook disables nothing but this
 * sensor: the bridge stops it for the claim at once, then its transformer is removed, and when that fails too, the
 * bridge stops it for good.
 */
final class ThreadSensor {

    static final String THREAD = "java.lang.Thread";
    static final String VIRTUAL_THREAD = "java.lang.VirtualThread";
    static final String TPE = "java.util.concurrent.ThreadPoolExecutor";

    /** Every hook: its id, the type it transforms, and whether it keys or applies. */
    static final String[][] HOOKS = {
        {"Thread.start", THREAD, "key"},
        {"VirtualThread.start", VIRTUAL_THREAD, "key"},
        {"Thread.run", THREAD, "apply"},
        {"Thread subclass run", "(claimed packages)", "apply"}
    };

    private final Instrumentation instrumentation;
    private final boolean privileged;
    private final TransformStats stats = new TransformStats();
    private volatile List<String> packages = Collections.emptyList();
    private volatile ResettableClassFileTransformer transformer;
    private volatile String state = "off";
    private volatile long durationMillis = -1;
    private volatile Map<String, String> selfTest = new LinkedHashMap<String, String>();
    private volatile Map<String, String> selfTestSteps = new LinkedHashMap<String, String>();
    private volatile long generation;
    private volatile boolean stuck;
    private volatile boolean selfTestPassed;
    private volatile String selfTestError;
    private Thread worker;
    private int pending;

    private static final int INSTALL = 1;
    private static final int RELEASE = 2;
    private static final int REFINE = 4;

    ThreadSensor(Instrumentation instrumentation, boolean privileged) {
        this.instrumentation = instrumentation;
        this.privileged = privileged;
    }

    /** Installs the sensor once and self-tests it, off the claiming thread. */
    synchronized void claimed(long claimGeneration, List<String> claimedPackages) {
        generation = claimGeneration;
        packages = Collections.unmodifiableList(new ArrayList<String>(claimedPackages));
        if (stuck || (transformer != null && selfTestPassed)) {
            return;
        }
        schedule(INSTALL);
    }

    /** New claimed packages: their {@code Thread} subclasses already loaded are retransformed, off the caller's thread. */
    synchronized void refined(List<String> claimedPackages) {
        packages = Collections.unmodifiableList(new ArrayList<String>(claimedPackages));
        if (pending != RELEASE && (transformer != null || pending != 0 || worker != null)) {
            schedule(pending | REFINE);
        }
    }

    /** Removes the transformer and restores {@code Thread} and every transformed class, off the caller's thread. */
    synchronized void release() {
        schedule(RELEASE);
    }

    private void schedule(int job) {
        pending = job;
        if (worker == null) {
            worker = AgentThreads.newThread("bootui-agent-threads", new Worker(), privileged);
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

    Map<String, Object> status() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("id", ThreadPropagation.SENSOR);
        map.put("state", state);
        map.put("durationMillis", Long.valueOf(durationMillis));
        map.put("selfTestPassed", Boolean.valueOf(selfTestPassed));
        map.put("selfTestError", selfTestError);
        map.put("selfTestSteps", new LinkedHashMap<String, String>(selfTestSteps));
        List<Object> hooks = new ArrayList<Object>();
        Map<String, String> results = selfTest;
        for (String[] hook : HOOKS) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("id", hook[0]);
            row.put("kind", hook[2]);
            row.put("type", hook[1]);
            boolean subclass = hook[1].startsWith("(");
            row.put("present", Boolean.valueOf(subclass || ExecutorSensor.present(hook[1])));
            row.put("transformed", Boolean.valueOf(subclass ? transformer != null : stats.transformed(hook[1])));
            row.put("selfTest", results.getOrDefault(hook[0], "not-run"));
            hooks.add(row);
        }
        map.put("hooks", hooks);
        stats.putInto(map);
        return map;
    }

    final class Worker implements Runnable {

        @Override
        public void run() {
            int job;
            while ((job = nextJob()) != 0) {
                try {
                    if ((job & RELEASE) != 0) {
                        reset();
                    }
                    if ((job & INSTALL) != 0) {
                        if (transformer == null) {
                            install();
                        }
                        selfTest();
                    }
                    if ((job & REFINE) != 0 && transformer != null) {
                        retransformSubclasses();
                    }
                } catch (Throwable ex) {
                    state = "failed";
                    stats.failure("threads: " + ex);
                    AgentBridge.message("the BootUI agent could not install its threads sensor: " + ex);
                }
            }
        }
    }

    private void install() {
        long started = System.nanoTime();
        state = "installing";
        InstallAction action = new InstallAction();
        transformer = privileged ? (ResettableClassFileTransformer) AgentThreads.privileged(action) : action.run();
        durationMillis = (System.nanoTime() - started) / 1_000_000L;
        state = "installed";
    }

    /** {@code Thread} subclasses in the claimed packages loaded before they were claimed. */
    private void retransformSubclasses() {
        List<Class<?>> classes = new ArrayList<Class<?>>();
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            if (type != Thread.class
                    && Thread.class.isAssignableFrom(type)
                    && AgentInstaller.inPackages(type.getName(), packages)
                    && instrumentation.isModifiableClass(type)) {
                classes.add(type);
            }
        }
        for (Class<?> type : classes) {
            try {
                instrumentation.retransformClasses(type);
            } catch (Throwable ex) {
                stats.failure(type.getName() + ": " + ex);
            }
        }
    }

    private void reset() {
        ResettableClassFileTransformer installed = transformer;
        transformer = null;
        selfTestPassed = false;
        if (installed == null) {
            state = stuck ? "release-failed" : "released";
            return;
        }
        boolean restored = installed.reset(
                instrumentation,
                AgentBuilder.RedefinitionStrategy.RETRANSFORMATION,
                AgentBuilder.RedefinitionStrategy.BatchAllocator.ForFixedSize.ofSize(64),
                new AgentBuilder.RedefinitionStrategy.Listener.Compound(
                        AgentBuilder.RedefinitionStrategy.Listener.BatchReallocator.splitting(),
                        stats.redefinitionFailures()));
        if (!restored) {
            stuck = true;
            ThreadPropagation.disable(generation, true);
        }
        state = restored ? "released" : "release-failed";
    }

    final class InstallAction implements PrivilegedAction<ResettableClassFileTransformer> {

        @Override
        public ResettableClassFileTransformer run() {
            return builder().installOn(instrumentation);
        }
    }

    private AgentBuilder builder() {
        MethodDescription runThreadTask = method(ThreadPropagation.class, "runThreadTask", Runnable.class);
        MethodDescription run = method(Runnable.class, "run");
        ElementMatcher.Junction<TypeDescription> subclasses = new SubclassMatcher();
        return stats.configure(new AgentBuilder.Default())
                .assureReadEdgeTo(instrumentation, ThreadPropagation.class)
                .ignore(ElementMatchers.not(ElementMatchers.<TypeDescription>namedOneOf(THREAD, VIRTUAL_THREAD, TPE)
                        .or(subclasses)))
                .type(ElementMatchers.named(THREAD))
                .transform(new ExecutorSensor.Visit(Advice.to(ThreadAdvice.Start.class)
                                .on(ElementMatchers.named("start")
                                        .and(ElementMatchers.takesArguments(0).or(ElementMatchers.takesArguments(1)))))
                        .and(MemberSubstitution.relaxed()
                                .method(ElementMatchers.is(run))
                                .replaceWith(runThreadTask)
                                .on(ElementMatchers.named("run")
                                        .and(ElementMatchers.takesArguments(0))
                                        .or(ElementMatchers.named("runWith")))))
                .type(ElementMatchers.named(VIRTUAL_THREAD))
                .transform(new ExecutorSensor.Visit(Advice.to(ThreadAdvice.VirtualStart.class)
                        .on(ElementMatchers.named("start").and(ElementMatchers.takesArguments(1)))))
                .type(ElementMatchers.named(TPE))
                .transform(new ExecutorSensor.Visit(Advice.to(ThreadAdvice.AddingWorker.class)
                        .on(ElementMatchers.named("addWorker").and(ElementMatchers.takesArguments(2)))))
                .type(subclasses)
                .transform(new ExecutorSensor.Visit(Advice.to(ThreadAdvice.SubclassRun.class)
                        .on(ElementMatchers.named("run")
                                .and(ElementMatchers.takesArguments(0))
                                .and(ElementMatchers.not(ElementMatchers.isAbstract())))));
    }

    /** {@code Thread} subclasses in the claimed packages: their own {@code run()} bypasses {@code Thread.run}. */
    final class SubclassMatcher extends ElementMatcher.Junction.AbstractBase<TypeDescription> {

        @Override
        public boolean matches(TypeDescription target) {
            if (target.isInterface() || !AgentInstaller.inPackages(target.getName(), packages)) {
                return false;
            }
            try {
                TypeDescription.Generic superClass = target.getSuperClass();
                while (superClass != null) {
                    TypeDescription erasure = superClass.asErasure();
                    if (erasure.getName().equals(THREAD)) {
                        return true;
                    }
                    superClass = erasure.getSuperClass();
                }
            } catch (Throwable unresolvable) {
                // A missing superclass: not a thread we can tell.
            }
            return false;
        }
    }

    private static MethodDescription method(Class<?> type, String name, Class<?>... parameters) {
        try {
            return new MethodDescription.ForLoadedMethod(type.getMethod(name, parameters));
        } catch (NoSuchMethodException ex) {
            throw new IllegalStateException(ex);
        }
    }

    // ---- self-test -----------------------------------------------------------------------------------------------

    private void selfTest() {
        Map<String, String> steps = new LinkedHashMap<String, String>();
        Map<String, Object> seen;
        ThreadPropagation.beginSelfTest();
        try {
            steps.put("platform", ExecutorSensor.step(new PlatformStep(), 5));
            steps.put(
                    "virtual",
                    ExecutorSensor.present(VIRTUAL_THREAD) ? ExecutorSensor.step(new VirtualStep(), 5) : "unsupported");
        } finally {
            seen = ThreadPropagation.endSelfTest();
        }
        Map<String, String> results = evaluate(seen, steps);
        selfTest = results;
        selfTestSteps = steps;
        List<String> failed = new ArrayList<String>();
        for (Map.Entry<String, String> result : results.entrySet()) {
            if ("failed".equals(result.getValue())) {
                failed.add(result.getKey());
            }
        }
        if (failed.isEmpty()) {
            selfTestPassed = true;
            selfTestError = null;
        } else {
            selfTestPassed = false;
            selfTestError = "self-test failed for " + failed + " " + steps;
            ThreadPropagation.disable(generation, false);
            AgentBridge.message(
                    "the BootUI agent's threads sensor failed its self-test and was removed: " + selfTestError);
            reset();
            state = stuck ? "self-test-failed (release-failed)" : "self-test-failed";
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, String> evaluate(Map<String, Object> seen, Map<String, String> steps) {
        Map<String, Object> keyed = (Map<String, Object>) seen.get("keyed");
        Map<String, Object> applied = (Map<String, Object>) seen.get("applied");
        Map<String, String> results = new LinkedHashMap<String, String>();
        results.put("Thread.start", result(keyed, "Thread.start", true, steps.get("platform")));
        results.put(
                "VirtualThread.start",
                result(keyed, "VirtualThread.start", ExecutorSensor.present(VIRTUAL_THREAD), steps.get("virtual")));
        results.put("Thread.run", result(applied, "Thread.run", true, steps.get("platform")));
        results.put("Thread subclass run", "not-exercised");
        return results;
    }

    private static String result(Map<String, Object> counts, String hook, boolean present, String outcome) {
        if (!present) {
            return "unsupported";
        }
        Object count = counts == null ? null : counts.get(hook);
        if (count instanceof Long && (Long) count > 0) {
            return "passed";
        }
        return "ok".equals(outcome) ? "failed" : "not-exercised (" + outcome + ")";
    }

    /** A platform thread started from the self-test thread, with no context class loader or inherited locals. */
    static final class PlatformStep implements ExecutorSensor.Step {

        @Override
        public void run(int seconds) throws Exception {
            Thread thread = new Thread(null, new ExecutorSensor.Noop(), "bootui-agent-self-test-thread", 0, false);
            thread.setDaemon(true);
            thread.setContextClassLoader(null);
            thread.start();
            thread.join(TimeUnit.SECONDS.toMillis(seconds));
            if (thread.isAlive()) {
                throw new java.util.concurrent.TimeoutException();
            }
        }
    }

    /** JDK 21+: a virtual thread, through reflection since the agent compiles for Java 17. */
    static final class VirtualStep implements ExecutorSensor.Step {

        @Override
        public void run(int seconds) throws Exception {
            Object builder = Thread.class.getMethod("ofVirtual").invoke(null);
            Thread thread = (Thread) Class.forName("java.lang.Thread$Builder")
                    .getMethod("start", Runnable.class)
                    .invoke(builder, new ExecutorSensor.Noop());
            thread.join(TimeUnit.SECONDS.toMillis(seconds));
            if (thread.isAlive()) {
                throw new java.util.concurrent.TimeoutException();
            }
        }
    }
}
