package io.github.jdubois.bootui.engine.insights;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.RuntimeObservationDetailDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationRowDto;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.AuthorizationPayload;
import io.github.jdubois.bootui.engine.journal.ConnectionPayload;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.MessagingPayload;
import io.github.jdubois.bootui.engine.journal.RequestTiming;
import io.github.jdubois.bootui.engine.journal.RestClientPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeEventPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.resources.ResourceUsage;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RouteTimeBreakdownTests {

    private static final long MS = 1_000_000;

    private final RuntimeJournal journal = new RuntimeJournal(
            new RuntimeJournalSettings(true, 10_000, 50_000_000, 10_000, 10, 10, JournalSource.all()),
            RunIdentity.start());
    private int requests;
    private long clock = 1_000_000_000L;

    @AfterEach
    void close() {
        journal.close();
    }

    @Test
    void warmRequestsAreSplitIntoNamedPhasesWithOverlappingCallsCountedOnceAndTheColdRequestApart() {
        request("/api/orders/{id}", 400 * MS, new RequestTiming(0, -1, -1, -1));
        for (int i = 0; i < 5; i++) {
            long start = clock;
            request(
                    "/api/orders/{id}",
                    40 * MS,
                    new RequestTiming(start, 2 * MS, 5 * MS, 35 * MS),
                    new Child(
                            JournalSource.CONNECTION, 30 * MS, new ConnectionPayload("db", 2 * MS, 2, start + 10 * MS)),
                    new Child(
                            JournalSource.SQL,
                            10 * MS,
                            new SqlPayload("select 1", null, "db", false, null, null, start + 20 * MS)),
                    new Child(
                            JournalSource.SQL,
                            10 * MS,
                            new SqlPayload("select 2", null, "db", false, null, null, start + 25 * MS)),
                    new Child(
                            JournalSource.REST_CLIENT,
                            4 * MS,
                            new RestClientPayload(
                                    "GET", "stock:8080", "/items", 200, "RestClient", false, null, start + 30 * MS)));
        }
        for (int i = 0; i < 3; i++) {
            request("/api/customers", 12 * MS, new RequestTiming(clock, -1, 1 * MS, 10 * MS));
        }

        RuntimeInsightsService service =
                new RuntimeInsightsService(journal, null, null, InsightsStack.SPRING_MVC, null);
        List<RuntimeObservationDto> breakdowns = service.report().observations().stream()
                .filter(observation -> observation.kind().equals(RouteTimeBreakdown.KIND))
                .toList();

        RuntimeObservationDto orders = breakdowns.stream()
                .filter(o -> o.subject().equals("GET /api/orders/{id}"))
                .findFirst()
                .orElseThrow();
        assertThat(orders.status()).isEqualTo("OBSERVED");
        assertThat(orders.sentence())
                .isEqualTo("`GET /api/orders/{id}`: warm median 40 ms over 5 requests; SQL 38 %, Handler, other work"
                        + " 23 %, Response write 13 %. First request 400 ms (cold).");
        assertThat(orders.whatToCheck().get(0)).contains("SQL Trace");
        assertThat(orders.exemplarRequestIds()).hasSize(3).doesNotContain("r1");
        RuntimeObservationDetailDto detail = service.insight(orders.id());
        assertThat(detail.rows())
                .extracting(RuntimeObservationRowDto::cells)
                .containsExactly(
                        List.of("Authentication", "10", "5 %", "2.0"),
                        List.of("Other filters", "15", "8 %", "3.0"),
                        List.of("Connection wait", "10", "5 %", "2.0"),
                        List.of("SQL", "75", "38 %", "15"),
                        List.of("REST client", "20", "10 %", "4.0"),
                        List.of("Handler, other work", "45", "23 %", "9.0"),
                        List.of("Response write", "25", "13 %", "5.0"),
                        List.of("Overlapping calls, counted once above", "25", "", ""));

        RuntimeObservationDto customers = breakdowns.stream()
                .filter(o -> o.subject().equals("GET /api/customers"))
                .findFirst()
                .orElseThrow();
        assertThat(customers.status()).isEqualTo("INSUFFICIENT");
        assertThat(customers.sentence())
                .isEqualTo("`GET /api/customers`: 2 of 5 warm requests needed. First request 12 ms (cold).");
    }

    @Test
    void authorizationTimeIsTakenFromTheFiltersForARequestAndFromTheHandlerForAMethod() {
        request("/api/admin", 100 * MS, new RequestTiming(0, -1, -1, -1));
        for (int i = 0; i < 5; i++) {
            request(
                    "/api/admin",
                    20 * MS,
                    new RequestTiming(clock, 1 * MS, 10 * MS, 18 * MS),
                    new Child(
                            JournalSource.AUTHORIZATION,
                            4 * MS,
                            new AuthorizationPayload("REQUEST", null, null, "AUTHENTICATED", true, 1)),
                    new Child(
                            JournalSource.AUTHORIZATION,
                            3 * MS,
                            new AuthorizationPayload(
                                    "METHOD",
                                    "AdminService#purge",
                                    "hasAnyAuthority(ROLE_ADMIN)",
                                    "AUTHENTICATED",
                                    true,
                                    1)));
        }

        RuntimeInsightsService service =
                new RuntimeInsightsService(journal, null, null, InsightsStack.SPRING_MVC, null);
        RuntimeObservationDto admin = service.report().observations().stream()
                .filter(observation -> observation.kind().equals(RouteTimeBreakdown.KIND))
                .findFirst()
                .orElseThrow();

        assertThat(service.insight(admin.id()).rows())
                .extracting(RuntimeObservationRowDto::cells)
                .containsExactly(
                        List.of("Authentication", "5.0", "5 %", "1.0"),
                        List.of("Authorization", "35", "35 %", "7.0"),
                        List.of("Other filters", "25", "25 %", "5.0"),
                        List.of("Handler, other work", "25", "25 %", "5.0"),
                        List.of("Response write", "10", "10 %", "2.0"));
        assertThat(admin.whatToCheck().get(0)).contains("authorization");
    }

    @Test
    void withoutPhasesTheTimeAroundTheCallsIsUnattributed() {
        for (int i = 0; i < 6; i++) {
            long start = clock;
            request(
                    "/api/orders",
                    10 * MS,
                    RequestTiming.startedAt(start),
                    new Child(
                            JournalSource.SQL,
                            4 * MS,
                            new SqlPayload("select 1", null, "db", false, null, null, start + 6 * MS)));
        }

        RuntimeObservationDto breakdown = new RuntimeInsightsService(
                        journal, null, null, InsightsStack.SPRING_WEBFLUX, null)
                .report().observations().stream()
                        .filter(observation -> observation.kind().equals(RouteTimeBreakdown.KIND))
                        .findFirst()
                        .orElseThrow();

        assertThat(breakdown.status()).isEqualTo("OBSERVED");
        assertThat(breakdown.sentence()).contains("Unattributed 60 %, SQL 40 %");
        assertThat(breakdown.whatToCheck().get(0))
                .as("unattributed time is not known to be application code")
                .doesNotContain("application code")
                .contains("does not tell apart");
        assertThat(breakdown.limitations())
                .anySatisfy(limitation -> assertThat(limitation).contains("WebFlux"));
        assertThat(breakdown.whatToCheck().get(0))
                .contains("does not tell apart")
                .doesNotContain("hottest frames");
    }

    @Test
    void authenticationIsNamedOutOfTheUnattributedTimeOfARequestWithoutPhases() {
        for (int i = 0; i < 6; i++) {
            long start = clock;
            request(
                    "/api/orders",
                    10 * MS,
                    new RequestTiming(start, 2 * MS, -1, -1),
                    new Child(
                            JournalSource.SQL,
                            4 * MS,
                            new SqlPayload("select 1", null, "db", false, null, null, start + 6 * MS)));
        }

        RuntimeInsightsService service =
                new RuntimeInsightsService(journal, null, null, InsightsStack.SPRING_WEBFLUX, null);
        RuntimeObservationDto breakdown = service.report().observations().stream()
                .filter(observation -> observation.kind().equals(RouteTimeBreakdown.KIND))
                .findFirst()
                .orElseThrow();

        assertThat(service.insight(breakdown.id()).rows())
                .extracting(RuntimeObservationRowDto::cells)
                .containsExactly(
                        List.of("Authentication", "10", "20 %", "2.0"),
                        List.of("SQL", "20", "40 %", "4.0"),
                        List.of("Unattributed", "20", "40 %", "4.0"));
    }

    @Test
    void authenticationNeverTakesMoreThanTheUnattributedTimeItIsNamedOutOf() {
        for (int i = 0; i < 6; i++) {
            long start = clock;
            request(
                    "/api/orders",
                    10 * MS,
                    new RequestTiming(start, 9 * MS, -1, -1),
                    new Child(
                            JournalSource.SQL,
                            4 * MS,
                            new SqlPayload("select 1", null, "db", false, null, null, start + 6 * MS)));
        }

        RuntimeInsightsService service =
                new RuntimeInsightsService(journal, null, null, InsightsStack.SPRING_WEBFLUX, null);
        RuntimeObservationDto breakdown = service.report().observations().stream()
                .filter(observation -> observation.kind().equals(RouteTimeBreakdown.KIND))
                .findFirst()
                .orElseThrow();

        assertThat(service.insight(breakdown.id()).rows())
                .extracting(RuntimeObservationRowDto::cells)
                .as("the authentication reported is capped at the time left around the recorded calls")
                .containsExactly(List.of("Authentication", "30", "60 %", "6.0"), List.of("SQL", "20", "40 %", "4.0"));
    }

    @Test
    void authenticationTimeAloneIsEnoughToBreakDownAWebFluxRouteWithoutARecordedCall() {
        for (int i = 0; i < 6; i++) {
            request("/api/orders", 10 * MS, new RequestTiming(clock, 4 * MS, -1, -1));
        }

        RuntimeInsightsService service =
                new RuntimeInsightsService(journal, null, null, InsightsStack.SPRING_WEBFLUX, null);
        RuntimeObservationDto breakdown = service.report().observations().stream()
                .filter(observation -> observation.kind().equals(RouteTimeBreakdown.KIND))
                .findFirst()
                .orElseThrow();

        assertThat(breakdown.status())
                .as("authentication names part of the time, so the breakdown is no longer one unattributed span")
                .isEqualTo("OBSERVED");
        assertThat(service.insight(breakdown.id()).rows())
                .extracting(RuntimeObservationRowDto::cells)
                .containsExactly(
                        List.of("Authentication", "20", "40 %", "4.0"), List.of("Unattributed", "30", "60 %", "6.0"));
    }

    @Test
    void authenticationOfARequestThatReachedNoMarkedHandlerIsEvidenceRatherThanARecordedCall() {
        for (int i = 0; i < 6; i++) {
            long start = clock;
            request(
                    "/api/admin",
                    403,
                    20 * MS,
                    new RequestTiming(start, 5 * MS, -1, -1),
                    new Child(
                            JournalSource.SQL,
                            4 * MS,
                            new SqlPayload("select 1", null, "db", false, null, null, start + 6 * MS)));
        }

        RuntimeInsightsService service =
                new RuntimeInsightsService(journal, null, null, InsightsStack.SPRING_MVC, null);
        RuntimeObservationDto breakdown = service.report().observations().stream()
                .filter(observation -> observation.kind().equals(RouteTimeBreakdown.KIND))
                .findFirst()
                .orElseThrow();

        assertThat(breakdown.status()).isEqualTo("INSUFFICIENT");
        assertThat(breakdown.sentence())
                .as("authentication is an observed phase, not a call the request made")
                .contains("Recorded calls: SQL")
                .doesNotContain("Recorded calls: Authentication")
                .doesNotContain("application code");
        assertThat(service.insight(breakdown.id()).rows())
                .extracting(RuntimeObservationRowDto::cells)
                .as("the authentication time is still named in the evidence")
                .anySatisfy(cells -> assertThat(cells).first().isEqualTo("Authentication"));
    }

    @Test
    void securityRejectedRequestsWithoutHandlerMarksAreNotCalledApplicationCode() {
        for (int i = 0; i < 6; i++) {
            request("/api/secure", 12 * MS, RequestTiming.startedAt(clock));
        }

        RuntimeObservationDto breakdown = new RuntimeInsightsService(
                        journal, null, null, InsightsStack.SPRING_MVC, null)
                .report().observations().stream()
                        .filter(observation -> observation.kind().equals(RouteTimeBreakdown.KIND))
                        .findFirst()
                        .orElseThrow();

        assertThat(breakdown.sentence())
                .contains("reached no handler BootUI marks")
                .doesNotContain("application code");
        assertThat(breakdown.whatToCheck())
                .noneSatisfy(check -> assertThat(check).contains("application code outside recorded calls"));
    }

    @Test
    void withoutPhasesOrRecordedCallsABreakdownSaysItCannotTellWhereTheTimeWent() {
        for (int i = 0; i < 6; i++) {
            request("/api/greetings/{id}", 401, 7 * MS, RequestTiming.startedAt(clock));
        }

        RuntimeObservationDto breakdown =
                breakdowns(InsightsStack.SPRING_WEBFLUX).get(0);

        assertThat(breakdown.status()).isEqualTo("INSUFFICIENT");
        assertThat(breakdown.sentence())
                .isEqualTo("`GET /api/greetings/{id}`: warm median 7.0 ms over 5 requests, none of it in a recorded"
                        + " call; Spring WebFlux marks no phases, so where that time went is not known. Every one was"
                        + " answered 401 or 403, as security filters do when they reject a request. First request"
                        + " 7.0 ms (cold).");
        assertThat(breakdown.whatToCheck()).noneMatch(check -> check.contains("application code"));
    }

    @Test
    void requestsThatReachedNoMarkedHandlerAreNotSplitAndTheirRecordedCallsAreKept() {
        for (int i = 0; i < 6; i++) {
            long start = clock;
            // Filters ran, but no handler BootUI marks did: an Actuator endpoint answers through its own mapping.
            request(
                    "/actuator/health",
                    20 * MS,
                    new RequestTiming(start, -1, -1, -1),
                    new Child(
                            JournalSource.SQL,
                            5 * MS,
                            new SqlPayload("select 1", null, "db", false, null, null, start + 10 * MS)));
        }

        RuntimeObservationDto health = breakdowns(InsightsStack.SPRING_MVC).get(0);

        assertThat(health.status()).isEqualTo("INSUFFICIENT");
        assertThat(health.sentence())
                .isEqualTo("`GET /actuator/health`: warm median 20 ms over 5 requests; all of them reached no handler"
                        + " BootUI marks (an Actuator endpoint, a request the security filters answered, or a servlet"
                        + " outside Spring MVC), so the time is not split into phases. Recorded calls: SQL 25 %."
                        + " First request 20 ms (cold).");
        assertThat(health.affected()).isEqualTo(5);
        assertThat(health.whatToCheck()).noneMatch(check -> check.contains("application code"));
    }

    @Test
    void onQuarkusAFrameworkRouteIsNamedAsSuch() {
        for (int i = 0; i < 6; i++) {
            request("/q/health", 3 * MS, RequestTiming.startedAt(clock));
        }

        RuntimeObservationDto health = breakdowns(InsightsStack.QUARKUS).get(0);

        assertThat(health.status()).isEqualTo("INSUFFICIENT");
        assertThat(health.sentence())
                .contains("a framework endpoint such as /q/health")
                .doesNotContain("Recorded");
    }

    @Test
    void securityRejectionsAreNamedAndLeftOutOfTheHandlersSplit() {
        request("/api/account", 30 * MS, new RequestTiming(clock, -1, 1 * MS, 9 * MS));
        for (int i = 0; i < 5; i++) {
            request("/api/account", 10 * MS, new RequestTiming(clock, -1, 1 * MS, 9 * MS));
        }
        for (int i = 0; i < 3; i++) {
            request("/api/account", 401, 2 * MS, new RequestTiming(clock, 1 * MS, -1, -1));
        }
        for (int i = 0; i < 4; i++) {
            request("/api/admin", 403, 2 * MS, new RequestTiming(clock, 1 * MS, -1, -1));
        }

        List<RuntimeObservationDto> breakdowns = breakdowns(InsightsStack.SPRING_MVC);
        RuntimeObservationDto account = bySubject(breakdowns, "GET /api/account");
        RuntimeObservationDto admin = bySubject(breakdowns, "GET /api/admin");

        assertThat(account.status()).isEqualTo("OBSERVED");
        assertThat(account.eligible()).isEqualTo(5);
        assertThat(account.sentence()).startsWith("`GET /api/account`: warm median 10 ms over 5 requests;");
        assertThat(account.limitations())
                .contains("3 warm requests were answered 401 or 403 before reaching a handler BootUI marks, as"
                        + " security filters do when they reject a request, so they are left out of the phases.");
        assertThat(admin.status()).isEqualTo("INSUFFICIENT");
        assertThat(admin.sentence())
                .isEqualTo("`GET /api/admin`: warm median 2.0 ms over 3 requests; all of them answered 401 or 403"
                        + " before reaching a handler BootUI marks, as security filters do when they reject a request,"
                        + " so the time is not split into phases. First request 2.0 ms (cold).");
        assertThat(admin.whatToCheck()).singleElement().asString().contains("requests it accepts");
    }

    @Test
    void onlySendsThatBlockTheCallerAreTakenOutOfTheHandler() {
        for (int i = 0; i < 6; i++) {
            request(
                    "/api/orders",
                    100 * MS,
                    new RequestTiming(clock, -1, 2 * MS, 95 * MS),
                    new Child(JournalSource.MESSAGING, 60 * MS, new MessagingPayload("kafka", true, "orders", false)));
            request(
                    "/api/payments",
                    100 * MS,
                    new RequestTiming(clock, -1, 2 * MS, 95 * MS),
                    new Child(
                            JournalSource.MESSAGING,
                            60 * MS,
                            new MessagingPayload("rabbitmq", true, "payments", false)));
        }

        List<RuntimeObservationDto> breakdowns = breakdowns(InsightsStack.SPRING_MVC);
        RuntimeObservationDto kafka = bySubject(breakdowns, "GET /api/orders");
        RuntimeObservationDto rabbit = bySubject(breakdowns, "GET /api/payments");

        assertThat(kafka.sentence())
                .as("a fire-and-forget Kafka send is timed to its asynchronous acknowledgement")
                .doesNotContain("Message sends")
                .contains("Handler, other work 93 %");
        assertThat(kafka.limitations())
                .anySatisfy(limitation -> assertThat(limitation)
                        .startsWith("5 requests sent Kafka messages")
                        .contains("not counted as Message sends"));
        assertThat(rabbit.sentence()).contains("Message sends 60 %");
        assertThat(rabbit.limitations()).noneMatch(limitation -> limitation.contains("Kafka"));
    }

    private List<RuntimeObservationDto> breakdowns(InsightsStack stack) {
        return new RuntimeInsightsService(journal, null, null, stack, null)
                .report().observations().stream()
                        .filter(observation -> observation.kind().equals(RouteTimeBreakdown.KIND))
                        .toList();
    }

    private static RuntimeObservationDto bySubject(List<RuntimeObservationDto> observations, String subject) {
        return observations.stream()
                .filter(observation -> observation.subject().equals(subject))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void routesServedOnVirtualThreadsPointToProfileResourcesForTheirCpu() {
        for (int i = 0; i < 6; i++) {
            String requestId = "v" + i;
            journal.offer(RuntimeEvent.of(
                    JournalSource.HTTP,
                    1_000 + i,
                    20 * MS,
                    CorrelationContext.forRequest(requestId),
                    "virtual-1",
                    null,
                    false,
                    new HttpPayload(
                            "GET",
                            "/api/virtual",
                            "/api/virtual",
                            null,
                            200,
                            new ResourceUsage(0, 0, 1, 1, ResourceUsage.Unmeasured.VIRTUAL_THREAD, 0, List.of(), false),
                            new RequestTiming(clock, -1, 1 * MS, 18 * MS))));
            clock += 1_000 * MS;
        }
        try {
            assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }

        RuntimeObservationDto virtual = new RuntimeInsightsService(journal, null, null, InsightsStack.SPRING_MVC, null)
                .report().observations().stream()
                        .filter(observation -> observation.kind().equals(RouteTimeBreakdown.KIND))
                        .findFirst()
                        .orElseThrow();

        assertThat(virtual.limitations()).anyMatch(limitation -> limitation.contains("Profile resources samples them"));
    }

    private void request(String template, long durationNanos, RequestTiming timing, Child... children) {
        request(template, 200, durationNanos, timing, children);
    }

    private void request(String template, int status, long durationNanos, RequestTiming timing, Child... children) {
        String requestId = "r" + (++requests);
        CorrelationContext context = CorrelationContext.forRequest(requestId);
        List<RuntimeEvent> events = new ArrayList<>();
        for (Child child : children) {
            events.add(RuntimeEvent.of(
                    child.source(), 1_000, child.nanos(), context, "http-1", null, false, child.payload()));
        }
        events.add(RuntimeEvent.of(
                JournalSource.HTTP,
                1_000 + requests,
                durationNanos,
                context,
                "http-1",
                null,
                false,
                new HttpPayload("GET", template.replace("{id}", "42"), template, null, status, null, timing)));
        events.forEach(journal::offer);
        clock += 1_000 * MS;
        try {
            assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }

    private record Child(JournalSource source, long nanos, RuntimeEventPayload payload) {}
}
