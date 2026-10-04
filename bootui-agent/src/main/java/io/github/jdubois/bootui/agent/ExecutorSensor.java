package io.github.jdubois.bootui.agent;

import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import io.github.jdubois.bootui.agent.bridge.TaskPropagation;
import java.lang.instrument.Instrumentation;
import java.security.PrivilegedAction;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.MemberSubstitution;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;

/**
 * The {@code executors} sensor (PLAN-v2 M5-2, D32): identity-preserving propagation of BootUI's context through the JDK's
 * executors. It installs one transformer on the JDK executor types only, runs a behavioral self-test on private pools,
 * and reports each hook: whether its type exists on this JDK, whether it was transformed, and what the self-test saw. A
 * self-test failure of a core hook disables propagation for the claim, and the next claim tests again.
 */
final class ExecutorSensor {

    static final String TPE = "java.util.concurrent.ThreadPoolExecutor";
    static final String STPE = "java.util.concurrent.ScheduledThreadPoolExecutor";
    static final String FJP = "java.util.concurrent.ForkJoinPool";
    static final String FJT = "java.util.concurrent.ForkJoinTask";
    static final String RUNNABLE_EXECUTE_ACTION = "java.util.concurrent.ForkJoinTask$RunnableExecuteAction";
    static final String ADAPTED = "java.util.concurrent.ForkJoinTask$Adapted";
    static final String DELAYED = "java.util.concurrent.DelayScheduler$ScheduledForkJoinTask";
    static final String THREAD_PER_TASK = "java.util.concurrent.CompletableFuture$ThreadPerTaskExecutor";
    static final String ASYNC_SUPPLY = "java.util.concurrent.CompletableFuture$AsyncSupply";
    static final String ASYNC_RUN = "java.util.concurrent.CompletableFuture$AsyncRun";
    static final String FUTURE_TASK = "java.util.concurrent.FutureTask";
    static final String COMPLETABLE_FUTURE = "java.util.concurrent.CompletableFuture";

    /** Every hook: its id, the type it transforms, and whether it keys or applies. */
    static final String[][] HOOKS = {
        {"ThreadPoolExecutor", TPE, "key"},
        {"ScheduledThreadPoolExecutor", STPE, "key"},
        {"ForkJoinPool", FJP, "key"},
        {"ForkJoinTask adapters", RUNNABLE_EXECUTE_ACTION, "key"},
        {"ForkJoinTask.fork", FJT, "key"},
        {"DelayScheduler", DELAYED, "key"},
        {"CompletableFuture.ThreadPerTaskExecutor", THREAD_PER_TASK, "key"},
        {"ThreadPoolExecutor.runWorker", TPE, "apply"},
        {"ForkJoinTask.doExec", FJT, "apply"},
        {"CompletableFuture.Async", ASYNC_SUPPLY, "apply"}
    };

    /** The hooks every supported JDK has: a self-test failure of one of them disables propagation. */
    static final String[] CORE = {"ThreadPoolExecutor", "ThreadPoolExecutor.runWorker", "ForkJoinTask.doExec"};

    private final Instrumentation instrumentation;
    private final boolean privileged;
    private final TransformStats stats = new TransformStats();
    private volatile ResettableClassFileTransformer transformer;
    private volatile String state = "off";
    private volatile long durationMillis = -1;
    private volatile Map<String, String> selfTest = new LinkedHashMap<String, String>();
    private volatile boolean selfTestPassed;
    private volatile String selfTestError;
    private volatile Map<String, String> selfTestSteps = new LinkedHashMap<String, String>();
    private Thread worker;
    private long pendingGeneration = -1L;
    private boolean releasing;

    ExecutorSensor(Instrumentation instrumentation, boolean privileged) {
        this.instrumentation = instrumentation;
        this.privileged = privileged;
    }

    /** Installs the sensor once, then self-tests it, off the claiming thread; later claims test again after a failure. */
    synchronized void claimed(long generation) {
        if (!releasing && transformer != null && selfTestPassed) {
            pendingGeneration = -1L;
            return;
        }
        pendingGeneration = generation;
        if (worker == null) {
            worker = AgentThreads.newThread("bootui-agent-executors", new Worker(), privileged);
            worker.start();
        }
    }

    /** Removes the transformer and restores every executor class, off the caller's thread. */
    synchronized void release() {
        pendingGeneration = -2L;
        if (worker == null) {
            worker = AgentThreads.newThread("bootui-agent-executors", new Worker(), privileged);
            worker.start();
        }
    }

    Map<String, Object> status() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("id", TaskPropagation.SENSOR);
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
            row.put("present", Boolean.valueOf(present(hook[1])));
            boolean transformed = RUNNABLE_EXECUTE_ACTION.equals(hook[1])
                    ? stats.transformed(hook[1]) || stats.transformedAnyStartingWith(ADAPTED)
                    : stats.transformed(hook[1]);
            row.put("transformed", Boolean.valueOf(transformed));
            row.put("selfTest", results.getOrDefault(hook[0], "not-run"));
            hooks.add(row);
        }
        map.put("hooks", hooks);
        stats.putInto(map);
        return map;
    }

    private synchronized long nextJob() {
        long job = pendingGeneration;
        pendingGeneration = -1L;
        releasing = job == -2L;
        if (job == -1L) {
            worker = null;
        }
        return job;
    }

    final class Worker implements Runnable {

        @Override
        public void run() {
            long job;
            while ((job = nextJob()) != -1L) {
                try {
                    if (job == -2L) {
                        reset();
                    } else {
                        if (transformer == null) {
                            install();
                        }
                        selfTest(job);
                    }
                } catch (Throwable ex) {
                    state = "failed";
                    stats.failure("executors: " + ex);
                    AgentBridge.message("the BootUI agent could not install its executors sensor: " + ex);
                }
            }
        }
    }

    void install() {
        long started = System.nanoTime();
        state = "installing";
        InstallAction action = new InstallAction();
        transformer = privileged ? (ResettableClassFileTransformer) AgentThreads.privileged(action) : action.run();
        durationMillis = (System.nanoTime() - started) / 1_000_000L;
        state = "installed";
    }

    void reset() {
        ResettableClassFileTransformer installed = transformer;
        transformer = null;
        selfTestPassed = false;
        if (installed == null) {
            return;
        }
        boolean restored = installed.reset(
                instrumentation,
                AgentBuilder.RedefinitionStrategy.RETRANSFORMATION,
                AgentBuilder.RedefinitionStrategy.BatchAllocator.ForFixedSize.ofSize(64),
                new AgentBuilder.RedefinitionStrategy.Listener.Compound(
                        AgentBuilder.RedefinitionStrategy.Listener.BatchReallocator.splitting(),
                        stats.redefinitionFailures()));
        state = restored ? "released" : "release-failed";
    }

    final class InstallAction implements PrivilegedAction<ResettableClassFileTransformer> {

        @Override
        public ResettableClassFileTransformer run() {
            return builder().installOn(instrumentation);
        }
    }

    private AgentBuilder builder() {
        MethodDescription runTask = method(TaskPropagation.class, "runTask", Runnable.class);
        MethodDescription run = method(Runnable.class, "run");
        MethodDescription bridgeOffer = method(TaskPropagation.class, "offer", BlockingQueue.class, Object.class);
        MethodDescription queueOffer = method(BlockingQueue.class, "offer", Object.class);
        return stats.configure(new AgentBuilder.Default())
                .assureReadEdgeTo(instrumentation, TaskPropagation.class)
                .ignore(ElementMatchers.not(executorTypes()))
                .type(ElementMatchers.named(TPE))
                .transform(new Visit(Advice.to(ExecutorAdvice.AddWorker.class)
                                .on(ElementMatchers.named("addWorker").and(ElementMatchers.takesArguments(2))))
                        .and(Advice.to(ExecutorAdvice.Remove.class)
                                .on(ElementMatchers.named("remove")
                                        .and(ElementMatchers.takesArguments(Runnable.class))))
                        .and(MemberSubstitution.relaxed()
                                .method(ElementMatchers.is(queueOffer))
                                .replaceWith(bridgeOffer)
                                .on(ElementMatchers.named("execute")
                                        .and(ElementMatchers.takesArguments(Runnable.class))))
                        .and(MemberSubstitution.relaxed()
                                .method(ElementMatchers.is(run))
                                .replaceWith(runTask)
                                .on(ElementMatchers.named("runWorker"))))
                .type(ElementMatchers.named(STPE))
                .transform(new Visit(Advice.to(ExecutorAdvice.DelayedExecute.class)
                        .on(ElementMatchers.named("delayedExecute").and(ElementMatchers.takesArguments(1)))))
                .type(ElementMatchers.named(FJP))
                .transform(new Visit(Advice.to(ExecutorAdvice.ForkJoinRoot.class)
                                .on(ElementMatchers.named("externalSubmit")
                                        .and(ElementMatchers.takesArguments(ForkJoinTask.class))))
                        .and(Advice.to(ExecutorAdvice.ForkJoinPoolSubmit.class)
                                .on(ElementMatchers.named("poolSubmit")
                                        .and(ElementMatchers.takesArguments(boolean.class, ForkJoinTask.class)))))
                .type(ElementMatchers.named(FJT))
                .transform(new Visit(Advice.to(ExecutorAdvice.DoExec.class).on(ElementMatchers.named("doExec")))
                        .and(Advice.to(ExecutorAdvice.Fork.class)
                                .on(ElementMatchers.named("fork").and(ElementMatchers.takesArguments(0))))
                        .and(Advice.to(ExecutorAdvice.BodyCompleted.class)
                                .on(ElementMatchers.namedOneOf(
                                        "setDone", "trySetThrown", "trySetException", "setExceptionalCompletion"))))
                .type(ElementMatchers.named(FUTURE_TASK))
                .transform(new Visit(Advice.to(ExecutorAdvice.BodyCompleted.class)
                        .on(ElementMatchers.namedOneOf("set", "setException"))))
                .type(ElementMatchers.named(COMPLETABLE_FUTURE))
                .transform(new Visit(Advice.to(ExecutorAdvice.BodyCompleted.class)
                        .on(ElementMatchers.namedOneOf("completeValue", "completeNull", "completeThrowable"))))
                .type(ElementMatchers.nameStartsWith(ADAPTED).or(ElementMatchers.named(RUNNABLE_EXECUTE_ACTION)))
                .transform(new Visit(Advice.to(ExecutorAdvice.Adapter.class).on(ElementMatchers.isConstructor())))
                .type(ElementMatchers.named(DELAYED))
                .transform(new Visit(Advice.to(ExecutorAdvice.Delayed.class)
                        .on(ElementMatchers.isConstructor().and(ElementMatchers.takesArguments(6)))))
                .type(ElementMatchers.named(THREAD_PER_TASK))
                .transform(new Visit(Advice.to(ExecutorAdvice.ThreadPerTask.class)
                        .on(ElementMatchers.named("execute").and(ElementMatchers.takesArguments(Runnable.class)))))
                .type(ElementMatchers.namedOneOf(ASYNC_SUPPLY, ASYNC_RUN))
                .transform(new Visit(Advice.to(ExecutorAdvice.AsyncRun.class)
                        .on(ElementMatchers.named("run").and(ElementMatchers.takesArguments(0)))));
    }

    static ElementMatcher.Junction<TypeDescription> executorTypes() {
        return ElementMatchers.<TypeDescription>namedOneOf(
                        TPE,
                        STPE,
                        FJP,
                        FJT,
                        RUNNABLE_EXECUTE_ACTION,
                        DELAYED,
                        THREAD_PER_TASK,
                        ASYNC_SUPPLY,
                        ASYNC_RUN,
                        FUTURE_TASK,
                        COMPLETABLE_FUTURE)
                .or(ElementMatchers.nameStartsWith(ADAPTED));
    }

    static boolean present(String type) {
        return Object.class.getResource("/" + type.replace('.', '/') + ".class") != null;
    }

    private static MethodDescription method(Class<?> type, String name, Class<?>... parameters) {
        try {
            return new MethodDescription.ForLoadedMethod(type.getMethod(name, parameters));
        } catch (NoSuchMethodException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Applies a list of visitors to a type. */
    static final class Visit implements AgentBuilder.Transformer {

        private final List<net.bytebuddy.asm.AsmVisitorWrapper> visitors = new ArrayList<>();

        Visit(net.bytebuddy.asm.AsmVisitorWrapper visitor) {
            visitors.add(visitor);
        }

        Visit and(net.bytebuddy.asm.AsmVisitorWrapper visitor) {
            visitors.add(visitor);
            return this;
        }

        @Override
        public DynamicType.Builder<?> transform(
                DynamicType.Builder<?> builder,
                TypeDescription type,
                ClassLoader classLoader,
                JavaModule module,
                ProtectionDomain protectionDomain) {
            DynamicType.Builder<?> result = builder;
            for (net.bytebuddy.asm.AsmVisitorWrapper visitor : visitors) {
                result = result.visit(visitor);
            }
            return result;
        }
    }

    // ---- self-test -----------------------------------------------------------------------------------------------

    /** Which self-test step exercises each hook. */
    static final Map<String, String> STEP_OF_HOOK = stepOfHook();

    private static Map<String, String> stepOfHook() {
        Map<String, String> map = new LinkedHashMap<String, String>();
        map.put("ThreadPoolExecutor", "thread-pool");
        map.put("ThreadPoolExecutor.runWorker", "thread-pool");
        map.put("CompletableFuture.Async", "thread-pool");
        map.put("ScheduledThreadPoolExecutor", "scheduled");
        map.put("ForkJoinPool", "fork-join");
        map.put("ForkJoinTask adapters", "fork-join");
        map.put("ForkJoinTask.doExec", "fork-join");
        map.put("DelayScheduler", "delayed");
        map.put("ForkJoinTask.fork", "fork");
        return map;
    }

    /**
     * Runs marker tasks through private pools on this agent thread, one step at a time: the bridge recognizes them by
     * identity and counts, per hook, the marker tasks it keyed and applied, without ever calling the engine. Each step
     * has its own outcome; only a core hook that ran and saw nothing disables propagation. The common-pool step runs
     * last, with a short wait.
     */
    void selfTest(long generation) {
        Map<String, String> steps = new LinkedHashMap<String, String>();
        TaskPropagation.asyncApplies(present(ASYNC_SUPPLY));
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                1, 1, 1, TimeUnit.MINUTES, new LinkedBlockingQueue<Runnable>(), new SelfTestThreads());
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, new SelfTestThreads());
        ForkJoinPool forkJoin = new ForkJoinPool(2, new SelfTestForkJoinThreads(), null, false);
        Map<String, Object> seen;
        TaskPropagation.beginSelfTest();
        try {
            steps.put("thread-pool", step(new ThreadPoolStep(pool), 5));
            steps.put("scheduled", step(new ScheduledStep(scheduler), 5));
            steps.put("fork-join", step(new ForkJoinStep(forkJoin), 5));
            steps.put("delayed", present(DELAYED) ? step(new DelayedStep(forkJoin), 5) : "unsupported");
            steps.put("fork", step(new ForkStep(), 2));
            try {
                Thread.sleep(20);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        } finally {
            seen = TaskPropagation.endSelfTest();
            pool.shutdownNow();
            scheduler.shutdownNow();
            forkJoin.shutdownNow();
        }
        Map<String, String> results = evaluate(seen, steps);
        selfTest = results;
        selfTestSteps = steps;
        TaskPropagation.asyncApplies("passed".equals(results.get("CompletableFuture.Async")));
        List<String> failed = new ArrayList<String>();
        for (String core : CORE) {
            if ("failed".equals(results.get(core))) {
                failed.add(core);
            }
        }
        selfTestError = failed.isEmpty() ? null : "core hooks saw no task: " + failed;
        if (failed.isEmpty()) {
            selfTestPassed = true;
            TaskPropagation.enable();
        } else {
            selfTestPassed = false;
            TaskPropagation.disable(generation, "self-test failed for " + failed + " " + steps);
        }
    }

    /** Runs a step with a timeout in seconds: {@code ok}, {@code timeout}, or {@code error: ...}. */
    static String step(Step step, int seconds) {
        try {
            step.run(seconds);
            return "ok";
        } catch (java.util.concurrent.TimeoutException ex) {
            return "timeout";
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return "interrupted";
        } catch (Throwable ex) {
            return "error: " + ex;
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, String> evaluate(Map<String, Object> seen, Map<String, String> steps) {
        Map<String, Object> keyed = (Map<String, Object>) seen.get("keyed");
        Map<String, Object> applied = (Map<String, Object>) seen.get("applied");
        Map<String, String> results = new LinkedHashMap<String, String>();
        for (String[] hook : HOOKS) {
            Map<String, Object> counts = "key".equals(hook[2]) ? keyed : applied;
            Object count = counts == null ? null : counts.get(hook[0]);
            String step = STEP_OF_HOOK.get(hook[0]);
            String outcome = step == null ? null : steps.get(step);
            String result;
            if (!present(hook[1])) {
                result = "unsupported";
            } else if (count instanceof Long && (Long) count > 0) {
                result = "passed";
            } else if (step == null) {
                result = "not-exercised";
            } else if (!"ok".equals(outcome)) {
                result = "not-exercised (" + outcome + ")";
            } else {
                result = "failed";
            }
            results.put(hook[0], result);
        }
        return results;
    }

    interface Step {
        void run(int seconds) throws Exception;
    }

    static final class ThreadPoolStep implements Step {

        private final ThreadPoolExecutor pool;

        ThreadPoolStep(ThreadPoolExecutor pool) {
            this.pool = pool;
        }

        @Override
        public void run(int seconds) throws Exception {
            CountDownLatch gate = new CountDownLatch(1);
            pool.execute(new Await(gate));
            java.util.concurrent.Future<?> queued = pool.submit(new Noop());
            gate.countDown();
            queued.get(seconds, TimeUnit.SECONDS);
            CompletableFuture.supplyAsync(new Value(), pool).get(seconds, TimeUnit.SECONDS);
        }
    }

    static final class ScheduledStep implements Step {

        private final ScheduledThreadPoolExecutor scheduler;

        ScheduledStep(ScheduledThreadPoolExecutor scheduler) {
            this.scheduler = scheduler;
        }

        @Override
        public void run(int seconds) throws Exception {
            scheduler.schedule(new Noop(), 1, TimeUnit.MILLISECONDS).get(seconds, TimeUnit.SECONDS);
        }
    }

    static final class ForkJoinStep implements Step {

        private final ForkJoinPool forkJoin;

        ForkJoinStep(ForkJoinPool forkJoin) {
            this.forkJoin = forkJoin;
        }

        @Override
        public void run(int seconds) throws Exception {
            forkJoin.submit((Callable<Object>) new Value()).get(seconds, TimeUnit.SECONDS);
            forkJoin.submit(new Action()).get(seconds, TimeUnit.SECONDS);
        }
    }

    /** JDK 25+: {@code ForkJoinPool.schedule}, through reflection since the agent compiles for Java 17. */
    static final class DelayedStep implements Step {

        private final ForkJoinPool forkJoin;

        DelayedStep(ForkJoinPool forkJoin) {
            this.forkJoin = forkJoin;
        }

        @Override
        public void run(int seconds) throws Exception {
            Object future = ForkJoinPool.class
                    .getMethod("schedule", Runnable.class, long.class, TimeUnit.class)
                    .invoke(forkJoin, new Noop(), Long.valueOf(1L), TimeUnit.MILLISECONDS);
            ((java.util.concurrent.Future<?>) future).get(seconds, TimeUnit.SECONDS);
        }
    }

    /** {@code fork()} from a thread that is not a worker goes to the JVM-wide common pool: last, with a short wait. */
    static final class ForkStep implements Step {

        @Override
        public void run(int seconds) throws Exception {
            Action forked = new Action();
            forked.fork();
            forked.get(seconds, TimeUnit.SECONDS);
        }
    }

    /** Daemon threads named for the self-test, with no context class loader, so application thread numbering is kept. */
    static final class SelfTestThreads implements java.util.concurrent.ThreadFactory {

        private final java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(null, task, "bootui-agent-self-test-" + count.incrementAndGet(), 0, false);
            thread.setDaemon(true);
            thread.setContextClassLoader(null);
            return thread;
        }
    }

    static final class SelfTestForkJoinThreads implements ForkJoinPool.ForkJoinWorkerThreadFactory {

        private final java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public java.util.concurrent.ForkJoinWorkerThread newThread(ForkJoinPool pool) {
            java.util.concurrent.ForkJoinWorkerThread thread =
                    ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
            thread.setName("bootui-agent-self-test-fj-" + count.incrementAndGet());
            return thread;
        }
    }

    static final class Noop implements Runnable {
        @Override
        public void run() {}
    }

    static final class Await implements Runnable {

        private final CountDownLatch gate;

        Await(CountDownLatch gate) {
            this.gate = gate;
        }

        @Override
        public void run() {
            try {
                gate.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static final class Value implements java.util.function.Supplier<Object>, Callable<Object> {
        @Override
        public Object get() {
            return Boolean.TRUE;
        }

        @Override
        public Object call() {
            return Boolean.TRUE;
        }
    }

    static final class Action extends RecursiveAction {
        @Override
        protected void compute() {}
    }
}
