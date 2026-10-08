package io.github.jdubois.bootui.agent.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The blocking sensor's bridge side (PLAN-v2 §5.16, M5-5c): event loops registered by the adapters, and the
 * substitutes and park hooks recording only what starts on one of them.
 */
class BlockingTests {

    private static final String REQUEST = "00000000000000cd";
    private static final long REQUEST_BITS = 0xcdL;

    private final List<Object> keep = new ArrayList<>();
    private final AtomicReference<Object[]> context = new AtomicReference<>();

    @BeforeEach
    void install() {
        AgentBridge.reset();
        AgentBridge.install(request -> {
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("status", "ok");
            return answer;
        });
    }

    @AfterEach
    void reset() {
        AgentBridge.reset();
    }

    @Test
    void nothingIsLookedUpUntilALoopIsRegistered() {
        enabledClaim();

        assertThat(SideEffects.mask & SideEffects.MASK_BLOCKING).isNotZero();
        assertThat(SideEffects.mask & SideEffects.MASK_LOOPS).isZero();
        assertThat(Blocking.parking()).isZero();
    }

    @Test
    void aSleepOnARegisteredLoopIsRecordedWithItsLoopOwnerAndDuration() throws Exception {
        long token = enabledClaim();
        context.set(owner(REQUEST));

        onLoop("loop-io-7", () -> {
            Blocking.registerEventLoop();
            Blocking.sleep(5L);
            return null;
        });

        List<long[]> records = drain(token);
        assertThat(records).hasSize(1);
        long[] record = records.get(0);
        assertThat(record[SideEffects.R_SENSOR]).isEqualTo(SideEffects.SENSOR_BLOCKING);
        assertThat(record[SideEffects.R_KIND]).isEqualTo(Blocking.KIND_SLEEP);
        assertThat(record[SideEffects.R_REQUEST]).isEqualTo(REQUEST_BITS);
        assertThat(record[SideEffects.R_COUNT]).isEqualTo(1L);
        assertThat(record[SideEffects.R_NANOS]).isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(5));
        assertThat(record[SideEffects.R_MAX_NANOS]).isEqualTo(record[SideEffects.R_NANOS]);
        assertThat(outcome(record)).isEqualTo(Blocking.OUTCOME_RETURNED);
        assertThat(string(record[SideEffects.R_TARGET])).isEqualTo("loop-io-7");
        assertThat(SideEffects.status(SideEffects.BLOCKING))
                .containsEntry("eventLoopRegistrations", 1L)
                .containsEntry("startedOnEventLoops", 1L);
    }

    @Test
    void theSameSleepOffAnEventLoopIsNotRecorded() throws Exception {
        long token = enabledClaim();
        context.set(owner(REQUEST));
        onLoop("loop-io-1", () -> {
            Blocking.registerEventLoop();
            return null;
        });

        Blocking.sleep(2L);
        Blocking.sleep(TimeUnit.MILLISECONDS, 2L);
        Object monitor = new Object();
        synchronized (monitor) {
            Blocking.waitOn(monitor, 2L);
        }
        Blocking.parked(Blocking.parking(), null);

        assertThat(SideEffects.mask & SideEffects.MASK_LOOPS).isNotZero();
        assertThat(drain(token)).isEmpty();
    }

    @Test
    void everySubstituteRecordsItsKindOnALoopAndWaitsKeepTheirMonitorSemantics() throws Exception {
        long token = enabledClaim();
        context.set(owner(REQUEST));
        Object monitor = new Object();

        onLoop("loop-io-2", () -> {
            Blocking.registerEventLoop();
            Blocking.sleep(1L, 0);
            Blocking.sleep(TimeUnit.MILLISECONDS, 1L);
            synchronized (monitor) {
                Blocking.waitOn(monitor, 1L);
                Blocking.waitOn(monitor, 1L, 0);
            }
            if (Runtime.version().feature() >= 19) {
                Blocking.sleep(Duration.ofMillis(1));
            }
            assertThatThrownBy(() -> Blocking.waitOn(new Object(), 1L))
                    .isInstanceOf(IllegalMonitorStateException.class);
            // Zero and negative waits never block for a while, and are never recorded.
            Blocking.sleep(0L);
            Blocking.sleep(TimeUnit.MILLISECONDS, 0L);
            if (Runtime.version().feature() >= 19) {
                Blocking.sleep(Duration.ZERO);
            }
            return null;
        });

        List<long[]> records = drain(token);
        long sleeps = records.stream()
                .filter(record -> record[SideEffects.R_KIND] == Blocking.KIND_SLEEP)
                .mapToLong(record -> record[SideEffects.R_COUNT])
                .sum();
        long waits = records.stream()
                .filter(record -> record[SideEffects.R_KIND] == Blocking.KIND_WAIT
                        && outcome(record) == Blocking.OUTCOME_RETURNED)
                .mapToLong(record -> record[SideEffects.R_COUNT])
                .sum();
        long failedWaits = records.stream()
                .filter(record ->
                        record[SideEffects.R_KIND] == Blocking.KIND_WAIT && outcome(record) == Blocking.OUTCOME_ERROR)
                .count();
        assertThat(sleeps).isEqualTo(Runtime.version().feature() >= 19 ? 3L : 2L);
        assertThat(waits).isEqualTo(2L);
        assertThat(failedWaits).isEqualTo(1L);
    }

    @Test
    void anInterruptedSleepThrowsAsBeforeAndIsRecordedInterrupted() throws Exception {
        long token = enabledClaim();
        context.set(owner(REQUEST));

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        onLoop("loop-io-3", () -> {
            Blocking.registerEventLoop();
            Thread.currentThread().interrupt();
            try {
                Blocking.sleep(1_000L);
            } catch (InterruptedException expected) {
                thrown.set(expected);
            }
            return null;
        });

        assertThat(thrown.get()).isInstanceOf(InterruptedException.class);
        List<long[]> records = drain(token);
        assertThat(records).hasSize(1);
        assertThat(outcome(records.get(0))).isEqualTo(Blocking.OUTCOME_INTERRUPTED);
    }

    @Test
    void aShortParkIsCountedAndALongOneRecorded() throws Exception {
        long token = enabledClaim();
        context.set(owner(REQUEST));

        onLoop("loop-io-4", () -> {
            Blocking.registerEventLoop();
            long quick = Blocking.parking();
            assertThat(quick).isNotZero();
            // The token is the park's start time: moving it a second ahead makes the park measure as instant, so a GC
            // pause or a descheduled thread on a loaded runner cannot stretch it past MIN_PARK_NANOS (#1385).
            Blocking.parked(quick + TimeUnit.SECONDS.toNanos(1L), null);
            long slow = Blocking.parking();
            Thread.sleep(3L);
            Blocking.parked(slow, null);
            return null;
        });

        List<long[]> records = drain(token);
        assertThat(records).hasSize(1);
        assertThat(records.get(0)[SideEffects.R_KIND]).isEqualTo(Blocking.KIND_PARK);
        assertThat(records.get(0)[SideEffects.R_NANOS]).isGreaterThanOrEqualTo(Blocking.MIN_PARK_NANOS);
        assertThat(SideEffects.status(SideEffects.BLOCKING)).containsEntry("shortParks", 1L);
    }

    @Test
    void aParkThatThrowsReleasesTheThreadsHookSoALaterSleepStillRecords() throws Exception {
        long token = enabledClaim();
        context.set(owner(REQUEST));

        onLoop("loop-io-5", () -> {
            Blocking.registerEventLoop();
            long parked = Blocking.parking();
            Thread.sleep(2L);
            Blocking.parked(parked, new IllegalStateException("blocking call (BlockHound)"));
            Blocking.sleep(1L);
            return null;
        });

        List<long[]> records = drain(token);
        assertThat(records)
                .extracting(record -> record[SideEffects.R_KIND])
                .containsExactlyInAnyOrder((long) Blocking.KIND_PARK, (long) Blocking.KIND_SLEEP);
    }

    @Test
    void aShortParkThatThrowsIsRecordedAsAnError() throws Exception {
        long token = enabledClaim();
        context.set(owner(REQUEST));

        onLoop("loop-io-9", () -> {
            Blocking.registerEventLoop();
            Blocking.parked(Blocking.parking(), new IllegalStateException("refused (BlockHound)"));
            return null;
        });

        List<long[]> records = drain(token);
        assertThat(records).hasSize(1);
        assertThat(records.get(0)[SideEffects.R_KIND]).isEqualTo(Blocking.KIND_PARK);
        assertThat(outcome(records.get(0))).isEqualTo(Blocking.OUTCOME_ERROR);
    }

    @Test
    void aBlockingNetworkOperationOnALoopIsABlockingRecordAndNettysNonBlockingConnectNever() throws Exception {
        long token = claim(List.of(SideEffects.NETWORK, SideEffects.BLOCKING));
        SideEffects.enable(SideEffects.MASK_NETWORK | SideEffects.MASK_BLOCKING);
        context.set(owner(REQUEST));
        java.net.InetSocketAddress remote = java.net.InetSocketAddress.createUnresolved("db.internal", 5432);

        onLoop("loop-io-10", () -> {
            Blocking.registerEventLoop();
            long connect = SideEffects.networkStarting(SideEffects.HOOK_SOCKET_CONNECT);
            SideEffects.connected(connect, SideEffects.HOOK_SOCKET_CONNECT, null, remote, true, null);
            long lookup = SideEffects.networkStarting(SideEffects.HOOK_LOOKUP);
            SideEffects.lookedUp(lookup, "db.internal", new java.net.InetAddress[0], null);
            try (java.nio.channels.SocketChannel channel = java.nio.channels.SocketChannel.open()) {
                channel.configureBlocking(false);
                long nonBlocking = SideEffects.networkStarting(SideEffects.HOOK_CHANNEL_CONNECT);
                SideEffects.connected(nonBlocking, SideEffects.HOOK_CHANNEL_CONNECT, channel, remote, false, null);
            }
            return null;
        });
        // The same connect off event loops is the network sensor's only.
        onLoop("worker-1", () -> {
            long connect = SideEffects.networkStarting(SideEffects.HOOK_SOCKET_CONNECT);
            SideEffects.connected(connect, SideEffects.HOOK_SOCKET_CONNECT, null, remote, true, null);
            return null;
        });

        List<long[]> blocking = drain(token).stream()
                .filter(record -> record[SideEffects.R_SENSOR] == SideEffects.SENSOR_BLOCKING)
                .toList();
        assertThat(blocking.stream()
                        .mapToLong(record -> record[SideEffects.R_COUNT])
                        .sum())
                .as("a connect and a lookup on the loop, never the non-blocking connect")
                .isEqualTo(2L);
        assertThat(blocking).allSatisfy(record -> {
            assertThat(record[SideEffects.R_KIND]).isEqualTo(Blocking.KIND_NETWORK);
            assertThat(string(record[SideEffects.R_TARGET])).isEqualTo("loop-io-10");
            assertThat(record[SideEffects.R_REQUEST]).isEqualTo(REQUEST_BITS);
        });
    }

    @Test
    void aConnectThatTimedOutOnALoopIsAFailureNotAnInterruption() throws Exception {
        long token = claim(List.of(SideEffects.NETWORK, SideEffects.BLOCKING));
        SideEffects.enable(SideEffects.MASK_NETWORK | SideEffects.MASK_BLOCKING);
        context.set(owner(REQUEST));
        java.net.InetSocketAddress remote = java.net.InetSocketAddress.createUnresolved("slow.internal", 443);

        onLoop("loop-io-12", () -> {
            Blocking.registerEventLoop();
            long connect = SideEffects.networkStarting(SideEffects.HOOK_SOCKET_CONNECT);
            SideEffects.connected(
                    connect,
                    SideEffects.HOOK_SOCKET_CONNECT,
                    null,
                    remote,
                    false,
                    new java.net.SocketTimeoutException("connect timed out"));
            return null;
        });

        assertThat(drain(token))
                .filteredOn(record -> record[SideEffects.R_SENSOR] == SideEffects.SENSOR_BLOCKING)
                .singleElement()
                .satisfies(record -> assertThat(outcome(record)).isEqualTo(Blocking.OUTCOME_ERROR));
    }

    @Test
    void aFileOpenedOnALoopIsABlockingRecordWhenTheFilesSensorRecordsIt() throws Exception {
        long token = claim(List.of(SideEffects.FILES, SideEffects.BLOCKING));
        SideEffects.enable(SideEffects.MASK_FILES | SideEffects.MASK_BLOCKING);
        SideEffects.warm();
        context.set(owner(REQUEST));
        java.nio.file.Path report = java.nio.file.Files.createTempFile("bootui-blocking", ".txt");
        try {
            onLoop("loop-io-11", () -> {
                Blocking.registerEventLoop();
                long opened = SideEffects.fileOpening(SideEffects.HOOK_FILE_INPUT_STREAM);
                SideEffects.fileOpened(
                        opened,
                        SideEffects.HOOK_FILE_INPUT_STREAM,
                        SideEffects.KIND_FILE_READ,
                        report.toString(),
                        null);
                return null;
            });
        } finally {
            java.nio.file.Files.deleteIfExists(report);
        }

        List<long[]> blocking = drain(token).stream()
                .filter(record -> record[SideEffects.R_SENSOR] == SideEffects.SENSOR_BLOCKING)
                .toList();
        assertThat(blocking).singleElement().satisfies(record -> {
            assertThat(record[SideEffects.R_KIND]).isEqualTo(Blocking.KIND_FILE);
            assertThat(string(record[SideEffects.R_TARGET])).isEqualTo("loop-io-11");
        });
    }

    @Test
    void registrationNeedsAClaimAskingForTheSensor() throws Exception {
        claim(List.of(SideEffects.PROCESSES));
        SideEffects.enable(SideEffects.MASK_PROCESSES | SideEffects.MASK_BLOCKING);

        onLoop("loop-io-6", () -> {
            Blocking.registerEventLoop();
            return null;
        });

        assertThat(SideEffects.status(SideEffects.BLOCKING)).containsEntry("eventLoopRegistrations", 0L);
        assertThat(SideEffects.mask & SideEffects.MASK_LOOPS).isZero();
    }

    @Test
    void aLoopOfAnEarlierRunMatchesOnlyOnceRegisteredAgain() throws Exception {
        enabledClaim();
        context.set(owner(REQUEST));
        Thread[] loop = new Thread[1];
        java.util.concurrent.SynchronousQueue<Callable<Object>> work = new java.util.concurrent.SynchronousQueue<>();
        java.util.concurrent.SynchronousQueue<Object> done = new java.util.concurrent.SynchronousQueue<>();
        loop[0] = new Thread(
                () -> {
                    try {
                        while (true) {
                            Callable<Object> task = work.take();
                            Object result;
                            try {
                                result = task.call();
                            } catch (Throwable ex) {
                                result = ex;
                            }
                            done.put(result == null ? "ok" : result);
                        }
                    } catch (InterruptedException ex) {
                        // Ends the loop.
                    }
                },
                "loop-io-8");
        loop[0].setDaemon(true);
        loop[0].start();
        try {
            run(work, done, () -> {
                Blocking.registerEventLoop();
                return null;
            });
            long second = enabledClaim();
            run(work, done, () -> {
                Blocking.sleep(2L);
                return null;
            });
            assertThat(drain(second)).as("registered by the earlier run only").isEmpty();

            run(work, done, () -> {
                Blocking.registerEventLoop();
                Blocking.sleep(2L);
                return null;
            });
            List<long[]> records = drain(second);
            assertThat(records).hasSize(1);
            assertThat(string(records.get(0)[SideEffects.R_TARGET])).isEqualTo("loop-io-8");
        } finally {
            loop[0].interrupt();
        }
    }

    @Test
    void theSelfTestThreadsHooksAreCountedNeverRecorded() throws Exception {
        long token = enabledClaim();
        SideEffects.beginSelfTest();
        try {
            assertThat(SideEffects.gate).isEqualTo(-1);
            Blocking.parked(Blocking.parking(), null);
        } finally {
            Map<String, Object> hits = SideEffects.endSelfTest();
            assertThat(hits).containsEntry("LockSupport.park", 1L);
        }
        Blocking.beginCallSiteSelfTest();
        long[] callSites;
        try {
            Blocking.sleep(1L);
            Object monitor = new Object();
            synchronized (monitor) {
                Blocking.waitOn(monitor, 1L);
            }
        } finally {
            callSites = Blocking.endCallSiteSelfTest();
        }
        assertThat(callSites).containsExactly(1L, 1L);
        assertThat(SideEffects.gate & SideEffects.MASK_LOOPS).isZero();
        assertThat(drain(token)).isEmpty();
    }

    @Test
    void deadLoopsAreForgottenAndTheirSlotsReused() throws Exception {
        enabledClaim();
        for (int i = 0; i < 40; i++) {
            onLoop("loop-io-dead-" + i, () -> {
                Blocking.registerEventLoop();
                return null;
            });
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (Blocking.loops(SideEffects.generation) > 0 && System.nanoTime() < deadline) {
            System.gc();
            Thread.sleep(20L);
        }
        assertThat(Blocking.loops(SideEffects.generation)).isZero();
        assertThat(SideEffects.status(SideEffects.BLOCKING)).containsEntry("eventLoopRegistrationsRefused", 0L);
    }

    @Test
    void aLoopRegisteredPastTheThreadNamesRoomIsNamedByItsFamily() throws Exception {
        enabledClaim();
        for (int i = 0; i < SideEffects.ROOM_GUARANTEED[SideEffects.ROOM_THREADS]; i++) {
            SideEffects.threadName("pool-" + i + "-thread-1");
        }
        assertThat(SideEffects.threadName("reactor-http-nio-7"))
                .as("the room is full")
                .isZero();

        Thread loop = new Thread(Blocking::registerEventLoop, "reactor-http-nio-7");
        loop.start();
        loop.join();
        Blocking.Loop registered = Blocking.find(loop.getId());
        assertThat(registered).isNotNull();
        assertThat(registered.name).isEqualTo(SideEffects.intern(SideEffects.threadFamily("reactor-http-nio-7")));
        assertThat(registered.name).isNotZero();
    }

    @Test
    void terminatedLoopsStillReferencedFreeTheirSlots() throws Exception {
        enabledClaim();
        java.util.List<Thread> held = new java.util.ArrayList<>();
        // More terminated loops than the table has slots, each still strongly referenced: every one must find a slot.
        for (int i = 0; i < 1_100; i++) {
            Thread loop = new Thread(Blocking::registerEventLoop, "loop-io-ended-" + i);
            loop.start();
            loop.join();
            held.add(loop);
        }
        assertThat(SideEffects.status(SideEffects.BLOCKING)).containsEntry("eventLoopRegistrationsRefused", 0L);
        assertThat(SideEffects.status(SideEffects.BLOCKING)).containsEntry("eventLoopRegistrations", 1_100L);
        assertThat(held).hasSize(1_100);
    }

    @Test
    void indexesSpreadSequentialThreadIds() {
        java.util.Set<Integer> slots = new java.util.HashSet<>();
        for (long id = 1; id <= 64; id++) {
            int index = Blocking.index(id);
            assertThat(index).isBetween(0, Blocking.SLOTS - 1);
            slots.add(index);
        }
        assertThat(slots).hasSizeGreaterThan(60);
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private static void run(
            java.util.concurrent.SynchronousQueue<Callable<Object>> work,
            java.util.concurrent.SynchronousQueue<Object> done,
            Callable<Object> task)
            throws Exception {
        work.put(task);
        Object result = done.poll(10, TimeUnit.SECONDS);
        if (result instanceof Throwable failure) {
            throw new AssertionError(failure);
        }
    }

    /** Runs {@code task} on a new thread named {@code name}, standing for an event loop, and waits for it. */
    private void onLoop(String name, Callable<Object> task) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(
                () -> {
                    try {
                        task.call();
                        SideEffects.flushThread();
                    } catch (Throwable ex) {
                        failure.set(ex);
                    }
                },
                name);
        thread.start();
        thread.join(10_000L);
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }

    private long enabledClaim() {
        long token = claim(List.of(SideEffects.BLOCKING));
        SideEffects.enable(SideEffects.MASK_BLOCKING);
        return token;
    }

    private long claim(List<String> sensors) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("application", "shop");
        request.put("mode", "dev");
        request.put("packages", List.of("com.example"));
        request.put("sensors", sensors);
        Supplier<Object> capture = context::get;
        Function<Object, AutoCloseable> reopen = snapshot -> null;
        keep.add(capture);
        keep.add(reopen);
        Map<String, Object> result = AgentBridge.claim(request, capture, reopen);
        assertThat(result.get("status")).isEqualTo(AgentBridge.ARMED);
        return (Long) result.get("token");
    }

    private static Object[] owner(String request) {
        return new Object[] {request, null, null, null, null, null, null, 1L, 1L};
    }

    private static long generation() {
        return (Long) ((Map<?, ?>) AgentBridge.status().get("claim")).get("generation");
    }

    private static int outcome(long[] record) {
        return (int) (record[SideEffects.R_FLAGS] & 0xFF);
    }

    private static List<long[]> drain(long token) {
        List<long[]> records = new ArrayList<>();
        SideEffects.drain(token, record -> records.add(record.clone()));
        return records;
    }

    private static String string(long id) {
        if (id <= 0) {
            return null;
        }
        String[] strings = SideEffects.interned(generation(), (int) id);
        return strings == null || strings.length == 0
                ? null
                : Arrays.asList(strings).get(0);
    }
}
