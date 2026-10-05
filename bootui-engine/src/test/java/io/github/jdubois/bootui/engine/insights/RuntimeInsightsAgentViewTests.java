package io.github.jdubois.bootui.engine.insights;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.RuntimeInsightAgentDetailDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightCheckDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightsAgentReportDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightsReportDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightsWindowDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDetailDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationRowDto;
import io.github.jdubois.bootui.core.dto.RuntimeRunChangeDto;
import io.github.jdubois.bootui.core.dto.RuntimeRunComparisonAgentDto;
import io.github.jdubois.bootui.core.dto.RuntimeRunComparisonDto;
import io.github.jdubois.bootui.core.dto.RuntimeRunRefDto;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** §5.6: the panel's report, observation, and comparison compacted into facts an agent can refuse to act on. */
class RuntimeInsightsAgentViewTests {

    @Test
    void theDefaultListPutsCoverageAndUnrunChecksFirstAndNamesTheValidationOfItsRowsKinds() {
        RuntimeInsightsAgentReportDto list = RuntimeInsightsAgentView.list(report(), null, null);

        assertThat(list.available()).isTrue();
        assertThat(list.checksNotRun())
                .containsExactly("event-loop-blocking: NOT_APPLICABLE, Spring MVC serves requests on worker threads.");
        assertThat(list.observations())
                .extracting(observation -> observation.kind())
                .contains(RouteTimeBreakdown.KIND)
                .hasSize(8);
        assertThat(list.omitted()).isEqualTo(3);
        assertThat(list.observations().get(0).exemplarRequestId()).isEqualTo("r-0");
        assertThat(list.observations().get(0).verify()).isEqualTo("Check the call site.");
        assertThat(list.limitations())
                .anyMatch(limitation -> limitation.startsWith("External validation (M4-20) of these rows' kinds:")
                        && limitation.contains(
                                "did not pass their external validation: route-time-breakdown," + " exception-hotspots")
                        && limitation.contains("too few facts to validate: repeated-selects, anonymous-data-reach"))
                .noneMatch(limitation -> limitation.contains("Latency rows are included"))
                .noneMatch(limitation -> limitation.contains("repeated-selects row"))
                .noneMatch(limitation -> limitation.contains("Latency-only observations are left out"));
        assertThat(list.requests()).isEqualTo(12);
        assertThat(list.notExercised()).hasSize(8).first().isEqualTo("GET /api/unused/0");
        assertThat(list.notExercisedOmitted()).isEqualTo(2 + 5);
    }

    @Test
    void aProlificKindCannotHideTheOthersAndWhatIsLeftOutSaysHowToListIt() {
        RuntimeInsightsAgentReportDto list = RuntimeInsightsAgentView.list(report(), null, null);

        assertThat(list.observations())
                .extracting(observation -> observation.kind())
                .contains(AnonymousDataReach.KIND, ExceptionHotspots.KIND)
                .filteredOn(RepeatedSelects.KIND::equals)
                .hasSize(5);
        assertThat(list.limitations()).contains("Left out by limit: repeated-selects 3.");
        assertThat(list.next())
                .extracting(step -> step.command())
                .contains("bootui insights list --query repeated-selects");
        assertThat(RuntimeInsightsAgentView.list(report(), "Repeated-Selects", 20)
                        .observations())
                .hasSize(8)
                .extracting(observation -> observation.kind())
                .containsOnly(RepeatedSelects.KIND);
    }

    @Test
    void aRunWithoutRequestsSaysItsEmptyListMeansNotExercised() {
        RuntimeInsightsReportDto idle = new RuntimeInsightsReportDto(
                true,
                null,
                new RuntimeInsightsWindowDto("run-1", null, null, 40, 0, 0, 0),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                0);

        RuntimeInsightsAgentReportDto list = RuntimeInsightsAgentView.list(idle, null, null);

        assertThat(list.requests()).isZero();
        assertThat(list.observations()).isEmpty();
        assertThat(list.limitations().get(0)).contains("not exercised, not healthy");
    }

    @Test
    void requestsZeroIsNotIdleWhenObservationsEvictionOrNonHttpExecutionsSayOtherwise() {
        RuntimeInsightsReportDto observed = idleReport(List.of(observation(1, RepeatedSelects.KIND, "job", "ran")), 0);
        assertThat(RuntimeInsightsAgentView.list(observed, null, null)
                        .limitations()
                        .get(0))
                .contains("requests counts completed HTTP exchanges only")
                .doesNotContain("not exercised, not healthy")
                .doesNotContain("nothing was exercised");

        RuntimeInsightsReportDto evicted = idleReport(List.of(), 4);
        assertThat(RuntimeInsightsAgentView.list(evicted, null, null)
                        .limitations()
                        .get(0))
                .contains("not proof the run was idle")
                .doesNotContain("not exercised, not healthy");

        RuntimeInsightsReportDto jobs = new RuntimeInsightsReportDto(
                true,
                null,
                new RuntimeInsightsWindowDto("run-1", null, null, 40, 0, 0, 0),
                List.of(),
                List.of(),
                List.of(),
                List.of(RuntimeInsightsService.NON_HTTP_PREFIX + " 1 scheduled run and 0 consumed messages."),
                List.of(),
                0);
        assertThat(RuntimeInsightsAgentView.list(jobs, null, null).limitations().get(0))
                .contains(RuntimeInsightsService.NON_HTTP_PREFIX)
                .doesNotContain("not exercised, not healthy");
    }

    @Test
    void requestsZeroAfterAClearSaysRequestsWereLeftOutRatherThanAskingForTraffic() {
        RuntimeInsightsReportDto cleared = new RuntimeInsightsReportDto(
                true,
                null,
                new RuntimeInsightsWindowDto("run-1", null, null, 12, 0, 0, 0),
                List.of(),
                List.of(),
                List.of(),
                List.of(RuntimeInsightsService.leftOutBeforeLoss(2, 0)),
                List.of(),
                0);

        assertThat(RuntimeInsightsAgentView.list(cleared, null, null)
                        .limitations()
                        .get(0))
                .contains(RuntimeInsightsService.LEFT_OUT_BEFORE_LOSS, "not proof the run was idle")
                .doesNotContain("not exercised, not healthy", "send it traffic");
    }

    @Test
    void theEmptyQueryOmitsRepeatedSelectsUnderTheFloorAndANamedQueryDoesNot() {
        RuntimeObservationDto cheap = observation(
                1,
                RepeatedSelects.KIND,
                "GET /api/cheap",
                "a cheap repeat",
                List.of(RepeatedSelects.UNDER_DEFAULT_FLOOR));
        RuntimeObservationDto kept = observation(2, RepeatedSelects.KIND, "GET /api/kept", "a measured repeat");
        RuntimeInsightsReportDto report = new RuntimeInsightsReportDto(
                true,
                null,
                new RuntimeInsightsWindowDto("run-1", 1L, 2L, 10, 3, 0, 0),
                List.of(),
                List.of(),
                List.of(cheap, kept),
                List.of(),
                List.of(),
                0);

        RuntimeInsightsAgentReportDto list = RuntimeInsightsAgentView.list(report, null, null);
        assertThat(list.observations())
                .extracting(observation -> observation.id())
                .containsExactly("repeated-selects:2");
        assertThat(list.limitations()).anyMatch(limitation -> limitation.contains("Left out 1 repeated-selects row"));
        assertThat(list.next())
                .anyMatch(step -> step.tool().equals("get_runtime_insights")
                        && step.arguments().equals(java.util.Map.of("query", RepeatedSelects.KIND)));
        assertThat(RuntimeInsightsAgentView.list(report, "repeated-selects", 20).observations())
                .extracting(observation -> observation.id())
                .containsExactly("repeated-selects:1", "repeated-selects:2");
        assertThat(RuntimeInsightsAgentView.list(report, "repeated-selects", 20).limitations())
                .noneMatch(limitation -> limitation.contains("Left out"));
    }

    @Test
    void aRunLevelObservationWithNoExemplarDoesNotHideTheIdleGuidance() {
        RuntimeObservationDto heap = new RuntimeObservationDto(
                "heap-growth-after-gc:1",
                HeapGrowthAfterGc.KIND,
                "jvm",
                "INSUFFICIENT",
                "one collection is not enough to judge heap growth",
                0,
                0,
                "RUN",
                List.of(),
                List.of(),
                0,
                List.of(),
                false,
                DefaultListing.MEMORY);

        RuntimeInsightsAgentReportDto list = RuntimeInsightsAgentView.list(idleReport(List.of(heap), 0), null, null);

        assertThat(list.observations())
                .as("heap rows are reached from the Memory panel")
                .isEmpty();
        assertThat(RuntimeInsightsAgentView.list(idleReport(List.of(heap), 0), "all", null)
                        .observations())
                .hasSize(1);
        assertThat(list.limitations().get(0))
                .contains("not exercised, not healthy", "Run the application's tests")
                .doesNotContain("empty list")
                .doesNotContain("not proof nothing ran");
    }

    @Test
    void aQueryNamesLatencySecurityNewARouteOrPointsToTheComparison() {
        assertThat(RuntimeInsightsAgentView.list(report(), "latency", 20).observations())
                .singleElement()
                .extracting(observation -> observation.kind())
                .isEqualTo(RouteTimeBreakdown.KIND);
        assertThat(RuntimeInsightsAgentView.list(report(), "security", 20).observations())
                .extracting(observation -> observation.kind())
                .containsOnly(AnonymousDataReach.KIND);
        assertThat(RuntimeInsightsAgentView.list(report(), "new", 20).observations())
                .extracting(observation -> observation.kind())
                .containsOnly(ExceptionHotspots.KIND);
        assertThat(RuntimeInsightsAgentView.list(report(), "/api/orders/3", 20).observations())
                .extracting(observation -> observation.subject())
                .containsExactly("GET /api/orders/3");
        assertThat(RuntimeInsightsAgentView.list(report(), "diff", 20).next())
                .first()
                .satisfies(step -> {
                    assertThat(step.command()).isEqualTo("bootui insights compare previous");
                    assertThat(step.tool()).isEqualTo("get_runtime_run_comparison");
                    assertThat(step.arguments()).containsExactly(java.util.Map.entry("id", "previous"));
                });
    }

    /**
     * The empty query lists what the panel lists by default (M4-19); {@code all}, a kind, or a route reach the rest,
     * listed rows first, and a limitation counts what the default left out.
     */
    @Test
    void theDefaultListLeavesOutUnlistedRowsAndSaysHowToReachThem() {
        List<RuntimeObservationDto> observations = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            observations.add(unlisted(
                    observation(i, RouteTimeBreakdown.KIND, "GET /api/fast/" + i, "warm median 3 ms"),
                    RouteTimeBreakdown.NOT_PROMINENT));
        }
        observations.add(observation(6, RouteTimeBreakdown.KIND, "GET /api/slow", "warm median 120 ms"));
        observations.add(unlisted(
                observation(7, GcInflatedLatency.KIND, "GET /api/slow", "a pause completed during"),
                DefaultListing.MEMORY));
        RuntimeInsightsReportDto report = idleReport(observations, 0);

        RuntimeInsightsAgentReportDto byDefault = RuntimeInsightsAgentView.list(report, "", 8);
        assertThat(byDefault.observations()).singleElement().satisfies(observation -> {
            assertThat(observation.subject()).isEqualTo("GET /api/slow");
            assertThat(observation.listed()).isTrue();
        });
        assertThat(byDefault.omitted()).isZero();
        assertThat(byDefault.limitations())
                .contains("Not listed by default: route-time-breakdown 6, gc-inflated-latency 1. Pass the query all,"
                        + " a kind, or a route to list them; get_runtime_insight on one says why it is left out.");

        assertThat(byDefault.next())
                .as("the next calls name the query that lists what the default left out")
                .anyMatch(step -> "all".equals(step.arguments().get("query")));
        assertThat(RuntimeInsightsAgentView.list(report, "all", 50).observations())
                .hasSize(8);
        RuntimeInsightsAgentReportDto byKind = RuntimeInsightsAgentView.list(report, RouteTimeBreakdown.KIND, 2);
        assertThat(byKind.observations())
                .extracting(observation -> observation.subject())
                .as("the listed row comes first, whatever the report order")
                .containsExactly("GET /api/slow", "GET /api/fast/0");
        assertThat(byKind.observations().get(1).listed()).isFalse();
        assertThat(byKind.limitations()).noneMatch(limitation -> limitation.startsWith("Not listed by default"));

        RuntimeInsightAgentDetailDto detail = RuntimeInsightsAgentView.detail(
                new RuntimeObservationDetailDto(true, null, observations.get(0), List.of("Phase"), List.of(), 0));
        assertThat(detail.limitations().get(0)).isEqualTo("Not listed by default: " + RouteTimeBreakdown.NOT_PROMINENT);
    }

    @Test
    void anUnavailableReportOrObservationSaysWhyInsteadOfReturningAnEmptySuccess() {
        RuntimeInsightsReportDto disabled = new RuntimeInsightsReportDto(
                false, "The journal is disabled.", null, List.of(), List.of(), List.of(), List.of(), List.of(), 0);
        assertThat(RuntimeInsightsAgentView.list(disabled, "", 8).unavailableReason())
                .isEqualTo("The journal is disabled.");
        RuntimeInsightAgentDetailDto unknown = RuntimeInsightsAgentView.detail(
                new RuntimeObservationDetailDto(false, "No observation x.", null, List.of(), List.of(), 0));
        assertThat(unknown.available()).isFalse();
        assertThat(unknown.unavailableReason()).isEqualTo("No observation x.");
        assertThat(unknown.next())
                .as("an unknown id names the call that lists the current ones")
                .singleElement()
                .satisfies(step -> assertThat(step.command()).isEqualTo("bootui insights list"));
        assertThat(RuntimeInsightsAgentView.list(disabled, "", 8).next())
                .extracting(step -> step.command())
                .containsExactly("bootui config --query bootui.runtime-journal");

        RuntimeInsightAgentDetailDto known = RuntimeInsightsAgentView.detail(new RuntimeObservationDetailDto(
                true,
                null,
                observation(1, RepeatedSelects.KIND, "GET /api/orders/1", "sentence"),
                List.of("Request"),
                List.of(new RuntimeObservationRowDto(List.of("r-1"))),
                4));
        assertThat(known.observation().id()).isEqualTo("repeated-selects:1");
        assertThat(known.whatToCheck()).containsExactly("Check the call site.", "Then the join.");
        assertThat(known.truncated()).isEqualTo(4);
    }

    @Test
    void aComparisonKeepsComparabilityAndEightBehaviorRowsAndLeavesOutLatency() {
        List<RuntimeRunChangeDto> behavior = IntStream.range(0, 11)
                .mapToObj(i -> new RuntimeRunChangeDto("k", "GET /a", null, "INCREASED", 1.0, 2.0, 5, 5, "s" + i))
                .toList();
        RuntimeRunComparisonAgentDto compact = RuntimeInsightsAgentView.comparison(new RuntimeRunComparisonDto(
                "NOT_COMPARABLE",
                "The data source changed.",
                new RuntimeRunRefDto("run-5", 5, 3, null, 12, "CURRENT"),
                new RuntimeRunRefDto("run-4", 4, 1, 2L, 10, "KEPT"),
                List.of(new RuntimeRunRefDto("run-4", 4, 1, 2L, 10, "KEPT")),
                List.of("jdbc:h2 then jdbc:postgresql"),
                behavior,
                List.of(),
                null,
                List.of(behavior.get(0)),
                List.of()));

        assertThat(compact.status()).isEqualTo("NOT_COMPARABLE");
        assertThat(compact.notComparableReasons()).containsExactly("jdbc:h2 then jdbc:postgresql");
        assertThat(compact.previousRunId()).isEqualTo("run-4");
        assertThat(compact.currentRunId()).isEqualTo("run-5");
        assertThat(compact.runs()).extracting(run -> run.runId()).containsExactly("run-4");
        assertThat(compact.behavior()).hasSize(8);
        assertThat(compact.behaviorOmitted()).isEqualTo(3);
        assertThat(compact.limitations()).anyMatch(limitation -> limitation.contains("Latency rows are left out"));
        assertThat(RuntimeInsightsAgentView.runId(" previous ")).isNull();
        assertThat(RuntimeInsightsAgentView.runId("run-4")).isEqualTo("run-4");
    }

    @Test
    void aListNamesTheEvidenceAndOneRequestOfItsLeadObservation() {
        RuntimeInsightsAgentReportDto list = RuntimeInsightsAgentView.list(report(), null, null);

        assertThat(list.next())
                .extracting(step -> step.command())
                .containsExactly(
                        "bootui insights show repeated-selects:0",
                        "bootui request-profile r-0",
                        "bootui insights list --query repeated-selects");
        assertThat(list.next().get(0).why()).contains("call sites");
        assertThat(list.next().get(1).arguments()).containsExactly(java.util.Map.entry("id", "r-0"));
    }

    @Test
    void theWaysToAskForMoreKeepTheirPlaceBesideABusyLeadObservation() {
        List<RuntimeObservationDto> observations = new ArrayList<>();
        observations.add(observation(0, ExceptionHotspots.KIND, "GET /api/boom", "recorded `X`"));
        for (int i = 1; i < 6; i++) {
            observations.add(observation(i, RepeatedSelects.KIND, "GET /api/orders/" + i, "repeated a SELECT"));
        }
        RuntimeInsightsReportDto report = new RuntimeInsightsReportDto(
                true,
                null,
                new RuntimeInsightsWindowDto("run-1", 1L, 2L, 10, 3, 0, 0),
                List.of(),
                List.of(),
                observations,
                List.of(),
                List.of(),
                0);

        RuntimeInsightsAgentReportDto list = RuntimeInsightsAgentView.list(report, null, 3);
        assertThat(list.next())
                .extracting(step -> step.command())
                .containsExactly(
                        "bootui exceptions list",
                        "bootui insights show exception-hotspots:0",
                        "bootui insights list --query repeated-selects");

        RuntimeInsightsAgentReportDto filtered = RuntimeInsightsAgentView.list(report, "/api/orders", 2);
        assertThat(filtered.next())
                .as("a filtered list keeps its filter and asks for more rows rather than another query")
                .extracting(step -> step.command())
                .contains("bootui insights list --query /api/orders --limit 50");
    }

    @Test
    void anAnonymousAccessObservationNamesTheSecurityRulesOnlyWhereTheStackHasThem() {
        RuntimeObservationDetailDto anonymous = new RuntimeObservationDetailDto(
                true,
                null,
                observation(9, AnonymousDataReach.KIND, "GET /api/payroll/report", "an anonymous read"),
                List.of(),
                List.of(),
                0);

        assertThat(RuntimeInsightsAgentView.detail(anonymous, tool -> true).next())
                .extracting(step -> step.command())
                .containsExactly(
                        "bootui security config",
                        "bootui mappings --query /api/payroll/report",
                        "bootui request-profile r-9");
        assertThat(RuntimeInsightsAgentView.detail(anonymous, tool -> !tool.equals("get_spring_security"))
                        .next())
                .as("Quarkus has no get_spring_security, so it is never named there")
                .extracting(step -> step.tool())
                .containsExactly("get_mappings", "get_request_profile");
    }

    @Test
    void anUnknownRunIdNamesThePreviousRunAndTheRunsStillKept() {
        RuntimeRunComparisonAgentDto unknown = RuntimeInsightsAgentView.comparison(
                new RuntimeRunComparisonDto(
                        "NO_PREVIOUS_RUN",
                        "No kept run has the id run-1.",
                        new RuntimeRunRefDto("run-5", 5, 3, null, 12, "CURRENT"),
                        null,
                        List.of(
                                new RuntimeRunRefDto("run-4", 4, 1, 2L, 10, "KEPT"),
                                new RuntimeRunRefDto("run-3", 3, 0, 1L, 9, "KEPT")),
                        List.of(),
                        List.of(),
                        List.of(),
                        null,
                        List.of(),
                        List.of()),
                "run-1",
                null);

        assertThat(unknown.next())
                .extracting(step -> step.command())
                .as("previous already selects run-4, the newest kept run")
                .containsExactly("bootui insights compare previous", "bootui insights compare run-3");
    }

    @Test
    void anAmbiguousOrUnknownImpactNamesTheCallsThatResolveIt() {
        io.github.jdubois.bootui.core.dto.RuntimeChangeImpactDto ambiguous =
                new io.github.jdubois.bootui.core.dto.RuntimeChangeImpactDto(
                        ChangeImpactService.AMBIGUOUS,
                        "`orders` names 2 nodes: name one of them.",
                        "orders",
                        null,
                        List.of("TABLE orders", "BEAN orders"),
                        0,
                        List.of(),
                        0,
                        List.of(),
                        0,
                        List.of(),
                        0,
                        List.of());
        assertThat(RuntimeInsightsAgentView.impact(ambiguous, null).next())
                .extracting(step -> step.command())
                .containsExactly("bootui insights impact 'TABLE orders'", "bootui insights impact 'BEAN orders'");

        io.github.jdubois.bootui.core.dto.RuntimeChangeImpactDto notFound =
                new io.github.jdubois.bootui.core.dto.RuntimeChangeImpactDto(
                        ChangeImpactService.NOT_FOUND,
                        "No route, bean, repository, table, cache, host, or method named `com.example.Orders#total`.",
                        "com.example.Orders#total",
                        null,
                        List.of(),
                        0,
                        List.of(),
                        0,
                        List.of(),
                        0,
                        List.of(),
                        0,
                        List.of());
        assertThat(RuntimeInsightsAgentView.impact(notFound, null).next())
                .extracting(step -> step.command())
                .containsExactly("bootui beans --query Orders", "bootui code inventory --query Orders");
    }

    private static RuntimeInsightsReportDto report() {
        List<RuntimeObservationDto> observations = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            observations.add(observation(i, RepeatedSelects.KIND, "GET /api/orders/" + i, "repeated a SELECT"));
        }
        observations.add(observation(8, RouteTimeBreakdown.KIND, "GET /api/slow", "warm median 120 ms"));
        observations.add(observation(9, AnonymousDataReach.KIND, "POST /api/debug", "an anonymous write"));
        observations.add(observation(
                10, ExceptionHotspots.KIND, "GET /api/boom", "recorded `X`, not observed in the previous run"));
        List<String> notExercised =
                IntStream.range(0, 10).mapToObj(i -> "GET /api/unused/" + i).toList();
        return new RuntimeInsightsReportDto(
                true,
                null,
                new RuntimeInsightsWindowDto("run-1", 1L, 2L, 100, 12, 0, 0),
                List.of(),
                List.of(
                        new RuntimeInsightCheckDto(
                                RepeatedSelects.KIND,
                                "Repeated SELECTs",
                                "EVALUATED",
                                9,
                                8,
                                null,
                                "UNDER_SAMPLED",
                                ExternalValidation.TOO_FEW_FACTS),
                        new RuntimeInsightCheckDto(
                                EventLoopBlocking.KIND,
                                "Blocking on event loops",
                                "NOT_APPLICABLE",
                                0,
                                0,
                                "Spring MVC serves requests on worker threads.",
                                "NOT_VALIDATED",
                                ExternalValidation.SILENT)),
                observations,
                List.of(),
                notExercised,
                5);
    }

    private static RuntimeInsightsReportDto idleReport(List<RuntimeObservationDto> observations, long evicted) {
        return new RuntimeInsightsReportDto(
                true,
                null,
                new RuntimeInsightsWindowDto("run-1", null, null, 40, 0, evicted, 0),
                List.of(),
                List.of(),
                observations,
                List.of(),
                List.of(),
                0);
    }

    private static RuntimeObservationDto observation(int i, String kind, String subject, String sentence) {
        return observation(i, kind, subject, sentence, List.of());
    }

    private static RuntimeObservationDto observation(
            int i, String kind, String subject, String sentence, List<String> limitations) {
        return new RuntimeObservationDto(
                kind + ":" + i,
                kind,
                subject,
                "OBSERVED",
                sentence,
                10,
                3,
                "REQUEST_ID",
                List.of("Check the call site.", "Then the join."),
                List.of("r-" + i, "r-x"),
                1,
                limitations,
                true,
                null);
    }

    private static RuntimeObservationDto unlisted(RuntimeObservationDto observation, String reason) {
        return new RuntimeObservationDto(
                observation.id(),
                observation.kind(),
                observation.subject(),
                observation.status(),
                observation.sentence(),
                observation.eligible(),
                observation.affected(),
                observation.minimumTier(),
                observation.whatToCheck(),
                observation.exemplarRequestIds(),
                observation.evidenceRows(),
                observation.limitations(),
                false,
                reason);
    }
}
