package io.github.jdubois.bootui.engine.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.jdubois.bootui.core.dto.CodeInventoryMethodDto;
import io.github.jdubois.bootui.core.dto.RuntimeChangeImpactDto;
import io.github.jdubois.bootui.core.dto.RuntimeImpactRouteDto;
import io.github.jdubois.bootui.core.dto.RuntimeImpactSymbolDto;
import io.github.jdubois.bootui.core.dto.RuntimeImpactSymbolsDto;
import io.github.jdubois.bootui.engine.codepaths.MethodRoutes;
import io.github.jdubois.bootui.engine.codepaths.TracedMethods;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.inventory.ClassScanner;
import io.github.jdubois.bootui.engine.inventory.CodeInventoryService;
import io.github.jdubois.bootui.engine.journal.AuthorizationPayload;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalAggregates;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.model.RuntimeModelService;
import io.github.jdubois.bootui.engine.model.StructureSnapshot;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ChangeImpactServiceTests {

    private final RuntimeJournal journal = new RuntimeJournal(
            new RuntimeJournalSettings(true, 10_000, 50_000_000, 10_000, 10, 10, JournalSource.all()),
            RunIdentity.start());
    private final JournalAggregates aggregates = new JournalAggregates();
    private int requests;

    @AfterEach
    void close() {
        journal.close();
    }

    @Test
    void changingARepositoryListsItsObservedRoutesItsUnexercisedRouteAndARouteSharingItsTable() throws Exception {
        journal.addListener(aggregates);
        request("/api/products", "select * from sample_products");
        request("/api/products", "select * from sample_products");
        request("/api/reviews", "select * from sample_products p join sample_reviews r on r.product_id = p.id");

        RuntimeChangeImpactDto impact = service(structure(null)).impact("ProductRepository");

        assertThat(impact.status()).isEqualTo(ChangeImpactService.RESOLVED);
        assertThat(impact.node()).isEqualTo("REPOSITORY productRepository");
        assertThat(impact.structuralReach())
                .as("the service, the controller, and its two routes")
                .isEqualTo(4);
        assertThat(impact.observed()).singleElement().satisfies(route -> {
            assertThat(route.route()).isEqualTo("GET /api/products");
            assertThat(route.requests()).isEqualTo(2);
            assertThat(route.exemplarRequestIds()).containsExactly("r2", "r1");
            assertThat(route.reads()).containsExactly("TABLE sample_products");
        });
        assertThat(impact.notExercised())
                .extracting(RuntimeImpactRouteDto::route, RuntimeImpactRouteDto::check)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        "GET /api/products/{id}",
                        "Exercise `GET /api/products/{id}` before relying on this change: no request reached it in"
                                + " this run."));
        assertThat(impact.sharedResources()).singleElement().satisfies(route -> {
            assertThat(route.route()).isEqualTo("GET /api/reviews");
            assertThat(route.shared()).containsExactly("TABLE sample_products");
        });
        assertThat(impact.limitations())
                .anySatisfy(
                        limitation -> assertThat(limitation).contains("does not prove that a request went through it"));
    }

    @Test
    void impactListsDmlReadSourcesAsReadsAndOnlyTheTargetAsAWritingTable() throws Exception {
        journal.addListener(aggregates);
        request("/api/products", "insert into audit_log select id from sample_products");

        RuntimeChangeImpactDto impact = service(structure(null)).impact("GET /api/products");

        assertThat(impact.observed()).singleElement().satisfies(route -> {
            assertThat(route.reads()).contains("TABLE sample_products");
            assertThat(route.writes()).containsExactly("TABLE audit_log");
        });
    }

    @Test
    void aTableResolvesToTheRoutesThatAccessedItAndAmbiguousOrUnknownSymbolsAreNeverGuessed() throws Exception {
        journal.addListener(aggregates);
        request("/api/products", "select * from sample_products");
        request("/api/reviews", "select * from sample_products p join sample_reviews r on r.product_id = p.id");

        RuntimeChangeImpactDto table = service(structure(null)).impact("sample_products");
        assertThat(table.node()).isEqualTo("TABLE sample_products");
        assertThat(table.observed())
                .extracting(RuntimeImpactRouteDto::route)
                .containsExactly("GET /api/products", "GET /api/reviews");
        assertThat(table.sharedResources()).isEmpty();

        RuntimeChangeImpactDto ambiguous = service(structure(null)).impact("Mapper");
        assertThat(ambiguous.status()).isEqualTo(ChangeImpactService.AMBIGUOUS);
        assertThat(ambiguous.candidates()).containsExactlyInAnyOrder("BEAN orderMapper", "BEAN productMapper");
        assertThat(ambiguous.observed()).isEmpty();

        assertThat(service(structure(null)).impact("NoSuchBean").status()).isEqualTo(ChangeImpactService.NOT_FOUND);
        assertThat(service(structure("Beans unreadable.")).impact("NoSuchBean")).satisfies(impact -> {
            assertThat(impact.status()).isEqualTo(ChangeImpactService.UNAVAILABLE);
            assertThat(impact.reason()).isEqualTo("Beans unreadable.");
        });
        assertThat(new ChangeImpactService(null, null, null, null, panel -> true)
                        .impact("x")
                        .status())
                .isEqualTo(ChangeImpactService.UNAVAILABLE);
    }

    @Test
    void aRouteIsItsOwnImpactWithTheRoutesSharingWhatItTouchedAndATypedSymbolNamesOneNode() throws Exception {
        journal.addListener(aggregates);
        request("/api/products", "select * from sample_products");
        request("/api/reviews", "select * from sample_products p join sample_reviews r on r.product_id = p.id");

        RuntimeChangeImpactDto route = service(structure(null)).impact("GET /api/products");
        assertThat(route.status()).isEqualTo(ChangeImpactService.RESOLVED);
        assertThat(route.node()).isEqualTo("ROUTE GET /api/products");
        assertThat(route.structuralReach()).isZero();
        assertThat(route.observed()).extracting(RuntimeImpactRouteDto::route).containsExactly("GET /api/products");
        assertThat(route.sharedResources()).singleElement().satisfies(shared -> {
            assertThat(shared.route()).isEqualTo("GET /api/reviews");
            assertThat(shared.shared()).containsExactly("TABLE sample_products");
        });
        assertThat(route.limitations())
                .anySatisfy(
                        limitation -> assertThat(limitation).contains("A route reaches no other route through code"));

        RuntimeChangeImpactDto unexercised = service(structure(null)).impact("ROUTE GET /api/products/{id}");
        assertThat(unexercised.node()).isEqualTo("ROUTE GET /api/products/{id}");
        assertThat(unexercised.observed()).isEmpty();
        assertThat(unexercised.notExercised())
                .extracting(RuntimeImpactRouteDto::route)
                .containsExactly("GET /api/products/{id}");

        RuntimeChangeImpactDto typed = service(structure(null)).impact("BEAN orderMapper");
        assertThat(typed.status()).isEqualTo(ChangeImpactService.RESOLVED);
        assertThat(typed.node()).isEqualTo("BEAN orderMapper");
    }

    @Test
    void symbolsSuggestWhatTheImpactCanCheckBestMatchesFirst() throws Exception {
        journal.addListener(aggregates);
        request("/api/products", "select * from sample_products");
        request("/api/reviews", "select * from sample_reviews");
        ChangeImpactService service = service(structure(null));

        RuntimeImpactSymbolsDto product = service.symbols(" product ");
        assertThat(product.available()).isTrue();
        assertThat(product.query()).isEqualTo("product");
        assertThat(product.symbols())
                .extracting(RuntimeImpactSymbolDto::kind, RuntimeImpactSymbolDto::name)
                .containsExactly(
                        tuple("BEAN", "productController"),
                        tuple("BEAN", "productMapper"),
                        tuple("BEAN", "productService"),
                        tuple("REPOSITORY", "productRepository"),
                        tuple("ROUTE", "GET /api/products"),
                        tuple("ROUTE", "GET /api/products/{id}"),
                        tuple("TABLE", "sample_products"));
        assertThat(product.total()).isEqualTo(7);
        assertThat(product.symbols().get(3).type()).isEqualTo("com.example.ProductRepository");
        assertThat(product.symbols().get(4).type()).isNull();

        assertThat(service.symbols("/api/rev").symbols())
                .extracting(RuntimeImpactSymbolDto::name)
                .containsExactly("GET /api/reviews");
        assertThat(service.symbols("Mapper").symbols())
                .extracting(RuntimeImpactSymbolDto::name)
                .containsExactly("orderMapper", "productMapper");
        assertThat(service.symbols("nothing-matches")).satisfies(none -> {
            assertThat(none.symbols()).isEmpty();
            assertThat(none.total()).isZero();
        });
        assertThat(service.symbols("").total()).isGreaterThan(RuntimeImpactSymbolsDto.MAX_SYMBOLS / 2);
        for (RuntimeImpactSymbolDto symbol : product.symbols()) {
            assertThat(service.impact(symbol.kind() + " " + symbol.name()).status())
                    .as("%s %s resolves", symbol.kind(), symbol.name())
                    .isEqualTo(ChangeImpactService.RESOLVED);
        }

        RuntimeImpactSymbolsDto disabled = new ChangeImpactService(null, null, null, null, panel -> true).symbols("x");
        assertThat(disabled.available()).isFalse();
        assertThat(disabled.unavailableReason()).contains("bootui.runtime-journal.enabled");
        assertThat(disabled.symbols()).isEmpty();
    }

    @Test
    void aHandlerMethodNarrowsTheImpactToTheRoutesMappedToItWhileItsClassStillChecksTheWholeBean() throws Exception {
        journal.addListener(aggregates);
        request("/api/products", "select * from sample_products");
        request("/api/products", "select * from sample_products");
        request("/api/products/{id}", "select * from sample_products where id = ?");
        ChangeImpactService service = service(structure(null));

        RuntimeChangeImpactDto list = service.impact("ProductController#list");
        assertThat(list.status()).isEqualTo(ChangeImpactService.RESOLVED);
        assertThat(list.node()).isEqualTo("METHOD com.example.ProductController#list");
        assertThat(list.structuralReach()).as("only the route mapped to list").isEqualTo(1);
        assertThat(list.observed()).singleElement().satisfies(route -> {
            assertThat(route.route()).isEqualTo("GET /api/products");
            assertThat(route.requests()).isEqualTo(2);
        });
        assertThat(list.notExercised()).isEmpty();
        assertThat(list.sharedResources())
                .as("the controller's other method is outside the method's reach, so it shares the table")
                .singleElement()
                .satisfies(route -> {
                    assertThat(route.route()).isEqualTo("GET /api/products/{id}");
                    assertThat(route.shared()).containsExactly("TABLE sample_products");
                });
        assertThat(list.limitations())
                .anySatisfy(limitation -> assertThat(limitation).contains("mapped to it as their handler"));

        for (String same : List.of(
                "com.example.ProductController#list",
                "com.example.ProductController#list(Pageable)",
                "METHOD com.example.ProductController#list")) {
            assertThat(service.impact(same)).satisfies(impact -> {
                assertThat(impact.status()).as(same).isEqualTo(ChangeImpactService.RESOLVED);
                assertThat(impact.node()).as(same).isEqualTo("METHOD com.example.ProductController#list");
                assertThat(impact.observed())
                        .extracting(RuntimeImpactRouteDto::route)
                        .containsExactly("GET /api/products");
            });
        }

        RuntimeChangeImpactDto bean = service.impact("ProductController");
        assertThat(bean.node()).isEqualTo("BEAN productController");
        assertThat(bean.structuralReach()).isEqualTo(2);
        assertThat(bean.observed())
                .extracting(RuntimeImpactRouteDto::route)
                .containsExactly("GET /api/products", "GET /api/products/{id}");
        assertThat(bean.sharedResources()).isEmpty();
    }

    @Test
    void anUnmappedMethodIsNeverGuessedAndAMethodOfClassesSharingASimpleNameIsAmbiguous() throws Exception {
        journal.addListener(aggregates);
        request("/api/products", "select * from sample_products");

        ChangeImpactService service = service(structure(null));
        assertThat(service.impact("ProductController#get")).satisfies(get -> {
            assertThat(get.status()).isEqualTo(ChangeImpactService.RESOLVED);
            assertThat(get.observed()).isEmpty();
            assertThat(get.notExercised())
                    .extracting(RuntimeImpactRouteDto::route)
                    .containsExactly("GET /api/products/{id}");
        });
        for (String unknown : List.of("ProductController#save", "ProductService#list", "Product#list")) {
            assertThat(service.impact(unknown)).satisfies(impact -> {
                assertThat(impact.status()).as(unknown).isEqualTo(ChangeImpactService.NOT_FOUND);
                assertThat(impact.reason()).as(unknown).contains("name its class to check the whole bean");
                assertThat(impact.observed()).isEmpty();
            });
        }

        ChangeImpactService twoPackages = service(new StructureSnapshot(
                null,
                List.of(
                        new StructureSnapshot.RouteHandler(
                                "GET /a/products", "com.example.a.ProductController", "list"),
                        new StructureSnapshot.RouteHandler(
                                "GET /b/products", "com.example.b.ProductController", "list")),
                List.of()));
        RuntimeChangeImpactDto ambiguous = twoPackages.impact("ProductController#list");
        assertThat(ambiguous.status()).isEqualTo(ChangeImpactService.AMBIGUOUS);
        assertThat(ambiguous.candidates())
                .containsExactly(
                        "METHOD com.example.a.ProductController#list", "METHOD com.example.b.ProductController#list");
        assertThat(twoPackages.impact(ambiguous.candidates().get(1))).satisfies(chosen -> {
            assertThat(chosen.status()).isEqualTo(ChangeImpactService.RESOLVED);
            assertThat(chosen.structuralReach()).isEqualTo(1);
            assertThat(chosen.notExercised())
                    .extracting(RuntimeImpactRouteDto::route)
                    .containsExactly("GET /b/products");
        });
    }

    @Test
    void routeTrafficSurvivesEvictionEvenWhenItsExemplarAndModelExecutionDoNot() throws Exception {
        RuntimeJournal shortJournal = new RuntimeJournal(
                new RuntimeJournalSettings(true, 1, 50_000_000, 10, 0, 0, JournalSource.all()), RunIdentity.start());
        JournalAggregates wholeRun = new JournalAggregates();
        try {
            shortJournal.addListener(wholeRun);
            for (String path : List.of("/api/products", "/api/reviews")) {
                String id = "eviction-" + path;
                shortJournal.offer(RuntimeEvent.of(
                        JournalSource.HTTP,
                        1_000,
                        1_000,
                        CorrelationContext.forRequest(id),
                        "http-1",
                        null,
                        false,
                        new HttpPayload("GET", path, path, null, 200)));
                assertThat(shortJournal.awaitDrained(Duration.ofSeconds(5))).isTrue();
            }
            RuntimeModelService models = new RuntimeModelService(shortJournal, null, runId -> structure(null));
            RuntimeChangeImpactDto impact = new ChangeImpactService(shortJournal, wholeRun, models, null, panel -> true)
                    .impact("GET /api/products");
            assertThat(impact.observed()).singleElement().satisfies(route -> {
                assertThat(route.requests()).isEqualTo(1);
                assertThat(route.exemplarRequestIds()).isEmpty();
            });
            assertThat(impact.notExercised()).isEmpty();
        } finally {
            shortJournal.close();
        }
    }

    @Test
    void routeOverflowReportsUnknownRoutesInsteadOfClaimingEveryMappedRouteRan() throws Exception {
        journal.addListener(aggregates);
        for (int i = 0; i <= JournalAggregates.MAX_ROUTES; i++) {
            String path = "/overflow/" + i;
            journal.offer(RuntimeEvent.of(
                    JournalSource.HTTP,
                    1_000,
                    1_000,
                    CorrelationContext.forRequest("overflow-" + i),
                    "http-1",
                    null,
                    false,
                    new HttpPayload("GET", path, path, null, 200)));
        }
        assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();
        assertThat(aggregates.snapshot().overflowed().get("routes")).isPositive();

        RuntimeChangeImpactDto impact = service(structure(null)).impact("ProductController#get");
        assertThat(impact.status()).isEqualTo(ChangeImpactService.RESOLVED);
        assertThat(impact.notExercised()).isEmpty();
        assertThat(impact.notExercisedTotal()).isZero();
        assertThat(impact.notExercisedUndetermined()).isTrue();
        assertThat(impact.limitations()).anySatisfy(limit -> assertThat(limit).contains("cannot be classified"));
    }

    @Test
    void disabledSourceFactsStayOutOfImpactAndSuggestionsEvenWhenThePolicyChangesWithoutNewEvents() throws Exception {
        journal.addListener(aggregates);
        request("/api/products", "select * from sample_products");
        AtomicBoolean sqlEnabled = new AtomicBoolean(true);
        Predicate<String> policy = panel -> !panel.equals(BootUiPanels.SQL_TRACE) || sqlEnabled.get();
        ChangeImpactService impact = service(structure(null), policy);
        assertThat(impact.impact("sample_products").status()).isEqualTo(ChangeImpactService.RESOLVED);
        sqlEnabled.set(false);
        assertThat(impact.impact("sample_products").status()).isEqualTo(ChangeImpactService.NOT_FOUND);
        assertThat(impact.symbols("sample_products").symbols()).isEmpty();
        RuntimeChangeImpactDto route = impact.impact("GET /api/products");
        assertThat(route.observed()).singleElement().satisfies(row -> {
            assertThat(row.reads()).isEmpty();
            assertThat(row.writes()).isEmpty();
        });
        assertThat(route.limitations()).anySatisfy(limit -> assertThat(limit).contains(BootUiPanels.SQL_TRACE));
        assertThat(route.limitations()).noneSatisfy(limit -> assertThat(limit).contains(BootUiPanels.HIBERNATE));

        ChangeImpactService noHttp = service(structure(null), panel -> !panel.equals(BootUiPanels.HTTP_EXCHANGES));
        assertThat(noHttp.impact("GET /api/products").status()).isEqualTo(ChangeImpactService.UNAVAILABLE);
        assertThat(noHttp.symbols("sample_products").available()).isFalse();
    }

    @Test
    void disabledSourcesWithoutRelevantEvidenceDoNotProduceSpuriousLimitations() throws Exception {
        journal.addListener(aggregates);
        journal.offer(RuntimeEvent.of(
                JournalSource.HTTP,
                1_000,
                1_000,
                CorrelationContext.forRequest("http-only"),
                "http-1",
                null,
                false,
                new HttpPayload("GET", "/api/products", "/api/products", null, 200)));
        assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();
        RuntimeChangeImpactDto impact = service(structure(null), panel -> panel.equals(BootUiPanels.HTTP_EXCHANGES))
                .impact("GET /api/products");
        assertThat(impact.limitations()).noneSatisfy(limit -> assertThat(limit).contains("panel is disabled"));
    }

    @Test
    void evictedAuthorizationStillExplainsWhyAnonymousCountsAreHidden() throws Exception {
        RuntimeJournal shortJournal = new RuntimeJournal(
                new RuntimeJournalSettings(true, 1, 50_000_000, 10, 0, 0, JournalSource.all()), RunIdentity.start());
        JournalAggregates wholeRun = new JournalAggregates();
        try {
            shortJournal.addListener(wholeRun);
            CorrelationContext context = CorrelationContext.forRequest("anonymous");
            shortJournal.offer(RuntimeEvent.of(
                    JournalSource.AUTHORIZATION,
                    1_000,
                    1_000,
                    context,
                    "http-1",
                    null,
                    false,
                    new AuthorizationPayload(
                            AuthorizationPayload.REQUEST, null, null, AuthorizationPayload.ANONYMOUS, true, 0)));
            assertThat(shortJournal.awaitDrained(Duration.ofSeconds(5))).isTrue();
            shortJournal.offer(RuntimeEvent.of(
                    JournalSource.HTTP,
                    1_001,
                    1_000,
                    context,
                    "http-1",
                    null,
                    false,
                    new HttpPayload("GET", "/api/products", "/api/products", null, 200)));
            assertThat(shortJournal.awaitDrained(Duration.ofSeconds(5))).isTrue();
            assertThat(shortJournal.entries())
                    .noneSatisfy(entry -> assertThat(entry.event().source()).isEqualTo(JournalSource.AUTHORIZATION));
            assertThat(wholeRun.snapshot().routes())
                    .anySatisfy(route ->
                            assertThat(route.authorization().anonymous()).isEqualTo(1));

            RuntimeModelService models = new RuntimeModelService(shortJournal, null, runId -> structure(null));
            RuntimeChangeImpactDto impact = new ChangeImpactService(
                            shortJournal, wholeRun, models, null, panel -> !panel.equals(BootUiPanels.SECURITY_LOGS))
                    .impact("GET /api/products");
            assertThat(impact.observed())
                    .singleElement()
                    .satisfies(route -> assertThat(route.anonymous()).isZero());
            assertThat(impact.limitations())
                    .anySatisfy(limit -> assertThat(limit).contains(BootUiPanels.SECURITY_LOGS));
        } finally {
            shortJournal.close();
        }
    }

    @Test
    void quarkusSaysItDoesNotRecordWhoPublishesAnApplicationEventAndSpringDoesNot() throws Exception {
        journal.addListener(aggregates);
        request("/api/products", "select * from sample_products");

        ChangeImpactService quarkus = service(structure(null));
        quarkus.setStack(InsightsStack.QUARKUS);
        ChangeImpactService spring = service(structure(null));
        spring.setStack(InsightsStack.SPRING_MVC);

        assertThat(quarkus.impact("productService").limitations())
                .as("a reach that exists only through an event is not counted on Quarkus, and the report says so"
                        + " rather than implying the publication was recorded")
                .anySatisfy(limitation ->
                        assertThat(limitation).contains("Quarkus does not record who publishes an application event"));
        assertThat(spring.impact("productService").limitations())
                .as("Spring wraps the multicaster, so it records the publication and claims nothing")
                .noneSatisfy(limitation ->
                        assertThat(limitation).contains("does not record who publishes an application event"));
    }

    private ChangeImpactService service(StructureSnapshot structure) {
        return service(structure, panel -> true);
    }

    // --- M5-7a: a method's impact from the route trees -------------------------------------------------------------

    private static final String FIND_ALL = "com.example.ProductService#findAll()Ljava/util/List;";
    private static final String FIND_BY_NAME = "com.example.ProductService#findAll(Ljava/lang/String;)Ljava/util/List;";
    private static final Set<String> BEAN_CLASSES =
            Set.of("com.example.ProductController", "com.example.ProductService", "com.example.ReviewController");

    @Test
    void aMethodsObservedRoutesAreThoseWhoseCallTreesRanItAndARouteThatRanWithoutItIsNeverSaidNotExercised()
            throws Exception {
        journal.addListener(aggregates);
        request("/api/products", "select * from sample_products");
        request("/api/products", "select * from sample_products");
        request("/api/products/{id}", "select * from sample_products where id = ?");
        ChangeImpactService service = service(structure(null));
        service.setCodePaths(wanted -> routes(
                wanted,
                Map.of(FIND_ALL, Map.of("GET /api/products", 2L)),
                Map.of(
                        "GET /api/products",
                        new MethodRoutes.RouteEvidence(2, false, List.of()),
                        "GET /api/products/{id}",
                        new MethodRoutes.RouteEvidence(1, false, List.of()))));
        service.setCodeInventory((type, name) ->
                lookup(ClassScanner.COMPLETE, method(FIND_ALL, CodeInventoryService.EXECUTED, "GET /api/products")));

        RuntimeChangeImpactDto impact = service.impact("ProductService#findAll");

        assertThat(impact.status()).isEqualTo(ChangeImpactService.RESOLVED);
        assertThat(impact.observedFrom()).isEqualTo(RuntimeChangeImpactDto.FROM_ROUTE_TREES);
        assertThat(impact.node()).isEqualTo("METHOD com.example.ProductService#findAll");
        assertThat(impact.methods()).containsExactly(FIND_ALL);
        assertThat(impact.methodStatus()).isEqualTo(CodeInventoryService.EXECUTED);
        assertThat(impact.observed()).singleElement().satisfies(route -> {
            assertThat(route.route()).isEqualTo("GET /api/products");
            assertThat(route.executedRequests()).isEqualTo(2);
            assertThat(route.requests()).isEqualTo(2);
            assertThat(route.partial()).isFalse();
        });
        assertThat(impact.notExercised())
                .as("the other route of its bean ran, so it is never said not exercised on silence alone")
                .isEmpty();
        assertThat(impact.notObserved()).singleElement().satisfies(route -> {
            assertThat(route.route()).isEqualTo("GET /api/products/{id}");
            assertThat(route.check()).contains("does not prove it did not run");
        });
        assertThat(impact.notObservedTotal()).isEqualTo(1);
        assertThat(impact.notExercisedUndetermined()).isTrue();
        assertThat(impact.limitations())
                .anySatisfy(limitation -> assertThat(limitation).contains("calls observed across requests are never"));

        assertThat(service.impact("ProductService.findAll").node())
                .as("Class.method, tried once no other symbol matched")
                .isEqualTo("METHOD com.example.ProductService#findAll");
    }

    @Test
    void codeInventorySeeingTheMethodNeverRunProvesEveryRouteNotExercised() throws Exception {
        journal.addListener(aggregates);
        request("/api/products", "select * from sample_products");
        ChangeImpactService service = service(structure(null));
        service.setCodePaths(wanted -> routes(
                wanted, Map.of(), Map.of("GET /api/products", new MethodRoutes.RouteEvidence(1, false, List.of()))));
        service.setCodeInventory((type, name) ->
                lookup(ClassScanner.COMPLETE, method(FIND_ALL, CodeInventoryService.NEVER_EXECUTED, null)));

        RuntimeChangeImpactDto impact = service.impact("com.example.ProductService#findAll");

        assertThat(impact.observed()).isEmpty();
        assertThat(impact.notObserved()).isEmpty();
        assertThat(impact.methodStatus()).isEqualTo(CodeInventoryService.NEVER_EXECUTED);
        assertThat(impact.notExercised())
                .extracting(RuntimeImpactRouteDto::route, RuntimeImpactRouteDto::check)
                .containsExactly(
                        tuple(
                                "GET /api/products",
                                "`GET /api/products` served 1 request in this run, and Code Inventory saw the method"
                                        + " never run: send a request of `GET /api/products` that reaches it."),
                        tuple(
                                "GET /api/products/{id}",
                                "Exercise `GET /api/products/{id}` before relying on this change: no request reached"
                                        + " it in this run."));

        service.setCodeInventory((type, name) ->
                lookup(ClassScanner.PARTIAL, method(FIND_ALL, CodeInventoryService.NEVER_EXECUTED, null)));
        assertThat(service.impact("ProductService#findAll").notObserved())
                .as("a partial scan may miss an overload, so the proof does not hold")
                .extracting(RuntimeImpactRouteDto::route)
                .containsExactly("GET /api/products");
    }

    @Test
    void parametersNameOneOverloadAndSeveralClassesAreAmbiguous() throws Exception {
        journal.addListener(aggregates);
        request("/api/products", "select * from sample_products");
        ChangeImpactService service = service(structure(null));
        service.setCodePaths(wanted -> routes(
                wanted,
                Map.of(FIND_ALL, Map.of("GET /api/products", 1L), FIND_BY_NAME, Map.of()),
                Map.of("GET /api/products", new MethodRoutes.RouteEvidence(1, false, List.of()))));
        service.setCodeInventory((type, name) -> lookup(
                ClassScanner.COMPLETE,
                method(FIND_ALL, CodeInventoryService.EXECUTED, null),
                method(FIND_BY_NAME, CodeInventoryService.NEVER_EXECUTED, null),
                method("com.example.other.ProductService#findAll()V", CodeInventoryService.NEVER_EXECUTED, null)));

        RuntimeChangeImpactDto ambiguous = service.impact("ProductService#findAll");
        assertThat(ambiguous.status()).isEqualTo(ChangeImpactService.AMBIGUOUS);
        assertThat(ambiguous.candidates())
                .containsExactly(
                        "METHOD com.example.ProductService#findAll", "METHOD com.example.other.ProductService#findAll");

        RuntimeChangeImpactDto byName = service.impact("com.example.ProductService#findAll(String)");
        assertThat(byName.methods()).containsExactly(FIND_BY_NAME);
        assertThat(byName.observed()).isEmpty();
        assertThat(byName.methodStatus()).isEqualTo(CodeInventoryService.NEVER_EXECUTED);

        RuntimeChangeImpactDto both = service.impact("METHOD com.example.ProductService#findAll");
        assertThat(both.methods()).as(both.toString()).containsExactlyInAnyOrder(FIND_ALL, FIND_BY_NAME);
        assertThat(both.limitations())
                .anySatisfy(limitation -> assertThat(limitation).contains("2 overloads"));
        assertThat(both.observed()).extracting(RuntimeImpactRouteDto::route).containsExactly("GET /api/products");
    }

    @Test
    void aFailedCodePathsReadIsUnavailableAndAMethodTheSensorDoesNotTimeSaysSo() throws Exception {
        journal.addListener(aggregates);
        request("/api/products", "select * from sample_products");
        ChangeImpactService service = service(structure(null));
        service.setCodePaths(wanted -> {
            throw new IllegalStateException("boom");
        });
        assertThat(service.impact("ProductService#findAll")).satisfies(impact -> {
            assertThat(impact.status()).isEqualTo(ChangeImpactService.UNAVAILABLE);
            assertThat(impact.reason()).startsWith(MethodRoutes.READ_FAILED);
        });

        String helper = "com.example.ProductService#normalize(Ljava/lang/String;)Ljava/lang/String;";
        service.setCodePaths(wanted -> routes(
                wanted, Map.of(), Map.of("GET /api/products", new MethodRoutes.RouteEvidence(1, false, List.of()))));
        service.setCodeInventory((type, name) -> new CodeInventoryService.MethodLookup(
                null,
                ClassScanner.COMPLETE,
                List.of(new CodeInventoryService.InventoryMethod(
                        method(helper, CodeInventoryService.EXECUTED, null).method(), 0x0002))));
        RuntimeChangeImpactDto impact = service.impact("ProductService#normalize");
        assertThat(impact.notObserved())
                .singleElement()
                .satisfies(route -> assertThat(route.check()).contains(TracedMethods.NOT_TRACED));
        assertThat(impact.notExercised())
                .extracting(RuntimeImpactRouteDto::route)
                .containsExactly("GET /api/products/{id}");
    }

    @Test
    void withoutTheAgentOnlyAHandlerMethodIsCheckedAndTheReasonIsNamed() throws Exception {
        journal.addListener(aggregates);
        request("/api/products", "select * from sample_products");
        ChangeImpactService service = service(structure(null));
        service.setCodePaths(wanted -> MethodRoutes.unavailable("The BootUI agent is not attached."));

        RuntimeChangeImpactDto handler = service.impact("ProductController#list");
        assertThat(handler.observedFrom()).isEqualTo(RuntimeChangeImpactDto.FROM_HANDLER_MAPPING);
        assertThat(handler.observed()).extracting(RuntimeImpactRouteDto::route).containsExactly("GET /api/products");
        assertThat(handler.limitations())
                .anySatisfy(limitation -> assertThat(limitation).contains("The BootUI agent is not attached"));

        RuntimeChangeImpactDto service2 = service.impact("ProductService#findAll");
        assertThat(service2.status()).isEqualTo(ChangeImpactService.NOT_FOUND);
        assertThat(service2.reason())
                .contains("only handler methods can be checked")
                .contains("agent is not attached");
    }

    private static MethodRoutes routes(
            Predicate<String> wanted,
            Map<String, Map<String, Long>> byKey,
            Map<String, MethodRoutes.RouteEvidence> evidence) {
        Map<String, Map<String, Long>> matching = new java.util.LinkedHashMap<>();
        byKey.forEach((key, routes) -> {
            if (wanted.test(key) && !routes.isEmpty()) {
                matching.put(key, routes);
            }
        });
        return new MethodRoutes(null, matching, Map.of(), evidence, Set.of(), BEAN_CLASSES, List.of());
    }

    private static CodeInventoryService.MethodLookup lookup(
            String scan, CodeInventoryService.InventoryMethod... methods) {
        return new CodeInventoryService.MethodLookup(null, scan, List.of(methods));
    }

    private static CodeInventoryService.InventoryMethod method(String key, String status, String firstRoute) {
        int hash = key.indexOf('#');
        int open = key.indexOf('(');
        String className = key.substring(0, hash);
        return new CodeInventoryService.InventoryMethod(
                new CodeInventoryMethodDto(
                        key,
                        className.substring(0, className.lastIndexOf('.')),
                        className,
                        key.substring(hash + 1, open),
                        key.substring(open),
                        status,
                        null,
                        null,
                        firstRoute == null ? null : "r1",
                        firstRoute,
                        null),
                0x0001);
    }

    private ChangeImpactService service(StructureSnapshot structure, Predicate<String> panelEnabled) {
        RuntimeModelService models = new RuntimeModelService(journal, null, runId -> structure);
        return new ChangeImpactService(journal, aggregates, models, null, panelEnabled);
    }

    private static StructureSnapshot structure(String beansUnavailable) {
        return new StructureSnapshot(
                null,
                List.of(
                        new StructureSnapshot.RouteHandler(
                                "GET /api/products", "com.example.ProductController", "list"),
                        new StructureSnapshot.RouteHandler(
                                "GET /api/products/{id}", "com.example.ProductController", "get"),
                        new StructureSnapshot.RouteHandler("GET /api/reviews", "com.example.ReviewController", "list")),
                beansUnavailable != null
                        ? List.of()
                        : List.of(
                                new StructureSnapshot.Bean(
                                        "productController",
                                        "com.example.ProductController",
                                        false,
                                        List.of("productService")),
                                new StructureSnapshot.Bean(
                                        "productService",
                                        "com.example.ProductService",
                                        false,
                                        List.of("productRepository")),
                                new StructureSnapshot.Bean(
                                        "productRepository", "com.example.ProductRepository", true, List.of()),
                                new StructureSnapshot.Bean(
                                        "reviewController",
                                        "com.example.ReviewController",
                                        false,
                                        List.of("reviewRepository")),
                                new StructureSnapshot.Bean(
                                        "reviewRepository", "com.example.ReviewRepository", true, List.of()),
                                new StructureSnapshot.Bean(
                                        "orderMapper", "com.example.orders.Mapper", false, List.of()),
                                new StructureSnapshot.Bean(
                                        "productMapper", "com.example.products.Mapper", false, List.of())),
                beansUnavailable);
    }

    private void request(String path, String sql) throws InterruptedException {
        String requestId = "r" + (++requests);
        CorrelationContext context = CorrelationContext.forRequest(requestId);
        journal.offer(RuntimeEvent.of(
                JournalSource.SQL,
                1_000,
                1_000,
                context,
                "http-1",
                null,
                false,
                new SqlPayload(sql, null, "db", false)));
        journal.offer(RuntimeEvent.of(
                JournalSource.HTTP,
                1_000 + requests,
                1_000_000,
                context,
                "http-1",
                null,
                false,
                new HttpPayload("GET", path, path, null, 200)));
        assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();
    }
}
