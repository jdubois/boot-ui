package bootuiagentit;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The threads sensor's behaviors (PLAN-v2 M5-2c), in a forked JVM beside the agent, claimed with the executors and
 * threads sensors and a harness engine whose context is a thread-local string. Prints one PASS, FAIL, or SKIP line per
 * behavior, then the bridge's status.
 */
public final class ThreadBehaviors {

    static final ThreadLocal<String> CONTEXT = new ThreadLocal<>();
    static final List<String> RESULTS = new ArrayList<>();
    static final List<String> FAILURES = java.util.Collections.synchronizedList(new ArrayList<>());
    static final java.util.concurrent.atomic.AtomicInteger REOPENS = new java.util.concurrent.atomic.AtomicInteger();
    static final AtomicReference<String> LAST_HOOK = new AtomicReference<>();
    static Supplier<Object> capture;
    static Function<Object, AutoCloseable> reopen;

    private ThreadBehaviors() {}

    public static void main(String[] args) throws Exception {
        Class<?> bridge = Class.forName("io.github.jdubois.bootui.agent.bridge.AgentBridge", true, null);
        // Started before the claim, as an application's threads are, so Thread is retransformed.
        Thread early = new Thread(() -> {});
        early.start();
        early.join();
        Object marker = new Object();
        capture = () -> CONTEXT.get() == null || marker == null
                ? null
                : new Object[] {CONTEXT.get(), null, null, null, null, null, null, 1L, System.nanoTime()};
        reopen = argument -> {
            REOPENS.incrementAndGet();
            LAST_HOOK.set((String) ((Object[]) argument)[2]);
            Object[] snapshot = (Object[]) ((Object[]) argument)[0];
            String previous = CONTEXT.get();
            CONTEXT.set((String) snapshot[0]);
            return new Handle(previous);
        };
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("application", "thread-behaviors");
        request.put("mode", "dev");
        request.put("packages", List.of("bootuiagentit"));
        request.put("sensors", List.of("executors", "threads"));
        request.put("executors", Map.of("skipTasks", List.of(), "skipThreads", List.of()));
        Method claim = bridge.getMethod("claim", Map.class, Supplier.class, Function.class);
        System.out.println("CLAIM=" + claim.invoke(null, request, capture, reopen));
        awaitSelfTests(bridge);

        CONTEXT.set("request-7");
        once("an application task's thread propagates", () -> "request-7".equals(onThread(new Read())));
        once("an application Thread subclass propagates", () -> "request-7".equals(onSubclass()));
        once("an application thread running a JDK task propagates", () -> "request-7".equals(onJdkTask()));
        never("a thread a library starts with an application task is not propagated", () -> {
            Read read = new Read();
            Thread thread = bootuiagentlib.LibraryThreads.startFromFactory(r -> new Thread(() -> r.run()), read);
            thread.join(5000);
            return read.seen.get() == null;
        });
        never("a JDK thread overriding run() is never keyed", () -> {
            java.util.Timer timer = new java.util.Timer("app-timer", true);
            java.util.concurrent.CountDownLatch ran = new java.util.concurrent.CountDownLatch(1);
            timer.schedule(
                    new java.util.TimerTask() {
                        @Override
                        public void run() {
                            ran.countDown();
                        }
                    },
                    0);
            boolean done = ran.await(5, TimeUnit.SECONDS);
            timer.cancel();
            return done;
        });

        CONTEXT.remove();
        never("a thread started from unowned work stays unowned", () -> onThread(new Read()) == null);
        CONTEXT.set("request-7");

        // A pool worker created by an application thread factory during an owned submission never keeps the request.
        ExecutorService pool = Executors.newFixedThreadPool(1, runnable -> new Thread(() -> runnable.run()));
        pool.submit(() -> {}).get(5, TimeUnit.SECONDS);
        CONTEXT.remove();
        String worker = pool.submit(() -> CONTEXT.get()).get(5, TimeUnit.SECONDS);
        check("a pool worker never inherits the request it was created under (" + worker + ")", worker == null);
        pool.shutdown();
        CONTEXT.set("request-7");

        Thread twice = new Thread(new Read());
        twice.start();
        twice.join();
        try {
            twice.start();
            check("a second start fails", false);
        } catch (IllegalThreadStateException expected) {
            check("a failing start is left to the application", true);
        }

        FAILURES.clear();
        Thread failing = new Thread(new Fail());
        failing.setUncaughtExceptionHandler((thread, ex) -> {});
        failing.start();
        failing.join();
        check("a failing thread reports its failure " + FAILURES, FAILURES.contains("request-7:IllegalStateException"));

        if (java.util.concurrent.ForkJoinPool.getCommonPoolParallelism() < 2) {
            FAILURES.clear();
            once("CompletableFuture's thread-per-task fallback is left to the executors sensor", () -> {
                try {
                    java.util.concurrent.CompletableFuture.runAsync(() -> {
                                throw new IllegalStateException("async failed");
                            })
                            .join();
                } catch (java.util.concurrent.CompletionException expected) {
                    // The stage's failure, which the executors sensor's apply hook reports.
                }
                for (int i = 0; i < 200 && FAILURES.isEmpty(); i++) {
                    Thread.sleep(10);
                }
                System.out.println("FALLBACK=" + LAST_HOOK.get() + " " + FAILURES);
                return "CompletableFuture.AsyncRun".equals(LAST_HOOK.get())
                        && FAILURES.stream().anyMatch(failure -> failure.startsWith("request-7:"));
            });
        }

        if (Runtime.version().feature() >= 21) {
            once("a virtual thread propagates", () -> {
                Object builder = Thread.class.getMethod("ofVirtual").invoke(null);
                Read read = new Read();
                Thread virtual = (Thread) Class.forName("java.lang.Thread$Builder")
                        .getMethod("start", Runnable.class)
                        .invoke(builder, read);
                virtual.join(5000);
                return "request-7".equals(read.seen.get());
            });
            once("a virtual-thread-per-task executor propagates", () -> {
                ExecutorService perTask = (ExecutorService) Executors.class
                        .getMethod("newVirtualThreadPerTaskExecutor")
                        .invoke(null);
                String seen =
                        perTask.submit((Callable<String>) () -> CONTEXT.get()).get(5, TimeUnit.SECONDS);
                perTask.shutdown();
                return "request-7".equals(seen);
            });
            never("a framework's virtual-thread dispatch is not propagated", () -> {
                Read read = new Read();
                bootuiagentlib.LibraryThreads.startVirtual(read).join(5000);
                return read.seen.get() == null;
            });
            never("a context-propagating wrapper of a virtual-thread-per-task executor is left alone", () -> {
                ExecutorService perTask = (ExecutorService) Executors.class
                        .getMethod("newVirtualThreadPerTaskExecutor")
                        .invoke(null);
                String seen = bootuiagentlib.LibraryThreads.submitWithContext(
                                perTask, CONTEXT::get, ThreadBehaviors::restore, () -> CONTEXT.get())
                        .get(5, TimeUnit.SECONDS);
                perTask.shutdown();
                return "request-7".equals(seen);
            });
        } else {
            RESULTS.add("  SKIP virtual threads (JDK " + Runtime.version().feature() + ")");
        }

        if (Runtime.version().feature() >= 25) {
            Class<?> scopedValue = Class.forName("java.lang.ScopedValue");
            Object key = scopedValue.getMethod("newInstance").invoke(null);
            Object carrier =
                    scopedValue.getMethod("where", scopedValue, Object.class).invoke(null, key, "bound");
            AtomicReference<Object> bound = new AtomicReference<>();
            Method get = scopedValue.getMethod("get");
            Method run = carrier.getClass().getMethod("run", Runnable.class);
            Thread scoped = new Thread(() -> {
                try {
                    run.invoke(carrier, (Runnable) () -> {
                        try {
                            bound.set(get.invoke(key) + "/" + CONTEXT.get());
                        } catch (ReflectiveOperationException ex) {
                            bound.set(ex.toString());
                        }
                    });
                } catch (ReflectiveOperationException ex) {
                    bound.set(ex.toString());
                }
            });
            scoped.start();
            scoped.join();
            check(
                    "scoped values still bind inside a propagated thread (" + bound.get() + ")",
                    "bound/request-7".equals(bound.get()));
            inheritedScopedValue(key, carrier, get, run);
        } else {
            RESULTS.add("  SKIP scoped values (JDK " + Runtime.version().feature() + ")");
        }

        CONTEXT.remove();
        System.gc();
        Thread.sleep(100);
        RESULTS.forEach(System.out::println);
        System.out.println("STATUS=" + bridge.getMethod("status").invoke(null));
        afterRelease(bridge);
        System.exit(0);
    }

    /**
     * JDK 25+ with preview features: a {@code StructuredTaskScope} subtask inherits its scope's bindings, which
     * {@code Thread.runWith} keeps in its frame, so a broken {@code runWith} would lose them.
     */
    static void inheritedScopedValue(Object key, Object carrier, Method get, Method run) throws Exception {
        Class<?> scopes;
        Method open;
        try {
            scopes = Class.forName("java.util.concurrent.StructuredTaskScope");
            open = scopes.getMethod("open");
        } catch (ReflectiveOperationException ex) {
            RESULTS.add("  SKIP inherited scoped values (" + ex + ")");
            return;
        }
        if (!Boolean.getBoolean("bootui.agent.it.preview")) {
            RESULTS.add("  SKIP inherited scoped values (preview features off)");
            return;
        }
        Class<?> subtask = Class.forName("java.util.concurrent.StructuredTaskScope$Subtask");
        AtomicReference<Object> inherited = new AtomicReference<>();
        int before = REOPENS.get();
        run.invoke(carrier, (Runnable) () -> {
            try {
                Object scope = open.invoke(null);
                try {
                    Object forked = scopes.getMethod("fork", Callable.class)
                            .invoke(scope, (Callable<String>) () -> get.invoke(key) + "/" + CONTEXT.get());
                    scopes.getMethod("join").invoke(scope);
                    inherited.set(subtask.getMethod("get").invoke(forked));
                } finally {
                    scopes.getMethod("close").invoke(scope);
                }
            } catch (ReflectiveOperationException ex) {
                inherited.set(
                        ex.getCause() == null ? ex.toString() : ex.getCause().toString());
            }
        });
        int reopens = REOPENS.get() - before;
        check(
                "a structured subtask inherits its scoped values and its request (" + inherited.get() + ", " + reopens
                        + " reopen)",
                "bound/request-7".equals(inherited.get()) && reopens == 1);
    }

    /** After the claim is released, the sensor removes its transformer and threads still start and run untouched. */
    @SuppressWarnings("unchecked")
    static void afterRelease(Class<?> bridge) throws Exception {
        bridge.getMethod("release", String.class, String.class).invoke(null, "thread-behaviors", "dev");
        String threadsState = null;
        String executorsState = null;
        for (int i = 0; i < 400; i++) {
            Map<String, Object> status =
                    (Map<String, Object>) bridge.getMethod("status").invoke(null);
            Map<String, Object> agent = (Map<String, Object>) status.get("agent");
            for (Object sensor : (List<Object>) agent.get("sensors")) {
                Map<String, Object> map = (Map<String, Object>) sensor;
                if ("threads".equals(map.get("id"))) {
                    threadsState = String.valueOf(map.get("state"));
                } else if ("executors".equals(map.get("id"))) {
                    executorsState = String.valueOf(map.get("state"));
                }
            }
            if ("released".equals(threadsState) && "released".equals(executorsState)) {
                break;
            }
            Thread.sleep(25);
        }
        CONTEXT.set("request-7");
        int before = REOPENS.get();
        Read read = new Read();
        Thread thread = new Thread(read);
        thread.start();
        thread.join(5000);
        String seen = read.seen.get();
        CONTEXT.remove();
        System.out.println((("released".equals(threadsState)
                                && "released".equals(executorsState)
                                && seen == null
                                && REOPENS.get() == before)
                        ? "  PASS "
                        : "  FAIL ")
                + "released sensors leave threads untouched (threads " + threadsState + ", executors "
                + executorsState + ", seen " + seen + ")");
    }

    interface Behavior {
        boolean run() throws Exception;
    }

    /** A behavior that must reopen the request exactly once. */
    static void once(String name, Behavior behavior) throws Exception {
        int before = REOPENS.get();
        boolean ok = behavior.run();
        int reopens = REOPENS.get() - before;
        check(name + " (" + reopens + " reopen)", ok && reopens == 1);
    }

    /** A behavior that must never reopen the request. */
    static void never(String name, Behavior behavior) throws Exception {
        int before = REOPENS.get();
        boolean ok = behavior.run();
        int reopens = REOPENS.get() - before;
        check(name + " (" + reopens + " reopen)", ok && reopens == 0);
    }

    static void restore(String context) {
        if (context == null) {
            CONTEXT.remove();
        } else {
            CONTEXT.set(context);
        }
    }

    static String onThread(Read task) throws InterruptedException {
        Thread thread = new Thread(task);
        thread.start();
        thread.join(5000);
        return task.seen.get();
    }

    /** An application thread whose task is a JDK {@code FutureTask}: started by the application, so propagated. */
    static String onJdkTask() throws InterruptedException {
        AtomicReference<String> seen = new AtomicReference<>("unset");
        FutureTask<Object> future = new FutureTask<>(() -> {
            seen.set(CONTEXT.get());
            return null;
        });
        Thread thread = new Thread(future);
        thread.start();
        thread.join(5000);
        return seen.get();
    }

    static String onSubclass() throws InterruptedException {
        ReadingThread thread = new ReadingThread();
        thread.start();
        thread.join(5000);
        return thread.seen.get();
    }

    @SuppressWarnings("unchecked")
    static void awaitSelfTests(Class<?> bridge) throws Exception {
        for (int i = 0; i < 400; i++) {
            Map<String, Object> status =
                    (Map<String, Object>) bridge.getMethod("status").invoke(null);
            Map<String, Object> agent = (Map<String, Object>) status.get("agent");
            List<Object> sensors = agent == null ? List.of() : (List<Object>) agent.get("sensors");
            int done = 0;
            for (Object sensor : sensors) {
                Map<String, Object> map = (Map<String, Object>) sensor;
                if (Boolean.TRUE.equals(map.get("selfTestPassed")) || map.get("selfTestError") != null) {
                    done++;
                }
            }
            if (done == 2) {
                for (Object sensor : sensors) {
                    Map<String, Object> map = (Map<String, Object>) sensor;
                    System.out.println("SELF_TEST_" + map.get("id") + "=" + map.get("selfTestPassed") + " "
                            + map.get("selfTestError") + " " + map.get("hooks"));
                }
                return;
            }
            Thread.sleep(25);
        }
        System.out.println("SELF_TEST_TIMEOUT=" + bridge.getMethod("status").invoke(null));
    }

    static void check(String name, boolean ok) {
        RESULTS.add((ok ? "  PASS " : "  FAIL ") + name);
    }

    /** An application task: records the context it ran with. */
    public static final class Read implements Runnable {
        final AtomicReference<String> seen = new AtomicReference<>("unset");

        @Override
        public void run() {
            seen.set(CONTEXT.get());
        }
    }

    /** An application task that fails. */
    public static final class Fail implements Runnable {
        @Override
        public void run() {
            throw new IllegalStateException("thread failed");
        }
    }

    /** An application {@code Thread} subclass whose own {@code run()} bypasses {@code Thread.run}. */
    public static final class ReadingThread extends Thread {
        final AtomicReference<String> seen = new AtomicReference<>("unset");

        @Override
        public void run() {
            seen.set(CONTEXT.get());
        }
    }

    static final class Handle implements AutoCloseable, java.util.function.Consumer<Throwable> {
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
            if (previous == null) {
                CONTEXT.remove();
            } else {
                CONTEXT.set(previous);
            }
        }
    }
}
