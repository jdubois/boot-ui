package io.github.jdubois.bootui.engine.insights;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.MappingDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightsReportDto;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalAggregates;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NotExercisedRoutesTests {

    private static final List<MappingDto> DECLARED = List.of(
            mapping("GET", "/api/orders/{id:[0-9]+}", "com.example.OrderController#get(Long)"),
            mapping("DELETE", "/api/orders/{id}", "com.example.OrderController#delete(Long)"),
            mapping("POST", "/graphql", "org.springframework.graphql.server.webmvc.GraphQlHttpHandler"),
            mapping("ANY", "/api/legacy", "com.example.LegacyController#any()"),
            mapping("ANY", "/api/ping", "com.example.PingController#any()"),
            mapping("GET", "/error", "org.springframework.boot.webmvc.autoconfigure.error.BasicErrorController#error"),
            mapping("GET", "/actuator/health", "org.springframework.boot.actuate.endpoint.web.Health"),
            mapping("GET", "/actuator/info", "Actuator web endpoint 'info'"),
            mapping("GET", "/webjars/**", "ResourceHttpRequestHandler"),
            mapping(null, "/q/dev-ui", "io.quarkus.devui.Handler"));

    @Test
    void listsDeclaredApplicationRoutesNoRequestReachedAndLeavesFrameworkRoutesOut() {
        List<String> missing = NotExercisedRoutes.of(
                DECLARED, Set.of("GET /api/orders/{id}", "POST /graphql (query Products)", "PUT /api/ping"));

        assertThat(missing).containsExactly("ANY /api/legacy", "DELETE /api/orders/{id}");
        assertThat(NotExercisedRoutes.of(List.of(), Set.of())).isEmpty();
    }

    @Test
    void anApplicationInTheQuarkusNamespaceKeepsItsRoutesWhileQuarkusOwnAreLeftOut() {
        List<MappingDto> declared = List.of(
                mapping("GET", "/api/villains/{id}", "io.quarkus.sample.superheroes.villain.rest.VillainResource#get"),
                mapping("DELETE", "/api/villains/{id}", "io.quarkus.foo.VillainResource#delete"),
                mapping("GET", "/q/health", "io.quarkus.smallrye.health.runtime.SmallRyeHealthHandler#handle"),
                mapping("GET", "/openapi", "io.quarkus.smallrye.openapi.runtime.OpenApiHandler#handle"),
                mapping("GET", "/q/metrics", "io.quarkus.micrometer.MetricsResource#scrape"));

        assertThat(NotExercisedRoutes.of(declared, Set.of()))
                .containsExactly("DELETE /api/villains/{id}", "GET /api/villains/{id}");
    }

    @Test
    void aRouteInventoryThatCannotBeReadIsSaidRatherThanListingNoRoute() throws Exception {
        RuntimeJournal journal = new RuntimeJournal(
                new RuntimeJournalSettings(true, 1_000, 10_000_000, 1_000, 10, 10, JournalSource.all()),
                RunIdentity.start());
        try {
            RuntimeInsightsService service = new RuntimeInsightsService(journal, null, null, null, null);
            service.setDeclaredRoutes(() -> null, () -> new JournalAggregates.RouteLabels(Set.of(), false));
            RuntimeInsightsReportDto unavailable = service.report();
            service.setDeclaredRoutes(
                    () -> {
                        throw new IllegalStateException("mappings endpoint failed");
                    },
                    () -> new JournalAggregates.RouteLabels(Set.of(), false));
            RuntimeInsightsReportDto failing = service.report();
            service.setDeclaredRoutes(List::of, () -> new JournalAggregates.RouteLabels(Set.of(), false));
            RuntimeInsightsReportDto none = service.report();

            for (RuntimeInsightsReportDto report : List.of(unavailable, failing)) {
                assertThat(report.notExercised()).isEmpty();
                assertThat(report.limitations()).contains(RuntimeInsightsService.ROUTE_INVENTORY_UNAVAILABLE);
            }
            assertThat(none.limitations())
                    .as("an application that declares no route is not an inventory failure")
                    .doesNotContain(RuntimeInsightsService.ROUTE_INVENTORY_UNAVAILABLE);
        } finally {
            journal.close();
        }
    }

    @Test
    void theReportCountsEvictedRequestsThroughTheRunsAggregates() throws Exception {
        RuntimeJournal journal = new RuntimeJournal(
                new RuntimeJournalSettings(true, 1_000, 10_000_000, 1_000, 10, 10, JournalSource.all()),
                RunIdentity.start());
        try {
            CorrelationContext context = CorrelationContext.forRequest("r1");
            journal.offer(RuntimeEvent.of(
                    JournalSource.HTTP,
                    1,
                    1,
                    context,
                    "http-1",
                    null,
                    false,
                    new HttpPayload("GET", "/api/orders/7", "/api/orders/{id}", null, 200)));
            assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();
            RuntimeInsightsService service = new RuntimeInsightsService(journal, null, null, null, null);
            service.setDeclaredRoutes(
                    () -> DECLARED, () -> new JournalAggregates.RouteLabels(Set.of("DELETE /api/orders/{id}"), true));

            RuntimeInsightsReportDto report = service.report();

            assertThat(report.notExercised()).containsExactly("ANY /api/legacy", "ANY /api/ping", "POST /graphql");
            assertThat(report.notExercisedOmitted()).isZero();
            assertThat(report.limitations())
                    .anySatisfy(limitation -> assertThat(limitation).contains("more routes than its aggregates keep"));
        } finally {
            journal.close();
        }
    }

    private static MappingDto mapping(String method, String pattern, String handler) {
        return new MappingDto(method, pattern, handler, null, null);
    }
}
