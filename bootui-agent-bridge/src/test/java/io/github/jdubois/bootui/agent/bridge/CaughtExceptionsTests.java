package io.github.jdubois.bootui.agent.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The caught-exceptions sensor's bridge side (PLAN-v2 M5-6a), driven as the inserted code drives it: {@code caught}
 * at a handler's entry, {@code leaving} from a method's exit handler before it rethrows.
 */
class CaughtExceptionsTests {

    private static final String REQUEST = "00000000000000ab";
    private static final long REQUEST_BITS = 0xabL;

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
    void aCaughtExceptionUnderARequestIsOneRecordWithItsOwnerSiteClassAndIdentity() {
        long token = claim();
        context.set(owner(REQUEST, null));
        int site = CaughtExceptions.site("com/example/Shop#buy()V#0#java/io/IOException", "java/io/IOException");
        CaughtExceptions.siteRead(site, CaughtExceptions.FLAG_EXIT_HANDLER, 42);
        java.io.IOException thrown = new java.io.IOException("secret message");

        CaughtExceptions.caught(thrown, site);

        List<long[]> records = drain(token);
        assertThat(records).hasSize(1);
        long[] record = records.get(0);
        assertThat(record[AgentRing.SENSOR]).isEqualTo(AgentRing.SENSOR_CAUGHT_EXCEPTIONS);
        assertThat(record[AgentRing.TYPE]).isEqualTo(CaughtExceptions.TYPE_CAUGHT);
        assertThat(record[AgentRing.PAYLOAD]).isEqualTo(REQUEST_BITS);
        assertThat(record[AgentRing.PAYLOAD + 1]).isZero();
        assertThat(record[AgentRing.PAYLOAD + 2] >>> 32).isEqualTo(site);
        assertThat((int) record[AgentRing.PAYLOAD + 2]).isEqualTo(System.identityHashCode(thrown));
        long flags = record[AgentRing.PAYLOAD + 3];
        assertThat(interned(flags >>> 32)).isEqualTo("java.io.IOException");
        assertThat(flags & 0x3L).isEqualTo(CaughtExceptions.FAMILY_IO);
        assertThat(interned((flags >>> 16) & 0xFFFFL)).isEqualTo(Thread.currentThread().getName());
        // Never the message: nothing interned holds it.
        assertThat(AgentRing.internedNow()).noneMatch(text -> text.contains("secret"));
        assertThat(CaughtExceptions.sites(site)[0])
                .isEqualTo("com/example/Shop#buy()V#0#java/io/IOException\tjava/io/IOException\t42\t"
                        + (CaughtExceptions.FLAG_EXIT_HANDLER | CaughtExceptions.FLAG_COMPLETE));
    }

    @Test
    void aCaughtExceptionLeavingAMethodIsThrownWithTheOwnerItWasCaughtUnder() {
        long token = claim();
        context.set(owner(REQUEST, null));
        int site = site("rethrow");
        IllegalStateException thrown = new IllegalStateException();

        CaughtExceptions.caught(thrown, site);
        context.set(null);
        CaughtExceptions.leaving(thrown, site);
        // Found once: a throw travelling up further frames finds nothing pending.
        CaughtExceptions.leaving(thrown, site);

        List<long[]> records = drain(token);
        assertThat(records).extracting(record -> record[AgentRing.TYPE])
                .containsExactly((long) CaughtExceptions.TYPE_CAUGHT, (long) CaughtExceptions.TYPE_THROWN);
        long[] rethrown = records.get(1);
        assertThat(rethrown[AgentRing.PAYLOAD]).isEqualTo(REQUEST_BITS);
        assertThat(rethrown[AgentRing.PAYLOAD + 2] >>> 32).isEqualTo(site);
        assertThat(rethrown[AgentRing.PAYLOAD + 3] & 0xFFL).isEqualTo(CaughtExceptions.THROWN_EXIT);
        assertThat(CaughtExceptions.pending()).isZero();
    }

    @Test
    void aWrappedRethrowIsFoundThroughTheCauseChainAndCaughtAgainThroughSuppressed() {
        long token = claim();
        context.set(owner(REQUEST, null));
        int inner = site("inner");
        int outer = site("outer");
        java.io.IOException cause = new java.io.IOException();
        CaughtExceptions.caught(cause, inner);
        RuntimeException wrapper = new RuntimeException(cause);
        CaughtExceptions.leaving(wrapper, inner);

        IllegalStateException primary = new IllegalStateException();
        CaughtExceptions.caught(primary, inner);
        IllegalArgumentException carrier = new IllegalArgumentException();
        carrier.addSuppressed(primary);
        CaughtExceptions.caught(carrier, outer);

        List<long[]> records = drain(token);
        assertThat(records).extracting(record -> record[AgentRing.TYPE])
                .containsExactly(
                        (long) CaughtExceptions.TYPE_CAUGHT,
                        (long) CaughtExceptions.TYPE_THROWN,
                        (long) CaughtExceptions.TYPE_CAUGHT,
                        (long) CaughtExceptions.TYPE_THROWN,
                        (long) CaughtExceptions.TYPE_CAUGHT);
        assertThat((int) records.get(1)[AgentRing.PAYLOAD + 2]).isEqualTo(System.identityHashCode(cause));
        assertThat(records.get(3)[AgentRing.PAYLOAD + 3] & 0xFFL).isEqualTo(CaughtExceptions.THROWN_CAUGHT_AGAIN);
        assertThat(records.get(3)[AgentRing.PAYLOAD + 3] >>> 32).isEqualTo(outer);
    }

    @Test
    void anOverriddenCauseThatThrowsOrCyclesEndsTheWalk() {
        long token = claim();
        context.set(owner(REQUEST, null));
        RuntimeException cyclic = new RuntimeException() {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        RuntimeException hostile = new RuntimeException() {
            @Override
            public synchronized Throwable getCause() {
                throw new IllegalStateException("hostile");
            }
        };

        CaughtExceptions.caught(cyclic, site("cyclic"));
        CaughtExceptions.caught(hostile, site("hostile"));

        assertThat(drain(token)).hasSize(2);
        assertThat(CaughtExceptions.status()).containsEntry("applicationErrors", 1L).containsEntry("errors", 0L);
    }

    @Test
    void aCatchInsideAnOverriddenGetCauseIsNeverRecordedInsideTheWalk() {
        long token = claim();
        context.set(owner(REQUEST, null));
        int nestedSite = site("nested");
        RuntimeException reentrant = new RuntimeException() {
            @Override
            public synchronized Throwable getCause() {
                CaughtExceptions.caught(new IllegalStateException(), nestedSite);
                return null;
            }
        };

        CaughtExceptions.caught(reentrant, site("outer"));

        assertThat(drain(token)).hasSize(1);
    }

    @Test
    void withoutAnOwnerNothingIsRecordedButARethrowOfAnOwnedOneStillIs() {
        long token = claim();
        int site = site("unowned");
        CaughtExceptions.caught(new IllegalStateException(), site);

        assertThat(drain(token)).isEmpty();
        assertThat(CaughtExceptions.status()).containsEntry("unowned", 1L);
    }

    @Test
    void anExecutionNoRequestOwnsIsTheOwner() {
        long token = claim();
        context.set(owner(null, "00000000000000cd"));

        CaughtExceptions.caught(new IllegalStateException(), site("job"));

        long[] record = drain(token).get(0);
        assertThat(record[AgentRing.PAYLOAD]).isZero();
        assertThat(record[AgentRing.PAYLOAD + 1]).isEqualTo(0xcdL);
        assertThat((record[AgentRing.PAYLOAD + 3] >>> 8) & 0xFL).isEqualTo(SideEffects.EXECUTION_OWN);
    }

    @Test
    void aSiteCaughtOftenPublishesItsFirstOccurrencesThenCountsTheRestUntilTheOwnerChanges() {
        long token = claim();
        context.set(owner(REQUEST, null));
        int site = site("loop");
        for (int i = 0; i < CaughtExceptions.PER_SITE + 5; i++) {
            CaughtExceptions.caught(new IllegalStateException(), site);
        }
        context.set(owner("00000000000000ac", null));
        CaughtExceptions.caught(new IllegalStateException(), site);

        List<long[]> records = drain(token);
        assertThat(records).filteredOn(record -> record[AgentRing.TYPE] == CaughtExceptions.TYPE_CAUGHT)
                .hasSize(CaughtExceptions.PER_SITE + 1);
        long[] untracked = records.stream()
                .filter(record -> record[AgentRing.TYPE] == CaughtExceptions.TYPE_UNTRACKED)
                .findFirst()
                .orElseThrow();
        assertThat(untracked[AgentRing.PAYLOAD]).isEqualTo(REQUEST_BITS);
        assertThat(untracked[AgentRing.PAYLOAD + 2] >>> 32).isEqualTo(site);
        assertThat((int) untracked[AgentRing.PAYLOAD + 2]).isEqualTo(5);
    }

    @Test
    void flushingTheThreadPublishesItsCounts() {
        long token = claim();
        context.set(owner(REQUEST, null));
        int site = site("flush");
        for (int i = 0; i < CaughtExceptions.PER_SITE + 2; i++) {
            CaughtExceptions.caught(new IllegalStateException(), site);
        }

        CaughtExceptions.flushThread();

        assertThat(drain(token)).filteredOn(record -> record[AgentRing.TYPE] == CaughtExceptions.TYPE_UNTRACKED)
                .singleElement()
                .satisfies(record -> assertThat((int) record[AgentRing.PAYLOAD + 2]).isEqualTo(2));
    }

    @Test
    void aFullStripeEvictsItsOldestEntryWithARecordSayingSo() {
        long token = claim();
        context.set(owner(REQUEST, null));
        // Distinct sites so the per-site count never stops publishing.
        int[] sites = new int[3000];
        for (int i = 0; i < sites.length; i++) {
            sites[i] = site("evict" + i);
        }
        List<Throwable> keepAlive = new ArrayList<>();
        int evicted = 0;
        for (int i = 0; i < sites.length; i++) {
            IllegalStateException thrown = new IllegalStateException();
            keepAlive.add(thrown);
            CaughtExceptions.caught(thrown, sites[i]);
            for (long[] record : drain(token)) {
                evicted += record[AgentRing.TYPE] == CaughtExceptions.TYPE_EVICTED ? 1 : 0;
            }
        }

        assertThat(CaughtExceptions.pending()).isLessThanOrEqualTo(CaughtExceptions.STRIPES * CaughtExceptions.STRIPE);
        assertThat(evicted).isEqualTo(3000 - CaughtExceptions.pending());
        assertThat(CaughtExceptions.status().get("evicted")).isEqualTo((long) evicted);
        assertThat(keepAlive).hasSize(3000);
    }

    @Test
    void handlersOfAnotherAgentsAdviceOrSharedWithAJumpAreSkipped() {
        long token = claim();
        context.set(owner(REQUEST, null));
        int foreign = site("foreign");
        int shared = site("shared");
        CaughtExceptions.siteRead(foreign, CaughtExceptions.FLAG_FOREIGN, 0);
        CaughtExceptions.siteRead(shared, CaughtExceptions.FLAG_SHARED, 3);

        CaughtExceptions.caught(new IllegalStateException(), foreign);
        CaughtExceptions.caught(new IllegalStateException(), shared);

        assertThat(drain(token)).isEmpty();
        assertThat(CaughtExceptions.status()).containsEntry("skipped", 2L);
    }

    @Test
    void nothingIsRecordedBeforeTheSelfTestPassedOrWhileBootUiWorks() {
        long token = claim(false);
        context.set(owner(REQUEST, null));
        CaughtExceptions.caught(new IllegalStateException(), site("early"));
        assertThat(drain(token)).isEmpty();

        CaughtExceptions.enable();
        boolean previous = AgentBridge.bootUiWork(true);
        CaughtExceptions.caught(new IllegalStateException(), site("bootui"));
        AgentBridge.bootUiWork(previous);
        assertThat(drain(token)).isEmpty();

        CaughtExceptions.caught(new IllegalStateException(), site("recorded"));
        assertThat(drain(token)).hasSize(1);
    }

    @Test
    void theSelfTestCountsEntriesAndTheExitOfTheExceptionItSaw() {
        CaughtExceptions.beginSelfTest();
        IllegalStateException thrown = new IllegalStateException();
        CaughtExceptions.caught(thrown, 0);
        CaughtExceptions.leaving(new IllegalStateException(), 0);
        CaughtExceptions.leaving(thrown, 0);

        assertThat(CaughtExceptions.endSelfTest()).containsExactly(1L, 1L);
    }

    @Test
    void aDisarmedClaimRecordsNothingAndANewGenerationForgetsPendingIdentities() {
        long token = claim();
        context.set(owner(REQUEST, null));
        int site = site("generation");
        IllegalStateException thrown = new IllegalStateException();
        CaughtExceptions.caught(thrown, site);
        drain(token);
        assertThat(CaughtExceptions.pending()).isEqualTo(1);

        AgentBridge.disarm(token);
        CaughtExceptions.caught(new IllegalStateException(), site);
        assertThat(CaughtExceptions.status()).containsEntry("caught", 1L);

        long next = claim();
        // The previous run's pending identity is freed with its run: never reported as the new run's rethrow.
        assertThat(CaughtExceptions.pending()).isZero();
        CaughtExceptions.leaving(thrown, site);
        assertThat(drain(next)).isEmpty();
    }

    @Test
    void aSelfTestWhileARunRecordsCountsItsHitsAndRecordsNothing() {
        long token = claim();
        context.set(owner(REQUEST, null));
        int site = site("probe");

        CaughtExceptions.beginSelfTest();
        IllegalStateException thrown = new IllegalStateException();
        CaughtExceptions.caught(thrown, site);
        CaughtExceptions.leaving(thrown, site);
        long[] hits = CaughtExceptions.endSelfTest();

        assertThat(hits).containsExactly(1L, 1L);
        assertThat(drain(token)).isEmpty();
        assertThat(CaughtExceptions.pending()).isZero();
    }

    @Test
    void aSuspendedSensorRecordsNothingUntilItsNextSelfTestPassed() {
        long token = claim();
        context.set(owner(REQUEST, null));
        int site = site("suspended");

        CaughtExceptions.suspend();
        CaughtExceptions.caught(new IllegalStateException(), site);
        assertThat(drain(token)).isEmpty();

        CaughtExceptions.enable();
        CaughtExceptions.caught(new IllegalStateException(), site);
        assertThat(drain(token)).hasSize(1);
    }

    @Test
    void siteIdsAreStableAndBoundedAndTheSameKeyKeepsItsId() {
        int first = CaughtExceptions.site("a#b()V#0#java/lang/Error", "java/lang/Error");
        int again = CaughtExceptions.site("a#b()V#0#java/lang/Error", "java/lang/Error");

        assertThat(again).isEqualTo(first);
        assertThat(CaughtExceptions.siteCount()).isEqualTo(first + 1);
        assertThat(CaughtExceptions.sites(first + 1)).isEmpty();
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static int site(String method) {
        return CaughtExceptions.site(
                "com/example/Shop#" + method + "()V#0#java/lang/RuntimeException", "java/lang/RuntimeException");
    }

    private static Object[] owner(String request, String execution) {
        return new Object[] {request, execution, null, null, "/shop", null, null, 1L, 1L};
    }

    private long generation() {
        return (Long) ((Map<?, ?>) AgentBridge.status().get("claim")).get("generation");
    }

    private long claim() {
        return claim(true);
    }

    private long claim(boolean enable) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("application", "shop");
        request.put("mode", "dev");
        request.put("packages", List.of("com.example"));
        request.put("sensors", List.of(CaughtExceptions.SENSOR));
        Supplier<Object> capture = context::get;
        Function<Object, AutoCloseable> reopen = snapshot -> null;
        keep.add(capture);
        keep.add(reopen);
        Map<String, Object> result = AgentBridge.claim(request, capture, reopen);
        assertThat(result.get("status")).isEqualTo(AgentBridge.ARMED);
        if (enable) {
            CaughtExceptions.enable();
        }
        return (Long) result.get("token");
    }

    private static List<long[]> drain(long token) {
        List<long[]> records = new ArrayList<>();
        AgentRing.drain(token, record -> records.add(record.clone()));
        return records;
    }

    private String interned(long id) {
        String[] strings = AgentRing.interned(generation(), (int) id);
        return strings == null || strings.length == 0 ? null : strings[0];
    }
}
