package io.github.jdubois.bootui.engine.codepaths;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import io.github.jdubois.bootui.agent.bridge.CodeInventory;
import io.github.jdubois.bootui.agent.bridge.CodePaths;
import io.github.jdubois.bootui.core.dto.CodePathsAgentReport;
import io.github.jdubois.bootui.core.dto.CodePathsNodeDto;
import io.github.jdubois.bootui.core.dto.CodePathsReport;
import io.github.jdubois.bootui.core.dto.CodePathsRequestTreeReport;
import io.github.jdubois.bootui.core.dto.CodePathsRouteDto;
import io.github.jdubois.bootui.core.dto.CodePathsRouteTreeReport;
import io.github.jdubois.bootui.engine.javaagent.AgentBridgeAccess;
import io.github.jdubois.bootui.engine.javaagent.AgentClaim;
import io.github.jdubois.bootui.engine.javaagent.AgentHandoffs;
import io.github.jdubois.bootui.engine.javaagent.AgentRecordDrainer;
import io.github.jdubois.bootui.engine.javaagent.AgentSensorSettings;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Code Paths against the real bridge class from the test class path, driven as the inlined advice would drive it: one
 * {@code CodePaths.enter} and {@code exit} per instrumented call, owned through the claim's handoffs.
 */
class CodePathsServiceTests {

    private static final String REQUEST = "00000000000000ab";

    private final AtomicReference<CorrelationContext> context = new AtomicReference<>(CorrelationContext.NONE);
    private final AtomicLong clock = new AtomicLong();
    private CodePathsService service;
    private AgentClaim claim;

    @BeforeEach
    void installAgent() {
        resetBridge();
        AgentBridge.install(request -> {
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("status", "ok");
            return answer;
        });
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
    void aRequestsFragmentsBecomeItsTree() {
        start(AgentSensorSettings.defaults());
        int controller = CodeInventory.methodId("shop.OrderController#place()V");
        int service = CodeInventory.methodId("shop.OrderService#price()I");

        context.set(CorrelationContext.forRequest(REQUEST));
        call(controller, () -> call(service, null));
        context.set(CorrelationContext.NONE);

        RequestTree tree = this.service.tree(REQUEST);
        assertThat(tree).isNotNull();
        assertThat(tree.method()).containsExactly(RequestTree.REQUEST, controller, service);
        assertThat(tree.parent()).containsExactly(-1, 0, 1);
        assertThat(this.service.status()).containsEntry("fragments", 1L).containsEntry("openTrees", 1);

        clock.addAndGet(RequestTreeStore.SETTLE_NANOS);
        assertThat(this.service.recent()).extracting(RequestTree::requestId).containsExactly(REQUEST);
    }

    @Test
    void aFrequentCheapMethodIsExcludedThroughTheBridge() {
        start(AgentSensorSettings.defaults());
        int controller = CodeInventory.methodId("shop.OrderController#list()V");
        int getter = CodeInventory.methodId("shop.Order#getId()J");
        context.set(CorrelationContext.forRequest(REQUEST));
        // Advice calls take far longer than 2 µs here only when the JVM is cold: 60,000 calls of an empty getter.
        call(controller, () -> {
            for (int i = 0; i < 60_000; i++) {
                call(getter, null);
            }
        });
        context.set(CorrelationContext.NONE);
        service.tree(REQUEST);

        // The window ends a second later, at the next fragment.
        clock.addAndGet(1_000_000_000L);
        context.set(CorrelationContext.forRequest(REQUEST));
        call(controller, null);
        context.set(CorrelationContext.NONE);
        service.tree(REQUEST);

        assertThat(CodePaths.EXCLUDED[getter]).isEqualTo((byte) 1);
        assertThat(CodePaths.EXCLUDED[controller]).isZero();
        assertThat(service.excludedMethods()).containsExactly("shop.Order#getId()J");
    }

    @Test
    void anotherGenerationsFragmentsAreDroppedAndCounted() {
        start(AgentSensorSettings.defaults());
        long[] stale = Blobs.request(claim.generation() - 1, 1L)
                .between(0L, 1L)
                .node(-1, 1, 0, 1L, 1L, 0L)
                .blob();
        AgentRecordDrainer drainer = claim.drainer();
        assertThat(drainer).isNotNull();

        // Routed exactly as the drainer routes a blob.
        service.tree(REQUEST);
        java.util.function.Consumer<long[]> route = routeOf(drainer);
        route.accept(stale);
        route.accept(new long[] {42L});

        assertThat(service.status()).containsEntry("staleFragments", 1L).containsEntry("malformedFragments", 1L);
    }

    /**
     * M5-4b: settled trees merge into their route's tree, the first recorded apart, and every read answers from it: the
     * summary, the route's tree with callers and reach, a request's tree, the agent view, and the handler's methods.
     */
    @Test
    void settledTreesBuildRouteTreesThatEveryReadAnswersFrom() {
        start(AgentSensorSettings.defaults());
        service.setRequestOutcomes(ids -> {
            Map<String, RequestOutcome> named = new LinkedHashMap<>();
            for (String id : ids) {
                named.put(id, new RequestOutcome(id.endsWith("9") ? "GET /api/other" : "GET /api/quote", 200, false));
            }
            return named;
        });
        service.setAssemblyOnly(id -> id.endsWith("9"));
        int controller = CodeInventory.methodId("shop.QuoteController#quote()I");
        int pricing = CodeInventory.methodId("shop.SlowPricingService#quote()I");
        for (int i = 1; i <= 4; i++) {
            request(
                    String.format("%016x", i),
                    () -> call(controller, () -> call(pricing, CodePathsServiceTests::spin)));
        }
        request("0000000000000009", () -> call(pricing, null));
        request("0000000000000019", () -> call(pricing, null));
        clock.addAndGet(RequestTreeStore.SETTLE_NANOS);

        CodePathsReport report = service.report();
        assertThat(report.available()).isTrue();
        assertThat(report.status().settledTrees()).isEqualTo(6);
        assertThat(report.status().routes()).isEqualTo(2);
        CodePathsRouteDto quote = report.routes().stream()
                .filter(route -> route.route().equals("GET /api/quote"))
                .findFirst()
                .orElseThrow();
        assertThat(quote.warmRequests()).as("the first recorded is apart").isEqualTo(3);
        assertThat(quote.firstRequestMillis()).isNotNull();
        assertThat(quote.assemblyOnly()).isFalse();
        assertThat(quote.topMethods().get(0).method()).isEqualTo("shop.SlowPricingService#quote()I");
        assertThat(quote.topMethods().get(0).methodName()).isEqualTo("quote");
        assertThat(report.routes())
                .filteredOn(route -> route.route().equals("GET /api/other"))
                .singleElement()
                .satisfies(route -> assertThat(route.assemblyOnly()).isTrue());
        assertThat(report.limitations()).anyMatch(limitation -> limitation.contains("assembly only"));

        CodePathsRouteTreeReport tree = service.routeTree("GET /api/quote", null, null, null);
        assertThat(tree.found()).isTrue();
        assertThat(tree.shareOf()).isEqualTo("handler");
        assertThat(tree.firstRequestId()).isEqualTo("0000000000000001");
        assertThat(tree.firstRequestMillis()).isNotNull();
        assertThat(tree.limitations()).contains(CodePathsService.LIMITATION_PERCENTILES);
        assertThat(tree.nodes()).extracting(CodePathsNodeDto::kind).containsExactly("REQUEST", "METHOD", "METHOD");
        CodePathsNodeDto slow = tree.nodes().get(2);
        assertThat(slow.method()).isEqualTo("shop.SlowPricingService#quote()I");
        assertThat(slow.phase()).isEqualTo("HANDLER");
        assertThat(slow.requests()).isEqualTo(3);
        assertThat(slow.callsPerRequest()).isEqualTo(1.0);
        assertThat(slow.p50Millis()).isNotNull();
        assertThat(tree.methods())
                .filteredOn(method -> method.method().equals("shop.SlowPricingService#quote()I"))
                .singleElement()
                .satisfies(method -> {
                    assertThat(method.callers()).containsExactly("shop.QuoteController#quote()I");
                    assertThat(method.routes()).containsExactlyInAnyOrder("GET /api/quote", "GET /api/other");
                });
        assertThat(tree.page().matched()).isEqualTo(3);
        CodePathsRouteTreeReport shallow = service.routeTree("GET /api/quote", 1, 0, 1);
        assertThat(shallow.nodes()).hasSize(1);
        assertThat(shallow.page().hasMore()).isTrue();
        assertThat(service.routeTree("GET /nothing", null, null, null).found()).isFalse();

        CodePathsRequestTreeReport request = service.requestTree("0000000000000002");
        assertThat(request.found()).isTrue();
        assertThat(request.route()).isEqualTo("GET /api/quote");
        assertThat(request.topMethods()).extracting(top -> top.method()).startsWith("shop.SlowPricingService#quote()I");
        assertThat(service.requestTree("00000000000000ff").found()).isFalse();

        CodePathsAgentReport agent = service.agentReport("quote", null);
        assertThat(agent.matched()).as("both routes name the method").isEqualTo(2);
        CodePathsAgentReport exact = service.agentReport("GET /api/quote", 1);
        assertThat(exact.routes()).hasSize(1);
        assertThat(exact.hottestNodes())
                .singleElement()
                .satisfies(node -> assertThat(node.method()).isEqualTo("shop.SlowPricingService#quote()I"));
        assertThat(exact.limitations()).contains(CodePathsService.LIMITATION_PERCENTILES);

        HandlerMethods handler = service.handlerMethods("GET /api/quote");
        assertThat(handler.requests()).isEqualTo(3);
        assertThat(handler.assemblyOnly()).isFalse();
        assertThat(handler.methods().get(0).label()).isEqualTo("SlowPricingService.quote");
        assertThat(handler.handlerNanos()).isPositive();
        assertThat(service.handlerMethods("GET /api/other").assemblyOnly()).isTrue();
        long fingerprint = service.routeTreesFingerprint();
        request("0000000000000005", () -> call(controller, null));
        clock.addAndGet(RequestTreeStore.SETTLE_NANOS);
        assertThat(service.routeTreesFingerprint()).isNotEqualTo(fingerprint);
    }

    /** Spring WebFlux installs {@link CodePathsService#EVERY_REQUEST} once: every request's tree is assembly only. */
    @Test
    void aStackWhoseHandlersAllAssembleMarksEveryRequestOnce() {
        start(AgentSensorSettings.defaults());
        assertThat(service.isAssemblyOnly("0000000000000001")).isFalse();
        service.setAssemblyOnly(CodePathsService.EVERY_REQUEST);
        assertThat(service.isAssemblyOnly("0000000000000001")).isTrue();
        assertThat(service.isAssemblyOnly("00000000000000ff")).isTrue();
        service.setAssemblyOnly(id -> {
            throw new IllegalStateException("boom");
        });
        assertThat(service.isAssemblyOnly("0000000000000001"))
                .as("never throws")
                .isFalse();
    }

    /** HTTP Exchanges owns the route trees' routes and outcomes: disabled, every read hides the trees it retained. */
    @Test
    void disablingHttpExchangesHidesTheRouteTreesAlreadyRetained() {
        start(AgentSensorSettings.defaults());
        service.setRequestOutcomes(ids -> {
            Map<String, RequestOutcome> named = new LinkedHashMap<>();
            for (String id : ids) {
                named.put(id, new RequestOutcome("GET /api/quote", 500, true));
            }
            return named;
        });
        boolean[] httpExchanges = {true};
        service.setRoutesVisible(() -> httpExchanges[0]);
        int controller = CodeInventory.methodId("shop.QuoteController#quote()I");
        for (int i = 1; i <= 3; i++) {
            request(String.format("%016x", i), () -> call(controller, CodePathsServiceTests::spin));
        }
        clock.addAndGet(RequestTreeStore.SETTLE_NANOS);
        assertThat(service.report().routes()).isNotEmpty();
        assertThat(service.handlerMethods("GET /api/quote")).isNotNull();

        httpExchanges[0] = false;

        assertThat(service.report().available()).isFalse();
        assertThat(service.report().unavailableReason()).isEqualTo(CodePathsService.ROUTES_HIDDEN);
        assertThat(service.report().routes()).isEmpty();
        assertThat(service.routeTree("GET /api/quote", null, null, null).found())
                .isFalse();
        assertThat(service.requestTree("0000000000000002").route()).isNull();
        assertThat(service.requestTree("0000000000000002").found()).isFalse();
        assertThat(service.agentReport(null, null).routes()).isEmpty();
        assertThat(service.agentReport(null, null).unavailableReason()).isEqualTo(CodePathsService.ROUTES_HIDDEN);
        assertThat(service.handlerMethods("GET /api/quote")).isNull();
        assertThat(service.routeTreesFingerprint()).isZero();
        assertThat(service.tree("0000000000000002")).isNull();
        assertThat(service.recent()).isEmpty();
        assertThat(service.exemplars("GET /api/quote")).isEmpty();

        httpExchanges[0] = true;

        assertThat(service.report().routes()).isNotEmpty();
        assertThat(service.exemplars("GET /api/quote")).isNotEmpty();
        assertThat(service.routeTreesFingerprint()).isNotZero();
    }

    @Test
    void withoutTheSensorEveryReadAnswersItsUnavailableShape() {
        service = new CodePathsService(
                AgentBridgeAccess.absent(), () -> null, () -> "Requires the BootUI agent's code-paths sensor.");
        assertThat(service.report().available()).isFalse();
        assertThat(service.report().unavailableReason()).startsWith("Requires the BootUI agent's code-paths sensor");
        assertThat(service.routeTree("GET /", null, null, null).available()).isFalse();
        assertThat(service.requestTree("0000000000000001").available()).isFalse();
        assertThat(service.agentReport(null, null).available()).isFalse();
        assertThat(service.handlerMethods("GET /")).isNull();
        assertThat(service.routeTreesFingerprint()).isZero();
    }

    private void request(String requestId, Runnable body) {
        context.set(CorrelationContext.forRequest(requestId));
        CodePaths.phase(2);
        try {
            body.run();
        } finally {
            CodePaths.phase(0);
            context.set(CorrelationContext.NONE);
        }
        service.tree(requestId);
    }

    private static void spin() {
        long until = System.nanoTime() + 200_000L;
        while (System.nanoTime() < until) {
            Thread.onSpinWait();
        }
    }

    @Test
    void withoutTheSensorInTheClaimNothingIsRouted() {
        start(new AgentSensorSettings(List.of("executors", "inventory"), List.of(), List.of(), null));

        assertThat(service.status()).containsEntry("generation", null);
        assertThat(claim.drainer().running()).isFalse();
    }

    @Test
    void closingTheServiceStopsTheDrainerAndDisarmingClosesIt() {
        start(AgentSensorSettings.defaults());
        AgentRecordDrainer drainer = claim.drainer();
        assertThat(drainer.running()).isTrue();

        service.close();
        assertThat(drainer.running()).as("its last route is gone").isFalse();

        claim.disarm();
        assertThat(drainer.closed()).isTrue();
        assertThat(claim.drainer()).isNull();
        assertThat(Thread.getAllStackTraces().keySet())
                .noneMatch(thread -> thread.getName().equals(AgentRecordDrainer.THREAD_NAME) && thread.isAlive());
    }

    private void start(AgentSensorSettings sensors) {
        claim = AgentClaim.claim(
                AgentBridgeAccess.bind(AgentBridge.class), "shop", "shop-owner", "dev", List.of("shop"), sensors);
        claim.attach(new AgentHandoffs(context::get, null, null));
        service = new CodePathsService(AgentBridgeAccess.bind(AgentBridge.class), () -> claim, () -> null, clock::get);
        service.start();
    }

    @SuppressWarnings("unchecked")
    private static java.util.function.Consumer<long[]> routeOf(AgentRecordDrainer drainer) {
        try {
            java.lang.reflect.Field field = AgentRecordDrainer.class.getDeclaredField("codePathsRoute");
            field.setAccessible(true);
            return (java.util.function.Consumer<long[]>) field.get(drainer);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void call(int id, Runnable body) {
        int token = CodePaths.enter(id);
        try {
            if (body != null) {
                body.run();
            }
        } finally {
            CodePaths.exit(token);
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
