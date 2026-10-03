package bootuiagentit;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The executor sensor's behaviors (PLAN-v2 M5-2, D32), ported from the M5-0 spike: run in a forked JVM beside the agent,
 * claimed with a harness engine whose context is a thread-local string. Prints one PASS, FAIL, or SKIP line per
 * behavior, then the bridge's status. Argument: {@code agent} to claim, {@code none} to run without a claim.
 */
public class Behaviors {

    static final ThreadLocal<String> CONTEXT = new ThreadLocal<>();
    static final List<String> results = new ArrayList<>();
    static final java.util.concurrent.atomic.AtomicInteger CARRIER_REOPENS =
            new java.util.concurrent.atomic.AtomicInteger();
    static final java.util.Set<String> SHARED_SEEN = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    static final List<String> SHARED_RUNS = java.util.Collections.synchronizedList(new ArrayList<>());
    static final Runnable SHARED = () -> SHARED_RUNS.add(String.valueOf(CONTEXT.get()));
    static final List<String> FAILURES = java.util.Collections.synchronizedList(new ArrayList<>());
    static final Map<String, Long> BODY_ENDS = new ConcurrentHashMap<>();
    static final Map<String, Future<?>> BODY_TARGETS = new ConcurrentHashMap<>();
    static final Map<String, Boolean> BODY_BEFORE_PUBLICATION = new ConcurrentHashMap<>();
    static Supplier<Object> capture;
    static Function<Object, AutoCloseable> reopen;

    /** The harness engine's handle: restores the previous context, and records the task's failure. */
    static final class Handle implements AutoCloseable, java.util.function.Consumer<Throwable>, Runnable {
        private final String previous;

        Handle(String previous) {
            this.previous = previous;
        }

        @Override
        public void accept(Throwable failure) {
            FAILURES.add(CONTEXT.get() + ":" + failure.getClass().getSimpleName());
        }

        @Override
        public void close() {
            CONTEXT.set(previous);
        }

        @Override
        public void run() {
            BODY_ENDS.putIfAbsent(CONTEXT.get(), System.nanoTime());
            Future<?> target = BODY_TARGETS.get(CONTEXT.get());
            if (target != null) {
                BODY_BEFORE_PUBLICATION.putIfAbsent(CONTEXT.get(), !target.isDone());
            }
        }
    }

    /** A task whose class the claim lists as an already-propagating wrapper. */
    static final class Skipped implements Runnable {
        final AtomicReference<String> seen = new AtomicReference<>("unset");

        @Override
        public void run() {
            seen.set(String.valueOf(CONTEXT.get()));
        }
    }

    @SuppressWarnings("unchecked")
    static void awaitSelfTest(Class<?> bridge) throws Exception {
        for (int i = 0; i < 400; i++) {
            Map<String, Object> status =
                    (Map<String, Object>) bridge.getMethod("status").invoke(null);
            Map<String, Object> agent = (Map<String, Object>) status.get("agent");
            List<Object> sensors = agent == null ? List.of() : (List<Object>) agent.get("sensors");
            for (Object sensor : sensors) {
                Map<String, Object> map = (Map<String, Object>) sensor;
                if (Boolean.TRUE.equals(map.get("selfTestPassed"))) {
                    System.out.println("SELF_TEST=" + map.get("hooks"));
                    return;
                }
                if (map.get("selfTestError") != null || "failed".equals(map.get("state"))) {
                    System.out.println("SELF_TEST_FAILED=" + map);
                    return;
                }
            }
            Thread.sleep(25);
        }
        System.out.println("SELF_TEST_TIMEOUT=" + bridge.getMethod("status").invoke(null));
    }

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        Class<?> bridge = null;
        try {
            bridge = Class.forName("io.github.jdubois.bootui.agent.bridge.AgentBridge", true, null);
        } catch (ClassNotFoundException ignored) {
        }
        // Load executors before the claim, as an application does, so retransformation is exercised.
        new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<>()).shutdown();
        ForkJoinPool.commonPool().submit(() -> {}).get();
        new ScheduledThreadPoolExecutor(1).shutdown();
        if (bridge != null && !mode.equals("none")) {
            Object marker = new Object();
            capture = () -> CONTEXT.get() == null || marker == null
                    ? null
                    : new Object[] {
                        CONTEXT.get(), null, null, null, null, null, null, System.currentTimeMillis(), System.nanoTime()
                    };
            reopen = argument -> {
                if (Thread.currentThread().getClass().getName().contains("CarrierThread")) {
                    CARRIER_REOPENS.incrementAndGet();
                }
                Object[] snapshot = (Object[]) ((Object[]) argument)[0];
                String previous = CONTEXT.get();
                CONTEXT.set((String) snapshot[0]);
                return new Handle(previous);
            };
            Map<String, Object> request = new java.util.LinkedHashMap<>();
            request.put("application", "behaviors");
            request.put("mode", "dev");
            request.put("packages", List.of("bootuiagentit"));
            request.put("sensors", List.of("executors"));
            request.put(
                    "executors",
                    Map.of(
                            "skipTasks",
                            List.of("bootuiagentit.Behaviors$Skipped"),
                            "skipThreads",
                            List.of("skipped-")));
            Method claim = bridge.getMethod("claim", Map.class, Supplier.class, Function.class);
            Object result = claim.invoke(null, request, capture, reopen);
            System.out.println("CLAIM=" + result);
            awaitSelfTest(bridge);
        }
        CONTEXT.set("request-42");
        if (bridge != null && !mode.equals("none")) {
            forkJoinAdmissions(bridge);
        }

        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 1, TimeUnit.MINUTES, new LinkedBlockingQueue<>());
        completionBoundaries(pool);
        check("TPE execute propagates", "request-42".equals(seen(pool, true)));
        check(
                "TPE submit propagates",
                "request-42".equals(pool.submit(() -> CONTEXT.get()).get()));
        check(
                "CompletableFuture on pool",
                "request-42"
                        .equals(CompletableFuture.supplyAsync(() -> CONTEXT.get(), pool)
                                .get()));
        check(
                "commonPool supplyAsync",
                "request-42"
                        .equals(CompletableFuture.supplyAsync(() -> CONTEXT.get())
                                .get()));
        check(
                "FJP submit(Callable)",
                "request-42"
                        .equals(ForkJoinPool.commonPool()
                                .submit(() -> CONTEXT.get())
                                .get()));
        List<String> streamed = java.util.stream.IntStream.range(0, 2048)
                .parallel()
                .mapToObj(i -> String.valueOf(CONTEXT.get()))
                .collect(java.util.stream.Collectors.toList());
        check(
                "parallel stream: caller thread sees request, workers never see another owner",
                streamed.contains("request-42")
                        && streamed.stream().allMatch(v -> "null".equals(v) || "request-42".equals(v)));
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        check(
                "STPE one-shot propagates",
                "request-42"
                        .equals(scheduler
                                .schedule(() -> CONTEXT.get(), 1, TimeUnit.MILLISECONDS)
                                .get()));
        AtomicReference<String> periodic = new AtomicReference<>("unset");
        ScheduledFuture<?> repeating = scheduler.scheduleAtFixedRate(
                () -> periodic.set(String.valueOf(CONTEXT.get())), 0, 5, TimeUnit.MILLISECONDS);
        Thread.sleep(30);
        repeating.cancel(false);
        check("STPE periodic not propagated", "null".equals(periodic.get()));

        // Identity behaviors: block the single worker, then queue tasks.
        CountDownLatch release = new CountDownLatch(1);
        pool.execute(() -> await(release));
        Thread.sleep(50);
        Runnable queued = () -> {};
        pool.execute(queued);
        check("getQueue().contains(task)", pool.getQueue().contains(queued));
        check("remove(task)", pool.remove(queued));
        Future<?> cancelled = pool.submit(() -> {});
        cancelled.cancel(false);
        pool.purge();
        check("purge removes cancelled FutureTask", pool.getQueue().isEmpty());
        Runnable left = () -> {};
        pool.execute(left);
        List<Runnable> drained = pool.shutdownNow();
        check("shutdownNow returns the caller's task", drained.size() == 1 && drained.get(0) == left);
        release.countDown();

        AtomicReference<Runnable> rejected = new AtomicReference<>();
        ThreadPoolExecutor full = new ThreadPoolExecutor(
                1, 1, 1, TimeUnit.SECONDS, new SynchronousQueue<>(), (task, executor) -> rejected.set(task));
        CountDownLatch hold = new CountDownLatch(1);
        full.execute(() -> await(hold));
        Runnable extra = () -> {};
        full.execute(extra);
        check("rejection handler gets the caller's task", rejected.get() == extra);
        hold.countDown();
        full.shutdown();

        AtomicReference<Boolean> futureSeen = new AtomicReference<>(false);
        ThreadPoolExecutor hooked = new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<>()) {
            @Override
            protected void afterExecute(Runnable r, Throwable t) {
                futureSeen.set(r instanceof Future);
            }
        };
        hooked.submit(() -> {}).get();
        Thread.sleep(20);
        check("afterExecute sees the Future", futureSeen.get());
        hooked.shutdown();

        ThreadPoolExecutor wrapping = new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<>()) {
            @Override
            public void execute(Runnable command) {
                super.execute(() -> command.run());
            }
        };
        check("application wrapper in execute", "request-42".equals(seen(wrapping, false)));
        wrapping.shutdown();

        ForkJoinTask<String> fjt = ForkJoinTask.adapt(() -> CONTEXT.get());
        ForkJoinPool.commonPool().execute(fjt);
        check("FJP execute(ForkJoinTask) keeps identity", "request-42".equals(fjt.get()) && fjt.isDone());
        rejectedForkJoinRoots();

        // B3: the same task object submitted by two owners before it runs is never cross-attributed.
        ThreadPoolExecutor single = new ThreadPoolExecutor(1, 1, 1, TimeUnit.MINUTES, new LinkedBlockingQueue<>());
        CountDownLatch gate = new CountDownLatch(1);
        single.execute(() -> await(gate));
        CONTEXT.set("owner-A");
        single.execute(SHARED);
        CONTEXT.set("owner-B");
        single.execute(SHARED);
        gate.countDown();
        single.shutdown();
        single.awaitTermination(5, TimeUnit.SECONDS);
        List<String> sharedRuns = new ArrayList<>(SHARED_RUNS);
        java.util.Collections.sort(sharedRuns);
        check(
                "shared task from two owners: unowned or each its own, never crossed " + sharedRuns,
                sharedRuns.equals(List.of("null", "null")) || sharedRuns.equals(List.of("owner-A", "owner-B")));
        // B3 residual: A and B submit, the first run happens, then C submits: the second run (B's) never takes C's
        // context.
        check(
                "A, B, run, C: no run takes another owner's context " + sequence("owner-A", "owner-B", "owner-C"),
                sequenceOk(sequence("owner-A", "owner-B", "owner-C"), "owner-A", "owner-B", "owner-C"));
        // A submits twice, the first run happens, then B submits: A's second run never takes B's context.
        check(
                "A, A, run, B: no run takes another owner's context " + sequence("owner-A", "owner-A", "owner-B"),
                sequenceOk(sequence("owner-A", "owner-A", "owner-B"), "owner-A", "owner-A", "owner-B"));
        CONTEXT.set("request-42");
        // B3: a task left keyed (queued, then drained by shutdownNow) and later submitted unowned runs unowned.
        SHARED_RUNS.clear();
        ThreadPoolExecutor draining = new ThreadPoolExecutor(1, 1, 1, TimeUnit.MINUTES, new LinkedBlockingQueue<>());
        CountDownLatch gate2 = new CountDownLatch(1);
        draining.execute(() -> await(gate2));
        CONTEXT.set("old-request");
        draining.execute(SHARED);
        draining.shutdownNow();
        gate2.countDown();
        CONTEXT.remove();
        ThreadPoolExecutor later = new ThreadPoolExecutor(1, 1, 1, TimeUnit.MINUTES, new LinkedBlockingQueue<>());
        later.submit(SHARED).get();
        later.shutdown();
        check("drained task resubmitted unowned runs unowned " + SHARED_RUNS, SHARED_RUNS.equals(List.of("null")));
        // B3: a rejected owned submission leaves nothing behind for a later unowned run.
        SHARED_RUNS.clear();
        ThreadPoolExecutor saturated = new ThreadPoolExecutor(
                1, 1, 1, TimeUnit.SECONDS, new SynchronousQueue<>(), new ThreadPoolExecutor.DiscardPolicy());
        CountDownLatch gate3 = new CountDownLatch(1);
        saturated.execute(() -> await(gate3));
        CONTEXT.set("old-request");
        saturated.execute(SHARED);
        CONTEXT.remove();
        gate3.countDown();
        Thread.sleep(50);
        ForkJoinPool.commonPool().submit(SHARED).get();
        check(
                "rejected task resubmitted unowned elsewhere runs unowned " + SHARED_RUNS,
                SHARED_RUNS.equals(List.of("null")));
        saturated.shutdown();
        CONTEXT.set("request-42");

        // B2: virtual threads started and unparked from owned work: no carrier thread ever reopens a snapshot.
        Method ofVirtual = null;
        try {
            ofVirtual = Thread.class.getMethod("ofVirtual");
        } catch (NoSuchMethodException absent) {
        }
        if (ofVirtual == null) {
            results.add("  SKIP virtual threads (JDK " + Runtime.version().feature() + ")");
        } else {
            Object builder = ofVirtual.invoke(null);
            Method start = Class.forName("java.lang.Thread$Builder").getMethod("start", Runnable.class);
            List<Thread> vthreads = new ArrayList<>();
            CountDownLatch parked = new CountDownLatch(16);
            for (int i = 0; i < 16; i++) {
                vthreads.add((Thread) start.invoke(builder, (Runnable) () -> {
                    parked.countDown();
                    java.util.concurrent.locks.LockSupport.park();
                    Thread.yield();
                }));
            }
            parked.await(5, TimeUnit.SECONDS);
            Thread.sleep(50);
            for (Thread t : vthreads) {
                java.util.concurrent.locks.LockSupport.unpark(t);
            }
            for (Thread t : vthreads) {
                t.join(5000);
            }
            check("virtual threads: no carrier reopen (" + CARRIER_REOPENS.get() + ")", CARRIER_REOPENS.get() == 0);
            // A virtual-thread-per-task executor submitted from owned work: no carrier reopen either.
            ExecutorService perTask = (ExecutorService)
                    Executors.class.getMethod("newVirtualThreadPerTaskExecutor").invoke(null);
            perTask.submit(() -> CONTEXT.get()).get();
            perTask.shutdown();
            check(
                    "virtual-thread-per-task executor: no carrier reopen (" + CARRIER_REOPENS.get() + ")",
                    CARRIER_REOPENS.get() == 0);
        }

        // B1: inside an active OpenTelemetry span (another agent replaces lambdas with its own wrapper).
        Class<?> otel = null;
        try {
            otel = Class.forName("io.opentelemetry.api.GlobalOpenTelemetry");
        } catch (ClassNotFoundException absent) {
        }
        if (otel == null) {
            results.add("  SKIP OpenTelemetry span cases (no API on the class path)");
        } else {
            Object tracer = otel.getMethod("getTracer", String.class).invoke(null, "behaviors");
            Class<?> tracerType = Class.forName("io.opentelemetry.api.trace.Tracer");
            Class<?> spanBuilderType = Class.forName("io.opentelemetry.api.trace.SpanBuilder");
            Class<?> spanType = Class.forName("io.opentelemetry.api.trace.Span");
            Class<?> spanContextType = Class.forName("io.opentelemetry.api.trace.SpanContext");
            Object spanBuilder =
                    tracerType.getMethod("spanBuilder", String.class).invoke(tracer, "behaviors");
            Object span = spanBuilderType.getMethod("startSpan").invoke(spanBuilder);
            AutoCloseable scope =
                    (AutoCloseable) spanType.getMethod("makeCurrent").invoke(span);
            Method current = spanType.getMethod("current");
            Method context = spanType.getMethod("getSpanContext");
            Method traceId = spanContextType.getMethod("getTraceId");
            String expectedTrace = (String) traceId.invoke(context.invoke(span));
            ThreadPoolExecutor traced = new ThreadPoolExecutor(2, 2, 1, TimeUnit.MINUTES, new LinkedBlockingQueue<>());
            AtomicReference<String> seenContext = new AtomicReference<>();
            AtomicReference<String> seenTrace = new AtomicReference<>();
            CountDownLatch ran = new CountDownLatch(1);
            traced.execute(() -> {
                seenContext.set(CONTEXT.get());
                try {
                    seenTrace.set((String) traceId.invoke(context.invoke(current.invoke(null))));
                } catch (ReflectiveOperationException ex) {
                    seenTrace.set("error " + ex);
                }
                ran.countDown();
            });
            ran.await(5, TimeUnit.SECONDS);
            check(
                    "in an OpenTelemetry span, execute(lambda): BootUI context (" + seenContext.get() + ")",
                    "request-42".equals(seenContext.get()));
            check(
                    "in an OpenTelemetry span, execute(lambda): OpenTelemetry trace kept",
                    expectedTrace.equals(seenTrace.get()));
            // Queued path too (both workers busy).
            CountDownLatch busy = new CountDownLatch(1);
            traced.execute(() -> await(busy));
            traced.execute(() -> await(busy));
            AtomicReference<String> queuedContext = new AtomicReference<>();
            CountDownLatch queuedRan = new CountDownLatch(1);
            traced.execute(() -> {
                queuedContext.set(CONTEXT.get());
                queuedRan.countDown();
            });
            busy.countDown();
            queuedRan.await(5, TimeUnit.SECONDS);
            check(
                    "in an OpenTelemetry span, queued execute(lambda): BootUI context (" + queuedContext.get() + ")",
                    "request-42".equals(queuedContext.get()));
            check(
                    "in an OpenTelemetry span, CompletableFuture on pool",
                    "request-42"
                            .equals(CompletableFuture.supplyAsync(() -> CONTEXT.get(), traced)
                                    .get()));
            traced.shutdown();
            scope.close();
            spanType.getMethod("end").invoke(span);
        }

        // M5-2: outcomes reach the engine's handle, with the task's own context.
        FAILURES.clear();
        ThreadPoolExecutor failing = new ThreadPoolExecutor(1, 1, 1, TimeUnit.MINUTES, new LinkedBlockingQueue<>());
        try {
            failing.submit(() -> {
                        throw new IllegalStateException("submitted");
                    })
                    .get();
        } catch (ExecutionException expected) {
        }
        try {
            CompletableFuture.supplyAsync(
                            () -> {
                                throw new IllegalArgumentException("supplied");
                            },
                            failing)
                    .join();
        } catch (CompletionException expected) {
        }
        try {
            ForkJoinPool.commonPool()
                    .submit(() -> {
                        throw new UnsupportedOperationException("forked");
                    })
                    .get();
        } catch (ExecutionException expected) {
        }
        failing.shutdown();
        Thread.sleep(50);
        check(
                "a failing submit, supplyAsync, and FJP task each report their failure " + FAILURES,
                FAILURES.containsAll(List.of(
                        "request-42:IllegalStateException",
                        "request-42:IllegalArgumentException",
                        "request-42:UnsupportedOperationException")));

        // M5-2: fork() from a thread that is not a pool worker, and invokeAll.
        AtomicReference<String> forkedSeen = new AtomicReference<>();
        RecursiveAction forked = new RecursiveAction() {
            @Override
            protected void compute() {
                forkedSeen.set(CONTEXT.get());
            }
        };
        forked.fork().join();
        check("fork() from a non-worker propagates (" + forkedSeen.get() + ")", "request-42".equals(forkedSeen.get()));

        // M5-2: CompletableFuture.delayedExecutor (DelayScheduler on JDK 25+, the Delayer before).
        check(
                "delayedExecutor propagates",
                "request-42"
                        .equals(CompletableFuture.supplyAsync(
                                        () -> CONTEXT.get(),
                                        CompletableFuture.delayedExecutor(5, TimeUnit.MILLISECONDS))
                                .get()));

        // M5-2: a task class listed as an already-propagating wrapper is never keyed.
        Skipped skipped = new Skipped();
        ThreadPoolExecutor wrappers = new ThreadPoolExecutor(1, 1, 1, TimeUnit.MINUTES, new LinkedBlockingQueue<>());
        // As a decorator hands its wrapper to execute(): submit() would hand over a FutureTask instead.
        wrappers.execute(skipped);
        wrappers.submit(() -> {}).get();
        check(
                "a listed wrapper is left to propagate itself (" + skipped.seen.get() + ")",
                "null".equals(skipped.seen.get()));
        wrappers.shutdown();

        // M5-2: workers of an executor that propagates itself never reopen.
        ThreadPoolExecutor own = new ThreadPoolExecutor(
                1,
                1,
                1,
                TimeUnit.MINUTES,
                new LinkedBlockingQueue<>(),
                runnable -> new Thread(runnable, "skipped-worker"));
        check(
                "a listed worker thread never reopens",
                own.submit(() -> CONTEXT.get()).get() == null);
        own.shutdown();

        // M5-2: Mockito spying a claimed ThreadPoolExecutor, when Mockito is on the class path.
        Class<?> mockito = null;
        try {
            mockito = Class.forName("org.mockito.Mockito");
        } catch (ClassNotFoundException absent) {
        }
        if (mockito == null) {
            results.add("  SKIP Mockito spy (no Mockito on the class path)");
        } else {
            ThreadPoolExecutor real = new ThreadPoolExecutor(1, 1, 1, TimeUnit.MINUTES, new LinkedBlockingQueue<>());
            ThreadPoolExecutor spy =
                    (ThreadPoolExecutor) mockito.getMethod("spy", Object.class).invoke(null, real);
            check(
                    "Mockito spy of a claimed ThreadPoolExecutor propagates",
                    "request-42".equals(spy.submit(() -> CONTEXT.get()).get(5, TimeUnit.SECONDS)));
            spy.shutdown();
        }

        CONTEXT.remove();
        check(
                "unowned work stays unowned",
                ForkJoinPool.commonPool().submit(() -> CONTEXT.get()).get() == null);
        System.gc();
        Thread.sleep(100);
        String status =
                bridge == null ? "" : "STATUS=" + bridge.getMethod("status").invoke(null);
        System.out.println("mode=" + mode + " jdk=" + Runtime.version().feature() + " cpus="
                + Runtime.getRuntime().availableProcessors());
        results.forEach(System.out::println);
        System.out.println(status);
        System.exit(0);
    }

    private static void completionBoundaries(ThreadPoolExecutor pool) throws Exception {
        for (boolean exceptional : List.of(false, true)) {
            for (String kind : List.of("future", "supply", "run", "forkjoin")) {
                String owner = "body-" + kind + "-" + exceptional;
                CONTEXT.set(owner);
                CountDownLatch targetKnown = new CountDownLatch(1);
                Callable<String> body = () -> {
                    await(targetKnown);
                    if (exceptional) {
                        throw new IllegalStateException("body failed");
                    }
                    return CONTEXT.get();
                };
                Future<?> future;
                if (kind.equals("future")) {
                    future = pool.submit(body);
                } else if (kind.equals("forkjoin")) {
                    future = ForkJoinPool.commonPool().submit(body);
                } else if (kind.equals("supply")) {
                    future = CompletableFuture.supplyAsync(() -> call(body), pool);
                } else {
                    future = CompletableFuture.runAsync(() -> call(body), pool);
                }
                BODY_TARGETS.put(owner, future);
                targetKnown.countDown();
                try {
                    future.get(5, TimeUnit.SECONDS);
                } catch (ExecutionException expected) {
                    if (!exceptional) {
                        throw expected;
                    }
                }
                check(
                        "body before publication: " + kind + (exceptional ? " failure" : " success"),
                        Boolean.TRUE.equals(BODY_BEFORE_PUBLICATION.get(owner)));
            }
        }
        CONTEXT.set("body-early-complete");
        CountDownLatch inBody = new CountDownLatch(1);
        CountDownLatch finishBody = new CountDownLatch(1);
        ForkJoinTask<Void> early = new RecursiveAction() {
            @Override
            protected void compute() {
                complete(null);
                inBody.countDown();
                await(finishBody);
            }
        };
        ForkJoinPool.commonPool().execute(early);
        if (!inBody.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("early-completion body never ran");
        }
        check("manual publication is not body completion", !BODY_ENDS.containsKey("body-early-complete"));
        finishBody.countDown();
        CONTEXT.set("body-raw-runnable");
        pool.execute(() -> {});
        CONTEXT.remove();
        pool.submit(() -> {}).get(5, TimeUnit.SECONDS);
        check("raw Runnable body return is marked", BODY_ENDS.containsKey("body-raw-runnable"));

        CONTEXT.set("body-decorated-future");
        ThreadPoolExecutor decorated = new ThreadPoolExecutor(1, 1, 1, TimeUnit.MINUTES, new LinkedBlockingQueue<>()) {
            @Override
            public void execute(Runnable command) {
                super.execute(() -> command.run());
            }
        };
        decorated.submit(() -> {}).get(5, TimeUnit.SECONDS);
        CONTEXT.remove();
        decorated.submit(() -> {}).get(5, TimeUnit.SECONDS);
        decorated.shutdown();
        check("hidden FutureTask keeps the conservative fallback", !BODY_ENDS.containsKey("body-decorated-future"));
        CONTEXT.set("body-manual-stage");
        CompletableFuture<Void> signalled = new CompletableFuture<>();
        pool.execute(() -> signalled.complete(null));
        signalled.get(5, TimeUnit.SECONDS);
        CONTEXT.remove();
        pool.submit(() -> {}).get(5, TimeUnit.SECONDS);
        check("manual stage publication keeps the conservative fallback", !BODY_ENDS.containsKey("body-manual-stage"));
        CONTEXT.set("request-42");
    }

    private static String call(Callable<String> body) {
        try {
            return body.call();
        } catch (Exception failure) {
            throw new CompletionException(failure);
        }
    }

    /** Two submissions of one task object queued, the first run, then a third submission; returns the three runs' contexts. */
    static List<String> sequence(String first, String second, String third) throws Exception {
        List<String> runs = java.util.Collections.synchronizedList(new ArrayList<>());
        java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger();
        CountDownLatch firstRan = new CountDownLatch(1);
        CountDownLatch thirdSubmitted = new CountDownLatch(1);
        Runnable task = new Runnable() {
            @Override
            public void run() {
                runs.add(String.valueOf(CONTEXT.get()));
                if (count.incrementAndGet() == 1) {
                    firstRan.countDown();
                    await(thirdSubmitted);
                }
            }
        };
        ThreadPoolExecutor single = new ThreadPoolExecutor(1, 1, 1, TimeUnit.MINUTES, new LinkedBlockingQueue<>());
        CountDownLatch gate = new CountDownLatch(1);
        single.execute(() -> await(gate));
        CONTEXT.set(first);
        single.execute(task);
        CONTEXT.set(second);
        single.execute(task);
        CONTEXT.remove();
        gate.countDown();
        firstRan.await(5, TimeUnit.SECONDS);
        CONTEXT.set(third);
        single.execute(task);
        CONTEXT.remove();
        thirdSubmitted.countDown();
        single.shutdown();
        single.awaitTermination(5, TimeUnit.SECONDS);
        return new ArrayList<>(runs);
    }

    /** Each run sees its own submitter's context or none, never another owner's. */
    static boolean sequenceOk(List<String> runs, String... owners) {
        if (runs.size() != owners.length) {
            return false;
        }
        for (int i = 0; i < owners.length; i++) {
            if (!"null".equals(runs.get(i)) && !owners[i].equals(runs.get(i))) {
                return false;
            }
        }
        return true;
    }

    static String seen(ExecutorService executor, boolean unused) throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        executor.execute(() -> {
            seen.set(CONTEXT.get());
            done.countDown();
        });
        done.await(5, TimeUnit.SECONDS);
        return seen.get();
    }

    static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void rejectedForkJoinRoots() throws Exception {
        ForkJoinPool stopped = new ForkJoinPool(1);
        stopped.shutdown();
        ForkJoinPool running = new ForkJoinPool(1);
        try {
            for (String method : List.of("execute", "submit", "invoke")) {
                for (String acceptedOwner : List.of("rejected-owner", "different-owner")) {
                    for (boolean completed : List.of(false, true)) {
                        RecursiveTask<String> task = new RecursiveTask<>() {
                            @Override
                            protected String compute() {
                                return CONTEXT.get();
                            }
                        };
                        if (completed) {
                            task.complete("already-completed");
                        }
                        CONTEXT.set("rejected-owner");
                        boolean rejected = false;
                        try {
                            switch (method) {
                                case "execute" -> stopped.execute(task);
                                case "submit" -> stopped.submit(task);
                                case "invoke" -> stopped.invoke(task);
                                default -> throw new AssertionError(method);
                            }
                        } catch (RejectedExecutionException expected) {
                            rejected = true;
                        }
                        check("FJP rejected " + method + " (completed=" + completed + ")", rejected);
                        task.reinitialize();
                        CONTEXT.set(acceptedOwner);
                        running.execute(task);
                        check(
                                "FJP rejected " + method + " resubmitted by " + acceptedOwner,
                                acceptedOwner.equals(task.get(5, TimeUnit.SECONDS)));
                        task.reinitialize();
                        CONTEXT.set("fresh-owner");
                        running.execute(task);
                        check(
                                "FJP rejected " + method + " leaves no residual snapshot",
                                "fresh-owner".equals(task.get(5, TimeUnit.SECONDS)));
                    }
                }
            }
        } finally {
            CONTEXT.set("request-42");
            running.shutdownNow();
            running.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @SuppressWarnings("unchecked")
    private static void forkJoinAdmissions(Class<?> bridge) throws Exception {
        ForkJoinPool pool = new ForkJoinPool(1);
        try {
            for (String name : List.of("execute", "submit", "invoke", "lazySubmit", "externalSubmit")) {
                Method method;
                try {
                    method = ForkJoinPool.class.getMethod(name, ForkJoinTask.class);
                } catch (NoSuchMethodException absent) {
                    results.add("  SKIP FJP " + name);
                    continue;
                }
                RecursiveTask<String> task = new RecursiveTask<>() {
                    @Override
                    protected String compute() {
                        return CONTEXT.get();
                    }
                };
                CountDownLatch ready = new CountDownLatch(1);
                CountDownLatch gate = new CountDownLatch(1);
                if (name.equals("lazySubmit")) {
                    pool.execute(() -> {
                        ready.countDown();
                        await(gate);
                    });
                    if (!ready.await(5, TimeUnit.SECONDS)) {
                        throw new TimeoutException("lazy submission worker did not start");
                    }
                }
                Map<String, Object> before = (Map<String, Object>)
                        ((Map<?, ?>) bridge.getMethod("status").invoke(null)).get("executors");
                long keyed = (Long) ((Map<?, ?>) before.get("keyed")).get("ForkJoinPool");
                try {
                    method.invoke(pool, task);
                } finally {
                    gate.countDown();
                }
                check("FJP " + name + " admission keeps its owner", "request-42".equals(task.get(5, TimeUnit.SECONDS)));
                Map<String, Object> after = (Map<String, Object>)
                        ((Map<?, ?>) bridge.getMethod("status").invoke(null)).get("executors");
                check(
                        "FJP " + name + " keys once and drains",
                        (Long) ((Map<?, ?>) after.get("keyed")).get("ForkJoinPool") == keyed + 1
                                && after.get("pending").equals(before.get("pending")));
            }
            admittedInvokeFailure(pool);
        } finally {
            CONTEXT.set("request-42");
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static void admittedInvokeFailure(ForkJoinPool pool) throws Exception {
        ForkJoinPool blocked = new ForkJoinPool(1);
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger runs = new java.util.concurrent.atomic.AtomicInteger();
        RecursiveTask<String> task = new RecursiveTask<>() {
            @Override
            protected String compute() {
                if (runs.incrementAndGet() == 1) {
                    throw new RejectedExecutionException("application failure after admission");
                }
                return CONTEXT.get();
            }
        };
        try {
            blocked.execute(() -> {
                ready.countDown();
                await(gate);
            });
            if (!ready.await(5, TimeUnit.SECONDS)) {
                throw new TimeoutException("blocked fork/join worker did not start");
            }
            CONTEXT.set("queued-owner");
            blocked.execute(task);
            CONTEXT.set("invoke-owner");
            try {
                pool.invoke(task);
                check("FJP admitted invoke throws the application's failure", false);
            } catch (RejectedExecutionException expected) {
                check("FJP admitted invoke throws the application's failure", true);
            }
            task.reinitialize();
            CONTEXT.set("third-owner");
            pool.execute(task);
            check(
                    "FJP admitted invoke failure preserves another pending submission",
                    task.get(5, TimeUnit.SECONDS) == null);
            gate.countDown();
            blocked.shutdown();
            if (!blocked.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new TimeoutException("blocked fork/join pool did not drain");
            }
            task.reinitialize();
            CONTEXT.set("fresh-owner");
            pool.execute(task);
            check("FJP admitted invoke failure eventually drains", "fresh-owner".equals(task.get(5, TimeUnit.SECONDS)));
        } finally {
            gate.countDown();
            blocked.shutdownNow();
            blocked.awaitTermination(5, TimeUnit.SECONDS);
            CONTEXT.set("request-42");
        }
    }

    static void check(String name, boolean ok) {
        results.add((ok ? "  PASS " : "  FAIL ") + name);
    }
}
