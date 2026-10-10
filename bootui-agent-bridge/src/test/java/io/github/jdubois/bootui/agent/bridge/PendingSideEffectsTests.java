package io.github.jdubois.bootui.agent.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PendingSideEffectsTests {
    private final List<Object> callbacks = new ArrayList<>();

    @BeforeEach
    void install() {
        AgentBridge.reset();
        AgentBridge.install(request -> Map.of("status", "ok"));
    }

    @AfterEach
    void reset() {
        AgentBridge.reset();
    }

    @Test
    void nativeScopesAndHandoffsShareTheSamePrimitiveLedgerAndFlushBeforeRemoval() {
        long token = claim();
        long generation = AgentBridge.current().generation;
        CodePaths.begin();
        Map<String, Object> before = SideEffects.pending(token);
        assertThat(before).containsEntry("qualified", true).containsEntry("unknownReason", null);
        assertThat(owners(before)).hasSize(1);
        CodePaths.Frame frame = CodePaths.frame();
        SideEffects.record(
                frame,
                SideEffects.owner(frame, AgentBridge.current()),
                SideEffects.SENSOR_FILES,
                SideEffects.KIND_FILE_WRITE,
                0,
                SideEffects.OUTCOME_DONE,
                0,
                0L,
                0L,
                0L);
        List<long[]> records = new ArrayList<>();
        assertThat(SideEffects.drain(token, record -> records.add(record.clone())))
                .isZero();

        SideEffects.handoff(new Object[] {"00000000000000cd"}, generation);
        Map<String, Object> nested = SideEffects.pending(token);
        assertThat(owners(nested)).hasSize(2);
        assertThat(SideEffects.drain(token, record -> records.add(record.clone())))
                .isEqualTo(1);
        assertThat(records.get(0)[SideEffects.R_REQUEST]).isEqualTo(0xabL);
        SideEffects.handoffDone();
        assertThat(owners(SideEffects.pending(token))).hasSize(1);
        CodePaths.end();
        assertThat(owners(SideEffects.pending(token))).isEmpty();
        assertThat((Long) SideEffects.pending(token).get("revision")).isGreaterThan((Long) before.get("revision"));
    }

    @Test
    void clearsAndSensorSwitchesNeverForgetAnActiveOwner() {
        long token = claim();
        CodePaths.begin();
        SideEffects.recordingCleared(AgentBridge.current().generation);
        AgentBridge.switchSensor(token, "files", false);
        AgentBridge.switchSensor(token, "files", true);
        assertThat(owners(SideEffects.pending(token))).hasSize(1);
        CodePaths.end();
        assertThat(owners(SideEffects.pending(token))).isEmpty();
    }

    @Test
    void staleClaimsAndScopeCleanupCannotRemoveANewerGenerationOwner() {
        long oldToken = claim();
        CodePaths.begin();
        long oldGeneration = AgentBridge.current().generation;
        long nextToken = claim();
        CodePaths.begin();
        PendingSideEffects.claimed(oldGeneration);
        CodePaths.end();
        Map<String, Object> newer = SideEffects.pending(nextToken);
        assertThat(newer).containsEntry("qualified", true).containsEntry("unknownReason", null);
        assertThat(owners(newer)).isEmpty();
        CodePaths.end();
        assertThat(SideEffects.pending(nextToken)).containsEntry("unknownReason", null);
        assertThat(SideEffects.pending(oldToken).get("qualified")).isEqualTo(false);
        AgentBridge.disarm(nextToken);
        assertThat(SideEffects.pending(nextToken).get("qualified")).isEqualTo(false);
        long reclaimed = claim();
        AgentBridge.release("shop", "dev");
        assertThat(SideEffects.pending(reclaimed).get("qualified")).isEqualTo(false);
    }

    @Test
    void markerOverflowIsStickyUnknownWithoutDroppingActualSideEffectRecords() {
        long token = claim();
        long generation = AgentBridge.current().generation;
        List<Long> markers = new ArrayList<>();
        for (int i = 0; i <= PendingSideEffects.CAPACITY; i++) {
            markers.add(PendingSideEffects.open(generation, i + 1L, 0L, 0));
        }
        assertThat(owners(SideEffects.pending(token))).hasSize(PendingSideEffects.CAPACITY);
        assertThat(SideEffects.pending(token).get("unknownReason").toString()).contains("bound");
        CodePaths.begin();
        CodePaths.Frame frame = CodePaths.frame();
        SideEffects.record(
                frame,
                SideEffects.owner(frame, AgentBridge.current()),
                SideEffects.SENSOR_FILES,
                SideEffects.KIND_FILE_WRITE,
                0,
                SideEffects.OUTCOME_DONE,
                0,
                0L,
                0L,
                0L);
        CodePaths.end();
        List<long[]> records = new ArrayList<>();
        assertThat(SideEffects.drain(token, record -> records.add(record.clone())))
                .isEqualTo(1);
        assertThat(records.get(0)[SideEffects.R_COUNT]).isEqualTo(1L);
        for (long marker : markers) {
            PendingSideEffects.closed(generation, marker);
        }
        assertThat(owners(SideEffects.pending(token))).isEmpty();
        assertThat(SideEffects.pending(token).get("unknownReason").toString()).contains("bound");
        long next = claim();
        assertThat(SideEffects.pending(next)).containsEntry("unknownReason", null);
        assertThat(owners(SideEffects.pending(next))).isEmpty();
    }

    @Test
    void unmatchedScopeEndsDepthOverflowAndErrorCleanupRemainUnknown() {
        long token = claim();
        CodePaths.begin();
        SideEffects.handoffDone();
        assertThat(SideEffects.pending(token).get("unknownReason").toString()).contains("unmatched");
        CodePaths.end();
        token = claim();
        CodePaths.begin();
        PendingSideEffects.frameFailed(CodePaths.frame());
        assertThat(SideEffects.pending(token).get("unknownReason").toString()).contains("error cleanup");
        CodePaths.end();
        token = claim();
        for (int i = 0; i < 40; i++) {
            CodePaths.begin();
        }
        assertThat(SideEffects.pending(token).get("unknownReason").toString()).contains("depth");
        for (int i = 0; i < 40; i++) {
            CodePaths.end();
        }
    }

    @Test
    void unownedScopesAndPublicStatusCarryNoPendingOwnerPayload() {
        long token = claim();
        SideEffects.scopeBegin(null, true);
        assertThat(owners(SideEffects.pending(token))).isEmpty();
        SideEffects.scopeEnd();
        assertThat(SideEffects.pending(token)).containsEntry("unknownReason", null);
        assertThat(SideEffects.status("files")).doesNotContainKeys("owners", "pendingOwners", "pendingRevision");
    }

    @Test
    void aStaleThreadsScopeEndNeverRemovesTheNewRunsActiveOwner() throws Exception {
        claim();
        CountDownLatch opened = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread old = new Thread(
                () -> {
                    try {
                        CodePaths.begin();
                        opened.countDown();
                        if (!finish.await(10, TimeUnit.SECONDS)) {
                            throw new AssertionError("the old scope was not resumed");
                        }
                        CodePaths.end();
                    } catch (Throwable ex) {
                        failure.set(ex);
                        opened.countDown();
                    }
                },
                "old-owned-scope");
        old.start();
        try {
            assertThat(opened.await(10, TimeUnit.SECONDS)).isTrue();
            long token = claim();
            CodePaths.begin();
            finish.countDown();
            old.join(10_000);
            assertThat(old.isAlive()).isFalse();
            assertThat(failure.get()).isNull();
            assertThat(owners(SideEffects.pending(token))).hasSize(1);
            assertThat(SideEffects.pending(token)).containsEntry("unknownReason", null);
            CodePaths.end();
            assertThat(owners(SideEffects.pending(token))).isEmpty();
        } finally {
            finish.countDown();
            old.join(10_000);
            assertThat(old.isAlive()).isFalse();
        }
    }

    @Test
    void aWeakIteratorSeeingDepartedAndNewOwnersStillHasABoundedSnapshot() throws Exception {
        long token = claim();
        long generation = AgentBridge.current().generation;
        List<Long> old = new ArrayList<>();
        for (int i = 0; i < PendingSideEffects.CAPACITY; i++) {
            old.add(PendingSideEffects.open(generation, i + 1L, 0L, 0));
        }
        var stateField = PendingSideEffects.class.getDeclaredField("STATE");
        stateField.setAccessible(true);
        Object state = ((AtomicReference<?>) stateField.get(null)).get();
        var ownersField = state.getClass().getDeclaredField("owners");
        ownersField.setAccessible(true);
        Map<?, ?> live = (Map<?, ?>) ownersField.get(state);
        Iterator<?> weak = live.values().iterator();
        Iterable<Object> changingView = () -> new Iterator<>() {
            int seen;

            @Override
            public boolean hasNext() {
                return weak.hasNext();
            }

            @Override
            public Object next() {
                Object owner = weak.next();
                if (++seen == 2) {
                    for (long marker : old) {
                        PendingSideEffects.closed(generation, marker);
                    }
                    for (int i = 0; i < PendingSideEffects.CAPACITY; i++) {
                        PendingSideEffects.open(generation, 10_000L + i, 0L, 0);
                    }
                }
                return owner;
            }
        };
        var snapshot = state.getClass().getDeclaredMethod("snapshot", Iterable.class);
        snapshot.setAccessible(true);
        Object answer = snapshot.invoke(state, changingView);
        assertThat(answer).isInstanceOf(Map.class);
        Map<?, ?> bounded = (Map<?, ?>) answer;
        assertThat((List<?>) bounded.get("owners")).hasSize(PendingSideEffects.CAPACITY);
        assertThat(bounded.get("qualified")).isEqualTo(false);
        assertThat(bounded.get("unknownReason").toString()).contains("snapshot bound");
        assertThat(SideEffects.pending(token)).containsEntry("qualified", true).containsEntry("unknownReason", null);
    }

    private long claim() {
        Object[] owner = {"00000000000000ab", null, null, null, "/reports"};
        Supplier<Object> capture = () -> owner;
        Function<Object, AutoCloseable> reopen = ignored -> () -> owner.getClass();
        callbacks.add(capture);
        callbacks.add(reopen);
        long token = (Long) AgentBridge.claim(
                        Map.of(
                                "application",
                                "shop",
                                "mode",
                                "dev",
                                "packages",
                                List.of("example"),
                                "sensors",
                                List.of("files", "environment", "executors")),
                        capture,
                        reopen)
                .get("token");
        SideEffects.enable(SideEffects.MASK_FILES | SideEffects.MASK_ENVIRONMENT);
        return token;
    }

    private static List<?> owners(Map<String, Object> snapshot) {
        assertThat(snapshot.get("owners")).isInstanceOf(List.class);
        return (List<?>) snapshot.get("owners");
    }
}
