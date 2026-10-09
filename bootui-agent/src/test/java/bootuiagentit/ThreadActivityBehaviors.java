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
        if (!"check".equals(mode) && !"worker-failure".equals(mode)) {
            leftRunning();
            joined();
            executorLeftRunning();
            executorShutDown();
            poolWorkers();
            jdkThread();
            timer();
            libraryThread();
            lazyHolder();
            lazySpringSingleton();
            springPrototype();
            lazyArcSingleton();
            arcApplicationScoped();
            unowned();
            virtualThreads();
            reclaimed();
            bootUiWork();
            anotherSwitchKeepsPendingChecks();
            if (!"beside-propagation".equals(mode)) {
                releaseRestores();
            }
        }
        if ("worker-failure".equals(mode)) {
            aFailedJobDisablesOnlyTheGroupItTouched();
            leftRunning();
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
        // The JDK's HttpClient starts its selector thread in its own constructor: the JDK's, never the application's.
        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        endRequest(request);
        long[] start = await(kind(SideEffects.KIND_THREAD_START));
        settle();
        check(
                "a thread the JDK starts for the application is the JDK's, recorded, never left running ("
                        + describe(RECORDS) + ")",
                client != null
                        && start != null
                        && origin(start) == ThreadActivity.ORIGIN_JDK
                        && none(kind(SideEffects.KIND_THREAD_LEFT_RUNNING)));
    }

    static void timer() throws Exception {
        RECORDS.clear();
        long request = request();
        // A java.util.Timer started per request, a classic leak: the Timer is the threading API, the caller the
        // creator.
        java.util.Timer timer = new java.util.Timer("it-timer-thread", true);
        endRequest(request);
        long[] left = await(kind(SideEffects.KIND_THREAD_LEFT_RUNNING));
        timer.cancel();
        check(
                "a java.util.Timer the application starts for a request is its own thread, left running ("
                        + describe(RECORDS) + ")",
                left != null && origin(left) == ThreadActivity.ORIGIN_APPLICATION);
    }

    /** A lazy holder's executor, created by its static initializer on first use inside a request. */
    static final class LazyHolder {
        static final ExecutorService POOL = Executors.newSingleThreadExecutor();

        private LazyHolder() {}
    }

    /** A bean whose constructor creates an executor and keeps it: a singleton's for the application's life. */
    public static final class PoolBean {
        final ExecutorService pool = Executors.newSingleThreadExecutor();

        public PoolBean() {}
    }

    static void lazyHolder() throws Exception {
        RECORDS.clear();
        long request = request();
        LazyHolder.POOL.submit(() -> 1).get();
        endRequest(request);
        long[] created = await(kind(SideEffects.KIND_EXECUTOR_CREATE));
        settle();
        LazyHolder.POOL.shutdown();
        check(
                "a lazy holder's executor created in its static initializer inside a request is a singleton's, never"
                        + " left running (" + describe(RECORDS) + ")",
                created != null && isStatic(created) && none(kind(SideEffects.KIND_EXECUTOR_LEFT_RUNNING)));
    }

    static void lazySpringSingleton() throws Exception {
        RECORDS.clear();
        org.springframework.beans.factory.support.DefaultListableBeanFactory factory =
                new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        org.springframework.beans.factory.support.RootBeanDefinition definition =
                new org.springframework.beans.factory.support.RootBeanDefinition(PoolBean.class);
        definition.setLazyInit(true);
        factory.registerBeanDefinition("poolBean", definition);
        long request = request();
        PoolBean bean = factory.getBean(PoolBean.class);
        endRequest(request);
        long[] created = await(kind(SideEffects.KIND_EXECUTOR_CREATE));
        settle();
        bean.pool.shutdown();
        check(
                "a lazy Spring singleton bean's executor created on first use inside a request is a singleton's, never"
                        + " left running (" + describe(RECORDS) + ")",
                created != null && isStatic(created) && none(kind(SideEffects.KIND_EXECUTOR_LEFT_RUNNING)));
    }

    static void springPrototype() throws Exception {
        RECORDS.clear();
        org.springframework.beans.factory.support.DefaultListableBeanFactory factory =
                new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        org.springframework.beans.factory.support.RootBeanDefinition definition =
                new org.springframework.beans.factory.support.RootBeanDefinition(PoolBean.class);
        definition.setScope(org.springframework.beans.factory.config.BeanDefinition.SCOPE_PROTOTYPE);
        factory.registerBeanDefinition("poolBean", definition);
        long request = request();
        PoolBean bean = factory.getBean(PoolBean.class);
        endRequest(request);
        long[] left = await(kind(SideEffects.KIND_EXECUTOR_LEFT_RUNNING));
        bean.pool.shutdown();
        check(
                "a Spring prototype bean's executor created for a request and never shut down is still left running ("
                        + describe(RECORDS) + ")",
                left != null && !isStatic(left) && left[SideEffects.R_REQUEST] == request);
    }

    /** An {@code @ApplicationScoped} bean whose constructor creates an executor it keeps, used through a client proxy. */
    public static final class ScopedPools implements Runnable {
        static volatile ExecutorService pool;

        public ScopedPools() {
            pool = Executors.newSingleThreadExecutor();
        }

        @Override
        public void run() {
            try {
                pool.submit(() -> 1).get();
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    static void arcApplicationScoped() throws Exception {
        RECORDS.clear();
        Runnable proxy =
                new io.quarkus.arc.impl.ItSingletonContext().applicationScoped("scoped-pools", () -> new ScopedPools());
        long request = request();
        proxy.run();
        proxy.run();
        endRequest(request);
        long[] created = await(kind(SideEffects.KIND_EXECUTOR_CREATE));
        settle();
        ScopedPools.pool.shutdown();
        check(
                "an ArC @ApplicationScoped bean's executor created through its client proxy on first use inside a"
                        + " request is a singleton's, never left running (" + describe(RECORDS) + ")",
                created != null && isStatic(created) && none(kind(SideEffects.KIND_EXECUTOR_LEFT_RUNNING)));
    }

    static void lazyArcSingleton() throws Exception {
        RECORDS.clear();
        io.quarkus.arc.impl.ItSingletonContext context = new io.quarkus.arc.impl.ItSingletonContext();
        long request = request();
        PoolBean bean = context.get("pool-bean", PoolBean.class, PoolBean::new);
        endRequest(request);
        long[] created = await(kind(SideEffects.KIND_EXECUTOR_CREATE));
        settle();
        bean.pool.shutdown();
        check(
                "an ArC singleton bean's executor created on first use inside a request is a singleton's, never left"
                        + " running (" + describe(RECORDS) + ")",
                created != null && isStatic(created) && none(kind(SideEffects.KIND_EXECUTOR_LEFT_RUNNING)));
    }

    static void anotherSwitchKeepsPendingChecks() throws Exception {
        RECORDS.clear();
        long request = request();
        CountDownLatch release = new CountDownLatch(1);
        Thread thread = new Thread(() -> await(release), "switch-survivor-1");
        thread.setDaemon(true);
        thread.start();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        Map<String, Object> on = AgentBridge.switchSensor(token, SideEffects.FILES, true);
        Map<String, Object> files = watch(seen, () -> {
            Map<String, Object> sensor = sensor(SideEffects.FILES);
            return SensorWait.settled(sensor) ? sensor : null;
        });
        endRequest(request);
        long[] left = await(kind(SideEffects.KIND_THREAD_LEFT_RUNNING));
        Map<String, Object> off = AgentBridge.switchSensor(token, SideEffects.FILES, false);
        Object released = watch(seen, () -> {
            Map<String, Object> sensor = sensor(SideEffects.FILES);
            return "released".equals(sensor.get("state")) && Boolean.TRUE.equals(sensor.get("idle"))
                    ? "released"
                    : null;
        });
        release.countDown();
        thread.join();
        check(
                "switching another side-effect sensor at run time keeps what thread-activity waits to check, and"
                        + " thread-activity reads installed and passed throughout, never tested again (" + on + " "
                        + (files == null ? null : files.get("state")) + " " + off + " " + released + " " + seen + " "
                        + describe(RECORDS) + ")",
                AgentBridge.ARMED.equals(on.get("status"))
                        && files != null
                        && "installed".equals(files.get("state"))
                        && "released".equals(released)
                        && seen.equals(java.util.Set.of("installed true"))
                        && left != null
                        && left[SideEffects.R_REQUEST] == request
                        && "switch-survivor-{n}".equals(string(left[SideEffects.R_TARGET])));
    }

    /**
     * A side-effect sensors' job that fails (here, the install of {@code files}, which the IT hook makes throw once)
     * marks and disables only the transformer group it touched: {@code files} reads failed, while thread-activity, in
     * the other group, keeps its state, its verdict, and its install and self-test durations, and goes on recording.
     */
    static void aFailedJobDisablesOnlyTheGroupItTouched() throws Exception {
        Map<String, Object> before = sensor(SideEffects.THREAD_ACTIVITY);
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        Map<String, Object> on = AgentBridge.switchSensor(token, SideEffects.FILES, true);
        Map<String, Object> files = watch(seen, () -> {
            Map<String, Object> sensor = sensor(SideEffects.FILES);
            return "failed".equals(sensor.get("state")) && Boolean.TRUE.equals(sensor.get("idle")) ? sensor : null;
        });
        Map<String, Object> after = sensor(SideEffects.THREAD_ACTIVITY);
        check(
                "a failed side-effect job disables only the group it touched: thread-activity keeps its state, verdict,"
                        + " and durations (" + on + " " + files + " " + seen + " before=" + before + " after=" + after
                        + ")",
                AgentBridge.ARMED.equals(on.get("status"))
                        && files != null
                        && Boolean.FALSE.equals(files.get("selfTestPassed"))
                        && seen.equals(java.util.Set.of("installed true"))
                        && "installed".equals(after.get("state"))
                        && Boolean.TRUE.equals(after.get("selfTestPassed"))
                        && after.get("selfTestError") == null
                        && before.get("installMillis").equals(after.get("installMillis"))
                        && before.get("selfTestMillis").equals(after.get("selfTestMillis")));
    }

    /**
     * Polls {@code done} every 5 ms for at most 30 s, noting thread-activity's state and self-test verdict at each poll
     * in {@code seen}; returns {@code done}'s first non-null answer, or {@code null}.
     */
    static <T> T watch(java.util.Set<String> seen, Supplier<T> done) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() - deadline < 0) {
            Map<String, Object> threads = sensor(SideEffects.THREAD_ACTIVITY);
            seen.add(threads.get("state") + " " + threads.get("selfTestPassed"));
            T answer = done.get();
            if (answer != null) {
                return answer;
            }
            Thread.sleep(5);
        }
        return null;
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

    static boolean isStatic(long[] record) {
        return (detail(record) & ThreadActivity.DETAIL_STATIC) != 0;
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
