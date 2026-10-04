package io.github.jdubois.bootui.engine.insights;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.RuntimeObservationDetailDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationRowDto;
import io.github.jdubois.bootui.engine.codepaths.HandlerMethods;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.AiPayload;
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

    /**
     * The handler split ({@code docs/PLAN-v2.md} §5.14, M5-4b and M5-4c): the handler's other work, what the recorded
     * SQL left of it, is shared among the route tree's top methods by own time, never more than it, the rest as other
     * handler time, and the slow method is named first.
     */
    @Test
    void withCodePathsTheHandlersOtherWorkIsSplitIntoItsTopMethodsBySelfTime() {
        quoteRequests(3 * MS);
        RuntimeInsightsService service =
                new RuntimeInsightsService(journal, null, null, InsightsStack.SPRING_MVC, null);
        service.setCodePaths(route -> route.equals("GET /api/quote") ? quoteMethods() : null, () -> 1L);

        RuntimeObservationDto quote = breakdown(service, "GET /api/quote");
        assertThat(quote.sentence())
                .isEqualTo(
                        "`GET /api/quote`: warm median 55 ms over 5 requests; Handler: SlowPricingService.quote 68 %,"
                                + " Other handler time 9 %, Handler: QuoteController.quote 9 %. First request 200 ms (cold).");
        assertThat(quote.whatToCheck().get(0)).contains("SlowPricingService.quote", "Code Paths");
        assertThat(quote.limitations())
                .anyMatch(limitation -> limitation.contains(
                                "its self time minus the SQL, REST client, cache, and AI" + " calls stamped to it")
                        && !limitation.contains("no stamp"));
        // The handler window is 50 ms, the SQL takes 3 ms of it: 47 ms of other work, 80 % and 10 % of it named.
        assertThat(service.insight(quote.id()).rows())
                .extracting(RuntimeObservationRowDto::cells)
                .containsExactly(
                        List.of("Other filters", "10", "4 %", "2.0"),
                        List.of("SQL", "15", "5 %", "3.0"),
                        List.of("Handler: SlowPricingService.quote", "188", "68 %", "38"),
                        List.of("Handler: QuoteController.quote", "24", "9 %", "4.7"),
                        List.of("Other handler time", "24", "9 %", "4.7"),
                        List.of("Response write", "15", "5 %", "3.0"));
    }

    /**
     * With call-site stamps (M5-4c), a method's own time is its self time minus the recorded calls stamped to it, so
     * however much of the handler the SQL takes, the methods share only the handler's other work: 10 ms of SQL in a
     * 50 ms handler leaves 40 ms, of which the slow method's 30 ms of own time is three quarters. Calls without a stamp
     * stay in their method's own time, and a limitation says so.
     */
    @Test
    void withStampedCallsTheHandlerIsSplitByOwnTimeHoweverMuchTheCallsTake() {
        quoteRequests(10 * MS);
        RuntimeInsightsService service =
                new RuntimeInsightsService(journal, null, null, InsightsStack.SPRING_MVC, null);
        HandlerMethods stamped = new HandlerMethods(
                "GET /api/quote",
                5,
                5 * 40 * MS,
                List.of(
                        new HandlerMethods.Method(
                                "shop.SlowPricingService#quote()I", "SlowPricingService.quote", 5 * 30 * MS),
                        new HandlerMethods.Method(
                                "shop.QuoteController#quote()I", "QuoteController.quote", 5 * 5 * MS)),
                false,
                5,
                0);
        service.setCodePaths(route -> route.equals("GET /api/quote") ? stamped : null, () -> 1L);

        RuntimeObservationDto quote = breakdown(service, "GET /api/quote");
        assertThat(quote.sentence()).contains("Handler: SlowPricingService.quote", "SQL 18 %");
        assertThat(service.insight(quote.id()).rows())
                .extracting(RuntimeObservationRowDto::cells)
                .containsExactly(
                        List.of("Other filters", "10", "4 %", "2.0"),
                        List.of("SQL", "50", "18 %", "10"),
                        List.of("Handler: SlowPricingService.quote", "150", "55 %", "30"),
                        List.of("Handler: QuoteController.quote", "25", "9 %", "5.0"),
                        List.of("Other handler time", "25", "9 %", "5.0"),
                        List.of("Response write", "15", "5 %", "3.0"));
        assertThat(quote.limitations()).noneMatch(limitation -> limitation.contains("no stamp"));

        // A few calls without a stamp, 1 ms of a 50 ms handler: still split, and said.
        HandlerMethods few =
                new HandlerMethods("GET /api/quote", 5, stamped.handlerNanos(), stamped.methods(), false, 5, 2, 5 * MS);
        service.setCodePaths(route -> route.equals("GET /api/quote") ? few : null, () -> 2L);
        RuntimeObservationDto split = breakdown(service, "GET /api/quote");
        assertThat(split.sentence()).contains("Handler: SlowPricingService.quote");
        assertThat(split.limitations())
                .anyMatch(limitation -> limitation.contains("2 recorded calls carried no stamp")
                        && limitation.contains("under 10 % of the handler's time"));
    }

    /**
     * I3: calls recorded without a stamp stay in the own time of the method that waited for them, so a handler whose
     * unstamped calls take a tenth of its phase or more is not split, nor one none of whose calls carried a stamp.
     */
    @Test
    void theHandlerIsNotSplitWhenCallsWithoutAStampTakeATenthOfItOrNoneIsStamped() {
        quoteRequests(10 * MS);
        RuntimeInsightsService service =
                new RuntimeInsightsService(journal, null, null, InsightsStack.SPRING_MVC, null);
        List<HandlerMethods.Method> methods = List.of(
                new HandlerMethods.Method("shop.SlowPricingService#quote()I", "SlowPricingService.quote", 5 * 30 * MS),
                new HandlerMethods.Method("shop.QuoteController#quote()I", "QuoteController.quote", 5 * 5 * MS));

        // 10 ms of a 50 ms handler phase per request carried no stamp: 20 %.
        HandlerMethods heavy = new HandlerMethods("GET /api/quote", 5, 5 * 40 * MS, methods, false, 5, 5, 5 * 10 * MS);
        service.setCodePaths(route -> route.equals("GET /api/quote") ? heavy : null, () -> 1L);
        RuntimeObservationDto quote = breakdown(service, "GET /api/quote");
        assertThat(quote.sentence()).doesNotContain("Handler: SlowPricingService.quote");
        assertThat(quote.limitations())
                .anyMatch(limitation -> limitation.startsWith("The handler is not split by method: 5 recorded calls")
                        && limitation.contains("take 20 % of its time"));

        // No call carried a stamp, as with an agent predating them: never split, whatever their time.
        HandlerMethods none = new HandlerMethods("GET /api/quote", 5, 5 * 40 * MS, methods, false, 0, 5, 0L);
        service.setCodePaths(route -> route.equals("GET /api/quote") ? none : null, () -> 2L);
        quote = breakdown(service, "GET /api/quote");
        assertThat(quote.sentence()).doesNotContain("Handler: SlowPricingService.quote");
        assertThat(quote.limitations())
                .anyMatch(limitation -> limitation.startsWith(
                        "The handler is not split by method: none of its 5 recorded calls carried a code-paths stamp"));

        // Just under a tenth: split.
        HandlerMethods under = new HandlerMethods("GET /api/quote", 5, 5 * 40 * MS, methods, false, 5, 5, 5 * 4 * MS);
        service.setCodePaths(route -> route.equals("GET /api/quote") ? under : null, () -> 3L);
        assertThat(breakdown(service, "GET /api/quote").sentence()).contains("Handler: SlowPricingService.quote");
    }

    /** A cold request, then five warm ones whose 50 ms handler runs {@code sql} of SQL. */
    private void quoteRequests(long sql) {
        request("/api/quote", 200 * MS, new RequestTiming(0, -1, -1, -1));
        for (int i = 0; i < 5; i++) {
            long start = clock;
            request(
                    "/api/quote",
                    55 * MS,
                    new RequestTiming(start, -1, 2 * MS, 52 * MS),
                    new Child(
                            JournalSource.SQL,
                            sql,
                            new SqlPayload("select 1", null, "db", false, null, null, start + 40 * MS)));
        }
    }

    private static HandlerMethods quoteMethods() {
        return new HandlerMethods(
                "GET /api/quote",
                5,
                5 * 50 * MS,
                List.of(
                        new HandlerMethods.Method(
                                "shop.SlowPricingService#quote()I", "SlowPricingService.quote", 200 * MS),
                        new HandlerMethods.Method("shop.QuoteController#quote()I", "QuoteController.quote", 25 * MS)),
                false);
    }

    @Test
    void theHandlerSplitNeverExceedsTheHandlersWorkAndSkipsAnAssemblyOnlyRoute() {
        request("/api/quote", 200 * MS, new RequestTiming(0, -1, -1, -1));
        for (int i = 0; i < 5; i++) {
            request("/api/quote", 30 * MS, new RequestTiming(clock, -1, 0, 30 * MS));
        }
        RuntimeInsightsService service =
                new RuntimeInsightsService(journal, null, null, InsightsStack.SPRING_MVC, null);
        // The tree's methods claim twice the handler's time, as when clocks disagree: the parts are clipped.
        HandlerMethods methods = new HandlerMethods(
                "GET /api/quote",
                5,
                5 * 60 * MS,
                List.of(
                        new HandlerMethods.Method("a.A#a()V", "A.a", 5 * 40 * MS),
                        new HandlerMethods.Method("a.B#b()V", "B.b", 5 * 20 * MS)),
                false);
        service.setCodePaths(route -> methods, () -> 1L);
        List<List<String>> rows = service
                .insight(breakdown(service, "GET /api/quote").id())
                .rows()
                .stream()
                .map(RuntimeObservationRowDto::cells)
                .toList();
        assertThat(rows)
                .containsExactly(
                        List.of("Handler: A.a", "100", "67 %", "20"), List.of("Handler: B.b", "50", "33 %", "10"));

        HandlerMethods assembly = new HandlerMethods("GET /api/quote", 5, 5 * 30 * MS, methods.methods(), true);
        service.setCodePaths(route -> assembly, () -> 2L);
        RuntimeObservationDto quote = breakdown(service, "GET /api/quote");
        assertThat(quote.sentence()).contains("Handler, other work 100 %").doesNotContain("A.a");
        assertThat(quote.limitations()).anyMatch(limitation -> limitation.contains("assembly"));
    }

    /**
     * M52-03: two overloads and two same-named classes in different packages keep a row each, labelled apart, and every
     * part taken from the handler stays in a row: the rows add up to the requests' whole time.
     */
    @Test
    void overloadsAndSameNamedClassesKeepTheirOwnRowsAndNoTimeIsLost() {
        request("/api/quote", 200 * MS, new RequestTiming(0, -1, -1, -1));
        for (int i = 0; i < 5; i++) {
            request("/api/quote", 30 * MS, new RequestTiming(clock, -1, 0, 30 * MS));
        }
        RuntimeInsightsService service =
                new RuntimeInsightsService(journal, null, null, InsightsStack.SPRING_MVC, null);
        HandlerMethods methods = new HandlerMethods(
                "GET /api/quote",
                5,
                5 * 30 * MS,
                List.of(
                        new HandlerMethods.Method("shop.Service#work(Ljava/lang/String;)V", "Service.work", 5 * 7 * MS),
                        new HandlerMethods.Method("shop.Service#work(I)V", "Service.work", 5 * 6 * MS),
                        new HandlerMethods.Method("a.Util#run()V", "Util.run", 5 * 5 * MS),
                        new HandlerMethods.Method("b.Util#run()V", "Util.run", 5 * 4 * MS)),
                false);
        service.setCodePaths(route -> methods, () -> 1L);

        List<List<String>> rows = service
                .insight(breakdown(service, "GET /api/quote").id())
                .rows()
                .stream()
                .map(RuntimeObservationRowDto::cells)
                .toList();

        assertThat(rows)
                .extracting(row -> row.get(0) + "=" + row.get(1))
                .containsExactly(
                        "Handler: Service.work(String)=35",
                        "Handler: Service.work(int)=30",
                        "Handler: a.Util.run()=25",
                        "Handler: b.Util.run()=20",
                        "Other handler time=40");
        assertThat(rows.stream()
                        .mapToDouble(row -> Double.parseDouble(row.get(1)))
                        .sum())
                .as("every part of the five 30 ms requests is in a row")
                .isEqualTo(150.0);
    }

    @Test
    void theHandlerIsNotSplitWhileTheCodePathsPanelIsDisabled() {
        request("/api/quote", 200 * MS, new RequestTiming(0, -1, -1, -1));
        for (int i = 0; i < 5; i++) {
            request("/api/quote", 30 * MS, new RequestTiming(clock, -1, 0, 30 * MS));
        }
        RuntimeInsightsService service = new RuntimeInsightsService(
                journal, null, panel -> !panel.equals("code-paths"), InsightsStack.SPRING_MVC, null);
        service.setCodePaths(
                route -> new HandlerMethods(
                        route,
                        5,
                        5 * 30 * MS,
                        List.of(new HandlerMethods.Method("a.A#a()V", "A.a", 5 * 30 * MS)),
                        false),
                () -> 1L);
        assertThat(breakdown(service, "GET /api/quote").sentence()).doesNotContain("A.a");
    }

    private static RuntimeObservationDto breakdown(RuntimeInsightsService service, String route) {
        return service.report().observations().stream()
                .filter(observation -> observation.kind().equals(RouteTimeBreakdown.KIND))
                .filter(observation -> observation.subject().equals(route))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void afterAClearNoRequestIsReportedAsColdBecauseTheJournalKeptCountingSinceStartup() {
        request("/api/orders/{id}", 400 * MS, new RequestTiming(0, -1, -1, -1));
        journal.clear();
        // The next requests start well after the cleared one, so none may have lost an event to the clear.
        requests += 100;
        for (int i = 0; i < 5; i++) {
            request("/api/orders/{id}", 40 * MS, new RequestTiming(clock, 2 * MS, 5 * MS, 35 * MS));
        }

        RuntimeObservationDto orders = new RuntimeInsightsService(journal, null, null, InsightsStack.SPRING_MVC, null)
                .report().observations().stream()
                        .filter(observation -> observation.kind().equals(RouteTimeBreakdown.KIND))
                        .findFirst()
                        .orElseThrow();

        assertThat(orders.sentence()).doesNotContain("cold").contains("warm median 40 ms over 5 requests");
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

    @Test
    void anAiCallTimedOnTheRequestClockClaimsTheModelHttpCallInsideItRatherThanCountingItTwice() {
        for (int i = 0; i < 6; i++) {
            long start = clock;
            request(
                    "/api/chat",
                    100 * MS,
                    new RequestTiming(start, -1, 2 * MS, 95 * MS),
                    restCall(start + 90 * MS, 40 * MS),
                    new Child(
                            JournalSource.AI,
                            50 * MS,
                            new AiPayload("chat", "openai", "gpt-4o", 10L, 5L, "stop", false, null, start + 92 * MS)));
        }

        List<String> rows = phaseRows("GET /api/chat");

        assertThat(rows)
                .as("the model's HTTP call inside the AI call is counted once, as AI")
                .contains("AI calls=50", "Handler, other work=43")
                .noneMatch(row -> row.startsWith("REST client"));
    }

    @Test
    void anAiCallWithoutItsCompletionIsCarvedOnlyBeyondTheRestClientTimeAlreadyCounted() {
        for (int i = 0; i < 6; i++) {
            long start = clock;
            request(
                    "/api/chat",
                    100 * MS,
                    new RequestTiming(start, -1, 2 * MS, 95 * MS),
                    restCall(start + 90 * MS, 40 * MS),
                    new Child(
                            JournalSource.AI,
                            50 * MS,
                            new AiPayload("chat", "openai", "gpt-4o", 10L, 5L, "stop", false),
                            45));
        }

        assertThat(phaseRows("GET /api/chat"))
                .as("only the AI time beyond its model HTTP call is taken out of the handler")
                .contains("REST client=40", "AI calls=10", "Handler, other work=43");
    }

    @Test
    void anUntimedAiCallIsCarvedOnlyBeyondTheTimedCallsAndStatementsNestedInIt() {
        for (int i = 0; i < 6; i++) {
            long start = clock;
            request(
                    "/api/chat",
                    100 * MS,
                    new RequestTiming(start, -1, 2 * MS, 95 * MS),
                    new Child(
                            JournalSource.SQL,
                            10 * MS,
                            new SqlPayload("select 1", null, "db", false, null, null, start + 20 * MS)),
                    new Child(
                            JournalSource.AI,
                            30 * MS,
                            new AiPayload("chat", "openai", "gpt-4o", 10L, 5L, "stop", false, null, start + 60 * MS)),
                    new Child(
                            JournalSource.AI,
                            60 * MS,
                            new AiPayload("chat", "openai", "gpt-4o", 10L, 5L, "stop", false),
                            5));
        }

        assertThat(phaseRows("GET /api/chat"))
                .as("the untimed outer call adds only what its timed inner call and statement did not cover")
                .contains("SQL=10", "AI calls=50", "Handler, other work=33");
    }

    @Test
    void nestedAiCallsWithoutTheirCompletionAreCountedOnce() {
        for (int i = 0; i < 6; i++) {
            request(
                    "/api/chat",
                    100 * MS,
                    new RequestTiming(clock, -1, 2 * MS, 95 * MS),
                    new Child(
                            JournalSource.AI,
                            60 * MS,
                            new AiPayload("chat", "openai", "gpt-4o", 10L, 5L, "stop", false),
                            2),
                    new Child(
                            JournalSource.AI,
                            30 * MS,
                            new AiPayload("embeddings", "openai", "text-embedding", 10L, null, null, false),
                            2));
        }

        assertThat(phaseRows("GET /api/chat"))
                .as("both started together, so the inner call adds nothing to the outer one")
                .contains("AI calls=60", "Handler, other work=33");
    }

    @Test
    void anUntimedAiCallKeepsTheCallsTheRequestMadeOutsideItsWindow() {
        for (int i = 0; i < 6; i++) {
            long start = clock;
            request(
                    "/api/rag",
                    800 * MS,
                    new RequestTiming(start, -1, 2 * MS, 795 * MS),
                    new Child(
                            JournalSource.SQL,
                            300 * MS,
                            new SqlPayload("select embedding", null, "db", false, null, null, start + 310 * MS)),
                    new Child(
                            JournalSource.AI,
                            400 * MS,
                            new AiPayload("chat", "openai", "gpt-4o", 10L, 5L, "stop", false),
                            350));
        }

        assertThat(phaseRows("GET /api/rag"))
                .as("the vector-store query before the model call is not taken out of the model's time")
                .contains("SQL=300", "AI calls=400", "Handler, other work=93");
        RuntimeObservationDto rag = bySubject(breakdowns(InsightsStack.SPRING_MVC), "GET /api/rag");
        assertThat(rag.limitations())
                .anySatisfy(limitation ->
                        assertThat(limitation).startsWith("5 requests made AI calls known only from GenAI spans"));
    }

    @Test
    void anUntimedAiCallMovesOnlyItsTimeInsideTheHandler() {
        for (int i = 0; i < 6; i++) {
            request(
                    "/api/chat",
                    100 * MS,
                    new RequestTiming(clock, -1, 20 * MS, 95 * MS),
                    new Child(
                            JournalSource.AI,
                            50 * MS,
                            new AiPayload("chat", "openai", "gpt-4o", 10L, 5L, "stop", false),
                            0));
        }

        assertThat(phaseRows("GET /api/chat"))
                .as("the 20 ms it overlapped the filters stay in the filters")
                .contains("Other filters=20", "AI calls=30", "Handler, other work=45");
    }

    @Test
    void toolAndRetrievalCallsWithoutTheirCompletionStayInTheHandlerTheyWrap() {
        for (int i = 0; i < 6; i++) {
            request(
                    "/api/agent",
                    100 * MS,
                    new RequestTiming(clock, -1, 2 * MS, 95 * MS),
                    new Child(
                            JournalSource.AI, 30 * MS, new AiPayload("tool", null, "lookup", null, null, null, false)),
                    new Child(
                            JournalSource.AI,
                            20 * MS,
                            new AiPayload("retrieval", null, "docs", null, null, null, false)));
        }

        assertThat(phaseRows("GET /api/agent"))
                .contains("Handler, other work=93")
                .noneMatch(row -> row.startsWith("AI calls"));
    }

    private Child restCall(long completedNanos, long nanos) {
        return new Child(
                JournalSource.REST_CLIENT,
                nanos,
                new RestClientPayload(
                        "POST", "api.openai.com", "/v1/chat", 200, "RestClient", false, null, completedNanos));
    }

    /** The breakdown's phase rows, as {@code label=median ms}. */
    private List<String> phaseRows(String subject) {
        RuntimeInsightsService service =
                new RuntimeInsightsService(journal, null, null, InsightsStack.SPRING_MVC, null);
        RuntimeObservationDto breakdown = service.report().observations().stream()
                .filter(observation -> observation.kind().equals(RouteTimeBreakdown.KIND))
                .filter(observation -> observation.subject().equals(subject))
                .findFirst()
                .orElseThrow();
        return service.insight(breakdown.id()).rows().stream()
                .map(RuntimeObservationRowDto::cells)
                .map(cells -> cells.get(0) + "=" + cells.get(3))
                .toList();
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
                    child.source(),
                    1_000 + requests + child.startMillis(),
                    child.nanos(),
                    context,
                    "http-1",
                    null,
                    false,
                    child.payload()));
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

    /** A child event that started {@code startMillis} after its request, by the wall clock. */
    private record Child(JournalSource source, long nanos, RuntimeEventPayload payload, long startMillis) {

        Child(JournalSource source, long nanos, RuntimeEventPayload payload) {
            this(source, nanos, payload, 0);
        }
    }
}
