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

    private RunApp() {}

    @SuppressWarnings("unchecked")
    public static long claim() throws Exception {
        Class<?> bridge = Class.forName("io.github.jdubois.bootui.agent.bridge.AgentBridge", false, null);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("application", "it");
        request.put("owner", "it run " + SENTINEL.run);
        request.put("mode", "dev");
        request.put("packages", List.of("bootuiagentit.run"));
        request.put("sensors", List.of("executors", "threads"));
        Object marker = new Object();
        capture = () -> marker != null && CURRENT.get() != null
                ? new Object[] {"run-" + SENTINEL.run, null, null, null, null, null, null, 1L, 1L}
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
                    .submit(() -> Probed.touch(SENTINEL.run))
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
