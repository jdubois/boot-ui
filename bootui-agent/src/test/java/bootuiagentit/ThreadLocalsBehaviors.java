package bootuiagentit;

import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import io.github.jdubois.bootui.agent.bridge.SideEffects;
import io.github.jdubois.bootui.agent.bridge.ThreadLocals;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The thread-locals sensor's behaviors (PLAN-v2 §5.16, M5-5f), in a forked JVM beside the agent, claimed with the
 * {@code thread-locals} sensor and M5-2's {@code executors}, and a harness engine whose context is a thread-local
 * request id. Scopes open and close as the engine's adapters open them: explicitly ({@link ThreadLocals#open()}), and
 * around a request's pool task the executors sensor reopens. Prints one PASS or FAIL line per behavior, then the
 * bridge's status. No value set in a thread local here may ever reach a record or a string the bridge interned.
 */
public final class ThreadLocalsBehaviors {

    static final String SECRET = "tenant-secret-value-42";

    static final ThreadLocal<String> CONTEXT = new ThreadLocal<>();
    static final ThreadLocal<String> TENANT = new ThreadLocal<>();
    static final ThreadLocal<String> CLEARED = new ThreadLocal<>();
    static final ThreadLocal<String> NULLED = new ThreadLocal<>();
    static final InheritableThreadLocal<String> INHERITED = new InheritableThreadLocal<>();
    static final ThreadLocal<StringBuilder> CACHE = ThreadLocal.withInitial(StringBuilder::new);

    /** A thread local held in an instance field, as a singleton bean would hold it: its holder is not resolved. */
    final ThreadLocal<String> perInstance = new ThreadLocal<>();

    static final List<String> RESULTS = new ArrayList<>();
    static final List<long[]> RECORDS = new ArrayList<>();
    static long token;
    static long generation;
    static long nextRequest = 0x500L;

    private ThreadLocalsBehaviors() {}

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "behaviors";
        new ThreadPoolExecutor(0, 1, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<>()).shutdown();
        // Loaded, never initialized: the resolver walks it and must never run its static initializer.
        Class.forName("bootuiagentit.UninitializedHolder", false, ThreadLocalsBehaviors.class.getClassLoader());
        List<String> sensors = List.of("executors", SideEffects.THREAD_LOCALS);
        token = claim(sensors);
        for (String sensor : sensors) {
            Map<String, Object> row = SensorWait.awaitSettled(sensor);
            System.out.println("SELF_TEST_" + sensor + "=" + row.get("selfTestPassed") + " " + row.get("selfTestError")
                    + " " + row.get("hooks"));
        }
        System.out.println("SENSOR=" + SideEffectsBehaviors.sensor(SideEffects.THREAD_LOCALS));
        if (!"check".equals(mode)) {
            leftSet();
            clearedNulledAndBefore();
            if ("fallback".equals(mode)) {
                fallback();
            } else {
                holders();
                depthOne();
            }
            poolTask();
            jdkThreadLocals();
            virtualThreads();
            noValue();
        }
        Map<String, Object> status = AgentBridge.status();
        System.out.println("THREAD_LOCALS=" + status.get(SideEffects.THREAD_LOCALS));
        RESULTS.forEach(System.out::println);
        System.out.println("STATUS=" + status);
    }

    static long claim(List<String> sensors) {
        Supplier<Object> capture = () -> CONTEXT.get() == null
                ? null
                : new Object[] {CONTEXT.get(), null, null, null, "/tenants", null, null, 1L, 1L};
        Function<Object, AutoCloseable> reopen = argument -> {
            Object[] snapshot = (Object[]) ((Object[]) argument)[0];
            String previous = CONTEXT.get();
            CONTEXT.set((String) snapshot[0]);
            return () -> {
                if (previous == null) {
                    CONTEXT.remove();
                } else {
                    CONTEXT.set(previous);
                }
            };
        };
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("application", "thread-locals-behaviors");
        request.put("mode", "dev");
        request.put("packages", List.of("bootuiagentit"));
        request.put("sensors", sensors);
        Map<String, Object> result = AgentBridge.claim(request, capture, reopen);
        System.out.println("CLAIM=" + result.get("status"));
        generation = (Long) result.get("generation");
        // Kept reachable for the claim's life, as the engine keeps them.
        KEEP.add(capture);
        KEEP.add(reopen);
        return (Long) result.get("token");
    }

    static final List<Object> KEEP = new ArrayList<>();

    // ---- behaviors ---------------------------------------------------------------------------------------------

    static void leftSet() {
        RECORDS.clear();
        long request = request();
        long scope = ThreadLocals.open();
        TENANT.set(SECRET);
        ThreadLocals.close(scope);
        endRequest();
        drain();
        long[] record = RECORDS.size() == 1 ? RECORDS.get(0) : null;
        check(
                "a thread local a request left set is reported once with its request, never its value (" + describe()
                        + ")",
                scope != 0L
                        && record != null
                        && record[SideEffects.R_REQUEST] == request
                        && record[SideEffects.R_KIND] == SideEffects.KIND_THREAD_LOCAL_LEFT_SET
                        && "java.lang.ThreadLocal".equals(string(record[SideEffects.R_TARGET])));
        TENANT.remove();
    }

    static void clearedNulledAndBefore() {
        RECORDS.clear();
        request();
        TENANT.set("set before the request");
        long scope = ThreadLocals.open();
        CLEARED.set(SECRET);
        CLEARED.remove();
        NULLED.set(SECRET);
        NULLED.set(null);
        try {
            CLEARED.set(SECRET);
        } finally {
            CLEARED.remove();
        }
        TENANT.set("overwritten during the request");
        ThreadLocals.close(scope);
        endRequest();
        drain();
        check(
                "thread locals cleared in finally, set to null, or set before the request are never reported ("
                        + describe() + ")",
                RECORDS.isEmpty());
        TENANT.remove();
        NULLED.remove();
    }

    static void holders() {
        RECORDS.clear();
        request();
        ThreadLocalsBehaviors instance = new ThreadLocalsBehaviors();
        long scope = ThreadLocals.open();
        TENANT.set(SECRET);
        INHERITED.set(SECRET);
        CACHE.get().append(SECRET);
        instance.perInstance.set(SECRET);
        ThreadLocals.close(scope);
        endRequest();
        drain();
        Map<String, String[]> holders = new LinkedHashMap<>();
        for (long[] record : RECORDS) {
            int detail = (int) ((record[SideEffects.R_FLAGS] >>> 32) & 0xFF);
            int id = (int) ((record[SideEffects.R_FLAGS] >>> 40) & 0xFFFF);
            String[] holder = ThreadLocals.holder(
                    generation,
                    id,
                    (int) record[SideEffects.R_NANOS],
                    new String[] {"bootuiagentit"},
                    new String[0],
                    5_000_000_000L);
            holders.put(
                    String.valueOf(holder == null ? null : holder[0]),
                    new String[] {String.valueOf(detail), holder == null ? null : holder[1]});
        }
        String prefix = ThreadLocalsBehaviors.class.getName() + ".";
        check(
                "holders are resolved to their static fields, a withInitial one flagged, an instance field's left"
                        + " unresolved (" + holders.keySet() + ")",
                holders.size() == 4
                        && holders.containsKey(prefix + "TENANT")
                        && holders.containsKey(prefix + "INHERITED")
                        && "1".equals(holders.get(prefix + "INHERITED")[0])
                        && holders.containsKey(prefix + "CACHE")
                        && "true".equals(holders.get(prefix + "CACHE")[1])
                        && holders.containsKey("null")
                        && System.getProperty("bootui.it.clinit-ran") == null);
        TENANT.remove();
        INHERITED.remove();
        CACHE.remove();
        instance.perInstance.remove();
    }

    /**
     * The resolver's Code Inventory fallback, forced by omitting its Unsafe check: leftovers are still reported, and
     * the sensor says how it tells an initialized class.
     */
    static void fallback() {
        RECORDS.clear();
        request();
        long scope = ThreadLocals.open();
        TENANT.set(SECRET);
        ThreadLocals.close(scope);
        endRequest();
        drain();
        Map<?, ?> status = (Map<?, ?>) AgentBridge.status().get(SideEffects.THREAD_LOCALS);
        check(
                "without the Unsafe check the resolver falls back to Code Inventory and leftovers are still reported ("
                        + describe() + ", " + (status == null ? null : status.get("initializationCheck")) + ")",
                RECORDS.size() == 1 && status != null && "inventory".equals(status.get("initializationCheck")));
        TENANT.remove();
    }

    /**
     * A framework singleton's instance field, reached one level deep from the static field holding the singleton, and a
     * holder class loaded but never initialized, which the resolver never initializes.
     */
    static void depthOne() {
        RECORDS.clear();
        request();
        long scope = ThreadLocals.open();
        FrameworkLike.INSTANCE.local.set(SECRET);
        ThreadLocals.close(scope);
        endRequest();
        drain();
        String holder = null;
        if (RECORDS.size() == 1) {
            long[] record = RECORDS.get(0);
            String[] answer = ThreadLocals.holder(
                    generation,
                    (int) ((record[SideEffects.R_FLAGS] >>> 40) & 0xFFFF),
                    (int) record[SideEffects.R_NANOS],
                    new String[] {"bootuiagentit"},
                    new String[] {"bootuiagentit.FrameworkLike"},
                    5_000_000_000L);
            holder = answer == null ? null : answer[0];
        }
        check(
                "a framework singleton's instance field is resolved one level deep, and a holder class never"
                        + " initialized stays so (" + holder + ")",
                "bootuiagentit.FrameworkLike.local (via bootuiagentit.FrameworkLike.INSTANCE)".equals(holder)
                        && System.getProperty("bootui.it.clinit-ran") == null);
        FrameworkLike.INSTANCE.local.remove();
    }

    static void poolTask() throws Exception {
        RECORDS.clear();
        long request = request();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        try {
            pool.submit(() -> TENANT.set(SECRET)).get();
            pool.submit(() -> {
                        try {
                            CLEARED.set(SECRET);
                        } finally {
                            CLEARED.remove();
                        }
                    })
                    .get();
            // Its handoff closes once the task returned: waited for by a later task on the same worker.
            pool.submit(() -> {}).get();
            endRequest();
            drain();
            long[] record = RECORDS.size() == 1 ? RECORDS.get(0) : null;
            check(
                    "a request's pool task leaving a thread local set on its worker is reported, one clearing it in"
                            + " finally never is (" + describe() + ")",
                    record != null && record[SideEffects.R_REQUEST] == request);
            pool.submit(() -> TENANT.remove()).get();
        } finally {
            pool.shutdownNow();
        }
    }

    static void jdkThreadLocals() throws Exception {
        RECORDS.clear();
        request();
        ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        Thread first = new Thread(() -> {
            lock.readLock().lock();
            held.countDown();
            try {
                done.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                lock.readLock().unlock();
            }
        });
        first.start();
        held.await(10, TimeUnit.SECONDS);
        long scope = ThreadLocals.open();
        // The second reader's hold count lives in the lock's ThreadLocalHoldCounter.
        lock.readLock().lock();
        ThreadLocals.close(scope);
        lock.readLock().unlock();
        done.countDown();
        first.join();
        endRequest();
        drain();
        check(
                "the JDK's own thread locals, as a read lock's hold counter, are never reported (" + describe() + ")",
                RECORDS.isEmpty() && counter("jdkKeys") > 0);
    }

    static void virtualThreads() throws Exception {
        if (Runtime.version().feature() < 21) {
            return;
        }
        AtomicLong opened = new AtomicLong(-1);
        Object builder = Thread.class.getMethod("ofVirtual").invoke(null);
        Thread thread = (Thread) Class.forName("java.lang.Thread$Builder")
                .getMethod("unstarted", Runnable.class)
                .invoke(builder, (Runnable) () -> {
                    CONTEXT.set(String.format("%016x", nextRequest++));
                    opened.set(ThreadLocals.open());
                    TENANT.set(SECRET);
                    ThreadLocals.close(opened.get());
                });
        thread.start();
        thread.join();
        check("a virtual thread, never pooled, is never scanned", opened.get() == 0L && counter("virtualSkipped") > 0);
    }

    static void noValue() {
        String interned = String.join("|", strings());
        check(
                "no value set in a thread local ever reaches a string the bridge interned",
                !interned.contains(SECRET) && !interned.contains("set before the request"));
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    static long request() {
        long request = nextRequest++;
        CONTEXT.set(String.format("%016x", request));
        return request;
    }

    static void endRequest() {
        CONTEXT.remove();
    }

    static void drain() {
        SideEffects.drain(token, record -> {
            if (record[SideEffects.R_SENSOR] == SideEffects.SENSOR_THREAD_LOCALS) {
                RECORDS.add(record.clone());
            }
        });
    }

    static long counter(String name) {
        Map<?, ?> status = (Map<?, ?>) AgentBridge.status().get(SideEffects.THREAD_LOCALS);
        Object value = status == null ? null : status.get(name);
        return value instanceof Long ? (Long) value : -1L;
    }

    static String string(long id) {
        if (id <= 0) {
            return null;
        }
        String[] strings = SideEffects.interned(generation, (int) id);
        return strings == null || strings.length == 0 ? null : strings[0];
    }

    static List<String> strings() {
        String[] strings = SideEffects.interned(generation, 1);
        return strings == null ? List.of() : Arrays.asList(strings);
    }

    static String describe() {
        List<String> described = new ArrayList<>();
        for (long[] record : RECORDS) {
            described.add(string(record[SideEffects.R_TARGET]) + "@" + Long.toHexString(record[SideEffects.R_REQUEST]));
        }
        return described.toString();
    }

    static void check(String name, boolean ok) {
        RESULTS.add((ok ? "  PASS " : "  FAIL ") + name);
    }
}
