package io.github.jdubois.bootui.engine.sideeffects;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import io.github.jdubois.bootui.agent.bridge.SideEffects;
import io.github.jdubois.bootui.agent.bridge.ThreadLocals;
import io.github.jdubois.bootui.core.dto.SideEffectsRowDto;
import io.github.jdubois.bootui.core.dto.SideEffectsSensorDto;
import io.github.jdubois.bootui.core.dto.SideEffectsSensorReport;
import io.github.jdubois.bootui.engine.javaagent.AgentBridgeAccess;
import io.github.jdubois.bootui.engine.javaagent.AgentClaim;
import io.github.jdubois.bootui.engine.javaagent.AgentHandoffs;
import io.github.jdubois.bootui.engine.javaagent.AgentSensorSettings;
import io.github.jdubois.bootui.engine.javaagent.JavaAgentService;
import io.github.jdubois.bootui.engine.journal.AgentEvidence;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The {@code thread-locals} rows ({@code docs/PLAN-v2.md} §5.16, M5-5f) against the real bridge class from the test
 * class path, its scanner standing in for the agent's, and the holder the agent would name standing in for its
 * resolver: a holder decides a row, a framework's or a per-thread cache's drops it, counted, and no value ever shows.
 */
class ThreadLocalsSideEffectsTests {

    private static final String REQUEST = "00000000000000cd";
    private static final String SECRET = "tenant-secret-42";

    static final ThreadLocal<String> TENANT = new ThreadLocal<>();
    static final ThreadLocal<String> FRAMEWORK = new ThreadLocal<>();
    static final ThreadLocal<String> CACHE = ThreadLocal.withInitial(() -> SECRET);
    static final ThreadLocal<String> UNRESOLVED = new ThreadLocal<>();

    private final AtomicReference<CorrelationContext> context = new AtomicReference<>(CorrelationContext.NONE);
    private final AtomicLong clock = new AtomicLong();
    private final Map<Object, String[]> answers = new IdentityHashMap<>();
    private final List<Integer> excluded = new ArrayList<>();
    private final Scanner scanner = new Scanner();
    private boolean outOfTime;
    private SideEffectsService service;
    private AgentClaim claim;

    @BeforeEach
    void installAgent() {
        resetBridge();
        AgentBridge.install(request -> {
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("status", "ok");
            return answer;
        });
        clock.set(System.currentTimeMillis() - 60_000L);
        answers.put(TENANT, new String[] {getClass().getName() + ".TENANT", "false", "true", null});
        answers.put(FRAMEWORK, new String[] {
            "org.springframework.web.context.request.RequestContextHolder.holder", "false", "false", null
        });
        answers.put(CACHE, new String[] {"com.zaxxer.hikari.util.ConcurrentBag.threadList", "true", "false", null});
        answers.put(UNRESOLVED, new String[] {null, "false", "false", null});
    }

    @AfterEach
    void resetAgent() {
        if (service != null) {
            service.close();
        }
        if (claim != null) {
            claim.disarm();
        }
        resetBridge();
    }

    @Test
    void aThreadLocalLeftSetIsARowOfItsRouteNamedByItsHolderNeverItsValue() {
        start();
        leaveSet(TENANT);
        leaveSet(TENANT);

        SideEffectsSensorReport report = service.sensor("thread-locals", null, null);

        assertThat(report.sensor().state()).isEqualTo(SideEffectsSensorDto.RECORDING);
        assertThat(report.rows()).singleElement().satisfies(row -> {
            assertThat(row.scope()).isEqualTo(SideEffectsRowDto.ROUTE);
            assertThat(row.attribution()).isEqualTo("GET /tenants");
            assertThat(row.kind()).isEqualTo(SideEffectsCatalog.LEFT_SET);
            assertThat(row.target()).isEqualTo(getClass().getName() + ".TENANT");
            assertThat(row.origin()).isEqualTo(SideEffectOrigins.APPLICATION);
            assertThat(row.callSite()).isNull();
            assertThat(row.count()).isEqualTo(2L);
            assertThat(row.requests()).isEqualTo(1L);
            assertThat(row.exemplarRequestIds()).containsExactly(REQUEST);
        });
        assertThat(report.toString()).doesNotContain(SECRET);
        assertThat(report.limitations()).contains(SideEffectsService.LIMITATION_THREAD_LOCALS);
    }

    @Test
    void aFrameworksThreadLocalAndALibrarysPerThreadCacheAreDroppedCountedAndSkippedByTheBridgeFromThen() {
        start();
        leaveSet(FRAMEWORK);
        leaveSet(CACHE);

        SideEffectsSensorReport report = service.sensor("thread-locals", null, null);

        assertThat(report.rows()).isEmpty();
        assertThat(report.limitations())
                .anySatisfy(limitation -> assertThat(limitation)
                        .contains("org.springframework.web.context.request.RequestContextHolder 1")
                        .contains(ThreadLocalHolders.PER_THREAD_CACHE + " 1"));
        assertThat(excluded).hasSize(2);
        assertThat(report.toString()).doesNotContain(SECRET);
    }

    @Test
    void aThreadLocalWhoseHolderIsNotResolvedIsNamedByItsClass() {
        start();
        leaveSet(UNRESOLVED);

        assertThat(service.sensor("thread-locals", null, null).rows())
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.target()).isEqualTo("holder not resolved (java.lang.ThreadLocal)");
                    assertThat(row.origin()).isEqualTo(SideEffectOrigins.UNKNOWN);
                });
    }

    @Test
    void whileTheAgentHasNoTimeARecordWaitsThenShowsAsNotResolved() {
        start();
        outOfTime = true;
        leaveSet(TENANT);

        assertThat(service.sensor("thread-locals", null, null).rows()).isEmpty();

        clock.addAndGet(SideEffectsService.HOLDER_WAIT_MILLIS + 1);
        assertThat(service.sensor("thread-locals", null, null).rows())
                .singleElement()
                .satisfies(row -> assertThat(row.target()).isEqualTo("holder not resolved (java.lang.ThreadLocal)"));
    }

    @Test
    void aRetryPastTheWaitStillAsksTheAgentFirst() {
        start();
        outOfTime = true;
        leaveSet(TENANT);
        assertThat(service.sensor("thread-locals", null, null).rows()).isEmpty();

        // Retries are as sparse as the records: the first one comes after the wait, with the agent's time back.
        outOfTime = false;
        clock.addAndGet(SideEffectsService.HOLDER_WAIT_MILLIS + 1);

        assertThat(service.sensor("thread-locals", null, null).rows())
                .singleElement()
                .satisfies(row -> assertThat(row.target()).isEqualTo(getClass().getName() + ".TENANT"));
    }

    @Test
    void aHolderGivenUpOnIsAskedAgainForItsNextRecord() {
        start();
        outOfTime = true;
        leaveSet(TENANT);
        assertThat(service.sensor("thread-locals", null, null).rows()).isEmpty();
        clock.addAndGet(SideEffectsService.HOLDER_WAIT_MILLIS + 1);
        assertThat(service.sensor("thread-locals", null, null).rows())
                .singleElement()
                .satisfies(row -> assertThat(row.target()).isEqualTo("holder not resolved (java.lang.ThreadLocal)"));

        outOfTime = false;
        leaveSet(TENANT);

        assertThat(service.sensor("thread-locals", null, null).rows())
                .extracting(SideEffectsRowDto::target)
                .contains(getClass().getName() + ".TENANT");
    }

    @Test
    void aReusedRegistrySlotReplacesWhatItNamedAndTheCachesStayBounded() {
        Map<Integer, SideEffectsService.Named<String>> cache = new java.util.HashMap<>();
        SideEffectsService.remember(cache, 7, 111, "first");
        SideEffectsService.remember(cache, 7, 222, "reused");

        assertThat(cache).hasSize(1);
        assertThat(SideEffectsService.Named.of(cache.get(7), 111)).isNull();
        assertThat(SideEffectsService.Named.of(cache.get(7), 222)).isEqualTo("reused");

        for (int id = 1; id <= SideEffectsService.MAX_HOLDERS + 100; id++) {
            SideEffectsService.remember(cache, id, id, "local-" + id);
        }
        assertThat(cache).hasSize(SideEffectsService.MAX_HOLDERS);
        SideEffectsService.remember(cache, 1, 999, "replaced at the cap");
        assertThat(SideEffectsService.Named.of(cache.get(1), 999)).isEqualTo("replaced at the cap");
    }

    @Test
    void decisionsNameTheKindAndKeepSpringSecuritysContext() {
        ThreadLocalHolders.Holder security = ThreadLocalHolders.decide(
                new String[] {
                    "org.springframework.security.core.context.ThreadLocalSecurityContextHolderStrategy.contextHolder",
                    "false",
                    "false",
                    null
                },
                "java.lang.ThreadLocal",
                0);
        assertThat(security.excludedBy()).isNull();
        assertThat(security.origin()).isEqualTo(SideEffectOrigins.LIBRARY);

        assertThat(ThreadLocalHolders.decide(
                                new String[] {"com.example.Holder.LOCAL", "false", "true", null},
                                "java.lang.InheritableThreadLocal",
                                SideEffectsCatalog.DETAIL_INHERITABLE)
                        .kind())
                .isEqualTo(SideEffectsCatalog.LEFT_SET_INHERITABLE);
        ThreadLocalHolders.Holder initial =
                ThreadLocalHolders.decide(new String[] {"com.example.Formats.FORMAT", "true", "true", null}, "x", 0);
        assertThat(initial.kind()).isEqualTo(SideEffectsCatalog.LEFT_SET_INITIAL_VALUE);
        assertThat(initial.excludedBy()).isNull();
        assertThat(ThreadLocalHolders.decide(
                                new String[] {null, "true", "false", "com.example.Formats"},
                                "java.lang.ThreadLocal$SuppliedThreadLocal",
                                SideEffectsCatalog.DETAIL_SUPPLIED)
                        .excludedBy())
                .isEqualTo(ThreadLocalHolders.PER_THREAD_CACHE);
        assertThat(ThreadLocalHolders.decide(
                                new String[] {
                                    "ch.qos.logback.classic.util.LogbackMDCAdapter.readWriteThreadLocalMap (via"
                                            + " org.slf4j.MDC.mdcAdapter)",
                                    "false",
                                    "false",
                                    null
                                },
                                "java.lang.ThreadLocal",
                                0)
                        .excludedBy())
                .isEqualTo("ch.qos.logback.classic.util.LogbackMDCAdapter");
        assertThat(ThreadLocalHolders.decide(
                                new String[] {
                                    "io.opentelemetry.api.internal.TemporaryBuffers.CHAR_ARRAY", "false", "false", null
                                },
                                "java.lang.ThreadLocal",
                                0)
                        .excludedBy())
                .as("OpenTelemetry's per-thread char buffer, filled on first use")
                .isEqualTo("io.opentelemetry.api.internal.TemporaryBuffers");
    }

    @Test
    void quarkusVertxMdcIsAFrameworksPerRequestMdcResolvedOrNot() {
        assertThat(ThreadLocalHolders.decide(
                                new String[] {
                                    "io.quarkus.vertx.core.runtime.VertxMDC.inheritableThreadLocalMap (via"
                                            + " io.quarkus.vertx.core.runtime.VertxMDC.INSTANCE)",
                                    "false",
                                    "false",
                                    null
                                },
                                "io.quarkus.vertx.core.runtime.VertxMDC$1",
                                SideEffectsCatalog.DETAIL_INHERITABLE)
                        .excludedBy())
                .as("resolved one level deep through its enum singleton")
                .isEqualTo("io.quarkus.vertx.core.runtime.VertxMDC");
        assertThat(ThreadLocalHolders.decide(
                                new String[] {null, "false", "false", null},
                                "io.quarkus.vertx.core.runtime.VertxMDC$1",
                                SideEffectsCatalog.DETAIL_INHERITABLE)
                        .excludedBy())
                .as("its anonymous subclass, matched by its holder class when the holder is not resolved")
                .isEqualTo("io.quarkus.vertx.core.runtime.VertxMDC");
        assertThat(ThreadLocalHolders.decide(new String[] {null, "false", "false", null}, "com.example.Tenants$1", 0)
                        .excludedBy())
                .as("an application's own subclass stays reported")
                .isNull();
        assertThat(ThreadLocalHolders.HOLDER_CLASSES).contains("io.quarkus.vertx.core.runtime.VertxMDC");
    }

    private void leaveSet(ThreadLocal<String> local) {
        context.set(CorrelationContext.forRequest(REQUEST));
        long scope = ThreadLocals.open();
        assertThat(scope).isPositive();
        scanner.leave(local);
        ThreadLocals.close(scope);
        scanner.clear();
        context.set(CorrelationContext.NONE);
    }

    private void start() {
        claim = AgentClaim.claim(
                AgentBridgeAccess.bind(AgentBridge.class),
                "shop",
                "shop-owner",
                "dev",
                List.of("io.github.jdubois.bootui.engine.sideeffects"),
                new AgentSensorSettings(
                        List.of("thread-locals"),
                        List.of(),
                        List.of(),
                        null,
                        AgentSensorSettings.DEFAULT_RING_CAPACITY));
        claim.attach(new AgentHandoffs(context::get, null, null));
        ThreadLocals.install(scanner, null);
        SideEffects.enable(SideEffects.MASK_THREAD_LOCALS);
        service = new SideEffectsService(
                AgentBridgeAccess.bind(AgentBridge.class),
                () -> claim,
                () -> null,
                id -> new JavaAgentService.SideEffectsCoverage(
                        SideEffectsSensorDto.RECORDING, null, List.of(), 0L, Map.of()),
                new AgentEvidence(panel -> true, null),
                clock::get,
                "/home/someone");
        service.setRequestRoutes(ids -> ids.contains(REQUEST) ? Map.of(REQUEST, "GET /tenants") : Map.of());
        service.threadLocalHolders(new ThreadLocalHolders.Resolver() {
            @Override
            public String[] holder(
                    long generation, int id, int hash, String[] packages, String[] holders, long budgetNanos) {
                if (outOfTime) {
                    return null;
                }
                for (Map.Entry<Object, String[]> answer : answers.entrySet()) {
                    if (System.identityHashCode(answer.getKey()) == hash) {
                        return answer.getValue();
                    }
                }
                return new String[] {null, "false", "false", null};
            }

            @Override
            public void exclude(long generation, int id, int hash) {
                excluded.add(hash);
            }
        });
        service.start();
    }

    /** The calling thread's maps as the test leaves them: a thread local set during the scope, or none. */
    static final class Scanner extends ThreadLocals.Scanner {

        private volatile Object left;

        void leave(Object local) {
            left = local;
        }

        void clear() {
            left = null;
        }

        @Override
        public int snapshot(int[] hashes) {
            return 0;
        }

        @Override
        public int leftovers(int[] open, int openCount, Object[] keys, int[] hashes, boolean[] inheritable) {
            Object local = left;
            if (local == null) {
                return 0;
            }
            keys[0] = local;
            hashes[0] = System.identityHashCode(local);
            inheritable[0] = false;
            return 1;
        }
    }

    private static void resetBridge() {
        try {
            Method reset = AgentBridge.class.getDeclaredMethod("reset");
            reset.setAccessible(true);
            reset.invoke(null);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
