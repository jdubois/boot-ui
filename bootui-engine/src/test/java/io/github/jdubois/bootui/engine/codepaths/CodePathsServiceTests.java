package io.github.jdubois.bootui.engine.codepaths;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import io.github.jdubois.bootui.agent.bridge.CodeInventory;
import io.github.jdubois.bootui.agent.bridge.CodePaths;
import io.github.jdubois.bootui.core.dto.CodePathsAgentReport;
import io.github.jdubois.bootui.core.dto.CodePathsBeansReport;
import io.github.jdubois.bootui.core.dto.CodePathsCallsDto;
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
import io.github.jdubois.bootui.engine.journal.AgentEvidence;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
    private final Set<String> hiddenPanels = ConcurrentHashMap.newKeySet();
    private final AgentEvidence evidence = new AgentEvidence(panel -> !hiddenPanels.contains(panel), null);
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
        int controller = CodeInventory.methodId("shop.QuoteController#quote()I");
        for (int i = 1; i <= 3; i++) {
            request(String.format("%016x", i), () -> call(controller, CodePathsServiceTests::spin));
        }
        awaitFragments(3);
        assertThat(service.status()).containsEntry("fragments", 3L);
        clock.addAndGet(RequestTreeStore.SETTLE_NANOS);
        assertThat(service.report().routes()).isNotEmpty();
        assertThat(service.handlerMethods("GET /api/quote")).isNotNull();
        long fingerprint = service.routeTreesFingerprint();

        hiddenPanels.add(BootUiPanels.HTTP_EXCHANGES);

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
        assertThat(service.routeTreesFingerprint()).isNotEqualTo(fingerprint);
        assertThat(service.tree("0000000000000002")).isNull();
        assertThat(service.recent()).isEmpty();
        assertThat(service.exemplars("GET /api/quote")).isEmpty();

        hiddenPanels.remove(BootUiPanels.HTTP_EXCHANGES);

        assertThat(service.report().routes()).isNotEmpty();
        assertThat(service.exemplars("GET /api/quote")).isNotEmpty();
        assertThat(service.routeTreesFingerprint()).isNotZero();
    }

    /** Code Paths owns its trees: disabled, every read, Beans at runtime, and the issuing method say so (M5-11). */
    @Test
    void disablingCodePathsHidesEveryReadWithItsReasonAfterTheAgentsOwn() {
        start(AgentSensorSettings.defaults());
        service.setRequestOutcomes(ids -> {
            Map<String, RequestOutcome> named = new LinkedHashMap<>();
            for (String id : ids) {
                named.put(id, new RequestOutcome("GET /api/quote", 200, false));
            }
            return named;
        });
        int controller = CodeInventory.methodId("shop.QuoteController#quote()I");
        for (int i = 1; i <= 2; i++) {
            request(String.format("%016x", i), () -> call(controller, CodePathsServiceTests::spin));
        }
        awaitFragments(2);
        clock.addAndGet(RequestTreeStore.SETTLE_NANOS);
        assertThat(service.report().routes()).isNotEmpty();
        assertThat(service.methodKey(controller)).isNotNull();

        hiddenPanels.add(BootUiPanels.CODE_PATHS);

        String reason = "The Code Paths panel is disabled.";
        assertThat(service.report().unavailableReason()).isEqualTo(reason);
        assertThat(service.routeTree("GET /api/quote", null, null, null).unavailableReason())
                .isEqualTo(reason);
        assertThat(service.requestTree("0000000000000002").unavailableReason()).isEqualTo(reason);
        assertThat(service.agentReport(null, null).unavailableReason()).isEqualTo(reason);
        assertThat(service.beans().unavailableReason()).isEqualTo(reason);
        assertThat(service.handlerMethods("GET /api/quote")).isNull();
        assertThat(service.invocations()).isEmpty();
        assertThat(service.methodKey(controller)).isNull();
        assertThat(service.issuingMethod("GET /api/quote", controller)).isNull();
        assertThat(service.routeTreesFingerprint()).isZero();
        assertThat(evidence.status().stores()).singleElement().satisfies(store -> {
            assertThat(store.visible()).isFalse();
            assertThat(store.counts()).isEmpty();
        });

        hiddenPanels.remove(BootUiPanels.CODE_PATHS);
        assertThat(service.report().routes()).isNotEmpty();
    }

    /** Clear recording drops every request and route tree, keeps the counts, and says so (M5-11). */
    @Test
    void clearingTheRecordingDropsTheTreesAndKeepsTheCounts() {
        start(AgentSensorSettings.defaults());
        service.setRequestOutcomes(ids -> {
            Map<String, RequestOutcome> named = new LinkedHashMap<>();
            for (String id : ids) {
                named.put(id, new RequestOutcome("GET /api/quote", 200, false));
            }
            return named;
        });
        int controller = CodeInventory.methodId("shop.QuoteController#quote()I");
        for (int i = 1; i <= 2; i++) {
            request(String.format("%016x", i), () -> call(controller, CodePathsServiceTests::spin));
        }
        awaitFragments(2);
        clock.addAndGet(RequestTreeStore.SETTLE_NANOS);
        assertThat(service.report().routes()).isNotEmpty();
        long fingerprint = service.routeTreesFingerprint();
        assertThat(evidence.status().stores()).singleElement().satisfies(store -> {
            assertThat(store.retainedBytes()).isPositive();
            assertThat(store.counts()).containsEntry("requestTrees", 2L).containsEntry("routes", 1L);
        });

        assertThat(evidence.clear()).isEqualTo("2 request trees and 1 route tree of Code Paths");

        CodePathsReport report = service.report();
        assertThat(report.routes()).isEmpty();
        assertThat(report.limitations()).contains(CodePathsService.RECORDING_CLEARED);
        assertThat(report.status().fragments())
                .as("counts since the claim are kept")
                .isEqualTo(2L);
        assertThat(service.requestTree("0000000000000001").found()).isFalse();
        assertThat(service.invocations()).isEmpty();
        assertThat(service.routeTreesFingerprint()).isNotEqualTo(fingerprint);
        assertThat(service.status()).containsEntry("clearedTrees", 2L);

        request("0000000000000003", () -> call(controller, CodePathsServiceTests::spin));
        awaitFragments(3);
        clock.addAndGet(RequestTreeStore.SETTLE_NANOS);
        assertThat(service.report().routes())
                .singleElement()
                .satisfies(route -> assertThat(route.route()).isEqualTo("GET /api/quote"));
    }

    /**
     * M5-4c against the real bridge: a statement stamped where it ran, inside the service method, shows under that
     * method in the route tree and leaves its own time; the observed calls between classes map to beans; Beans at
     * runtime lists them beside the declared dependencies, with a declared dependency on a traced bean not called in
     * this run, and one on a bean the sensor does not instrument never counted as such.
     */
    @Test
    void stampedCallsShowUnderTheirMethodAndBeansAtRuntimeComparesCallsWithDeclarations() {
        claim = AgentClaim.claim(
                AgentBridgeAccess.bind(AgentBridge.class),
                "shop",
                "shop-owner",
                "dev",
                List.of("shop"),
                AgentSensorSettings.defaults(),
                List.of("shop.QuoteController", "shop.QuoteService", "shop.SlowPricingService", "shop.AuditService"));
        claim.attach(new AgentHandoffs(context::get, null, null));
        service = new CodePathsService(
                AgentBridgeAccess.bind(AgentBridge.class), () -> claim, () -> null, evidence, clock::get);
        service.start();
        Map<String, List<RequestOutcome.StampedCall>> stamped = new LinkedHashMap<>();
        service.setRequestOutcomes(ids -> {
            Map<String, RequestOutcome> named = new LinkedHashMap<>();
            for (String id : ids) {
                named.put(id, new RequestOutcome("GET /api/quote", 200, false, stamped.getOrDefault(id, List.of()), 1));
            }
            return named;
        });
        int controller = CodeInventory.methodId("shop.QuoteController#quote()I");
        int quotes = CodeInventory.methodId("shop.QuoteService#quote()I");
        int pricing = CodeInventory.methodId("shop.SlowPricingService#quote()I");
        for (int i = 1; i <= 3; i++) {
            String id = String.format("%016x", i);
            request(
                    id,
                    () -> call(
                            controller,
                            () -> call(quotes, () -> {
                                stamped.computeIfAbsent(id, ignored -> new java.util.ArrayList<>())
                                        .add(new RequestOutcome.StampedCall(
                                                CodePathStamps.SQL, CodePaths.stamp(), 100_000L));
                                call(pricing, CodePathsServiceTests::spin);
                            })));
        }
        clock.addAndGet(RequestTreeStore.SETTLE_NANOS);

        CodePathsRouteTreeReport tree = service.routeTree("GET /api/quote", null, null, null);
        CodePathsNodeDto quoteNode = tree.nodes().stream()
                .filter(node -> "shop.QuoteService#quote()I".equals(node.method()))
                .findFirst()
                .orElseThrow();
        assertThat(quoteNode.calls()).containsExactly(new CodePathsCallsDto("SQL", 1.0, 0.1));
        assertThat(tree.nodes())
                .filteredOn(node -> !"shop.QuoteService#quote()I".equals(node.method()))
                .allSatisfy(node -> assertThat(node.calls()).isEmpty());
        assertThat(tree.limitations())
                .contains(CodePathsService.LIMITATION_CALLS)
                .anyMatch(
                        limitation -> limitation.startsWith("2 recorded calls of the warm requests carried no stamp"));
        HandlerMethods handler = service.handlerMethods("GET /api/quote");
        assertThat(handler.stampedCalls()).isEqualTo(2);
        assertThat(handler.unstampedCalls()).isEqualTo(2);
        assertThat(service.methodKey(pricing)).isEqualTo("shop.SlowPricingService#quote()I");

        // The route's first request, kept apart from its warm tree, counts too.
        assertThat(service.invocations())
                .containsExactlyInAnyOrder(
                        new io.github.jdubois.bootui.engine.model.ClassInvocation(
                                "shop.QuoteController", "shop.QuoteService", 3),
                        new io.github.jdubois.bootui.engine.model.ClassInvocation(
                                "shop.QuoteService", "shop.SlowPricingService", 3));

        assertThat(service.beans().beansAvailable()).isFalse();
        service.setStructure(() -> new io.github.jdubois.bootui.engine.model.StructureSnapshot(
                null,
                List.of(),
                List.of(
                        bean("quoteController", "shop.QuoteController", "quoteService"),
                        bean(
                                "quoteService",
                                "shop.QuoteService$$SpringCGLIB$$0",
                                "slowPricingService",
                                "auditService",
                                "ownerRepository"),
                        bean("slowPricingService", "shop.SlowPricingService"),
                        bean("auditService", "shop.AuditService"),
                        // Declares the audit service, but its own class is not instrumented: no call of its is seen.
                        bean("legacyJob", "shop.LegacyJob", "auditService"),
                        new io.github.jdubois.bootui.engine.model.StructureSnapshot.Bean(
                                "ownerRepository", "jdk.proxy2.$Proxy91", true, List.of()))));
        CodePathsBeansReport beans = service.beans();
        assertThat(beans.available()).isTrue();
        assertThat(beans.beansAvailable()).isTrue();
        assertThat(beans.edges())
                .extracting(edge -> edge.from() + ">" + edge.to() + ":" + edge.declared() + ":" + edge.observed() + ":"
                        + edge.calls() + ":" + edge.observable())
                .containsExactly(
                        "quoteController>quoteService:true:true:3:true",
                        "quoteService>slowPricingService:true:true:3:true",
                        "quoteService>auditService:true:false:0:true",
                        "legacyJob>auditService:true:false:0:false",
                        "quoteService>ownerRepository:true:false:0:false");
        assertThat(beans.edges().get(2).unobservableReason()).isNull();
        assertThat(beans.edges().get(3).unobservableReason()).startsWith("The calling bean's class is not one");
        assertThat(beans.edges().get(4).unobservableReason()).startsWith("The called bean's class is not one");
        assertThat(beans.observedEdges()).isEqualTo(2);
        assertThat(beans.declaredEdges()).isEqualTo(5);
        assertThat(beans.notCalled())
                .as("only where both classes are instrumented")
                .isEqualTo(1);
        assertThat(beans.limitations()).contains(CodePathsService.LIMITATION_BEANS);
    }

    /**
     * I6: a statement stamped inside a repository method of the application's own, which the sensor instruments as a
     * bean, names the first method above it that is not a repository's, through the repository method; one stamped in
     * a service method names that method.
     */
    @Test
    void theIssuingMethodOfARepositoryMethodsStatementIsTheMethodThatCalledIt() {
        claim = AgentClaim.claim(
                AgentBridgeAccess.bind(AgentBridge.class),
                "shop",
                "shop-owner",
                "dev",
                List.of("shop"),
                AgentSensorSettings.defaults(),
                List.of("shop.OwnerController", "shop.OwnerService", "shop.OwnerStore", "shop.PetDao"));
        claim.attach(new AgentHandoffs(context::get, null, null));
        service = new CodePathsService(
                AgentBridgeAccess.bind(AgentBridge.class), () -> claim, () -> null, evidence, clock::get);
        service.start();
        Map<String, List<RequestOutcome.StampedCall>> stamped = new LinkedHashMap<>();
        service.setRequestOutcomes(ids -> {
            Map<String, RequestOutcome> named = new LinkedHashMap<>();
            for (String id : ids) {
                named.put(id, new RequestOutcome("GET /owners", 200, false, stamped.getOrDefault(id, List.of()), 0));
            }
            return named;
        });
        // OwnerStore is a repository by its bean, though not by its name; PetDao by its name.
        service.setStructure(() -> new io.github.jdubois.bootui.engine.model.StructureSnapshot(
                null,
                List.of(),
                List.of(new io.github.jdubois.bootui.engine.model.StructureSnapshot.Bean(
                        "ownerRepository", "shop.OwnerStore", true, List.of())),
                null));
        int controller = CodeInventory.methodId("shop.OwnerController#list()V");
        int owners = CodeInventory.methodId("shop.OwnerService#findAll()V");
        int store = CodeInventory.methodId("shop.OwnerStore#findPets()V");
        int pets = CodeInventory.methodId("shop.PetDao#byOwner()V");
        for (int i = 1; i <= 3; i++) {
            String id = String.format("%016x", i);
            request(
                    id,
                    () -> call(
                            controller,
                            () -> call(owners, () -> {
                                stamp(stamped, id);
                                call(
                                        store,
                                        () -> call(pets, () -> {
                                            stamp(stamped, id);
                                            stamp(stamped, id);
                                        }));
                            })));
        }
        clock.addAndGet(RequestTreeStore.SETTLE_NANOS);

        assertThat(service.issuingMethod("GET /owners", pets))
                .isEqualTo(new IssuingMethod("shop.OwnerService#findAll()V", "shop.PetDao#byOwner()V", false));
        assertThat(service.issuingMethod("GET /owners", owners))
                .isEqualTo(new IssuingMethod("shop.OwnerService#findAll()V", null, false));
        // Without the route's tree, the repository method itself, said to be one.
        assertThat(service.issuingMethod("GET /elsewhere", pets))
                .isEqualTo(new IssuingMethod("shop.PetDao#byOwner()V", null, true));
    }

    private static void stamp(Map<String, List<RequestOutcome.StampedCall>> stamped, String id) {
        stamped.computeIfAbsent(id, ignored -> new java.util.ArrayList<>())
                .add(new RequestOutcome.StampedCall(CodePathStamps.SQL, CodePaths.stamp(), 1_000L));
    }

    private static io.github.jdubois.bootui.engine.model.StructureSnapshot.Bean bean(
            String name, String type, String... dependencies) {
        return new io.github.jdubois.bootui.engine.model.StructureSnapshot.Bean(
                name, type, false, List.of(dependencies));
    }

    /**
     * M52-05 through the service: a request whose exchange the journal records only after its tree settled, as a slow
     * WebFlux response's, is in no route while Code Paths is read, then joins its route once the exchange arrives.
     */
    @Test
    void aSlowResponsesTreeJoinsItsRouteWhenItsExchangeArrivesAfterReads() {
        start(AgentSensorSettings.defaults());
        Map<String, RequestOutcome> journal = new LinkedHashMap<>();
        service.setRequestOutcomes(ids -> {
            Map<String, RequestOutcome> named = new LinkedHashMap<>();
            for (String id : ids) {
                if (journal.containsKey(id)) {
                    named.put(id, journal.get(id));
                }
            }
            return named;
        });
        service.setAssemblyOnly(CodePathsService.EVERY_REQUEST);
        int controller = CodeInventory.methodId("shop.StreamController#prices()V");
        request("0000000000000001", () -> call(controller, null));
        request("0000000000000002", () -> call(controller, null));

        clock.addAndGet(RequestTreeStore.SETTLE_NANOS);
        assertThat(service.report().routes()).isEmpty();
        clock.addAndGet(RequestTreeStore.RESOLVE_EVERY_NANOS);
        assertThat(service.report().routes()).isEmpty();
        assertThat(service.status()).containsEntry("treesWaitingForExchange", 2);

        journal.put("0000000000000001", new RequestOutcome("GET /api/stream", 200, false));
        journal.put("0000000000000002", new RequestOutcome("GET /api/stream", 200, false));
        clock.addAndGet(RequestTreeStore.RESOLVE_EVERY_NANOS);
        CodePathsReport report = service.report();
        assertThat(report.routes()).singleElement().satisfies(route -> {
            assertThat(route.route()).isEqualTo("GET /api/stream");
            assertThat(route.assemblyOnly()).isTrue();
            assertThat(route.warmRequests()).isEqualTo(1);
        });
        assertThat(service.status()).containsEntry("treesWaitingForExchange", 0).containsEntry("treesNamedLate", 2L);
    }

    @Test
    void withoutTheSensorEveryReadAnswersItsUnavailableShape() {
        service = new CodePathsService(
                AgentBridgeAccess.absent(),
                () -> null,
                () -> "Requires the BootUI agent's code-paths sensor.",
                AgentEvidence.open());
        assertThat(service.report().available()).isFalse();
        assertThat(service.report().unavailableReason()).startsWith("Requires the BootUI agent's code-paths sensor");
        assertThat(service.routeTree("GET /", null, null, null).available()).isFalse();
        assertThat(service.requestTree("0000000000000001").available()).isFalse();
        assertThat(service.agentReport(null, null).available()).isFalse();
        assertThat(service.handlerMethods("GET /")).isNull();
        assertThat(service.routeTreesFingerprint()).isZero();
        assertThat(service.beans().available()).isFalse();
        assertThat(service.beans().unavailableReason()).startsWith("Requires the BootUI agent's code-paths sensor");
        assertThat(service.invocations()).isEmpty();
        assertThat(service.methodKey(1)).isNull();
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

    /**
     * Waits until the run has taken {@code count} fragments: the drain thread may have taken them off the bridge before
     * a read's drain, and must add them before the clock moves, or they would settle only later.
     */
    private void awaitFragments(long count) {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (((Number) service.status().get("fragments")).longValue() < count) {
            assertThat(System.nanoTime()).as("fragments drained in time").isLessThan(deadline);
            Thread.onSpinWait();
            service.tree("0000000000000001");
        }
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
        service = new CodePathsService(
                AgentBridgeAccess.bind(AgentBridge.class), () -> claim, () -> null, evidence, clock::get);
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
