package io.github.jdubois.bootui.engine.insights;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.MappingDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightsAgentReportDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightsReportDto;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalAggregates;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import io.github.jdubois.bootui.engine.journal.SynchronousJournals;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
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

    @Test
    void noRouteIsListedAsNotExercisedWhileHttpExchangesIsHiddenOrHttpIsNotRecorded() throws Exception {
        RuntimeJournal journal = new RuntimeJournal(
                new RuntimeJournalSettings(true, 1_000, 10_000_000, 1_000, 10, 10, JournalSource.all()),
                RunIdentity.start());
        RuntimeJournal withoutHttp = new RuntimeJournal(
                new RuntimeJournalSettings(
                        true, 1_000, 10_000_000, 1_000, 10, 10, EnumSet.complementOf(EnumSet.of(JournalSource.HTTP))),
                RunIdentity.start());
        try {
            journal.offer(RuntimeEvent.of(
                    JournalSource.HTTP,
                    1,
                    1,
                    CorrelationContext.forRequest("r1"),
                    "http-1",
                    null,
                    false,
                    new HttpPayload("GET", "/api/orders/7", "/api/orders/{id}", null, 200)));
            assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();
            AtomicBoolean httpEnabled = new AtomicBoolean(true);
            RuntimeInsightsService service = new RuntimeInsightsService(
                    journal,
                    null,
                    panel -> !panel.equals(BootUiPanels.HTTP_EXCHANGES) || httpEnabled.get(),
                    null,
                    null);
            // Production wiring: the run's aggregates count every request, whatever the panel shows.
            service.setDeclaredRoutes(
                    () -> DECLARED, () -> new JournalAggregates.RouteLabels(Set.of("DELETE /api/orders/{id}"), false));
            assertThat(service.report().notExercised())
                    .containsExactly("ANY /api/legacy", "ANY /api/ping", "POST /graphql");

            httpEnabled.set(false);
            RuntimeInsightsReportDto hidden = service.report();

            assertThat(hidden.notExercised()).isEmpty();
            assertThat(hidden.notExercisedOmitted()).isZero();
            assertThat(hidden.limitations())
                    .contains("The http-exchanges panel is disabled, so the routes no request reached are not listed.");
            RuntimeInsightsAgentReportDto agent = RuntimeInsightsAgentView.list(hidden, null, null);
            assertThat(agent.notExercised()).isEmpty();
            assertThat(agent.notExercisedOmitted()).isZero();

            RuntimeInsightsService unrecorded = new RuntimeInsightsService(withoutHttp, null, null, null, null);
            unrecorded.setDeclaredRoutes(() -> DECLARED, () -> new JournalAggregates.RouteLabels(Set.of(), false));
            RuntimeInsightsReportDto report = unrecorded.report();
            assertThat(report.notExercised()).isEmpty();
            assertThat(report.limitations()).contains(RuntimeInsightsService.ROUTE_EXERCISE_UNRECORDED);
        } finally {
            journal.close();
            withoutHttp.close();
        }
    }

    @Test
    void droppedHttpOrAClearCannotProveDeclaredRoutesWereNotExercised() {
        for (boolean cleared : List.of(false, true)) {
            try (RuntimeJournal journal =
                    SynchronousJournals.create(RuntimeJournalSettings.defaults(), index -> !cleared && index == 0)) {
                JournalAggregates aggregates = new JournalAggregates();
                journal.addListener(aggregates);
                journal.offer(RuntimeEvent.of(
                        JournalSource.HTTP,
                        1,
                        1,
                        CorrelationContext.forRequest("r1"),
                        "worker",
                        null,
                        false,
                        new HttpPayload("GET", "/api/orders/7", "/api/orders/{id}", null, 200)));
                SynchronousJournals.dispatch(journal);
                if (cleared) {
                    journal.clear();
                }
                RuntimeInsightsService service = new RuntimeInsightsService(journal, null, null, null, null, List.of());
                service.setDeclaredRoutes(() -> DECLARED, aggregates::routeLabels);
                RuntimeInsightsReportDto report = service.report();
                assertThat(report.notExercised()).isEmpty();
                assertThat(report.notExercisedOmitted()).isZero();
                assertThat(report.limitations()).anyMatch(limit -> limit.contains(cleared ? "clear" : "dropped"));
                assertThat(RuntimeInsightsAgentView.list(report, null, null).notExercised())
                        .isEmpty();
            }
        }
    }

    private static MappingDto mapping(String method, String pattern, String handler) {
        return new MappingDto(method, pattern, handler, null, null);
    }
}
