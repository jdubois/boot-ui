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
import java.util.Set;
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
        {"VirtualThread.run", VIRTUAL_THREAD, "apply"},
        {"Thread subclass run", "(claimed packages)", "apply"}
    };

    private final Instrumentation instrumentation;
    private final boolean privileged;
    /** Hooks left out of the transformer: only ever non-empty in BootUI's own mutation tests. */
    private final Set<String> omitted;

    private final TransformStats stats = new TransformStats();
    private volatile List<String> packages = Collections.emptyList();
    private SubclassMatcher installedSubclasses;
    private volatile ResettableClassFileTransformer transformer;
    private volatile String state = "off";
    private volatile long installMillis = -1;
    private volatile long selfTestMillis = -1;
    private volatile Map<String, String> selfTest = new LinkedHashMap<String, String>();
    private volatile Map<String, String> selfTestSteps = new LinkedHashMap<String, String>();
    private volatile long generation;
    private volatile boolean stuck;
    private volatile boolean selfTestPassed;
    private volatile String selfTestError;
    private Thread worker;
    private int pending;
    private boolean releasing;
    private long jobGeneration;

    private static final int INSTALL = 1;
    private static final int RELEASE = 2;
    private static final int REFINE = 4;

    ThreadSensor(Instrumentation instrumentation, boolean privileged) {
        this(instrumentation, privileged, Collections.<String>emptySet());
    }

    ThreadSensor(Instrumentation instrumentation, boolean privileged, Set<String> omitted) {
        this.instrumentation = instrumentation;
        this.privileged = privileged;
        this.omitted = omitted;
    }

    /** Installs the sensor once and self-tests it, off the claiming thread. */
    synchronized void claimed(long claimGeneration, List<String> claimedPackages) {
        boolean changedPackages = !packages.equals(claimedPackages);
        generation = claimGeneration;
        packages = Collections.unmodifiableList(new ArrayList<String>(claimedPackages));
        if (stuck) {
            return;
        }
        boolean refineInstalled = changedPackages && installedSubclasses != null;
        if (!releasing && installedSubclasses != null) {
            installedSubclasses.update(packages);
        }
        if (!releasing && transformer != null && selfTestPassed) {
            pending &= ~RELEASE;
            if (changedPackages) {
                schedule(pending | REFINE);
            }
            return;
        }
        schedule(INSTALL | (pending & REFINE) | (refineInstalled ? REFINE : 0));
    }

    /** New claimed packages: their {@code Thread} subclasses already loaded are retransformed, off the caller's thread. */
    synchronized void refined(List<String> claimedPackages) {
        packages = Collections.unmodifiableList(new ArrayList<String>(claimedPackages));
        if (!releasing && pending != RELEASE && installedSubclasses != null) {
            installedSubclasses.update(packages);
        }
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
        releasing = (job & RELEASE) != 0;
        jobGeneration = generation;
        if (job == 0) {
            worker = null;
        }
        return job;
    }

    Map<String, Object> status() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("id", ThreadPropagation.SENSOR);
        map.put("state", state);
        map.put("durationMillis", Long.valueOf(ExecutorSensor.durationMillis(installMillis, selfTestMillis)));
        map.put("installMillis", Long.valueOf(installMillis));
        map.put("selfTestMillis", Long.valueOf(selfTestMillis));
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
                        reset(jobGeneration);
                    }
                    if ((job & INSTALL) != 0 && !stuck) {
                        if (transformer == null) {
                            install();
                        }
                        selfTest(jobGeneration);
                    }
                    if ((job & REFINE) != 0 && transformer != null) {
                        retransformSubclasses();
                    }
                } catch (Throwable ex) {
                    selfTestPassed = false;
                    selfTestError = "threads sensor error: " + ex;
                    ThreadPropagation.disable(jobGeneration, stuck);
                    state = "failed";
                    stats.failure("threads: " + ex);
                    AgentBridge.message("the BootUI agent could not install its threads sensor: " + ex);
                }
            }
        }
    }

    void install() {
        long started = System.nanoTime();
        state = "installing";
        SubclassMatcher subclasses = newSubclassMatcher();
        InstallAction action = new InstallAction(subclasses);
        selfTestMillis = -1;
        try {
            transformer = privileged ? (ResettableClassFileTransformer) AgentThreads.privileged(action) : action.run();
        } catch (Throwable ex) {
            synchronized (this) {
                if (installedSubclasses == subclasses) {
                    installedSubclasses = null;
                }
            }
            throw ex;
        } finally {
            long elapsed = System.nanoTime() - started;
            stats.retransformedFor(elapsed);
            installMillis = elapsed / 1_000_000L;
        }
        state = "testing";
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
        long started = System.nanoTime();
        try {
            for (Class<?> type : classes) {
                try {
                    instrumentation.retransformClasses(type);
                } catch (Throwable ex) {
                    stats.failure(type.getName() + ": " + ex);
                }
            }
        } finally {
            stats.retransformedFor(System.nanoTime() - started);
        }
    }

    void reset(long claimGeneration) {
        ResettableClassFileTransformer installed;
        synchronized (this) {
            installed = transformer;
            transformer = null;
            selfTestPassed = false;
            if (installedSubclasses != null) {
                installedSubclasses.beginReset();
                installedSubclasses = null;
            }
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
            stuck = true;
            ThreadPropagation.disable(claimGeneration, true);
        }
        state = restored ? "released" : "release-failed";
    }

    final class InstallAction implements PrivilegedAction<ResettableClassFileTransformer> {

        private final SubclassMatcher subclasses;

        InstallAction(SubclassMatcher subclasses) {
            this.subclasses = subclasses;
        }

        @Override
        public ResettableClassFileTransformer run() {
            return builder(subclasses).installOn(instrumentation);
        }
    }

    private synchronized SubclassMatcher newSubclassMatcher() {
        installedSubclasses = new SubclassMatcher(packages);
        return installedSubclasses;
    }

    private AgentBuilder builder(SubclassMatcher subclasses) {
        MethodDescription runThreadTask = method(ThreadPropagation.class, "runThreadTask", Runnable.class);
        MethodDescription run = method(Runnable.class, "run");
        return stats.configure(new AgentBuilder.Default())
                .assureReadEdgeTo(instrumentation, ThreadPropagation.class)
                .ignore(ElementMatchers.not(ElementMatchers.<TypeDescription>namedOneOf(THREAD, VIRTUAL_THREAD, TPE)
                        .or(subclasses)))
                .type(ElementMatchers.named(THREAD))
                .transform(new ExecutorSensor.Visit(omitted)
                        .and(
                                "Thread.start",
                                Advice.to(ThreadAdvice.Start.class)
                                        .on(ElementMatchers.named("start")
                                                .and(ElementMatchers.takesArguments(0)
                                                        .or(ElementMatchers.takesArguments(1)))))
                        // One run point on JDK 21+ (runWith) for platform and virtual threads: counted per kind.
                        .and(
                                "Thread.run",
                                "VirtualThread.run",
                                MemberSubstitution.relaxed()
                                        .method(ElementMatchers.is(run))
                                        .replaceWith(runThreadTask)
                                        .on(ElementMatchers.named("run")
                                                .and(ElementMatchers.takesArguments(0))
                                                .or(ElementMatchers.named("runWith")))))
                .type(ElementMatchers.named(VIRTUAL_THREAD))
                .transform(new ExecutorSensor.Visit(omitted)
                        .and(
                                "VirtualThread.start",
                                Advice.to(ThreadAdvice.VirtualStart.class)
                                        .on(ElementMatchers.named("start").and(ElementMatchers.takesArguments(1)))))
                .type(ElementMatchers.named(TPE))
                .transform(new ExecutorSensor.Visit(omitted)
                        .and(
                                null,
                                Advice.to(ThreadAdvice.AddingWorker.class)
                                        .on(ElementMatchers.named("addWorker").and(ElementMatchers.takesArguments(2)))))
                .type(subclasses)
                .transform(new ExecutorSensor.Visit(omitted)
                        .and(
                                "Thread subclass run",
                                Advice.to(ThreadAdvice.SubclassRun.class)
                                        .on(ElementMatchers.named("run")
                                                .and(ElementMatchers.takesArguments(0))
                                                .and(ElementMatchers.not(ElementMatchers.isAbstract())))));
    }

    /** {@code Thread} subclasses in the claimed packages: their own {@code run()} bypasses {@code Thread.run}. */
    static final class SubclassMatcher extends ElementMatcher.Junction.AbstractBase<TypeDescription> {

        private volatile List<String> current;
        private List<String> history;

        SubclassMatcher(List<String> packages) {
            current = Collections.unmodifiableList(new ArrayList<String>(packages));
            history = current;
        }

        void update(List<String> packages) {
            List<String> expanded = new ArrayList<String>(history);
            for (String name : packages) {
                if (!expanded.contains(name)) {
                    expanded.add(name);
                }
            }
            history = Collections.unmodifiableList(expanded);
            current = Collections.unmodifiableList(new ArrayList<String>(packages));
        }

        void beginReset() {
            current = history;
        }

        @Override
        public boolean matches(TypeDescription target) {
            if (target.isInterface() || !AgentInstaller.inPackages(target.getName(), current)) {
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

    void selfTest(long claimGeneration) {
        beginSelfTest();
        long started = System.nanoTime();
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
        selfTestMillis = (System.nanoTime() - started) / 1_000_000L;
        recordSelfTest(claimGeneration, results, steps);
    }

    void beginSelfTest() {
        selfTestPassed = false;
        selfTestError = null;
        selfTestMillis = -1L;
        state = "testing";
    }

    void recordSelfTest(long claimGeneration, Map<String, String> results, Map<String, String> steps) {
        selfTest = results;
        selfTestSteps = steps;
        List<String> failed = new ArrayList<String>();
        for (String[] hook : HOOKS) {
            if (!hook[1].startsWith("(")
                    && ExecutorSensor.present(hook[1])
                    && !(VIRTUAL_THREAD.equals(hook[1]) && "unsupported".equals(results.get(hook[0])))
                    && !"passed".equals(results.get(hook[0]))) {
                failed.add(hook[0]);
            }
        }
        if (failed.isEmpty()) {
            selfTestError = null;
            selfTestPassed = true;
            state = "installed";
        } else {
            selfTestPassed = false;
            selfTestError = "self-test failed for " + failed + " " + steps;
            ThreadPropagation.disable(claimGeneration, false);
            AgentBridge.message(
                    "the BootUI agent's threads sensor failed its self-test and was removed: " + selfTestError);
            reset(claimGeneration);
            state = stuck ? "self-test-failed (release-failed)" : "self-test-failed";
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, String> evaluate(Map<String, Object> seen, Map<String, String> steps) {
        Map<String, Object> keyed = (Map<String, Object>) seen.get("keyed");
        Map<String, Object> applied = (Map<String, Object>) seen.get("applied");
        Map<String, String> results = new LinkedHashMap<String, String>();
        boolean virtualSupported =
                ExecutorSensor.present(VIRTUAL_THREAD) && !"unsupported".equals(steps.get("virtual"));
        results.put("Thread.start", result(keyed, "Thread.start", true, steps.get("platform")));
        results.put(
                "VirtualThread.start", result(keyed, "VirtualThread.start", virtualSupported, steps.get("virtual")));
        results.put("Thread.run", result(applied, "Thread.run", true, steps.get("platform")));
        results.put("VirtualThread.run", result(applied, "VirtualThread.run", virtualSupported, steps.get("virtual")));
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
            Object builder;
            try {
                builder = Thread.class.getMethod("ofVirtual").invoke(null);
            } catch (java.lang.reflect.InvocationTargetException ex) {
                if (ex.getCause() instanceof UnsupportedOperationException unsupported) {
                    throw unsupported;
                }
                throw ex;
            }
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
