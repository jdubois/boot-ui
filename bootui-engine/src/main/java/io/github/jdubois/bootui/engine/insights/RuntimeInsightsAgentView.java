package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.core.dto.RuntimeInsightAgentDetailDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightAgentDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightCheckDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightsAgentReportDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightsReportDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDetailDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDto;
import io.github.jdubois.bootui.core.dto.RuntimeRunChangeDto;
import io.github.jdubois.bootui.core.dto.RuntimeRunComparisonAgentDto;
import io.github.jdubois.bootui.core.dto.RuntimeRunComparisonDto;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Runtime Insights for agents ({@code docs/PLAN-v2.md} §5.6): the panel's report, observation, and run comparison,
 * compacted into short, stable facts an agent can diff and refuse to act on. Each adapter's MCP tools and CLI commands
 * call it with what its panel endpoints already return, so the three stacks answer alike.
 */
public final class RuntimeInsightsAgentView {

    /**
     * The observation kinds that only describe latency. The default list includes them (M4-18b); {@code query=latency}
     * still selects only these.
     */
    static final Set<String> LATENCY_KINDS = Set.of(RouteTimeBreakdown.KIND, GcInflatedLatency.KIND);

    /** The observation kinds about who could reach what. */
    static final Set<String> SECURITY_KINDS = Set.of(AnonymousDataReach.KIND, AnonymousSuccessOnRestrictedRoute.KIND);

    /** The most observations one list returns, whatever {@code limit} asks. */
    static final int MAX_LIMIT = 50;

    private static final String NEW_MARK = "not observed in the previous run";

    private RuntimeInsightsAgentView() {}

    /**
     * The report's observations matching {@code query}, at most {@code limit}: empty for every observation, including
     * latency rows, except repeated-selects under 50 ms of summed measured time; {@code latency} for latency rows only;
     * {@code security} for anonymous access; {@code new} or {@code diff} for what the previous run did not show; an
     * observation kind for that kind; anything else for observations naming that route, table, bean, or class. When
     * more match than {@code limit}, every kind's most affected observation comes before any kind's second, so one
     * prolific kind cannot hide the others.
     */
    public static RuntimeInsightsAgentReportDto list(RuntimeInsightsReportDto report, String query, Integer limit) {
        String asked = query == null ? "" : query.trim();
        int max =
                Math.min(MAX_LIMIT, limit == null || limit <= 0 ? RuntimeInsightsAgentReportDto.DEFAULT_LIMIT : limit);
        if (!report.available()) {
            return new RuntimeInsightsAgentReportDto(
                    false,
                    report.unavailableReason(),
                    asked,
                    0,
                    List.of(),
                    List.of(),
                    List.of(),
                    0,
                    List.of(),
                    0,
                    List.of());
        }
        List<RuntimeObservationDto> matching = new ArrayList<>();
        for (RuntimeObservationDto observation : report.observations()) {
            if (matches(observation, asked)) {
                matching.add(observation);
            }
        }
        List<RuntimeObservationDto> listed = breadthFirst(matching, max);
        long requests = report.window() == null ? 0 : report.window().requests();
        List<String> limitations = new ArrayList<>();
        if (requests == 0) {
            limitations.add(requestsZero(report));
        }
        limitations.addAll(report.limitations());
        if (asked.equalsIgnoreCase("diff")) {
            limitations.add("For what changed between runs, statements, calls, routes, and edges, call"
                    + " get_runtime_run_comparison with previous.");
        }
        if (asked.isEmpty()) {
            limitations.add("Repeated SELECTs whose summed measured time is under 50 ms are left out; ask for them"
                    + " with the query repeated-selects. Latency rows are included.");
        }
        String leftOut = leftOut(matching, listed);
        if (leftOut != null) {
            limitations.add(leftOut);
        }
        List<String> notExercised = report.notExercised();
        int notExercisedShown = Math.min(RuntimeInsightsAgentReportDto.MAX_NOT_EXERCISED, notExercised.size());
        return new RuntimeInsightsAgentReportDto(
                true,
                null,
                asked,
                requests,
                report.coverage(),
                checksNotRun(report.checks()),
                listed.stream().map(RuntimeInsightsAgentView::compact).toList(),
                matching.size() - listed.size(),
                notExercised.subList(0, notExercisedShown),
                notExercised.size() - notExercisedShown + report.notExercisedOmitted(),
                limitations);
    }

    /** One observation with its evidence; an unknown or evicted id answers unavailable with the reason. */
    public static RuntimeInsightAgentDetailDto detail(RuntimeObservationDetailDto detail) {
        RuntimeObservationDto observation = detail.observation();
        if (!detail.available() || observation == null) {
            return new RuntimeInsightAgentDetailDto(
                    false, detail.unavailableReason(), null, List.of(), List.of(), List.of(), List.of(), 0);
        }
        return new RuntimeInsightAgentDetailDto(
                true,
                null,
                compact(observation),
                observation.whatToCheck(),
                observation.limitations(),
                detail.columns(),
                detail.rows(),
                detail.truncated());
    }

    /** A run comparison with comparability first and at most eight behavior rows and edges; latency is left out. */
    public static RuntimeRunComparisonAgentDto comparison(RuntimeRunComparisonDto comparison) {
        List<String> limitations = new ArrayList<>(comparison.limitations());
        if (!comparison.latency().isEmpty()) {
            limitations.add("Latency rows are left out: they are noisy, and a change in them is never a reason to"
                    + " edit code on its own.");
        }
        return new RuntimeRunComparisonAgentDto(
                comparison.status(),
                comparison.reason(),
                comparison.current() == null ? null : comparison.current().runId(),
                comparison.previous() == null ? null : comparison.previous().runId(),
                comparison.runs(),
                comparison.notComparableReasons(),
                head(comparison.behavior()),
                omitted(comparison.behavior()),
                head(comparison.edges()),
                omitted(comparison.edges()),
                limitations);
    }

    /** {@code previous}, blank, or {@code null} names the newest kept run; anything else is a run id. */
    public static String runId(String asked) {
        return asked == null || asked.isBlank() || asked.trim().equalsIgnoreCase("previous") ? null : asked.trim();
    }

    static RuntimeInsightAgentDto compact(RuntimeObservationDto observation) {
        return new RuntimeInsightAgentDto(
                observation.id(),
                observation.kind(),
                observation.status(),
                observation.subject(),
                observation.sentence(),
                observation.eligible(),
                observation.affected(),
                observation.minimumTier(),
                observation.exemplarRequestIds().isEmpty()
                        ? null
                        : observation.exemplarRequestIds().get(0),
                observation.whatToCheck().isEmpty()
                        ? null
                        : observation.whatToCheck().get(0));
    }

    /**
     * What {@code requests} 0 means, decided from the unfiltered report. The idle sentence is only for a run that
     * retained no observation, evicted nothing, and recorded no scheduled run or consumed message.
     */
    private static String requestsZero(RuntimeInsightsReportDto report) {
        boolean nonHttp = report.limitations().stream()
                .anyMatch(limitation -> limitation.startsWith(RuntimeInsightsService.NON_HTTP_PREFIX));
        long evicted = report.window() == null ? 0 : report.window().evictedEvents();
        if (!report.observations().isEmpty()) {
            return "requests counts completed HTTP exchanges only. Observations here can come from scheduled jobs,"
                    + " messages, or other non-HTTP executions, so requests 0 is not proof nothing ran.";
        }
        if (evicted > 0) {
            return "The journal evicted older events, so requests 0 is not proof the run was idle.";
        }
        if (nonHttp) {
            return "requests counts completed HTTP exchanges only. See the limitation that starts with \""
                    + RuntimeInsightsService.NON_HTTP_PREFIX + "\".";
        }
        return "No HTTP request completed in this run's retained events, so request-level checks had"
                + " nothing to judge: an empty list here means not exercised, not healthy. Run the application's"
                + " tests or send it traffic, then call again.";
    }

    /** Whether the empty query leaves this repeated-selects row out, matching the finding's exact-nanos decision. */
    private static boolean underFloor(RuntimeObservationDto observation) {
        return RepeatedSelects.KIND.equals(observation.kind())
                && observation.limitations().contains(RepeatedSelects.UNDER_DEFAULT_FLOOR);
    }

    private static boolean matches(RuntimeObservationDto observation, String query) {
        String kind = observation.kind();
        switch (query.toLowerCase(Locale.ROOT)) {
            case "" -> {
                return !underFloor(observation);
            }
            case "latency" -> {
                return LATENCY_KINDS.contains(kind);
            }
            case "security" -> {
                return SECURITY_KINDS.contains(kind);
            }
            case "new", "diff" -> {
                return observation.sentence() != null && observation.sentence().contains(NEW_MARK);
            }
            default -> {
                String needle = query.toLowerCase(Locale.ROOT);
                return kind.equalsIgnoreCase(query)
                        || contains(observation.subject(), needle)
                        || contains(observation.sentence(), needle);
            }
        }
    }

    /**
     * At most {@code max} of {@code rows}, kept in report order: every kind's first row before any kind's second, and so
     * on, so the answer stays as broad as the limit allows.
     */
    private static List<RuntimeObservationDto> breadthFirst(List<RuntimeObservationDto> rows, int max) {
        if (rows.size() <= max) {
            return rows;
        }
        Map<String, Integer> seen = new HashMap<>();
        int[] rank = new int[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            rank[i] = seen.merge(rows.get(i).kind(), 1, Integer::sum);
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparing(
                        (Integer i) -> "INSUFFICIENT".equals(rows.get(i).status()))
                .thenComparingInt(i -> rank[i])
                .thenComparingInt(i -> i));
        List<Integer> chosen = new ArrayList<>(order.subList(0, max));
        chosen.sort(Comparator.naturalOrder());
        return chosen.stream().map(rows::get).toList();
    }

    /** Which kinds lost rows to {@code limit}, and how to list them, or {@code null} when nothing was left out. */
    private static String leftOut(List<RuntimeObservationDto> matching, List<RuntimeObservationDto> listed) {
        if (matching.size() == listed.size()) {
            return null;
        }
        Map<String, Integer> omitted = new LinkedHashMap<>();
        for (RuntimeObservationDto observation : matching) {
            omitted.merge(observation.kind(), 1, Integer::sum);
        }
        for (RuntimeObservationDto observation : listed) {
            omitted.merge(observation.kind(), -1, Integer::sum);
        }
        List<String> parts = new ArrayList<>();
        omitted.forEach((kind, count) -> {
            if (count > 0) {
                parts.add(kind + " " + count);
            }
        });
        return "Left out by limit: " + String.join(", ", parts)
                + ". List one kind by passing its name as the query, or raise limit.";
    }

    private static boolean contains(String text, String needle) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static List<String> checksNotRun(List<RuntimeInsightCheckDto> checks) {
        List<String> notRun = new ArrayList<>();
        for (RuntimeInsightCheckDto check : checks) {
            if (!"EVALUATED".equals(check.status())) {
                notRun.add(
                        check.kind() + ": " + check.status() + (check.reason() == null ? "" : ", " + check.reason()));
            }
        }
        return notRun;
    }

    private static List<RuntimeRunChangeDto> head(List<RuntimeRunChangeDto> rows) {
        return rows.subList(0, Math.min(RuntimeRunComparisonAgentDto.MAX_ROWS, rows.size()));
    }

    private static int omitted(List<RuntimeRunChangeDto> rows) {
        return Math.max(0, rows.size() - RuntimeRunComparisonAgentDto.MAX_ROWS);
    }
}
