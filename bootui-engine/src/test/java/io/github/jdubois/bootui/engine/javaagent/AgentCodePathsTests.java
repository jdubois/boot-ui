package io.github.jdubois.bootui.engine.javaagent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.agent.bridge.CodeInventory;
import io.github.jdubois.bootui.agent.bridge.CodePaths;
import io.github.jdubois.bootui.engine.correlation.RequestPhase;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The adapters' hooks into the code-paths sensor, bound to the bridge class from the test class path. */
class AgentCodePathsTests {

    @BeforeEach
    void install() {
        Bridges.reset();
        Bridges.StubAgent.install();
    }

    @AfterEach
    void reset() {
        AgentCodePaths.clearAssemblyOnly();
        AgentCodePaths.rebind();
        Bridges.reset();
    }

    @Test
    void withoutTheAgentEveryHookDoesNothing() {
        AgentCodePaths.bind(null);

        AgentCodePaths.begin();
        AgentCodePaths.phase(RequestPhase.HANDLER);
        AgentCodePaths.clearPhase();
        AgentCodePaths.end();

        assertThat(AgentCodePaths.bound()).isFalse();
    }

    @Test
    void requestsAreRememberedAsAssemblyOnlyOnlyWithTheAgentAndWithinABound() {
        AgentCodePaths.bind(null);
        AgentCodePaths.assemblyOnly("0000000000000001");
        assertThat(AgentCodePaths.isAssemblyOnly("0000000000000001"))
                .as("nothing is kept without the agent")
                .isFalse();

        AgentCodePaths.bind(CodePaths.class);
        AgentCodePaths.assemblyOnly(null);
        for (int i = 0; i <= AgentCodePaths.ASSEMBLY_ONLY_REQUESTS; i++) {
            AgentCodePaths.assemblyOnly(String.format("%016x", i));
        }
        assertThat(AgentCodePaths.isAssemblyOnly(String.format("%016x", 0)))
                .as("the eldest is forgotten")
                .isFalse();
        assertThat(AgentCodePaths.isAssemblyOnly(String.format("%016x", AgentCodePaths.ASSEMBLY_ONLY_REQUESTS)))
                .isTrue();
        assertThat(AgentCodePaths.isAssemblyOnly(null)).isFalse();
        assertThat(AgentCodePaths.assemblyOnlyCount()).isEqualTo(AgentCodePaths.ASSEMBLY_ONLY_REQUESTS);
        AgentCodePaths.assemblyOnly(String.format("%016x", AgentCodePaths.ASSEMBLY_ONLY_REQUESTS));
        assertThat(AgentCodePaths.assemblyOnlyCount())
                .as("marking a request again keeps one entry")
                .isEqualTo(AgentCodePaths.ASSEMBLY_ONLY_REQUESTS);
    }

    /** Requests marked from many threads at once, without a global lock, stay within the bound. */
    @Test
    void concurrentMarksStayWithinTheBound() throws Exception {
        AgentCodePaths.bind(CodePaths.class);
        int threads = 8;
        int perThread = AgentCodePaths.ASSEMBLY_ONLY_REQUESTS;
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            List<java.util.concurrent.Future<?>> done = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int thread = t;
                done.add(executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        AgentCodePaths.assemblyOnly(String.format("%08x%08x", thread, i));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<?> future : done) {
                future.get(30, java.util.concurrent.TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }
        assertThat(AgentCodePaths.assemblyOnlyCount()).isLessThanOrEqualTo(AgentCodePaths.ASSEMBLY_ONLY_REQUESTS);
        assertThat(AgentCodePaths.assemblyOnlyCount()).isPositive();
    }

    @Test
    void clearingThePhaseOnAWorkerLeavesTheThreadsNextWorkWithoutOne() {
        AgentCodePaths.bind(CodePaths.class);
        AgentClaim claim = AgentClaim.claim(Bridges.access(), "shop", "shop@1", "dev", List.of("shop"));
        claim.attach(new AgentHandoffs(() -> CorrelationContext.forRequest("00000000000000ab"), null, null));
        int serializer = CodeInventory.methodId("shop.Serializer#write()V");

        // A worker thread, after the response filter marked the response phase and the response was written.
        AgentCodePaths.phase(RequestPhase.RESPONSE);
        AgentCodePaths.clearPhase();
        CodePaths.exit(CodePaths.enter(serializer));

        List<long[]> blobs = new ArrayList<>();
        claim.drainCodePaths(blobs::add);
        assertThat(blobs)
                .singleElement()
                .satisfies(blob ->
                        assertThat(blob[CodePaths.HEADER + CodePaths.N_PHASE]).isEqualTo(CodePaths.PHASE_UNKNOWN));
        claim.disarm();
    }

    @Test
    void withoutTheAgentOrWithOnePredatingStampsEveryStampIsZero() {
        AgentCodePaths.bind(null);
        assertThat(AgentCodePaths.stamp()).isZero();

        AgentCodePaths.bind(PreStampCodePaths.class);
        assertThat(AgentCodePaths.bound())
                .as("begin, end, and phase still bind")
                .isTrue();
        assertThat(AgentCodePaths.stamp()).isZero();
    }

    /**
     * M5-4c: SQL, REST client, and cache recorders stamp what they record on the issuing thread with the innermost
     * open node; a REST call whose thread kind was captured elsewhere, as a WebClient response, and anything recorded
     * outside a fragment, carry none.
     */
    @Test
    void recordersStampTheirCallsWithTheInnermostOpenNodeOnTheIssuingThread() {
        AgentCodePaths.bind(CodePaths.class);
        AgentClaim claim = AgentClaim.claim(Bridges.access(), "shop", "shop@1", "dev", List.of("shop"));
        CorrelationContext request = CorrelationContext.forRequest("00000000000000ab");
        claim.attach(new AgentHandoffs(() -> request, null, null));
        int repository = CodeInventory.methodId("shop.OwnerService#findAll()V");
        List<io.github.jdubois.bootui.engine.journal.RuntimeEvent> published = new ArrayList<>();
        io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder sql =
                new io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder(
                        true, true, false, false, 10, 500, 2000, 200, 5);
        sql.setRuntimeEventSink(published::add);
        io.github.jdubois.bootui.engine.restclienttrace.RestClientTraceRecorder rest =
                new io.github.jdubois.bootui.engine.restclienttrace.RestClientTraceRecorder(
                        true, true, true, false, 8, 500, 2000, 200, 5);
        rest.setRuntimeEventSink(published::add);
        io.github.jdubois.bootui.engine.cache.CacheActivityRecorder cache =
                new io.github.jdubois.bootui.engine.cache.CacheActivityRecorder(true, 10);
        cache.setRuntimeEventSink(published::add);
        long[] open = new long[1];

        int token = CodePaths.enter(repository);
        try {
            open[0] = AgentCodePaths.stamp();
            recordSql(sql);
            rest.recordNanos(
                    "GET",
                    "http://h/x",
                    "h",
                    "/x",
                    200,
                    1L,
                    true,
                    null,
                    "RestClient",
                    Map.of(),
                    "main",
                    null,
                    request,
                    null);
            rest.recordNanos(
                    "GET",
                    "http://h/x",
                    "h",
                    "/x",
                    200,
                    1L,
                    true,
                    null,
                    "WebClient",
                    Map.of(),
                    "loop",
                    null,
                    request,
                    io.github.jdubois.bootui.spi.ThreadKind.EVENT_LOOP);
            cache.recordHit("caffeine", "owners", "k");
        } finally {
            CodePaths.exit(token);
        }
        recordSql(sql);

        assertThat(open[0]).isEqualTo(CodePaths.pack(CodePaths.stampSequence(open[0]), 0, repository));
        assertThat(published)
                .extracting(event -> io.github.jdubois.bootui.engine.codepaths.CodePathStamps.of(event))
                .containsExactly(open[0], open[0], 0L, open[0], 0L);
        claim.disarm();
    }

    private static void recordSql(io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder sql) {
        sql.recordNanos(
                io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder.StatementType.STATEMENT,
                io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder.Category.SELECT,
                "select * from owners",
                List.of(),
                1_000L,
                true,
                null,
                null,
                0,
                "c1",
                "main");
    }

    @Test
    void theHooksBoundARequestsFragmentAndMarkItsPhases() {
        AgentCodePaths.bind(CodePaths.class);
        AgentClaim claim = AgentClaim.claim(Bridges.access(), "shop", "shop@1", "dev", List.of("shop"));
        claim.attach(new AgentHandoffs(() -> CorrelationContext.forRequest("00000000000000ab"), null, null));
        int handler = CodeInventory.methodId("shop.OrderController#place()V");

        AgentCodePaths.begin();
        AgentCodePaths.phase(RequestPhase.FILTERS);
        AgentCodePaths.phase(RequestPhase.HANDLER);
        CodePaths.exit(CodePaths.enter(handler));
        AgentCodePaths.end();

        List<long[]> blobs = new ArrayList<>();
        claim.drainCodePaths(blobs::add);
        assertThat(AgentCodePaths.bound()).isTrue();
        assertThat(blobs).singleElement().satisfies(blob -> {
            assertThat(blob[CodePaths.H_FLAGS] & CodePaths.FLAG_BEGUN).isNotZero();
            assertThat(blob[CodePaths.H_REQUEST]).isEqualTo(0xabL);
            assertThat(blob[CodePaths.HEADER + CodePaths.N_PHASE]).isEqualTo(CodePaths.PHASE_HANDLER);
        });
        assertThat(AgentCodePaths.code(RequestPhase.RESPONSE)).isEqualTo(CodePaths.PHASE_RESPONSE);
        claim.disarm();
    }
}
