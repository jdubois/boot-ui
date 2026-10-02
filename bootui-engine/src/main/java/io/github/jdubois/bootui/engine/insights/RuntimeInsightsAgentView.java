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
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Runtime Insights for agents ({@code docs/PLAN-v2.md} §5.6): the panel's report, observation, and run comparison,
 * compacted into short, stable facts an agent can diff and refuse to act on. Each adapter's MCP tools and CLI commands
 * call it with what its panel endpoints already return, so the three stacks answer alike.
 */
public final class RuntimeInsightsAgentView {

    /** The observation kinds that only describe latency, which the default list leaves out. */
    static final Set<String> LATENCY_KINDS = Set.of(RouteTimeBreakdown.KIND, GcInflatedLatency.KIND);

    /** The observation kinds about who could reach what. */
    static final Set<String> SECURITY_KINDS = Set.of(AnonymousDataReach.KIND, AnonymousSuccessOnRestrictedRoute.KIND);

    /** The most observations one list returns, whatever {@code limit} asks. */
    static final int MAX_LIMIT = 50;

    private static final String NEW_MARK = "not observed in the previous run";

    private RuntimeInsightsAgentView() {}

    /**
     * The report's observations matching {@code query}, at most {@code limit}: empty for every observation but
     * latency-only ones; {@code latency} for those; {@code security} for anonymous access; {@code new} or {@code diff}
     * for what the previous run did not show; anything else for observations naming that route, table, bean, or class.
     */
    public static RuntimeInsightsAgentReportDto list(RuntimeInsightsReportDto report, String query, Integer limit) {
        String asked = query == null ? "" : query.trim();
        int max =
                Math.min(MAX_LIMIT, limit == null || limit <= 0 ? RuntimeInsightsAgentReportDto.DEFAULT_LIMIT : limit);
        if (!report.available()) {
            return new RuntimeInsightsAgentReportDto(
                    false, report.unavailableReason(), asked, List.of(), List.of(), List.of(), 0, List.of());
        }
        List<RuntimeInsightAgentDto> matching = new ArrayList<>();
        for (RuntimeObservationDto observation : report.observations()) {
            if (matches(observation, asked)) {
                matching.add(compact(observation));
            }
        }
        List<String> limitations = new ArrayList<>(report.limitations());
        if (asked.equalsIgnoreCase("diff")) {
            limitations.add("For what changed between runs, statements, calls, routes, and edges, call"
                    + " get_runtime_run_comparison with previous.");
        }
        if (asked.isEmpty()) {
            limitations.add("Latency-only observations are left out; ask for them with the query latency.");
        }
        return new RuntimeInsightsAgentReportDto(
                true,
                null,
                asked,
                report.coverage(),
                checksNotRun(report.checks()),
                matching.subList(0, Math.min(max, matching.size())),
                Math.max(0, matching.size() - max),
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
                comparison.previous() == null ? null : comparison.previous().runId(),
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

    private static boolean matches(RuntimeObservationDto observation, String query) {
        String kind = observation.kind();
        switch (query.toLowerCase(Locale.ROOT)) {
            case "" -> {
                return !LATENCY_KINDS.contains(kind);
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
                return contains(observation.subject(), needle) || contains(observation.sentence(), needle);
            }
        }
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
