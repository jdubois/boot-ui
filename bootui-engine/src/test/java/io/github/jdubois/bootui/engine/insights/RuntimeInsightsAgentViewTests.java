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
    void theDefaultListPutsCoverageAndUnrunChecksFirstAndLeavesOutLatencyOnlyRows() {
        RuntimeInsightsAgentReportDto list = RuntimeInsightsAgentView.list(report(), null, null);

        assertThat(list.available()).isTrue();
        assertThat(list.checksNotRun())
                .containsExactly("event-loop-blocking: NOT_APPLICABLE, Spring MVC serves requests on worker threads.");
        assertThat(list.observations())
                .extracting(observation -> observation.kind())
                .doesNotContain(RouteTimeBreakdown.KIND)
                .hasSize(8);
        assertThat(list.omitted()).isEqualTo(2);
        assertThat(list.observations().get(0).exemplarRequestId()).isEqualTo("r-0");
        assertThat(list.observations().get(0).verify()).isEqualTo("Check the call site.");
        assertThat(list.limitations()).anyMatch(limitation -> limitation.contains("query latency"));
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
                .hasSize(6);
        assertThat(list.limitations())
                .contains("Left out by limit: repeated-selects 2. List one kind by passing its name as the query, or"
                        + " raise limit.");
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
        assertThat(RuntimeInsightsAgentView.list(report(), "diff", 20).limitations())
                .anyMatch(limitation -> limitation.contains("get_runtime_run_comparison"));
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
                        new RuntimeInsightCheckDto(RepeatedSelects.KIND, "Repeated SELECTs", "EVALUATED", 9, 8, null),
                        new RuntimeInsightCheckDto(
                                EventLoopBlocking.KIND,
                                "Blocking on event loops",
                                "NOT_APPLICABLE",
                                0,
                                0,
                                "Spring MVC serves requests on worker threads.")),
                observations,
                List.of(),
                notExercised,
                5);
    }

    private static RuntimeObservationDto observation(int i, String kind, String subject, String sentence) {
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
                List.of());
    }
}
