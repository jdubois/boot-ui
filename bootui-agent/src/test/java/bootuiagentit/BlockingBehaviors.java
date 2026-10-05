package bootuiagentit;

import bootuiblockingapp.LoopWork;
import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import io.github.jdubois.bootui.agent.bridge.Blocking;
import io.github.jdubois.bootui.agent.bridge.CodeInventory;
import io.github.jdubois.bootui.agent.bridge.SideEffects;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The blocking sensor's behaviors (PLAN-v2 §5.16, M5-5c), in a forked JVM beside the agent, claimed with the
 * {@code blocking} sensor alone, packages {@code bootuiblockingapp} (run from a jar, whose call sites the claim
 * rewrites), and a harness engine whose context is a thread-local
 * request id. A thread named {@code it-loop-*} registers itself as an event loop, as an adapter would. Prints one PASS or
 * FAIL line per behavior, then the bridge's status. With {@code blockhound-first}, {@code bootui-first}, or
 * {@code blockhound-throwing}, BlockHound is installed before or after the claim, recording or throwing.
 */
public final class BlockingBehaviors {

    static final String REQUEST = "00000000000000be";
    static final long REQUEST_BITS = 0xbeL;

    static final ThreadLocal<String> CONTEXT = new ThreadLocal<>();
    static final List<String> RESULTS = new ArrayList<>();
    static final List<long[]> RECORDS = new ArrayList<>();
    /** What BlockHound's callback saw, by thread name and method. */
    static final List<String> HOUND = new CopyOnWriteArrayList<>();

    static Supplier<Object> capture;
    static Function<Object, AutoCloseable> reopen;
    static long token;
    static long generation;

    private BlockingBehaviors() {}

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "behaviors";
        // Loaded and used before the claim, as an application's are: their hooks retransform them.
        LockSupport.parkNanos(1L);
        LoopWork.sleepBriefly(0L);
        if ("blockhound-first".equals(mode) || "blockhound-throwing".equals(mode)) {
            Hound.install(!"blockhound-throwing".equals(mode));
        }
        token = "switch".equals(mode) ? claim(List.of("inventory", SideEffects.BLOCKING)) : claim();
        awaitSelfTest();
        if ("bootui-first".equals(mode)) {
            Hound.install(true);
        }
        if ("check".equals(mode)) {
            // The retransformation check only.
        } else if (mode.startsWith("blockhound") || "bootui-first".equals(mode)) {
            besideBlockHound("blockhound-throwing".equals(mode));
        } else if ("bench".equals(mode)) {
            bench();
        } else if ("switch".equals(mode)) {
            switchBesideInventory();
        } else {
            sleepOnALoop();
            sameSleepOffLoops();
            timeUnitAndWait();
            lambdaOnALoop();
            contendedLockOnALoop();
            parkOffLoops();
            interruptedSleep();
            releaseRestores();
        }
        Map<String, Object> status = AgentBridge.status();
        System.out.println("BLOCKING=" + status.get(SideEffects.BLOCKING));
        System.out.println("SENSOR=" + sensor(SideEffects.BLOCKING));
        RESULTS.forEach(System.out::println);
        System.out.println("STATUS=" + status);
    }

    static long claim() {
        return claim(List.of(SideEffects.BLOCKING));
    }

    static long claim(List<String> sensors) {
        Object marker = new Object();
        capture = () -> CONTEXT.get() == null || marker == null
                ? null
                : new Object[] {CONTEXT.get(), null, null, null, "/loop", null, null, 1L, 1L};
        reopen = argument -> null;
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("application", "blocking-behaviors");
        request.put("mode", "dev");
        request.put("packages", List.of("bootuiblockingapp"));
        request.put("sensors", sensors);
        Map<String, Object> result = AgentBridge.claim(request, capture, reopen);
        System.out.println("CLAIM=" + result.get("status"));
        generation = (Long) result.get("generation");
        return (Long) result.get("token");
    }

    /** Runs {@code task} on a new thread, an event loop when its name starts with {@code it-loop-}. */
    static Object on(String name, Callable<Object> task) throws Exception {
        AtomicReference<Object> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(
                () -> {
                    try {
                        if (Thread.currentThread().getName().startsWith("it-loop-")) {
                            Blocking.registerEventLoop();
                        }
                        CONTEXT.set(REQUEST);
                        result.set(task.call());
                    } catch (Throwable ex) {
                        failure.set(ex);
                    } finally {
                        CONTEXT.remove();
                        SideEffects.flushThread();
                    }
                },
                name);
        thread.start();
        thread.join(30_000L);
        if (failure.get() != null) {
            return failure.get();
        }
        return result.get();
    }

    static void sleepOnALoop() throws Exception {
        RECORDS.clear();
        on("it-loop-1", () -> {
            LoopWork.sleepBriefly(20L);
            return null;
        });
        long[] record = await(kind(Blocking.KIND_SLEEP));
        check(
                "a Thread.sleep on an event loop is recorded with its loop, owner, duration, and call site ("
                        + describe(RECORDS) + ")",
                record != null
                        && record[SideEffects.R_SENSOR] == SideEffects.SENSOR_BLOCKING
                        && "it-loop-1".equals(string(record[SideEffects.R_TARGET]))
                        && record[SideEffects.R_REQUEST] == REQUEST_BITS
                        && record[SideEffects.R_NANOS] >= TimeUnit.MILLISECONDS.toNanos(20)
                        && "bootuiblockingapp.LoopWork#sleepBriefly"
                                .equals(string((int) record[SideEffects.R_FRAMES])));
    }

    static void sameSleepOffLoops() throws Exception {
        RECORDS.clear();
        on("it-worker-1", () -> {
            LoopWork.sleepBriefly(20L);
            return null;
        });
        Thread.sleep(200L);
        drain();
        check("the same sleep on a worker thread is not recorded (" + describe(RECORDS) + ")", RECORDS.isEmpty());
    }

    static void timeUnitAndWait() throws Exception {
        RECORDS.clear();
        Object monitor = new Object();
        on("it-loop-2", () -> {
            LoopWork.sleepAndWait(monitor);
            return null;
        });
        await(records -> records.stream().anyMatch(record -> record[SideEffects.R_KIND] == Blocking.KIND_WAIT)
                && records.stream().anyMatch(record -> record[SideEffects.R_KIND] == Blocking.KIND_SLEEP));
        check(
                "TimeUnit.sleep and Object.wait on an event loop are recorded (" + describe(RECORDS) + ")",
                RECORDS.stream().anyMatch(record -> record[SideEffects.R_KIND] == Blocking.KIND_SLEEP)
                        && RECORDS.stream().anyMatch(record -> record[SideEffects.R_KIND] == Blocking.KIND_WAIT));
    }

    static void lambdaOnALoop() throws Exception {
        RECORDS.clear();
        Runnable handler = LoopWork.sleepingHandler();
        on("it-loop-3", () -> {
            handler.run();
            return null;
        });
        long[] record = await(kind(Blocking.KIND_SLEEP));
        String frame = record == null ? null : string((int) record[SideEffects.R_FRAMES]);
        check(
                "a lambda's sleep on an event loop is recorded at the lambda (" + frame + ")",
                frame != null && frame.startsWith("bootuiblockingapp.LoopWork#lambda$"));
    }

    static void contendedLockOnALoop() throws Exception {
        RECORDS.clear();
        ReentrantLock lock = new ReentrantLock();
        CountDownLatch held = new CountDownLatch(1);
        Thread holder = new Thread(
                () -> {
                    lock.lock();
                    try {
                        held.countDown();
                        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(30));
                    } finally {
                        lock.unlock();
                    }
                },
                "it-holder");
        holder.start();
        held.await();
        on("it-loop-4", () -> {
            LoopWork.lockBriefly(lock);
            // A park shorter than a millisecond is only counted.
            LockSupport.parkNanos(1_000L);
            return null;
        });
        holder.join();
        long[] record = await(kind(Blocking.KIND_PARK));
        Map<String, Object> blocking = blockingStatus();
        check(
                "a contended lock parks the event loop and is recorded; a short park is only counted ("
                        + describe(RECORDS) + " shortParks=" + blocking.get("shortParks") + ")",
                record != null
                        && "it-loop-4".equals(string(record[SideEffects.R_TARGET]))
                        && record[SideEffects.R_NANOS] >= Blocking.MIN_PARK_NANOS
                        && "bootuiblockingapp.LoopWork#lockBriefly".equals(string((int) record[SideEffects.R_FRAMES]))
                        && ((Long) blocking.get("shortParks")) >= 1L);
    }

    static void parkOffLoops() throws Exception {
        RECORDS.clear();
        on("it-worker-2", () -> {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
            return null;
        });
        Thread.sleep(200L);
        drain();
        check("a park off event loops is never recorded (" + describe(RECORDS) + ")", RECORDS.isEmpty());
    }

    static void interruptedSleep() throws Exception {
        RECORDS.clear();
        Object thrown = on("it-loop-5", LoopWork::interruptedSleep);
        long[] record = await(kind(Blocking.KIND_SLEEP));
        boolean substituted = thrown instanceof InterruptedException interrupted
                && java.util.Arrays.stream(interrupted.getStackTrace())
                        .anyMatch(frame -> frame.getClassName().equals(Blocking.class.getName()));
        check(
                "an interrupted sleep throws as before, through the substitute, and is recorded interrupted ("
                        + describe(RECORDS) + ")",
                substituted && record != null && outcome(record) == Blocking.OUTCOME_INTERRUPTED);
    }

    static void releaseRestores() throws Exception {
        AgentBridge.release("blocking-behaviors", "dev");
        Object state = awaitState("released");
        SideEffects.beginSelfTest();
        LockSupport.parkNanos(1L);
        Map<String, Object> hits = SideEffects.endSelfTest();
        Blocking.beginCallSiteSelfTest();
        LoopWork.sleepBriefly(1L);
        long[] callSites = Blocking.endCallSiteSelfTest();
        check(
                "release restores LockSupport and the rewritten call sites (" + state + ", " + hits + ", "
                        + callSites[0] + ")",
                "released".equals(state)
                        && Long.valueOf(0L).equals(hits.get("LockSupport.park"))
                        && callSites[0] == 0L);
    }

    /**
     * BlockHound beside the sensor: both see the same sleep and park on the loop. When BlockHound throws from inside them,
     * its error reaches the caller unchanged, the park is recorded as an error, and a later sleep on the same loop is
     * still recorded, so the thread's hook was closed.
     */
    static void besideBlockHound(boolean throwing) throws Exception {
        RECORDS.clear();
        List<String> outcomes = new ArrayList<>();
        on("it-loop-6", () -> {
            outcomes.add(attempt(() -> LoopWork.sleepBriefly(5L)));
            outcomes.add(attempt(() -> LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5))));
            outcomes.add(attempt(() -> LoopWork.sleepBriefly(5L)));
            return null;
        });
        await(records -> records.stream()
                                .filter(record -> record[SideEffects.R_KIND] == Blocking.KIND_SLEEP)
                                .mapToLong(record -> record[SideEffects.R_COUNT])
                                .sum()
                        >= 2
                && records.stream().anyMatch(record -> record[SideEffects.R_KIND] == Blocking.KIND_PARK));
        System.out.println("HOUND=" + HOUND + " OUTCOMES=" + outcomes);
        long sleeps = RECORDS.stream()
                .filter(record -> record[SideEffects.R_KIND] == Blocking.KIND_SLEEP)
                .mapToLong(record -> record[SideEffects.R_COUNT])
                .sum();
        boolean parkRecorded = RECORDS.stream()
                .anyMatch(record -> record[SideEffects.R_KIND] == Blocking.KIND_PARK
                        && outcome(record) == (throwing ? Blocking.OUTCOME_ERROR : Blocking.OUTCOME_RETURNED));
        String expected = throwing ? "BlockingOperationError" : "returned";
        check(
                "BlockHound and the sensor both see a sleep and a park on an event loop"
                        + (throwing ? ", BlockHound throwing" : "") + " (" + describe(RECORDS) + " " + outcomes + ")",
                HOUND.size() >= 3
                        && sleeps >= 2
                        && parkRecorded
                        && outcomes.stream().allMatch(expected::equals));
    }

    interface Blocked {
        void run() throws Exception;
    }

    /** {@code returned}, or the simple name of the error BlockHound threw from inside the call. */
    static String attempt(Blocked call) throws Exception {
        try {
            call.run();
            return "returned";
        } catch (Error blocked) {
            return blocked.getClass().getSimpleName();
        }
    }

    /**
     * The call-site visit switched off and on again beside the inventory's on the shared transformer: the inventory keeps
     * tracking and recording the application's methods through both switches, and the call sites are rewritten only
     * while the blocking sensor is claimed.
     */
    static void switchBesideInventory() throws Exception {
        String key = "bootuiblockingapp.LoopWork#sleepBriefly(J)V";
        boolean first = callSitesRewritten() && executedAfterCall(key);
        claim(List.of("inventory"));
        awaitIdle();
        boolean withoutBlocking = !callSitesRewritten() && executedAfterCall(key);
        token = claim(List.of("inventory", SideEffects.BLOCKING));
        awaitSelfTest();
        boolean again = callSitesRewritten() && executedAfterCall(key);
        Object failed =
                CodeInventory.snapshot(CodeInventory.currentGeneration()).get("failedClasses");
        check(
                "switching the blocking sensor keeps the inventory tracking the application's methods (" + first + ", "
                        + withoutBlocking + ", " + again + ", failed "
                        + java.util.Arrays.toString((String[]) failed) + ")",
                first && withoutBlocking && again && ((String[]) failed).length == 0);
    }

    /** Whether a call of {@code LoopWork.sleepBriefly} reaches the bridge's substitute. */
    static boolean callSitesRewritten() throws Exception {
        Blocking.beginCallSiteSelfTest();
        LoopWork.sleepBriefly(1L);
        return Blocking.endCallSiteSelfTest()[0] > 0;
    }

    /** Whether the inventory of this run records {@code key} as executed, once called. */
    static boolean executedAfterCall(String key) throws Exception {
        LoopWork.sleepBriefly(1L);
        int id = CodeInventory.idOf(key);
        Map<String, Object> snapshot = CodeInventory.snapshot(CodeInventory.currentGeneration());
        if (id < 0 || snapshot == null) {
            return false;
        }
        long[] bits = (long[]) snapshot.get("executed");
        return (id >>> 6) < bits.length && (bits[id >>> 6] & (1L << (id & 63))) != 0;
    }

    static void awaitIdle() throws Exception {
        for (int i = 0; i < 400; i++) {
            Map<String, Object> inventory = sensor("inventory");
            if (Boolean.TRUE.equals(inventory.get("idle")) && Boolean.TRUE.equals(inventory.get("selfTestPassed"))) {
                return;
            }
            Thread.sleep(25);
        }
    }

    /**
     * Parks off event loops with event loops registered, with the permit given so each park returns at once: the
     * advised {@code LockSupport.park} costs, per call, against a run before the sensor records.
     */
    static void bench() throws Exception {
        on("it-loop-bench", () -> null);
        double hooked = parks();
        System.out.println("PARK_NANOS_HOOKED=" + hooked);
        AgentBridge.release("blocking-behaviors", "dev");
        awaitState("released");
        double plain = parks();
        System.out.println("PARK_NANOS_PLAIN=" + plain);
    }

    private static double parks() {
        Thread self = Thread.currentThread();
        int calls = 2_000_000;
        long best = Long.MAX_VALUE;
        for (int round = 0; round < 15; round++) {
            long started = System.nanoTime();
            for (int i = 0; i < calls; i++) {
                LockSupport.unpark(self);
                LockSupport.park();
            }
            best = Math.min(best, System.nanoTime() - started);
        }
        return (double) best / calls;
    }

    /** BlockHound, linked only in its modes, whose class path holds it. */
    static final class Hound {

        private Hound() {}

        static void install(boolean recording) {
            reactor.blockhound.BlockHound.Builder builder = reactor.blockhound.BlockHound.builder()
                    .nonBlockingThreadPredicate(
                            current -> current.or(thread -> thread.getName().startsWith("it-loop-")));
            if (recording) {
                builder = builder.blockingMethodCallback(
                        method -> HOUND.add(Thread.currentThread().getName() + " " + method));
            } else {
                builder = builder.blockingMethodCallback(method -> {
                    HOUND.add(Thread.currentThread().getName() + " " + method);
                    throw new reactor.blockhound.BlockingOperationError(method);
                });
            }
            builder.install();
            System.out.println("BLOCKHOUND=installed");
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    static java.util.function.Predicate<List<long[]>> kind(int kind) {
        return records -> records.stream().anyMatch(record -> record[SideEffects.R_KIND] == kind);
    }

    static long[] await(java.util.function.Predicate<List<long[]>> done) throws Exception {
        for (int i = 0; i < 300; i++) {
            drain();
            if (done.test(RECORDS)) {
                break;
            }
            Thread.sleep(50);
        }
        for (long[] record : RECORDS) {
            if (done.test(List.of(record))) {
                return record;
            }
        }
        return null;
    }

    static void drain() {
        SideEffects.drain(token, record -> RECORDS.add(record.clone()));
    }

    static String string(long id) {
        if (id <= 0) {
            return null;
        }
        String[] strings = SideEffects.interned(generation, (int) id);
        return strings == null || strings.length == 0 ? null : strings[0];
    }

    static int outcome(long[] record) {
        return (int) (record[SideEffects.R_FLAGS] & 0xFF);
    }

    static String describe(List<long[]> records) {
        List<String> described = new ArrayList<>();
        for (long[] record : records) {
            described.add("kind=" + record[SideEffects.R_KIND] + " target=" + string(record[SideEffects.R_TARGET])
                    + " outcome=" + outcome(record) + " request=" + Long.toHexString(record[SideEffects.R_REQUEST])
                    + " count=" + record[SideEffects.R_COUNT] + " ms=" + record[SideEffects.R_NANOS] / 1_000_000L
                    + " frame=" + string((int) record[SideEffects.R_FRAMES]));
        }
        return described.toString();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> blockingStatus() {
        return (Map<String, Object>) AgentBridge.status().get(SideEffects.BLOCKING);
    }

    static void awaitSelfTest() throws Exception {
        Map<String, Object> sensor = Map.of();
        for (int i = 0; i < 400; i++) {
            sensor = sensor(SideEffects.BLOCKING);
            boolean park = Boolean.TRUE.equals(sensor.get("selfTestPassed")) || sensor.get("selfTestError") != null;
            boolean callSites = Boolean.TRUE.equals(sensor.get("callSitesSelfTestPassed"))
                    || sensor.get("callSitesSelfTestError") != null;
            if (park && callSites) {
                break;
            }
            Thread.sleep(25);
        }
        System.out.println("SELF_TEST_blocking=" + sensor.get("selfTestPassed") + " " + sensor.get("selfTestError")
                + " " + sensor.get("hooks"));
        System.out.println("CALL_SITES=" + sensor.get("callSitesSelfTestPassed") + " "
                + sensor.get("callSitesSelfTestError") + " rewritten=" + sensor.get("callSitesRewritten"));
    }

    static Object awaitState(String expected) throws Exception {
        Object state = null;
        for (int i = 0; i < 400; i++) {
            Map<String, Object> sensor = sensor(SideEffects.BLOCKING);
            state = sensor.get("state");
            if (expected.equals(state)
                    && expected.equals(sensor.get("callSitesState"))
                    && Boolean.TRUE.equals(sensor.get("idle"))
                    && Boolean.TRUE.equals(sensor.get("callSitesIdle"))) {
                return state;
            }
            Thread.sleep(25);
        }
        return state + "/" + sensor(SideEffects.BLOCKING).get("callSitesState");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> sensor(String id) {
        Map<String, Object> agent = (Map<String, Object>) AgentBridge.status().get("agent");
        for (Object item : (List<Object>) agent.get("sensors")) {
            Map<String, Object> sensor = (Map<String, Object>) item;
            if (id.equals(sensor.get("id"))) {
                return sensor;
            }
        }
        return Map.of();
    }

    static void check(String name, boolean ok) {
        RESULTS.add((ok ? "  PASS " : "  FAIL ") + name);
    }
}
