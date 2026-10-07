package bootuiagentit.run;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * One simulated application run, defined by its own class loader as a DevTools restart would be: it claims the agent
 * the way the engine does (fresh capture and reopen objects from this run's classes), exercises a probed class, and
 * disarms at the end.
 */
public final class RunApp {

    /** One per run, numbered, so a heap walk can tell which runs are still reachable. */
    static final Sentinel SENTINEL = new Sentinel(Integer.getInteger("bootui.agent.it.run", -1));

    static Supplier<Object> capture;
    static Function<Object, AutoCloseable> reopen;

    /** This run's correlation, as the engine's thread-local holds its own context objects. */
    static final ThreadLocal<Object> CURRENT = new ThreadLocal<>();

    /** A thread local a run's pool task leaves set, with this run's object, for the thread-locals sensor (M5-5f). */
    static final ThreadLocal<Object> LEAKED = new ThreadLocal<>();

    static final boolean THREAD_LOCALS = Boolean.getBoolean("bootui.agent.it.thread-locals");

    private RunApp() {}

    @SuppressWarnings("unchecked")
    public static long claim() throws Exception {
        Class<?> bridge = Class.forName("io.github.jdubois.bootui.agent.bridge.AgentBridge", false, null);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("application", "it");
        request.put("owner", "it run " + SENTINEL.run);
        request.put("mode", "dev");
        request.put("packages", List.of("bootuiagentit.run"));
        request.put(
                "sensors",
                THREAD_LOCALS
                        ? List.of("executors", "threads", "inventory", "code-paths", "thread-locals")
                        : List.of("executors", "threads", "inventory", "code-paths"));
        request.put("beanClasses", List.of(RunBean.class.getName()));
        Object marker = new Object();
        capture = () -> marker != null && CURRENT.get() != null
                ? new Object[] {String.format("%016x", SENTINEL.run), null, null, null, null, null, null, 1L, 1L}
                : null;
        reopen = snapshot -> {
            Object previous = CURRENT.get();
            CURRENT.set(new RunContext());
            return () -> {
                if (Boolean.getBoolean("bootui.agent.it.leave-context-set")) {
                    return;
                }
                if (previous == null) {
                    CURRENT.remove();
                } else {
                    CURRENT.set(previous);
                }
            };
        };
        Map<String, Object> result =
                (Map<String, Object>) bridge.getMethod("claim", Map.class, Supplier.class, Function.class)
                        .invoke(null, request, capture, reopen);
        if (!"armed".equals(result.get("status"))) {
            throw new IllegalStateException("claim not armed: " + result);
        }
        Probed.touch(SENTINEL.run);
        return (Long) result.get("token");
    }

    /**
     * Owned work handed to executors: the common pool, a raw pool, and a JDK-only task left queued for ten minutes on
     * {@code CompletableFuture.delayedExecutor}, so only something the agent kept could keep this run alive.
     */
    public static void propagate() throws Exception {
        CURRENT.set(new RunContext());
        try {
            // A bean call under the run's request: a code-paths fragment.
            new RunBean().work(SENTINEL.run);
            // execute and a latch, not submit().get(): a joining caller may run the task itself.
            java.util.concurrent.CountDownLatch ran = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.ForkJoinPool.commonPool().execute(() -> {
                Probed.touch(SENTINEL.run);
                if (!Thread.currentThread().getName().startsWith("ForkJoinPool.commonPool-worker")) {
                    throw new IllegalStateException("not a common-pool worker");
                }
                ran.countDown();
            });
            if (!ran.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IllegalStateException("the common pool never ran the task");
            }
            bootuiagentit.ChildMain.SHARED_POOL
                    .submit(() -> {
                        Probed.touch(SENTINEL.run);
                        if (THREAD_LOCALS) {
                            // Left set on the JVM-wide pool's worker: the sensor reports it, and must keep nothing.
                            LEAKED.set(new RunContext());
                        }
                    })
                    .get();
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
            pool.submit(() -> Probed.touch(SENTINEL.run)).get();
            pool.shutdown();
            // A thread this run starts itself, propagated by the threads sensor.
            Thread own = new Thread(() -> Probed.touch(SENTINEL.run));
            own.start();
            own.join();
            Thread inert = new Thread();
            inert.setContextClassLoader(null);
            java.util.concurrent.CompletableFuture.delayedExecutor(10, java.util.concurrent.TimeUnit.MINUTES)
                    .execute(new java.util.concurrent.FutureTask<Object>(
                            java.util.concurrent.Executors.callable(inert, null)));
        } finally {
            CURRENT.remove();
        }
    }

    static final class RunContext {
        final Sentinel sentinel = SENTINEL;
    }

    /**
     * The engine's drain, as the run's engine would do it: the thread-locals records drained and each thread local's
     * holder resolved by the agent, so the resolver's caches hold this run's classes, weakly.
     */
    public static int resolveThreadLocals(long token) throws Exception {
        if (!THREAD_LOCALS) {
            return 0;
        }
        Class<?> sideEffects = Class.forName("io.github.jdubois.bootui.agent.bridge.SideEffects", false, null);
        Class<?> threadLocals = Class.forName("io.github.jdubois.bootui.agent.bridge.ThreadLocals", false, null);
        List<long[]> records = new java.util.ArrayList<>();
        java.util.function.Consumer<long[]> sink = record -> {
            if (record[0] == 7L) {
                records.add(record.clone());
            }
        };
        sideEffects
                .getMethod("drain", long.class, java.util.function.Consumer.class)
                .invoke(null, token, sink);
        int resolved = 0;
        for (long[] record : records) {
            int id = (int) ((record[9] >>> 40) & 0xFFFF);
            String[] holder = (String[]) threadLocals
                    .getMethod("holder", long.class, int.class, int.class, String[].class, String[].class, long.class)
                    .invoke(
                            null,
                            record[2],
                            id,
                            (int) record[11],
                            new String[] {"bootuiagentit.run"},
                            new String[0],
                            5_000_000_000L);
            if (holder != null && (RunApp.class.getName() + ".LEAKED").equals(holder[0])) {
                resolved++;
            }
        }
        return resolved;
    }

    public static void disarm(long token) throws Exception {
        Class<?> bridge = Class.forName("io.github.jdubois.bootui.agent.bridge.AgentBridge", false, null);
        bridge.getMethod("disarm", long.class).invoke(null, token);
        Probed.touch(SENTINEL.run);
        capture = null;
        reopen = null;
    }

    static final class Sentinel {
        final int run;

        Sentinel(int run) {
            this.run = run;
        }
    }
}
