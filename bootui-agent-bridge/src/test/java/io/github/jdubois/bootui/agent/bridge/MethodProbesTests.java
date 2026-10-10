package io.github.jdubois.bootui.agent.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Method probes' bounds and lifecycle on the bridge side (PLAN-v2 M5-8), with a fake agent that installs nothing. */
class MethodProbesTests {

    static final String KEY = "com.example.shop.PriceService#quote(I)J";

    private final List<Map<String, Object>> calls = new CopyOnWriteArrayList<>();
    private final ThreadLocal<String> request = new ThreadLocal<>();
    private Supplier<Object> capture;
    private Function<Object, AutoCloseable> reopen;
    private String agentStatus = "ok";

    @BeforeEach
    void install() {
        AgentBridge.reset();
        AgentBridge.install(call -> {
            calls.add(call);
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("status", agentStatus);
            answer.put("reason", "ok".equals(agentStatus) ? null : "agent said no");
            return answer;
        });
        // The methods the inventory or code-paths transformer would have seen.
        CodeInventory.methodId(KEY);
        CodeInventory.methodId("com.example.shop.S#m()V");
        for (int i = 0; i < 10; i++) {
            CodeInventory.methodId("com.example.shop.S#m" + i + "()V");
        }
    }

    @AfterEach
    void reset() {
        AgentBridge.reset();
    }

    @Test
    void aStartedProbeAsksTheAgentAndRecordsNothingUntilActivated() {
        long token = claim("dev");
        Map<String, Object> started = MethodProbes.start(token, Map.of("method", KEY));

        assertThat(started.get("status")).isEqualTo(MethodProbes.STARTED);
        Map<String, Object> probe = probe(started);
        assertThat(probe.get("state")).isEqualTo("starting");
        assertThat(probe.get("className")).isEqualTo("com.example.shop.PriceService");
        assertThat(probe.get("methodName")).isEqualTo("quote");
        assertThat(probe.get("descriptor")).isEqualTo("(I)J");
        assertThat(calls.get(calls.size() - 1))
                .containsEntry("op", "method-probe")
                .containsEntry("slot", 0)
                .containsEntry("className", "com.example.shop.PriceService");
        long id = (Long) probe.get("id");
        assertThat(MethodProbes.enter(0, id)).isZero();
    }

    @Test
    void anActiveProbeRecordsTwentyInvocationsThenEnds() {
        long token = claim("dev");
        long id = id(MethodProbes.start(token, Map.of("method", KEY)));
        assertThat(MethodProbes.activate(0, id)).isTrue();
        request.set("00000000000000ab");
        for (int i = 0; i < 25; i++) {
            long started = MethodProbes.enter(0, id);
            MethodProbes.exit(0, id, started, i == 3 ? new IllegalStateException() : null);
        }
        request.remove();

        Map<String, Object> probe = only();
        assertThat(probe.get("state")).isEqualTo("ending");
        assertThat(probe.get("endReason")).isEqualTo(MethodProbes.END_INVOCATIONS);
        assertThat(probe.get("invocations")).isEqualTo(MethodProbes.MAX_INVOCATIONS);
        List<long[]> records = drain(token);
        assertThat(records).hasSize(MethodProbes.MAX_INVOCATIONS);
        long[] first = records.get(0);
        assertThat(first[AgentRing.SENSOR]).isEqualTo(AgentRing.SENSOR_METHOD_PROBES);
        assertThat(first[AgentRing.PAYLOAD] >>> 8).isEqualTo(id);
        assertThat(first[AgentRing.PAYLOAD] & 3).isEqualTo(MethodProbes.THREAD_PLATFORM);
        assertThat(first[AgentRing.PAYLOAD + 2]).isEqualTo(0xabL);
        long[] thrown = records.get(3);
        assertThat((thrown[AgentRing.PAYLOAD] >>> 2) & 1).isEqualTo(MethodProbes.OUTCOME_THREW);
        int exception = (int) thrown[AgentRing.PAYLOAD + 3];
        assertThat(AgentRing.internedNow().get(exception - 1)).isEqualTo("java.lang.IllegalStateException");
        // No frame of the probed method is on this test's stack: no calling frame is named.
        int caller = (int) (first[AgentRing.PAYLOAD + 3] >>> 32);
        assertThat(caller).isZero();

        MethodProbes.removed(0, id, null);
        assertThat(only()).containsEntry("state", "ended").containsEntry("removal", "removed");
    }

    @Test
    void theWindowEndsAProbeEvenWithoutCalls() throws Exception {
        long token = claim("dev");
        long id = id(MethodProbes.start(token, Map.of("method", KEY, "windowMillis", 20)));
        MethodProbes.activate(0, id);
        Thread.sleep(40);

        assertThat(MethodProbes.enter(0, id)).isZero();
        assertThat(only()).containsEntry("endReason", MethodProbes.END_WINDOW);
        assertThat(only().get("windowMillis")).isEqualTo(20L);
    }

    @Test
    void aRequestMayLowerTheBoundsButNeverRaiseThem() {
        long token = claim("dev");
        Map<String, Object> probe = probe(
                MethodProbes.start(token, Map.of("method", KEY, "maxInvocations", 500, "windowMillis", 3_600_000L)));

        assertThat(probe.get("maxInvocations")).isEqualTo(MethodProbes.MAX_INVOCATIONS);
        assertThat(probe.get("windowMillis")).isEqualTo(MethodProbes.MAX_WINDOW_MILLIS);
    }

    @Test
    void fiveProbesAtOnceThenTheSixthIsRefused() {
        long token = claim("dev");
        for (int i = 0; i < MethodProbes.SLOTS; i++) {
            assertThat(MethodProbes.start(token, Map.of("method", "com.example.shop.S#m" + i))
                            .get("status"))
                    .isEqualTo(MethodProbes.STARTED);
        }
        Map<String, Object> sixth = MethodProbes.start(token, Map.of("method", "com.example.shop.S#m9"));

        assertThat(sixth.get("status")).isEqualTo(MethodProbes.REFUSED);
        assertThat((String) sixth.get("reason")).contains("five probes");
    }

    @Test
    void theSameMethodIsNotProbedTwice() {
        long token = claim("dev");
        MethodProbes.start(token, Map.of("method", KEY));

        Map<String, Object> again = MethodProbes.start(token, Map.of("method", KEY));

        assertThat(again.get("status")).isEqualTo(MethodProbes.REFUSED);
        assertThat((String) again.get("reason")).contains("already probing");
    }

    @Test
    void theSameMethodSpelledWithoutItsDescriptorIsNotProbedTwice() {
        long token = claim("dev");
        MethodProbes.start(token, Map.of("method", KEY));

        Map<String, Object> again = MethodProbes.start(token, Map.of("method", "com.example.shop.PriceService#quote"));

        assertThat(again.get("status")).isEqualTo(MethodProbes.REFUSED);
    }

    @Test
    void aProbeWhoseRunEndedBeforeItsInstallIsNeverActivated() {
        long token = claim("dev");
        long id = id(MethodProbes.start(token, Map.of("method", KEY)));
        AgentBridge.disarm(token);

        assertThat(MethodProbes.activate(0, id)).isFalse();
        assertThat(only()).containsEntry("state", "ending").containsEntry("endReason", MethodProbes.END_RUN);
    }

    @Test
    void aProbeThatJustEndedIsNotReaped() {
        long token = claim("dev");
        MethodProbes.stuckNanos = 1_000_000_000L;
        long id = id(MethodProbes.start(token, Map.of("method", KEY)));
        MethodProbes.activate(0, id);
        MethodProbes.stop(token, id);

        assertThat(MethodProbes.list())
                .singleElement()
                .satisfies(probe -> assertThat(probe).containsEntry("state", "ending"));
        assertThat(MethodProbes.status()).containsEntry("reaped", 0L);
    }

    @Test
    void invalidMethodsAndOtherPackagesAreRefused() {
        long token = claim("dev");

        assertThat(MethodProbes.start(token, Map.of("method", "nohash")).get("status"))
                .isEqualTo(MethodProbes.INVALID);
        assertThat(MethodProbes.start(token, Map.of("method", "com.example.shop.S#<init>()V"))
                        .get("status"))
                .isEqualTo(MethodProbes.INVALID);
        assertThat((String) MethodProbes.start(token, Map.of("method", "org.other.S#m"))
                        .get("reason"))
                .contains("not in the application's packages");
        assertThat(MethodProbes.start(token, Map.of("method", "com.example.shop.S$$Proxy#m"))
                        .get("status"))
                .isEqualTo(MethodProbes.INVALID);
        assertThat(MethodProbes.start(token, Map.of("method", "com/example/shop/S#m"))
                        .get("status"))
                .isEqualTo(MethodProbes.INVALID);
    }

    @Test
    void aMethodTheAgentNeverSawIsRefused() {
        long token = claim("dev");

        Map<String, Object> unknown = MethodProbes.start(token, Map.of("method", "com.example.shop.S#never"));

        assertThat(unknown.get("status")).isEqualTo(MethodProbes.INVALID);
        assertThat((String) unknown.get("reason")).contains("neither Code Inventory nor Code Paths knows");
        assertThat(MethodProbes.start(token, Map.of("method", "com.example.shop.PriceService#quote(J)J"))
                        .get("status"))
                .isEqualTo(MethodProbes.INVALID);
    }

    @Test
    void aStaleTokenStartsNothing() {
        long token = claim("dev");
        claim("dev");

        assertThat(MethodProbes.start(token, Map.of("method", KEY)).get("status"))
                .isEqualTo(AgentBridge.STALE);
    }

    @Test
    void anExplicitStopEndsTheProbeAtOnce() {
        long token = claim("dev");
        long id = id(MethodProbes.start(token, Map.of("method", KEY)));
        MethodProbes.activate(0, id);

        Map<String, Object> stopped = MethodProbes.stop(token, id);

        assertThat(stopped.get("status")).isEqualTo("stopped");
        assertThat(probe(stopped)).containsEntry("endReason", MethodProbes.END_STOPPED);
        assertThat(MethodProbes.enter(0, id)).isZero();
        assertThat(MethodProbes.stop(token, 999).get("status")).isEqualTo("unknown");
    }

    @Test
    void aNewRunADisarmAndAReleaseEndProbes() {
        long token = claim("dev");
        long id = id(MethodProbes.start(token, Map.of("method", KEY)));
        MethodProbes.activate(0, id);

        long next = claim("dev");

        assertThat(only()).containsEntry("endReason", MethodProbes.END_RUN);
        assertThat(MethodProbes.enter(0, id)).isZero();
        MethodProbes.removed(0, id, null);
        long second = id(MethodProbes.start(next, Map.of("method", KEY)));
        MethodProbes.activate(0, second);
        AgentBridge.disarm(next);
        assertThat(MethodProbes.poll(0, second)).isEqualTo(MethodProbes.ENDING);

        MethodProbes.removed(0, second, null);
        long releasedToken = claim("dev");
        long released = id(MethodProbes.start(releasedToken, Map.of("method", KEY)));
        MethodProbes.activate(0, released);
        AgentBridge.release("shop", "dev");
        assertThat(MethodProbes.poll(0, released)).isEqualTo(MethodProbes.ENDING);
        assertThat(MethodProbes.endReason(0, released)).isEqualTo(MethodProbes.END_RUN);
    }

    @Test
    void anOlderClaimResumingAfterCaptureNeverEndsANewerRunsProbe() throws Exception {
        CountDownLatch capturing = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicReference<Map<String, Object>> answer = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Supplier<Object> olderCapture = () -> {
            capturing.countDown();
            try {
                if (!resume.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("the old claim was not resumed");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError(ex);
            }
            return null;
        };
        Function<Object, AutoCloseable> olderReopen = snapshot -> () -> {};
        Thread older = new Thread(
                () -> {
                    try {
                        answer.set(AgentBridge.claim(
                                Map.of(
                                        "application",
                                        "shop",
                                        "mode",
                                        "dev",
                                        "packages",
                                        List.of("com.example.shop"),
                                        "sensors",
                                        List.of("inventory", "code-paths")),
                                olderCapture,
                                olderReopen));
                    } catch (Throwable ex) {
                        failure.set(ex);
                    }
                },
                "old-probe-claim");
        older.start();
        try {
            assertThat(capturing.await(10, TimeUnit.SECONDS)).isTrue();
            long token = claim("dev");
            long generation = AgentBridge.current().generation;
            long id = id(MethodProbes.start(token, Map.of("method", KEY)));
            assertThat(MethodProbes.activate(0, id)).isTrue();
            assertThat(MethodProbes.poll(0, id)).isEqualTo(MethodProbes.ACTIVE);

            resume.countDown();
            older.join(10_000L);

            assertThat(older.isAlive()).isFalse();
            assertThat(failure.get()).isNull();
            assertThat(answer.get()).containsEntry("status", AgentBridge.ARMED);
            assertThat((Long) answer.get().get("generation")).isLessThan(generation);
            assertThat(AgentBridge.current().generation).isEqualTo(generation);
            assertThat(AgentBridge.current().armed).isTrue();
            assertThat(MethodProbes.poll(0, id)).isEqualTo(MethodProbes.ACTIVE);
            assertThat(MethodProbes.endReason(0, id)).isNull();
        } finally {
            resume.countDown();
            older.join(10_000L);
            java.lang.ref.Reference.reachabilityFence(olderCapture);
            java.lang.ref.Reference.reachabilityFence(olderReopen);
        }
    }

    @Test
    void aDelayedDisarmsCapturedGenerationNeverEndsANewerRunsProbe() throws Exception {
        claim("dev");
        long endedGeneration = AgentBridge.current().generation;
        delayedCleanup(() -> MethodProbes.disarmed(endedGeneration));
    }

    @Test
    void aDelayedReleasesCapturedCutoffNeverEndsANewerRunsProbe() throws Exception {
        claim("dev");
        long releaseCutoff = AgentBridge.current().generation + 1L;
        delayedCleanup(() -> MethodProbes.claimed(releaseCutoff));
    }

    private void delayedCleanup(Runnable cleanup) throws Exception {
        CountDownLatch captured = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread older = new Thread(
                () -> {
                    try {
                        captured.countDown();
                        if (!resume.await(10, TimeUnit.SECONDS)) {
                            throw new AssertionError("the older cleanup was not resumed");
                        }
                        cleanup.run();
                    } catch (Throwable ex) {
                        failure.set(ex);
                    }
                },
                "delayed-probe-cleanup");
        older.start();
        try {
            assertThat(captured.await(10, TimeUnit.SECONDS)).isTrue();
            claim("dev");
            long token = claim("dev");
            long generation = AgentBridge.current().generation;
            long id = id(MethodProbes.start(token, Map.of("method", KEY)));
            assertThat(MethodProbes.activate(0, id)).isTrue();

            resume.countDown();
            older.join(10_000L);

            assertThat(older.isAlive()).isFalse();
            assertThat(failure.get()).isNull();
            assertThat(AgentBridge.current().generation).isEqualTo(generation);
            assertThat(MethodProbes.poll(0, id)).isEqualTo(MethodProbes.ACTIVE);
            assertThat(MethodProbes.endReason(0, id)).isNull();
        } finally {
            resume.countDown();
            older.join(10_000L);
        }
    }

    @Test
    void aReusedSlotNeverRecordsForAnEndedProbe() {
        long token = claim("dev");
        long first = id(MethodProbes.start(token, Map.of("method", KEY)));
        MethodProbes.activate(0, first);
        long started = MethodProbes.enter(0, first);
        MethodProbes.stop(token, first);
        MethodProbes.removed(0, first, null);
        long second = id(MethodProbes.start(token, Map.of("method", KEY)));
        MethodProbes.activate(0, second);

        assertThat(MethodProbes.enter(0, first)).isZero();
        // An invocation that started within the first probe's bound still completes as its own.
        MethodProbes.exit(0, first, started, null);
        assertThat(drain(token))
                .singleElement()
                .satisfies(record -> assertThat(record[AgentRing.PAYLOAD] >>> 8).isEqualTo(first));
    }

    @Test
    void aProbeTheAgentNeverAnswersForFreesItsSlot() throws Exception {
        long token = claim("dev");
        MethodProbes.stuckNanos = 10_000_000L;
        long starting = id(MethodProbes.start(token, Map.of("method", KEY)));
        long ending = id(MethodProbes.start(token, Map.of("method", "com.example.shop.S#m")));
        MethodProbes.activate(1, ending);
        MethodProbes.stop(token, ending);
        Thread.sleep(30);

        List<Map<String, Object>> list = MethodProbes.list();

        assertThat(list)
                .anySatisfy(
                        probe -> assertThat(probe).containsEntry("id", starting).containsEntry("state", "failed"));
        assertThat(list).anySatisfy(probe -> {
            assertThat(probe).containsEntry("id", ending).containsEntry("state", "ended");
            assertThat((String) probe.get("removal")).startsWith("unknown");
        });
        assertThat(MethodProbes.status()).containsEntry("inUse", 0);
    }

    @Test
    void anAgentRefusalFailsTheProbeAndFreesItsSlot() {
        long token = claim("dev");
        agentStatus = AgentBridge.FAILED;

        Map<String, Object> started = MethodProbes.start(token, Map.of("method", KEY));

        assertThat(started.get("status")).isEqualTo(AgentBridge.FAILED);
        assertThat(only()).containsEntry("state", "failed").containsEntry("failure", "agent said no");
        assertThat(MethodProbes.status()).containsEntry("inUse", 0);
    }

    @Test
    void aShapesProbeRecordsArgumentAndReturnShapesJoinedByIndex() {
        long token = claim("dev");
        Map<String, Object> started = MethodProbes.start(token, Map.of("method", KEY, "shapes", Boolean.TRUE));
        long id = id(started);
        assertThat(probe(started)).containsEntry("shapes", Boolean.TRUE).containsEntry("shapesDropped", 0);
        assertThat(calls.get(calls.size() - 1)).containsEntry("shapes", Boolean.TRUE);
        MethodProbes.activate(0, id);

        for (int i = 0; i < 2; i++) {
            long start = MethodProbes.enter(0, id);
            assertThat(start & MethodProbes.INDEX_MASK).isEqualTo(i);
            Object[] arguments = new Object[11];
            arguments[0] = "sku-" + i;
            arguments[1] = List.of(1, 2);
            MethodProbes.arguments(0, id, start, arguments);
            MethodProbes.exit(0, id, start, null, i == 0 ? Optional.empty() : null);
        }

        List<long[]> records = drain(token);
        // Per invocation: three argument records (nine of eleven arguments), the return record, then the hit.
        assertThat(records).hasSize(10);
        long[] firstArguments = records.get(0);
        assertThat(firstArguments[AgentRing.TYPE]).isEqualTo(MethodProbes.PROBE_SHAPES);
        assertThat(firstArguments[AgentRing.PAYLOAD] >>> 8).isEqualTo(id);
        assertThat((firstArguments[AgentRing.PAYLOAD] >>> 3) & 31).isZero();
        assertThat(firstArguments[AgentRing.PAYLOAD] & 7).isZero();
        assertThat(ProbeShapes.kind(firstArguments[AgentRing.PAYLOAD + 1])).isEqualTo(ProbeShapes.STRING);
        assertThat(ProbeShapes.summary(firstArguments[AgentRing.PAYLOAD + 1])).isEqualTo(5);
        assertThat(ProbeShapes.kind(firstArguments[AgentRing.PAYLOAD + 2])).isEqualTo(ProbeShapes.COLLECTION);
        assertThat(ProbeShapes.kind(firstArguments[AgentRing.PAYLOAD + 3])).isEqualTo(ProbeShapes.NULL);
        assertThat(records.get(2)[AgentRing.PAYLOAD] & 7).isEqualTo(2);
        long[] returned = records.get(3);
        assertThat(returned[AgentRing.PAYLOAD] & 7).isEqualTo(MethodProbes.RETURN_PART);
        assertThat(ProbeShapes.kind(returned[AgentRing.PAYLOAD + 1])).isEqualTo(ProbeShapes.OPTIONAL);
        long[] hit = records.get(4);
        assertThat(hit[AgentRing.TYPE]).isEqualTo(MethodProbes.PROBE_HIT);
        assertThat((hit[AgentRing.PAYLOAD] >>> 3) & 31).isZero();
        assertThat(hit[AgentRing.PAYLOAD + 1]).isNotNegative();
        long[] secondHit = records.get(9);
        assertThat(secondHit[AgentRing.TYPE]).isEqualTo(MethodProbes.PROBE_HIT);
        assertThat((secondHit[AgentRing.PAYLOAD] >>> 3) & 31).isEqualTo(1);
        assertThat(secondHit[AgentRing.PAYLOAD] >>> 8).isEqualTo(id);
        assertThat(AgentRing.internedNow()).doesNotContain("sku-0", "sku-1");
    }

    @Test
    void aShapesProbeRecordsNoReturnShapeForAThrowOrAVoidMethod() {
        long token = claim("dev");
        long id = id(MethodProbes.start(token, Map.of("method", "com.example.shop.S#m()V", "shapes", Boolean.TRUE)));
        MethodProbes.activate(0, id);

        long start = MethodProbes.enter(0, id);
        MethodProbes.arguments(0, id, start, new Object[0]);
        MethodProbes.exit(0, id, start, null, null);
        long other = MethodProbes.enter(0, id);
        MethodProbes.exit(0, id, other, new IllegalStateException(), null);

        List<long[]> records = drain(token);
        assertThat(records)
                .hasSize(2)
                .allSatisfy(record -> assertThat(record[AgentRing.TYPE]).isEqualTo(MethodProbes.PROBE_HIT));
    }

    @Test
    void aMetadataProbeRecordsNoShapeAndKeepsItsExactStart() {
        long token = claim("dev");
        long id = id(MethodProbes.start(token, Map.of("method", KEY)));
        MethodProbes.activate(0, id);

        long start = MethodProbes.enter(0, id);
        MethodProbes.arguments(0, id, start, new Object[] {"secret"});
        MethodProbes.exit(0, id, start, null, "secret");

        assertThat(only()).containsEntry("shapes", Boolean.FALSE);
        List<long[]> records = drain(token);
        assertThat(records)
                .singleElement()
                .satisfies(record -> assertThat(record[AgentRing.TYPE]).isEqualTo(MethodProbes.PROBE_HIT));
    }

    @Test
    void anUnrecordedInvocationPublishesNoShape() {
        long token = claim("dev");
        long id = id(MethodProbes.start(token, Map.of("method", KEY, "shapes", Boolean.TRUE)));
        // Not activated: enter answers 0, and the advice passes nothing on.
        MethodProbes.arguments(0, id, 0L, new Object[] {"x"});
        MethodProbes.exit(0, id, 0L, null, "x");

        assertThat(drain(token)).isEmpty();
    }

    @Test
    void entryPointsNeverThrow() {
        MethodProbes.arguments(99, 1, 5L, new Object[] {"x"});
        MethodProbes.arguments(0, 1, 5L, null);
        MethodProbes.exit(99, 1, 5L, null, "x");
        assertThat(MethodProbes.enter(-1, 1)).isZero();
        assertThat(MethodProbes.enter(99, 1)).isZero();
        MethodProbes.exit(99, 1, 5L, null);
        MethodProbes.exit(0, 1, 0L, null);
        assertThat(MethodProbes.start(1L, null).get("status")).isEqualTo(AgentBridge.STALE);
        assertThat(MethodProbes.poll(7, 1)).isEqualTo(-1);
        MethodProbes.failed(7, 1, "x");
        MethodProbes.removed(-3, 1, "x");
    }

    // ---- harness -----------------------------------------------------------------------------------------------

    private long claim(String mode) {
        capture = () -> request.get() == null ? null : new Object[] {request.get(), null, null, null, "/quote"};
        reopen = snapshot -> () -> {};
        Map<String, Object> claim = new LinkedHashMap<>();
        claim.put("application", "shop");
        claim.put("mode", mode);
        claim.put("packages", List.of("com.example.shop"));
        claim.put("sensors", List.of("code-paths"));
        return (Long) AgentBridge.claim(claim, capture, reopen).get("token");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> probe(Map<String, Object> answer) {
        return (Map<String, Object>) answer.get("probe");
    }

    private static long id(Map<String, Object> answer) {
        return (Long) probe(answer).get("id");
    }

    private static Map<String, Object> only() {
        List<Map<String, Object>> list = MethodProbes.list();
        assertThat(list).hasSize(1);
        return list.get(0);
    }

    private static List<long[]> drain(long token) {
        List<long[]> records = new ArrayList<>();
        AgentRing.drain(token, record -> records.add(record.clone()));
        return records;
    }
}
