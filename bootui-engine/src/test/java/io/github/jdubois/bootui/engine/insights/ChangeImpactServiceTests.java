package io.github.jdubois.bootui.engine.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.jdubois.bootui.core.dto.RuntimeChangeImpactDto;
import io.github.jdubois.bootui.core.dto.RuntimeImpactRouteDto;
import io.github.jdubois.bootui.core.dto.RuntimeImpactSymbolDto;
import io.github.jdubois.bootui.core.dto.RuntimeImpactSymbolsDto;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalAggregates;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.model.RuntimeModelService;
import io.github.jdubois.bootui.engine.model.StructureSnapshot;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.time.Duration;
import java.util.List;
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
        assertThat(new ChangeImpactService(null, null, null, null).impact("x").status())
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

        RuntimeImpactSymbolsDto disabled = new ChangeImpactService(null, null, null, null).symbols("x");
        assertThat(disabled.available()).isFalse();
        assertThat(disabled.unavailableReason()).contains("bootui.runtime-journal.enabled");
        assertThat(disabled.symbols()).isEmpty();
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
        RuntimeModelService models = new RuntimeModelService(journal, null, runId -> structure);
        return new ChangeImpactService(journal, aggregates, models, null);
    }

    private static StructureSnapshot structure(String beansUnavailable) {
        return new StructureSnapshot(
                null,
                List.of(
                        new StructureSnapshot.RouteHandler("GET /api/products", "com.example.ProductController"),
                        new StructureSnapshot.RouteHandler("GET /api/products/{id}", "com.example.ProductController"),
                        new StructureSnapshot.RouteHandler("GET /api/reviews", "com.example.ReviewController")),
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
