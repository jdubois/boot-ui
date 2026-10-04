package io.github.jdubois.bootui.agent.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The inventory sensor's bridge side (PLAN-v2 M5-3), driven directly as the inlined advice and the class-load recorder
 * would: flags per run, first-hit and class-load records, the re-entrancy guard, and BootUI's own work.
 */
class CodeInventoryTests {

    private static final String REQUEST = "00000000000000ab";

    private final List<Object> keep = new ArrayList<>();
    private final AtomicReference<Object[]> context = new AtomicReference<>();
    private final AtomicInteger captures = new AtomicInteger();
    private Runnable insideCapture = () -> {};

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
    void methodIdsAreStableAndKeysReadable() {
        int greet = CodeInventory.methodId("com.example.Shop#greet()V");
        int buy = CodeInventory.methodId("com.example.Shop#buy(I)V");

        assertThat(CodeInventory.methodId("com.example.Shop#greet()V")).isEqualTo(greet);
        assertThat(CodeInventory.idOf("com.example.Shop#buy(I)V")).isEqualTo(buy);
        assertThat(CodeInventory.idOf("com.example.Shop#unknown()V")).isEqualTo(-1);
        assertThat(CodeInventory.methodId(null)).isEqualTo(-1);
        assertThat(CodeInventory.methodKeys(0, 10))
                .containsExactly("com.example.Shop#greet()V", "com.example.Shop#buy(I)V");
        assertThat(CodeInventory.methodKeys(1, 10)).containsExactly("com.example.Shop#buy(I)V");
        assertThat(CodeInventory.methodKeys(5, 10)).isEmpty();
    }

    @Test
    void beforeAnyClaimAdviceRecordsNothing() {
        int id = CodeInventory.methodId("com.example.Shop#greet()V");

        assertThat(CodeInventory.HITS[id]).isEqualTo(CodeInventory.epoch);
        CodeInventory.hit(id);
        assertThat(CodeInventory.snapshot(CodeInventory.currentGeneration())).isNull();
        assertThat(CodeInventory.status()).containsEntry("slowPathCalls", 0L);
    }

    @Test
    void aSnapshotBelongsToOneClaimGeneration() {
        int id = CodeInventory.methodId("com.example.Shop#greet()V");
        claim();
        long generation = CodeInventory.currentGeneration();
        advice(id);
        CodeInventory.tracked(new int[] {id});

        Map<String, Object> snapshot = CodeInventory.snapshot(generation);
        assertThat(snapshot)
                .containsEntry("generation", generation)
                .containsEntry("epoch", CodeInventory.currentEpoch())
                .containsEntry("methods", 1)
                .containsEntry("disabled", false);
        assertThat(bit((long[]) snapshot.get("executed"), id)).isTrue();
        assertThat((byte[]) snapshot.get("tracking")).containsExactly(CodeInventory.TRACKED);
        assertThat(CodeInventory.snapshot(generation + 1))
                .as("another generation")
                .isNull();

        claim();
        assertThat(CodeInventory.snapshot(generation))
                .as("a reader of the previous run gets nothing once a new run started")
                .isNull();
        assertThat(bit(
                        (long[]) CodeInventory.snapshot(CodeInventory.currentGeneration())
                                .get("executed"),
                        id))
                .isFalse();
    }

    @Test
    void aClassInstrumentedAfterItLoadedIsLateForThatRunOnly() {
        int early = CodeInventory.methodId("com.example.Early#run()V");
        int loadedLater = CodeInventory.methodId("com.example.Later#run()V");
        claim();
        CodeInventory.tracked(new int[] {early}, true);
        CodeInventory.tracked(new int[] {loadedLater}, false);

        Map<String, Object> snapshot = CodeInventory.snapshot(CodeInventory.currentGeneration());
        assertThat(bit((long[]) snapshot.get("late"), early)).isTrue();
        assertThat(bit((long[]) snapshot.get("late"), loadedLater)).isFalse();
        assertThat(CodeInventory.status()).containsEntry("methodsLate", 1L);

        claim();
        assertThat(bit(
                        (long[]) CodeInventory.snapshot(CodeInventory.currentGeneration())
                                .get("late"),
                        early))
                .as("instrumented for the whole of the next run")
                .isFalse();
        CodeInventory.tracked(new int[] {early}, true);
        CodeInventory.untrackAll();
        assertThat(bit(
                        (long[]) CodeInventory.snapshot(CodeInventory.currentGeneration())
                                .get("late"),
                        early))
                .as("a release forgets it")
                .isFalse();
    }

    @Test
    void aMethodIsExecutedOnceFirstAndLaterCallsTakeTheFastPath() {
        int id = CodeInventory.methodId("com.example.Shop#greet()V");
        long token = claim();
        context.set(new Object[] {REQUEST, "exec", null, null, "/greet"});

        assertThat(advice(id)).as("first call takes the slow path").isTrue();
        assertThat(advice(id)).as("later calls do not").isFalse();
        assertThat(advice(id)).isFalse();

        assertThat(executed(id)).isTrue();
        assertThat(captures.get()).isOne();
        List<long[]> records = drain(token);
        assertThat(records).singleElement().satisfies(record -> {
            assertThat(record[AgentRing.TYPE]).isEqualTo(CodeInventory.FIRST_HIT);
            assertThat(record[AgentRing.PAYLOAD]).isEqualTo(id);
            assertThat(record[AgentRing.PAYLOAD + 1]).isEqualTo(0xabL);
            assertThat(AgentRing.interned(record[AgentRing.GENERATION], (int) record[AgentRing.PAYLOAD + 2]))
                    .startsWith("/greet");
        });
    }

    @Test
    void aFirstCallWithNothingToCaptureIsMarkedByItsFlagAlone() {
        int id = CodeInventory.methodId("com.example.Shop#start()V");
        long token = claim();
        context.set(null);

        advice(id);

        assertThat(executed(id)).isTrue();
        assertThat(drain(token)).isEmpty();
        assertThat(CodeInventory.status()).containsEntry("firstHits", 1L).containsEntry("firstHitRecords", 0L);
    }

    @Test
    void aNewClaimGenerationStartsANewRun() {
        int id = CodeInventory.methodId("com.example.Shop#greet()V");
        claim();
        advice(id);
        assertThat(executed(id)).isTrue();

        claim();

        assertThat(executed(id)).isFalse();
        assertThat(advice(id)).isTrue();
        assertThat(executed(id)).isTrue();
    }

    @Test
    void aRetainedDefinitionCannotExecuteTheNewRunsLogicalMethod() {
        int id = CodeInventory.methodId("com.example.Shop#greet()V");
        int oldDefinition = CodeInventory.definitionToken();
        claim();
        CodeInventory.activateDefinition(oldDefinition, CodeInventory.currentGeneration());
        long token = claim();
        context.set(new Object[] {REQUEST, null, null, null, "/old"});

        CodeInventory.hit(id, oldDefinition);
        CodeInventory.tracked("com.example.Shop", new int[] {id}, false, oldDefinition);

        assertThat(executed(id)).isFalse();
        assertThat(bit(
                        (long[]) CodeInventory.snapshot(CodeInventory.currentGeneration())
                                .get("trackedThisRun"),
                        id))
                .isFalse();
        assertThat(captures.get()).isZero();
        assertThat(drain(token)).isEmpty();

        int newDefinition = CodeInventory.definitionToken();
        CodeInventory.activateDefinition(newDefinition, CodeInventory.currentGeneration());
        CodeInventory.hit(id, oldDefinition);
        assertThat(executed(id)).isFalse();
        CodeInventory.hit(id, newDefinition);
        assertThat(executed(id)).isTrue();
        assertThat(drain(token)).hasSize(1);
    }

    @Test
    void staleFlagsAndEligibilityNeverCrossAClaimEvenWhenTheByteEpochWraps() {
        int id = CodeInventory.methodId("com.example.Shop#greet()V");
        int definition = CodeInventory.definitionToken();
        claim();
        long generation = CodeInventory.currentGeneration();
        CodeInventory.activateDefinition(definition, generation);
        byte[] oldHits = CodeInventory.HITS;
        for (int run = 0; run < 255; run++) {
            claim();
        }
        assertThat(CodeInventory.currentEpoch()).isOne();
        oldHits[id] = CodeInventory.epoch;
        CodeInventory.activateDefinition(definition, generation);
        CodeInventory.hit(id, definition);
        assertThat(executed(id)).isFalse();
        assertThat(CodeInventory.HITS[id]).isZero();

        CodeInventory.activateDefinition(definition, CodeInventory.currentGeneration());
        CodeInventory.hit(id, definition);
        assertThat(executed(id)).isTrue();
    }

    @Test
    void definitionTokensAreBoundedAndOverflowFailsClosed() {
        for (int token = 1; token < CodeInventory.MAX_DEFINITIONS; token++) {
            assertThat(CodeInventory.definitionToken()).isEqualTo(token);
        }
        assertThat(CodeInventory.definitionToken()).isEqualTo(-1);
        claim();
        CodeInventory.activateDefinition(-1, CodeInventory.currentGeneration());
        assertThat(CodeInventory.eligibleDefinition(-1)).isFalse();
        assertThat(CodeInventory.status()).containsEntry("definitionOverflow", 1L);
    }

    @Test
    void recycledTokensLoseTheirOldEligibilityAndCanBeReturnedOnlyOnce() {
        claim();
        int token = CodeInventory.definitionToken();
        CodeInventory.activateDefinition(token, CodeInventory.currentGeneration());
        assertThat(CodeInventory.eligibleDefinition(token)).isTrue();
        CodeInventory.releaseDefinitionToken(token);
        CodeInventory.releaseDefinitionToken(token);

        assertThat(CodeInventory.eligibleDefinition(token)).isFalse();
        assertThat(CodeInventory.definitionToken()).isEqualTo(token);
        assertThat(CodeInventory.definitionToken()).isEqualTo(token + 1);
        assertThat(CodeInventory.eligibleDefinition(token)).isFalse();
        CodeInventory.activateDefinition(token, CodeInventory.currentGeneration());
        assertThat(CodeInventory.eligibleDefinition(token)).isTrue();
    }

    @Test
    void anOverflowedDefinitionsTrackingStaysUnknownAcrossClaimsUntilItIsReallyInstrumentedAgain() {
        int id = CodeInventory.methodId("com.example.Shop#greet()V");
        claim();
        CodeInventory.tracked("com.example.Shop", new int[] {id}, false, -1);
        assertThat(tracking()).containsExactly(CodeInventory.DEFINITION_UNTRACKED);
        CodeInventory.tracked(new int[] {id});
        assertThat(tracking())
                .as("a tracked copy does not prove another current copy was instrumented")
                .containsExactly(CodeInventory.DEFINITION_UNTRACKED);
        claim();
        assertThat(tracking()).containsExactly(CodeInventory.DEFINITION_UNTRACKED);
        assertThat(executed(id)).isFalse();
        CodeInventory.tracked(new int[] {id});
        assertThat(tracking()).containsExactly(CodeInventory.TRACKED);
    }

    @Test
    void sameGenerationDefinitionUncertaintyWinsInBothCallbackOrdersAndSurvivesEpochWrap() {
        int id = CodeInventory.methodId("com.example.Shop#greet()V");
        claim();
        int valid = CodeInventory.definitionToken();
        CodeInventory.activateDefinition(valid, CodeInventory.currentGeneration());
        CodeInventory.tracked("com.example.Shop", new int[] {id}, false, valid);
        CodeInventory.tracked("com.example.Shop", new int[] {id}, false, -1);
        assertThat(tracking()).containsExactly(CodeInventory.DEFINITION_UNTRACKED);
        CodeInventory.tracked("com.example.Shop", new int[] {id}, false, valid);
        assertThat(tracking()).containsExactly(CodeInventory.DEFINITION_UNTRACKED);

        for (int i = 0; i < 255; i++) {
            claim();
        }
        assertThat(tracking()).containsExactly(CodeInventory.DEFINITION_UNTRACKED);
        CodeInventory.activateDefinition(valid, CodeInventory.currentGeneration());
        CodeInventory.tracked("com.example.Shop", new int[] {id}, false, valid);
        assertThat(tracking()).containsExactly(CodeInventory.TRACKED);
    }

    @Test
    void aStaleTransformationCannotClearTheCurrentDefinitionsFailure() {
        int id = CodeInventory.methodId("com.example.Shop#greet()V");
        claim();
        int stale = CodeInventory.definitionToken();
        claim();
        CodeInventory.transformFailed("com.example.Shop", new int[] {id});
        CodeInventory.tracked("com.example.Shop", new int[] {id}, false, stale);
        assertThat(tracking()).containsExactly(CodeInventory.TRANSFORM_FAILED);
        assertThat((String[]) CodeInventory.snapshot(CodeInventory.currentGeneration())
                        .get("failedClasses"))
                .containsExactly("com.example.Shop");
    }

    @Test
    void theEpochWrapsAfter255RunsAndClearsEveryFlag() {
        int id = CodeInventory.methodId("com.example.Shop#greet()V");
        for (int run = 1; run <= 255; run++) {
            claim();
            advice(id);
        }
        assertThat(CodeInventory.currentEpoch()).isEqualTo(255);

        claim();

        assertThat(CodeInventory.currentEpoch()).isOne();
        assertThat(CodeInventory.HITS[id]).isZero();
        assertThat(advice(id)).isTrue();
    }

    @Test
    void aDisarmedOrReleasedClaimRecordsNothingYetItsMethodsTakeTheFastPathAfterOneCall() {
        int id = CodeInventory.methodId("com.example.Shop#greet()V");
        long token = claim();
        context.set(new Object[] {REQUEST, null, null, null, "/greet"});
        AgentBridge.disarm(token);

        assertThat(advice(id)).isTrue();
        assertThat(advice(id))
                .as("the flag is set even though nothing is recorded")
                .isFalse();
        assertThat(captures.get()).isZero();
        assertThat(drain(token)).isEmpty();
        assertThat(CodeInventory.status()).containsEntry("firstHits", 0L).containsEntry("slowPathCalls", 1L);

        int other = CodeInventory.methodId("com.example.Shop#other()V");
        claim();
        AgentBridge.release("shop", "dev");
        assertThat(advice(other)).isTrue();
        assertThat(advice(other)).isFalse();
        assertThat(captures.get()).isZero();
        assertThat(CodeInventory.status()).containsEntry("firstHits", 0L);
    }

    @Test
    void anotherApplicationsClaimWithoutTheSensorStopsRecording() {
        int id = CodeInventory.methodId("com.example.Shop#greet()V");
        long token = claim();
        context.set(new Object[] {REQUEST, null, null, null, "/greet"});
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("application", "shop");
        request.put("mode", "dev");
        request.put("sensors", List.of("executors"));
        Supplier<Object> capture = () -> null;
        Function<Object, AutoCloseable> reopen = snapshot -> null;
        keep.add(capture);
        keep.add(reopen);
        Map<String, Object> other = AgentBridge.claim(request, capture, reopen);

        assertThat(advice(id)).isTrue();
        assertThat(advice(id)).as("one slow call, then the fast path").isFalse();

        assertThat(captures.get()).isZero();
        assertThat(drain(token)).isEmpty();
        assertThat(CodeInventory.snapshot((Long) other.get("generation")))
                .as("the other claim has no inventory run")
                .isNull();
    }

    @Test
    void captureIsNeverReEnteredWhenItRunsAnInstrumentedMethodOrLoadsAClass() {
        int outer = CodeInventory.methodId("com.example.Shop#outer()V");
        int inner = CodeInventory.methodId("com.example.Shop#inner()V");
        long token = claim();
        context.set(new Object[] {REQUEST, null, null, null, "/outer"});
        insideCapture = () -> {
            advice(inner);
            CodeInventory.classLoaded("file:/libs/lazy.jar", "com/lazy/Lazy");
        };

        advice(outer);

        assertThat(captures.get()).as("one capture, for the outer method only").isOne();
        assertThat(executed(inner)).as("the inner method still counts").isTrue();
        List<long[]> records = drain(token);
        assertThat(records)
                .filteredOn(record -> record[AgentRing.TYPE] == CodeInventory.FIRST_HIT)
                .extracting(record -> record[AgentRing.PAYLOAD])
                .containsExactly((long) outer);
        assertThat(records)
                .filteredOn(record -> record[AgentRing.TYPE] == CodeInventory.CLASS_LOAD)
                .singleElement()
                .satisfies(record -> assertThat(record[AgentRing.PAYLOAD + 1])
                        .as("recorded without capturing")
                        .isZero());
        assertThat(AgentBridge.status().get("counters").toString()).contains("errors=0");
    }

    @Test
    void classLoadsAreCountedPerCodeSourceAndTheFirstOfARunIsRecorded() {
        long token = claim();
        context.set(new Object[] {REQUEST, null, null, null, "/orders"});
        CodeInventory.loadedBeforeClaim("file:/libs/early.jar", "com/early/A");

        CodeInventory.classLoaded("file:/libs/shop.jar", "com/shop/A");
        CodeInventory.classLoaded("file:/libs/shop.jar", "com/shop/B");

        Map<String, Map<String, Object>> sources = sources();
        assertThat(sources.get("file:/libs/shop.jar"))
                .containsEntry("loaded", 2L)
                .containsEntry("total", 2L)
                .containsEntry("beforeClaim", 0L);
        assertThat(sources.get("file:/libs/early.jar"))
                .containsEntry("beforeClaim", 1L)
                .containsEntry("loaded", 0L);
        List<long[]> records = drain(token);
        assertThat(records).singleElement().satisfies(record -> {
            assertThat(record[AgentRing.TYPE]).isEqualTo(CodeInventory.CLASS_LOAD);
            assertThat(record[AgentRing.PAYLOAD])
                    .isEqualTo(((Integer) sources.get("file:/libs/shop.jar").get("id")).longValue());
            assertThat(record[AgentRing.PAYLOAD + 1]).isEqualTo(0xabL);
        });

        claim();
        CodeInventory.classLoaded("file:/libs/shop.jar", "com/shop/C");
        assertThat(sources().get("file:/libs/shop.jar"))
                .containsEntry("loaded", 1L)
                .containsEntry("total", 3L);
    }

    @Test
    void bootUisOwnWorkIsNotCounted() {
        claim();
        assertThat(AgentBridge.bootUiWork(true)).isFalse();
        try {
            CodeInventory.classLoaded("file:/libs/scanned.jar", "com/scanned/A");
        } finally {
            assertThat(AgentBridge.bootUiWork(false)).isTrue();
        }

        assertThat(sources()).doesNotContainKey("file:/libs/scanned.jar");
        assertThat(CodeInventory.status()).containsEntry("bootUiLoadsSkipped", 1L);
    }

    @Test
    void trackingIsReportedPerMethod() {
        int tracked = CodeInventory.methodId("com.example.Shop#greet()V");
        int failed = CodeInventory.methodId("com.example.Broken#big()V");
        int unknown = CodeInventory.methodId("com.example.Later#run()V");

        claim();
        CodeInventory.tracked(new int[] {tracked});
        CodeInventory.transformFailed(new int[] {failed});

        assertThat(tracking())
                .containsExactly(CodeInventory.TRACKED, CodeInventory.TRANSFORM_FAILED, CodeInventory.UNKNOWN);
        assertThat(unknown).isEqualTo(2);
        assertThat(CodeInventory.status())
                .containsEntry("methodsTracked", 1L)
                .containsEntry("methodsFailed", 1L)
                .containsEntry("transformFailures", 1L);
        CodeInventory.untrackAll();
        assertThat(tracking()).containsOnly(CodeInventory.UNKNOWN);
    }

    @Test
    void aMethodIsTrackedInTheRunItsClassWasInstrumentedInOnly() {
        int early = CodeInventory.methodId("com.example.Shop#greet()V");
        claim();
        CodeInventory.tracked("com.example.Shop", new int[] {early}, false);
        Map<String, Object> first = CodeInventory.snapshot(CodeInventory.currentGeneration());
        assertThat(bit((long[]) first.get("trackedThisRun"), early)).isTrue();

        // A restart: the new class loader has not loaded the class yet, though its advice is kept elsewhere.
        claim();
        Map<String, Object> second = CodeInventory.snapshot(CodeInventory.currentGeneration());
        assertThat(bit((long[]) second.get("trackedThisRun"), early))
                .as("not instrumented in this run yet")
                .isFalse();
        assertThat((byte[]) second.get("tracking"))
                .as("the agent-lifetime state stays")
                .containsExactly(CodeInventory.TRACKED);

        CodeInventory.tracked("com.example.Shop", new int[] {early}, false);
        assertThat(bit(
                        (long[]) CodeInventory.snapshot(CodeInventory.currentGeneration())
                                .get("trackedThisRun"),
                        early))
                .as("instrumented again as the new class loader loads it")
                .isTrue();
        CodeInventory.untrackAll();
        assertThat(bit(
                        (long[]) CodeInventory.snapshot(CodeInventory.currentGeneration())
                                .get("trackedThisRun"),
                        early))
                .as("a release forgets it")
                .isFalse();
    }

    @Test
    void aClassThatFailsBeforeAnyMethodGotAnIdOrRunsPastTheLimitIsNamed() {
        int greet = CodeInventory.methodId("com.example.Shop#greet()V");
        claim();
        CodeInventory.tracked("com.example.Shop", new int[] {greet}, false);
        long before = CodeInventory.version();

        // Retransformed in a later run, the class fails before any method matched: its earlier ids fail with it.
        claim();
        CodeInventory.transformFailed("com.example.Shop", new int[] {greet});
        CodeInventory.transformFailed("com.example.Broken", new int[0]);
        CodeInventory.overLimit("com.example.Huge");

        Map<String, Object> snapshot = CodeInventory.snapshot(CodeInventory.currentGeneration());
        assertThat((byte[]) snapshot.get("tracking")).containsExactly(CodeInventory.TRANSFORM_FAILED);
        assertThat((String[]) snapshot.get("failedClasses")).containsExactly("com.example.Broken", "com.example.Shop");
        assertThat((String[]) snapshot.get("overLimitClasses")).containsExactly("com.example.Huge");
        assertThat(snapshot).containsEntry("transformFailures", 2L).containsEntry("namedClassOverflow", 0L);
        assertThat(CodeInventory.version()).isGreaterThan(before);

        CodeInventory.tracked("com.example.Shop", new int[] {greet}, false);
        assertThat((String[]) CodeInventory.snapshot(CodeInventory.currentGeneration())
                        .get("failedClasses"))
                .as("instrumented since: no longer failed")
                .containsExactly("com.example.Broken");
        CodeInventory.untrackAll();
        Map<String, Object> released = CodeInventory.snapshot(CodeInventory.currentGeneration());
        assertThat((String[]) released.get("failedClasses")).isEmpty();
        assertThat((String[]) released.get("overLimitClasses")).isEmpty();
    }

    @Test
    void theVersionChangesWithEveryFirstCallAndNewRunButNotWithFastPathCalls() {
        int id = CodeInventory.methodId("com.example.Shop#greet()V");
        long beforeClaim = CodeInventory.version();
        claim();
        long claimed = CodeInventory.version();
        assertThat(claimed).isGreaterThan(beforeClaim);

        advice(id);
        long called = CodeInventory.version();
        assertThat(called).isGreaterThan(claimed);
        advice(id);
        assertThat(CodeInventory.version())
                .as("a later call takes the fast path")
                .isEqualTo(called);
        assertThat(CodeInventory.snapshot(CodeInventory.currentGeneration())).containsEntry("version", called);
    }

    @Test
    void exclusionsNameTheClassesTheAgentNeverInstruments() {
        assertThat(Exclusions.excluded("com.example.Shop")).isFalse();
        assertThat(Exclusions.excluded("com.example.Shop$Inner")).isFalse();
        assertThat(Exclusions.excluded("com.example.Shop$$SpringCGLIB$$0")).isTrue();
        assertThat(Exclusions.excluded("com.example.Shop$HibernateProxy$abc")).isTrue();
        assertThat(Exclusions.excluded("com.example.Shop$MockitoMock$123")).isTrue();
        assertThat(Exclusions.excluded("com.example.Shop$ByteBuddy$x")).isTrue();
        assertThat(Exclusions.excluded("jdk.proxy2.$Proxy42")).isTrue();
        assertThat(Exclusions.excluded("com.example.$Proxy7")).isTrue();
        assertThat(Exclusions.excluded("com.example.Shop_Bean")).isTrue();
        assertThat(Exclusions.excluded("com.example.Shop_Subclass")).isTrue();
        assertThat(Exclusions.excluded("com.example.Shop_ClientProxy")).isTrue();
        assertThat(Exclusions.excluded("io.github.jdubois.bootui.engine.X")).isTrue();
        assertThat(Exclusions.excluded(null)).isTrue();
        String[] prefixes = Exclusions.prefixes();
        prefixes[0] = "changed.";
        assertThat(Exclusions.prefixes()[0]).as("a copy").isNotEqualTo("changed.");
    }

    @Test
    void aDisabledSensorRecordsNothingForItsGenerationAndSaysSo() {
        int id = CodeInventory.methodId("com.example.Shop#greet()V");
        long token = claim();
        context.set(new Object[] {REQUEST, null, null, null, "/greet"});
        CodeInventory.disable(CodeInventory.currentGeneration(), false, "self-test failed");

        assertThat(advice(id)).isTrue();
        assertThat(advice(id)).as("the fast path, even disabled").isFalse();

        assertThat(captures.get()).isZero();
        assertThat(drain(token)).isEmpty();
        assertThat(CodeInventory.snapshot(CodeInventory.currentGeneration())).containsEntry("disabled", true);
        assertThat(CodeInventory.status()).containsEntry("disabledReason", "self-test failed");
        long next = claim();
        CodeInventory.enable();
        advice(id);
        assertThat(executed(id)).isTrue();
        assertThat(CodeInventory.snapshot(CodeInventory.currentGeneration())).containsEntry("disabled", false);
        assertThat(drain(next)).hasSize(1);
    }

    @Test
    void aSensorDisabledForEveryGenerationStillLetsMethodsTakeTheFastPath() {
        int id = CodeInventory.methodId("com.example.Shop#greet()V");
        claim();
        CodeInventory.disable(CodeInventory.currentGeneration(), true, "its classes could not be restored");
        claim();

        assertThat(advice(id)).isTrue();
        assertThat(advice(id)).isFalse();
        assertThat(CodeInventory.status()).containsEntry("slowPathCalls", 1L).containsEntry("firstHits", 0L);
    }

    @Test
    void requestIdsAreParsedFromSixteenHexDigits() {
        assertThat(CodeInventory.parseRequestId("00000000000000ab")).isEqualTo(0xabL);
        assertThat(CodeInventory.parseRequestId("ffffffffffffffff")).isEqualTo(-1L);
        assertThat(CodeInventory.parseRequestId("not-a-request-id")).isZero();
        assertThat(CodeInventory.parseRequestId("abc")).isZero();
        assertThat(CodeInventory.parseRequestId(null)).isZero();
    }

    /** What the inlined advice does: whether it took the slow path. */
    private static boolean advice(int id) {
        if (CodeInventory.HITS[id] != CodeInventory.epoch) {
            CodeInventory.hit(id);
            return true;
        }
        return false;
    }

    /** Whether {@code id} ran in the current run, as the engine reads it. */
    private static boolean executed(int id) {
        Map<String, Object> snapshot = CodeInventory.snapshot(CodeInventory.currentGeneration());
        return snapshot != null && bit((long[]) snapshot.get("executed"), id);
    }

    private static byte[] tracking() {
        return (byte[])
                CodeInventory.snapshot(CodeInventory.currentGeneration()).get("tracking");
    }

    private static boolean bit(long[] bits, int id) {
        return (id >>> 6) < bits.length && (bits[id >>> 6] & (1L << (id & 63))) != 0;
    }

    private static List<long[]> drain(long token) {
        List<long[]> records = new ArrayList<>();
        AgentRing.drain(token, record -> records.add(record.clone()));
        return records;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> sources() {
        Map<String, Map<String, Object>> sources = new LinkedHashMap<>();
        for (Map<String, Object> source : CodeInventory.codeSources()) {
            sources.put((String) source.get("location"), source);
        }
        return sources;
    }

    private long claim() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("application", "shop");
        request.put("mode", "dev");
        request.put("packages", List.of("com.example"));
        request.put("sensors", List.of("inventory"));
        request.put("ringCapacity", 1024);
        Supplier<Object> capture = () -> {
            captures.incrementAndGet();
            insideCapture.run();
            return context.get();
        };
        Function<Object, AutoCloseable> reopen = snapshot -> null;
        keep.add(capture);
        keep.add(reopen);
        Map<String, Object> result = AgentBridge.claim(request, capture, reopen);
        assertThat(result.get("status")).isEqualTo(AgentBridge.ARMED);
        captures.set(0);
        return (Long) result.get("token");
    }
}
