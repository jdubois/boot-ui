package bootuiagentit;

import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import io.github.jdubois.bootui.agent.bridge.SideEffects;
import io.github.jdubois.bootui.agent.bridge.ThreadActivity;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.bootuiit.library.LibraryPool;

/**
 * The thread-activity sensor's behaviors (PLAN-v2 §5.16, M5-5e), in a forked JVM beside the agent, claimed with the
 * {@code thread-activity} sensor, alone or beside M5-2's {@code executors} and {@code threads}, and a harness engine
 * whose context is a thread-local request id. The harness ends a request as an adapter does, through {@link
 * ThreadActivity#requestEnded}, then drains past the grace. Prints one PASS or FAIL line per behavior, then the
 * bridge's status.
 */
public final class ThreadActivityBehaviors {

    static final String REQUEST = "00000000000000e5";
    static final long REQUEST_BITS = 0xe5L;
    static long nextRequest = 0x100L;

    static final ThreadLocal<String> CONTEXT = new ThreadLocal<>();
    static final List<String> RESULTS = new ArrayList<>();
    static final List<long[]> RECORDS = new ArrayList<>();
    static long token;
    static long generation;

    private ThreadActivityBehaviors() {}

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "behaviors";
        // Loaded and used before the claim, as an application's are: the hooks retransform them.
        new Thread(() -> {}).getName();
        new ThreadPoolExecutor(0, 1, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<>()).shutdown();
        List<String> sensors = "beside-propagation".equals(mode)
                ? List.of("executors", "threads", SideEffects.THREAD_ACTIVITY)
                : List.of(SideEffects.THREAD_ACTIVITY);
        token = claim(sensors);
        for (String sensor : sensors) {
            awaitSelfTest(sensor);
        }
        if (!"check".equals(mode)) {
            leftRunning();
            joined();
            executorLeftRunning();
            executorShutDown();
            poolWorkers();
            jdkThread();
            libraryThread();
            unowned();
            virtualThreads();
            reclaimed();
            bootUiWork();
            if (!"beside-propagation".equals(mode)) {
                releaseRestores();
            }
        }
        Map<String, Object> status = AgentBridge.status();
        System.out.println("THREAD_ACTIVITY=" + status.get(SideEffects.THREAD_ACTIVITY));
        System.out.println("SENSOR=" + sensor(SideEffects.THREAD_ACTIVITY));
        RESULTS.forEach(System.out::println);
        System.out.println("STATUS=" + status);
    }

    static long claim(List<String> sensors) {
        Supplier<Object> capture = () -> CONTEXT.get() == null
                ? null
                : new Object[] {CONTEXT.get(), null, null, null, "/reports", null, null, 1L, 1L};
        Function<Object, AutoCloseable> reopen = argument -> null;
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("application", "thread-activity-behaviors");
        request.put("mode", "dev");
        request.put("packages", List.of("bootuiagentit"));
        request.put("sensors", sensors);
        Map<String, Object> result = AgentBridge.claim(request, capture, reopen);
        System.out.println("CLAIM=" + result.get("status"));
        generation = (Long) result.get("generation");
        return (Long) result.get("token");
    }

    // ---- behaviors ---------------------------------------------------------------------------------------------

    static void leftRunning() throws Exception {
        RECORDS.clear();
        long request = request();
        CountDownLatch release = new CountDownLatch(1);
        Thread thread = new Thread(() -> await(release), "report-refresher-7");
        thread.setDaemon(true);
        thread.start();
        endRequest(request);
        long[] left = await(kind(SideEffects.KIND_THREAD_LEFT_RUNNING));
        long[] start = first(kind(SideEffects.KIND_THREAD_START));
        release.countDown();
        thread.join();
        check(
                "a thread the application started for a request and still running when it ended is reported left"
                        + " running (" + describe(RECORDS) + ")",
                start != null
                        && left != null
                        && start[SideEffects.R_REQUEST] == request
                        && left[SideEffects.R_REQUEST] == request
                        && "report-refresher-{n}".equals(string(start[SideEffects.R_TARGET]))
                        && left[SideEffects.R_TARGET] == start[SideEffects.R_TARGET]
                        && left[SideEffects.R_FRAMES] == start[SideEffects.R_FRAMES]
                        && origin(start) == ThreadActivity.ORIGIN_APPLICATION
                        && applicationFrame(start).startsWith("bootuiagentit.ThreadActivityBehaviors#leftRunning"));
    }

    static void joined() throws Exception {
        RECORDS.clear();
        long request = request();
        Thread thread = new Thread(() -> {}, "report-refresh-now");
        thread.start();
        thread.join();
        endRequest(request);
        long[] start = await(kind(SideEffects.KIND_THREAD_START));
        settle();
        check(
                "a thread joined before its request ended is recorded started, never left running (" + describe(RECORDS)
                        + ")",
                start != null && none(kind(SideEffects.KIND_THREAD_LEFT_RUNNING)));
    }

    static void executorLeftRunning() throws Exception {
        RECORDS.clear();
        long request = request();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        pool.allowCoreThreadTimeOut(true);
        pool.submit(() -> 42).get();
        endRequest(request);
        long[] left = await(kind(SideEffects.KIND_EXECUTOR_LEFT_RUNNING));
        long[] created = first(kind(SideEffects.KIND_EXECUTOR_CREATE));
        RECORDS.clear();
        pool.shutdown();
        long[] shutdown = await(kind(SideEffects.KIND_EXECUTOR_SHUTDOWN));
        check(
                "an executor created for a request and not shut down when it ended is reported left running, then its"
                        + " shutdown lands on its creation (" + describe(RECORDS) + ")",
                created != null
                        && left != null
                        && shutdown != null
                        && "java.util.concurrent.ThreadPoolExecutor".equals(string(created[SideEffects.R_TARGET]))
                        && left[SideEffects.R_TARGET] == created[SideEffects.R_TARGET]
                        && shutdown[SideEffects.R_REQUEST] == request
                        && executorKind(created) == ThreadActivity.EXECUTOR_THREAD_POOL);
    }

    static void executorShutDown() throws Exception {
        RECORDS.clear();
        long request = request();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(() -> 42).get();
        } finally {
            pool.shutdown();
        }
        endRequest(request);
        long[] shutdown = await(kind(SideEffects.KIND_EXECUTOR_SHUTDOWN));
        settle();
        check(
                "an executor shut down in finally is recorded created and shut down, never left running, and its pool"
                        + " worker is never a thread of its own (" + describe(RECORDS) + ")",
                shutdown != null
                        && first(kind(SideEffects.KIND_EXECUTOR_CREATE)) != null
                        && none(kind(SideEffects.KIND_EXECUTOR_LEFT_RUNNING))
                        && none(kind(SideEffects.KIND_THREAD_START)));
    }

    static void poolWorkers() throws Exception {
        RECORDS.clear();
        long request = request();
        java.util.concurrent.ForkJoinPool forkJoin = new java.util.concurrent.ForkJoinPool(2);
        forkJoin.submit(() -> 1).get();
        java.util.concurrent.CompletableFuture.supplyAsync(() -> 2).get();
        forkJoin.shutdownNow();
        endRequest(request);
        long[] created = await(kind(SideEffects.KIND_EXECUTOR_CREATE));
        await(kind(SideEffects.KIND_EXECUTOR_SHUTDOWN));
        settle();
        check(
                "fork-join workers and the common pool's are never threads of their own; the pool is an executor ("
                        + describe(RECORDS) + ")",
                created != null
                        && executorKind(created) == ThreadActivity.EXECUTOR_FORK_JOIN
                        && none(kind(SideEffects.KIND_THREAD_START)));
    }

    static void jdkThread() throws Exception {
        RECORDS.clear();
        long request = request();
        java.util.Timer timer = new java.util.Timer("it-timer-thread", true);
        endRequest(request);
        long[] start = await(kind(SideEffects.KIND_THREAD_START));
        settle();
        timer.cancel();
        check(
                "a thread the JDK starts for the application is the JDK's, recorded, never left running ("
                        + describe(RECORDS) + ")",
                start != null
                        && origin(start) == ThreadActivity.ORIGIN_JDK
                        && none(kind(SideEffects.KIND_THREAD_LEFT_RUNNING)));
    }

    static void libraryThread() throws Exception {
        RECORDS.clear();
        long request = request();
        LibraryPool pool = LibraryPool.start();
        endRequest(request);
        long[] start = await(kind(SideEffects.KIND_THREAD_START));
        long[] created = first(kind(SideEffects.KIND_EXECUTOR_CREATE));
        settle();
        pool.close();
        check(
                "a library's thread and executor on a request's thread are the library's, never left running ("
                        + describe(RECORDS) + ")",
                start != null
                        && created != null
                        && origin(start) == ThreadActivity.ORIGIN_LIBRARY
                        && origin(created) == ThreadActivity.ORIGIN_LIBRARY
                        && none(kind(SideEffects.KIND_THREAD_LEFT_RUNNING))
                        && none(kind(SideEffects.KIND_EXECUTOR_LEFT_RUNNING)));
    }

    static void unowned() throws Exception {
        RECORDS.clear();
        for (int i = 0; i < 3; i++) {
            Thread thread = new Thread(() -> {}, "unowned-worker-" + i);
            thread.start();
            thread.join();
        }
        long[] start = await(records -> records.stream()
                        .filter(record -> record[SideEffects.R_KIND] == SideEffects.KIND_THREAD_START)
                        .mapToLong(record -> record[SideEffects.R_COUNT])
                        .sum()
                >= 3);
        long count = RECORDS.stream()
                .filter(record -> record[SideEffects.R_KIND] == SideEffects.KIND_THREAD_START
                        && "unowned-worker-{n}".equals(string(record[SideEffects.R_TARGET])))
                .mapToLong(record -> record[SideEffects.R_COUNT])
                .sum();
        check(
                "starts no request owns are counted under the starting thread's family, by the drain ("
                        + describe(RECORDS) + ")",
                start != null
                        && count == 3
                        && start[SideEffects.R_REQUEST] == 0L
                        && "unowned-worker-{n}".equals(string(start[SideEffects.R_TARGET]))
                        && string((start[SideEffects.R_FLAGS] >>> 16) & 0xFFFF) != null);
    }

    static void virtualThreads() throws Exception {
        if (Runtime.version().feature() < 21) {
            RESULTS.add("  SKIP virtual threads need JDK 21");
            return;
        }
        RECORDS.clear();
        long request = request();
        Object builder = Thread.class.getMethod("ofVirtual").invoke(null);
        Runnable wait = () -> sleep(1_000);
        Thread virtual = (Thread) Class.forName("java.lang.Thread$Builder")
                .getMethod("start", Runnable.class)
                .invoke(builder, wait);
        ExecutorService perTask = (ExecutorService)
                Executors.class.getMethod("newVirtualThreadPerTaskExecutor").invoke(null);
        for (int i = 0; i < 5; i++) {
            perTask.submit(() -> 1).get();
        }
        ExecutorService.class.getMethod("close").invoke(perTask);
        endRequest(request);
        long[] left = await(kind(SideEffects.KIND_THREAD_LEFT_RUNNING));
        await(kind(SideEffects.KIND_EXECUTOR_SHUTDOWN));
        virtual.join();
        long virtualStarts = RECORDS.stream()
                .filter(record -> record[SideEffects.R_KIND] == SideEffects.KIND_THREAD_START)
                .mapToLong(record -> record[SideEffects.R_COUNT])
                .sum();
        long[] created = first(kind(SideEffects.KIND_EXECUTOR_CREATE));
        check(
                "a virtual thread a request started is recorded virtual and left running; a virtual-thread-per-task"
                        + " executor's tasks are its own, never threads (" + describe(RECORDS) + ")",
                left != null
                        && (detail(left) & ThreadActivity.DETAIL_VIRTUAL) != 0
                        && virtualStarts == 1
                        && created != null
                        && executorKind(created) == ThreadActivity.EXECUTOR_PER_TASK);
    }

    static void reclaimed() throws Exception {
        RECORDS.clear();
        long request = request();
        // No task: no worker thread references it, so nothing but this array and the sensor could keep it.
        Object[] held = {new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<>())};
        WeakReference<Object> pool = new WeakReference<>(held[0]);
        endRequest(request);
        long[] left = await(kind(SideEffects.KIND_EXECUTOR_LEFT_RUNNING));
        held[0] = null;
        for (int i = 0; i < 100 && pool.get() != null; i++) {
            System.gc();
            Thread.sleep(20);
        }
        long[] reclaimed = await(kind(SideEffects.KIND_EXECUTOR_RECLAIMED));
        check(
                "the sensor never keeps an executor: one never shut down is collected and reported reclaimed ("
                        + describe(RECORDS) + ")",
                left != null && pool.get() == null && reclaimed != null && reclaimed[SideEffects.R_REQUEST] == request);
    }

    static void bootUiWork() throws Exception {
        RECORDS.clear();
        long request = request();
        boolean previous = AgentBridge.bootUiWork(true);
        try {
            Thread thread = new Thread(() -> {}, "work-thread");
            thread.start();
            thread.join();
            new ThreadPoolExecutor(0, 1, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<>()).shutdown();
        } finally {
            AgentBridge.bootUiWork(previous);
        }
        endRequest(request);
        settle();
        check("BootUI's own threads and executors are never recorded (" + describe(RECORDS) + ")", RECORDS.isEmpty());
    }

    static void releaseRestores() throws Exception {
        AgentBridge.release("thread-activity-behaviors", "dev");
        Object state = SideEffectsBehaviors.awaitState(SideEffects.THREAD_ACTIVITY, "released");
        SideEffects.beginSelfTest();
        Thread thread = new Thread(() -> {});
        thread.start();
        thread.join();
        Map<String, Object> hits = SideEffects.endSelfTest();
        check(
                "release restores Thread and the executors (" + state + ", " + hits + ")",
                "released".equals(state) && Long.valueOf(0L).equals(hits.get("Thread.start")));
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    /** A new request on this thread, as an adapter's scope opens one. */
    static long request() {
        long request = nextRequest++;
        CONTEXT.set(String.format("%016x", request));
        return request;
    }

    /** The request on this thread ends: its scope closes and the adapter tells the engine. */
    static void endRequest(long request) {
        CONTEXT.remove();
        SideEffects.flushThread();
        ThreadActivity.requestEnded(generation, request);
    }

    /** Drains past the grace: what a request left running is checked by then. */
    static void settle() throws Exception {
        for (int i = 0; i < 8; i++) {
            Thread.sleep(60);
            drain();
        }
    }

    static Predicate<List<long[]>> kind(int kind) {
        return records -> records.stream().anyMatch(record -> record[SideEffects.R_KIND] == kind);
    }

    static boolean none(Predicate<List<long[]>> predicate) {
        return !predicate.test(RECORDS);
    }

    static long[] await(Predicate<List<long[]>> done) throws Exception {
        for (int i = 0; i < 200; i++) {
            drain();
            if (done.test(RECORDS)) {
                break;
            }
            Thread.sleep(50);
        }
        return first(done);
    }

    static long[] first(Predicate<List<long[]>> done) {
        for (long[] record : RECORDS) {
            if (done.test(List.of(record))) {
                return record;
            }
        }
        return null;
    }

    static void drain() {
        SideEffects.drain(token, record -> {
            if (record[SideEffects.R_SENSOR] == SideEffects.SENSOR_THREADS) {
                RECORDS.add(record.clone());
            }
        });
    }

    static String string(long id) {
        if (id <= 0) {
            return null;
        }
        String[] strings = SideEffects.interned(generation, (int) id);
        return strings == null || strings.length == 0 ? null : strings[0];
    }

    static int detail(long[] record) {
        return (int) (record[SideEffects.R_FLAGS] >>> 32);
    }

    static int origin(long[] record) {
        return detail(record) & 3;
    }

    static int executorKind(long[] record) {
        return (detail(record) >>> 4) & 0xF;
    }

    static String applicationFrame(long[] record) {
        String frame = string((int) record[SideEffects.R_FRAMES]);
        return frame == null ? "" : frame;
    }

    static String describe(List<long[]> records) {
        List<String> described = new ArrayList<>();
        for (long[] record : records) {
            described.add("kind=" + record[SideEffects.R_KIND] + " target=" + string(record[SideEffects.R_TARGET])
                    + " request=" + Long.toHexString(record[SideEffects.R_REQUEST]) + " count="
                    + record[SideEffects.R_COUNT] + " detail=" + Integer.toBinaryString(detail(record)) + " frames="
                    + string(record[SideEffects.R_FRAMES] >>> 32) + "|" + string((int) record[SideEffects.R_FRAMES]));
        }
        return described.toString();
    }

    static void awaitSelfTest(String id) throws Exception {
        Map<String, Object> sensor = SensorWait.awaitSettled(id);
        System.out.println("SELF_TEST_" + id + "=" + sensor.get("selfTestPassed") + " " + sensor.get("selfTestError")
                + " " + sensor.get("hooks"));
    }

    static Map<String, Object> sensor(String id) {
        return SideEffectsBehaviors.sensor(id);
    }

    static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    static void check(String name, boolean ok) {
        RESULTS.add((ok ? "  PASS " : "  FAIL ") + name);
    }

    static List<String> strings() {
        String[] strings = SideEffects.interned(generation, 1);
        return strings == null ? List.of() : Arrays.asList(strings);
    }
}
