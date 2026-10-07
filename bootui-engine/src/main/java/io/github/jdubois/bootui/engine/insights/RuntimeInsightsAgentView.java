package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.core.dto.RuntimeChangeImpactDto;
import io.github.jdubois.bootui.core.dto.RuntimeCodeChangeDto;
import io.github.jdubois.bootui.core.dto.RuntimeCodeChangesDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightAgentDetailDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightAgentDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightCheckDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightsAgentReportDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightsReportDto;
import io.github.jdubois.bootui.core.dto.RuntimeNextStepDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDetailDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDto;
import io.github.jdubois.bootui.core.dto.RuntimeRunChangeDto;
import io.github.jdubois.bootui.core.dto.RuntimeRunComparisonAgentDto;
import io.github.jdubois.bootui.core.dto.RuntimeRunComparisonDto;
import io.github.jdubois.bootui.core.dto.RuntimeRunRefDto;
import io.github.jdubois.bootui.core.dto.RuntimeSideEffectChangeDto;
import io.github.jdubois.bootui.core.dto.RuntimeSideEffectChangesDto;
import io.github.jdubois.bootui.engine.mcp.McpTool;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Runtime Insights for agents ({@code docs/PLAN-v2.md} §5.6): the panel's report, observation, and run comparison,
 * compacted into short, stable facts an agent can diff and refuse to act on. Each adapter's MCP tools and CLI commands
 * call it with what its panel endpoints already return, so the three stacks answer alike. Every answer, an unknown id
 * included, names the calls to make next (M4-21), restricted to the tools the adapter advertises.
 */
public final class RuntimeInsightsAgentView {

    /**
     * The observation kinds that only describe latency, which the default list leaves out since their external
     * validation (M4-20); {@code query=latency} selects every row of these kinds.
     */
    static final Set<String> LATENCY_KINDS = Set.of(RouteTimeBreakdown.KIND, GcInflatedLatency.KIND);

    /** The observation kinds about who could reach what. */
    static final Set<String> SECURITY_KINDS = Set.of(AnonymousDataReach.KIND, AnonymousSuccessOnRestrictedRoute.KIND);

    /** The most observations one list returns, whatever {@code limit} asks. */
    static final int MAX_LIMIT = 50;

    private static final String NEW_MARK = "not observed in the previous run";

    private static final String JOURNAL_SETTINGS = "bootui.runtime-journal";

    private RuntimeInsightsAgentView() {}

    /** Whether a tool is advertised, read from the adapter's current tool list at call time. */
    public static Predicate<String> advertisedBy(Supplier<List<McpTool>> tools) {
        return name -> tools.get().stream().anyMatch(tool -> tool.name().equals(name));
    }

    /** The query that lists every observation, including those the default list leaves out (M4-19). */
    static final String ALL = "all";

    /** {@link #list(RuntimeInsightsReportDto, String, Integer, Predicate)} naming any tool as a next step. */
    public static RuntimeInsightsAgentReportDto list(RuntimeInsightsReportDto report, String query, Integer limit) {
        return list(report, query, limit, null);
    }

    /**
     * The report's observations matching {@code query}, at most {@code limit}: empty for the default list, the
     * observations the panel lists by default (M4-19) except an insufficient repeated-selects row under 50 ms that ran
     * fewer than 10 times in any one request; {@code all} for every observation; {@code latency} for latency rows only;
     * {@code security} for anonymous access; {@code new} or {@code diff} for what the previous run did not show; an
     * observation kind for that kind; anything else for observations naming that route, table, bean, or class. Every
     * query but the empty one matches rows the default list leaves out. When more match than {@code limit}, listed rows
     * come first and every kind's most affected observation comes before any kind's second, so one prolific kind cannot
     * hide the others.
     */
    public static RuntimeInsightsAgentReportDto list(
            RuntimeInsightsReportDto report, String query, Integer limit, Predicate<String> callable) {
        String asked = query == null ? "" : query.trim();
        int max =
                Math.min(MAX_LIMIT, limit == null || limit <= 0 ? RuntimeInsightsAgentReportDto.DEFAULT_LIMIT : limit);
        if (!report.available()) {
            NextSteps next = new NextSteps(callable);
            next.add("get_config", "query", JOURNAL_SETTINGS, "whether the runtime journal is enabled");
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
                    List.of(),
                    next.list());
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
        // The ways to ask for more come first, so the lead observation's follow-ups never crowd them out.
        NextSteps asks = new NextSteps(callable);
        if (asked.equalsIgnoreCase("diff")) {
            asks.add(
                    "get_runtime_run_comparison",
                    "id",
                    "previous",
                    "what changed since the previous run: statements, calls, routes, and edges");
        }
        long dropped = 0;
        String unlisted = null;
        if (asked.isEmpty()) {
            dropped = report.observations().stream()
                    .filter(observation -> observation.listed() && underFloor(observation))
                    .count();
            if (dropped > 0) {
                limitations.add(floorDropped(dropped));
            }
            unlisted = unlisted(report.observations());
            if (unlisted != null) {
                limitations.add(unlisted);
            }
        }
        String leftOut = leftOut(matching, listed);
        if (leftOut != null) {
            limitations.add(leftOut);
            if (asked.isEmpty()) {
                String kind = firstLeftOutKind(matching, listed);
                asks.add("get_runtime_insights", "query", kind, "the " + kind + " observations this limit left out");
            } else if (max < MAX_LIMIT) {
                Map<String, Object> wider = new LinkedHashMap<>();
                wider.put("query", asked);
                wider.put("limit", MAX_LIMIT);
                asks.add("get_runtime_insights", wider, "every match, up to " + MAX_LIMIT);
            }
        }
        if (dropped > 0) {
            asks.add(
                    "get_runtime_insights",
                    "query",
                    RepeatedSelects.KIND,
                    "the cheap repeated-selects rows the default list leaves out");
        }
        if (unlisted != null) {
            asks.add("get_runtime_insights", "query", ALL, "every row, including those the default list leaves out");
        }
        if (listed.isEmpty() && requests == 0 && report.observations().isEmpty()) {
            asks.add("get_runtime_insights", "again after running the application's tests or sending it traffic");
        }
        NextSteps next = new NextSteps(callable, Math.max(1, NextSteps.MAX - asks.size()));
        RuntimeObservationDto lead = listed.stream()
                .filter(observation -> !"INSUFFICIENT".equals(observation.status()))
                .findFirst()
                .orElse(listed.isEmpty() ? null : listed.get(0));
        if (lead != null) {
            follow(next, lead, true);
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
                limitations,
                // A diff query asks for the comparison, so it comes before the lead observation's follow-ups.
                asked.equalsIgnoreCase("diff") ? asks.then(next) : next.then(asks));
    }

    /** {@link #detail(RuntimeObservationDetailDto, Predicate)} naming any tool as a next step. */
    public static RuntimeInsightAgentDetailDto detail(RuntimeObservationDetailDto detail) {
        return detail(detail, null);
    }

    /**
     * One observation with its evidence; an unknown or evicted id answers unavailable with the reason, and names the
     * call that lists the current ids. One the default list leaves out says why first among its limitations.
     */
    public static RuntimeInsightAgentDetailDto detail(RuntimeObservationDetailDto detail, Predicate<String> callable) {
        NextSteps next = new NextSteps(callable);
        RuntimeObservationDto observation = detail.observation();
        if (!detail.available() || observation == null) {
            next.add("get_runtime_insights", "the current observation ids, since ids change as the run goes on");
            return new RuntimeInsightAgentDetailDto(
                    false,
                    detail.unavailableReason(),
                    null,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    0,
                    next.list());
        }
        follow(next, observation, false);
        List<String> limitations = new ArrayList<>();
        if (!observation.listed() && observation.unlistedReason() != null) {
            limitations.add("Not listed by default: " + observation.unlistedReason());
        }
        limitations.addAll(observation.limitations());
        return new RuntimeInsightAgentDetailDto(
                true,
                null,
                compact(observation),
                observation.whatToCheck(),
                limitations,
                detail.columns(),
                detail.rows(),
                detail.truncated(),
                next.list());
    }

    /** {@link #comparison(RuntimeRunComparisonDto, String, Predicate)} for the default run, naming any tool. */
    public static RuntimeRunComparisonAgentDto comparison(RuntimeRunComparisonDto comparison) {
        return comparison(comparison, null, null);
    }

    /**
     * A run comparison with comparability first and at most eight behavior rows and edges; latency is left out.
     *
     * @param asked the run id the caller named, or {@code null} for the default, so an unknown one can be corrected
     */
    public static RuntimeRunComparisonAgentDto comparison(
            RuntimeRunComparisonDto comparison, String asked, Predicate<String> callable) {
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
                compact(comparison.codeChanges()),
                compact(comparison.sideEffects()),
                head(comparison.behavior()),
                omitted(comparison.behavior()),
                head(comparison.edges()),
                omitted(comparison.edges()),
                limitations,
                comparisonNext(comparison, runId(asked), callable));
    }

    /** {@link #impact(RuntimeChangeImpactDto, Predicate)} naming any tool as a next step. */
    public static RuntimeChangeImpactDto impact(RuntimeChangeImpactDto impact) {
        return impact(impact, null);
    }

    /** A change impact for an agent: an ambiguous, unknown, or unavailable symbol names the calls that resolve it. */
    public static RuntimeChangeImpactDto impact(RuntimeChangeImpactDto impact, Predicate<String> callable) {
        NextSteps next = new NextSteps(callable);
        String symbol = impact.symbol() == null ? "" : impact.symbol().strip();
        switch (impact.status()) {
            case ChangeImpactService.AMBIGUOUS ->
                impact.candidates().stream()
                        .limit(2)
                        .forEach(candidate -> next.add("get_runtime_impact", "id", candidate, "only " + candidate));
            case ChangeImpactService.NOT_FOUND -> {
                if (symbol.isEmpty()) {
                    next.add("get_mappings", "the routes, by the path a route symbol names");
                    next.add("get_beans", "the beans and classes, by the name a bean symbol uses");
                } else {
                    String name = simpleSymbol(symbol);
                    if (symbol.contains("/")) {
                        next.add("get_mappings", "query", name, "the routes as this application spells them");
                    }
                    next.add("get_beans", "query", name, "the beans and classes as this application names them");
                    if (symbol.contains("#")) {
                        next.add(
                                "get_code_inventory",
                                "query",
                                name,
                                "the methods of " + name + " the BootUI agent tracks");
                    }
                }
            }
            case ChangeImpactService.UNAVAILABLE -> {
                if (impact.reason() != null && impact.reason().contains(JOURNAL_SETTINGS)) {
                    next.add("get_config", "query", JOURNAL_SETTINGS, "whether the runtime journal is enabled");
                }
            }
            default -> {
                // A resolved symbol is a checklist the caller reads; it has no single follow-up call.
            }
        }
        if (next.list().isEmpty()) {
            return impact;
        }
        return new RuntimeChangeImpactDto(
                impact.status(),
                impact.reason(),
                impact.symbol(),
                impact.node(),
                impact.candidates(),
                impact.structuralReach(),
                impact.observed(),
                impact.observedTotal(),
                impact.notExercised(),
                impact.notExercisedTotal(),
                impact.sharedResources(),
                impact.sharedResourcesTotal(),
                impact.limitations(),
                impact.notExercisedUndetermined(),
                impact.observedFrom(),
                impact.methods(),
                impact.methodStatus(),
                impact.notObserved(),
                impact.notObservedTotal(),
                next.list());
    }

    /**
     * The calls that follow an observation up: the one kind-specific source of its cause, then its evidence rows (in a
     * list answer) and one request that shows it.
     */
    private static void follow(NextSteps next, RuntimeObservationDto observation, boolean fromList) {
        String kind = observation.kind();
        String subject = observation.subject();
        String exemplar = observation.exemplarRequestIds().isEmpty()
                ? null
                : observation.exemplarRequestIds().get(0);
        if (SECURITY_KINDS.contains(kind)) {
            next.add("get_spring_security", "the security rules that let anonymous requests reach " + subject);
            next.add("get_mappings", "query", routePath(subject), "the handler mapped to " + subject);
        } else if (ExceptionHotspots.KIND.equals(kind) || ErrorsBehind2xx.KIND.equals(kind)) {
            next.add("get_exceptions", "the exception group ids, for get_exception_detail");
        } else if (ChangedCodeNotExecuted.KIND.equals(kind)) {
            next.add("get_code_inventory", "query", "changed", "each changed method and whether it ran");
        } else if (RouteTimeBreakdown.KIND.equals(kind)) {
            next.add("get_code_paths", "query", subject, "the application methods " + subject + " spends its time in");
        }
        if (fromList) {
            next.add(
                    "get_runtime_insight",
                    "id",
                    observation.id(),
                    "the evidence rows behind this " + kind + " observation, call sites included");
        }
        next.add("get_request_profile", "id", exemplar, "one request or execution that shows it, step by step");
    }

    private static List<RuntimeNextStepDto> comparisonNext(
            RuntimeRunComparisonDto comparison, String asked, Predicate<String> callable) {
        NextSteps next = new NextSteps(callable);
        String current =
                comparison.current() == null ? null : comparison.current().runId();
        boolean unknownRun = asked != null
                && comparison.previous() == null
                && !RunComparison.UNAVAILABLE.equals(comparison.status());
        if (unknownRun) {
            next.add("get_runtime_run_comparison", "id", "previous", "the newest kept run, instead of " + asked);
            boolean newest = true;
            for (RuntimeRunRefDto run : comparison.runs()) {
                if (run.runId().equals(current) || run.runId().equals(asked)) {
                    continue;
                }
                if (newest) {
                    // previous already selects the newest kept run.
                    newest = false;
                    continue;
                }
                next.add("get_runtime_run_comparison", "id", run.runId(), "an older run this application still keeps");
            }
            return next.list();
        }
        switch (comparison.status()) {
            case RunComparison.COMPARED -> {
                RuntimeCodeChangesDto changes = comparison.codeChanges();
                if (changes != null) {
                    changes.methods().stream()
                            .filter(method -> "NEVER_EXECUTED".equals(method.status()))
                            .map(RuntimeInsightsAgentView::methodSymbol)
                            .filter(symbol -> symbol != null)
                            .findFirst()
                            .ifPresent(symbol -> next.add(
                                    "get_runtime_impact",
                                    "id",
                                    symbol,
                                    "the routes that reach this changed method, which the tests must still run"));
                }
                if (!comparison.behavior().isEmpty()) {
                    next.add("get_runtime_insights", "query", "new", "the observations the previous run did not show");
                }
            }
            case RunComparison.INSUFFICIENT ->
                next.add(
                        "get_runtime_run_comparison",
                        "again after the tests or traffic reach the routes both runs served");
            case RunComparison.NOT_COMPARABLE ->
                next.add("get_runtime_insights", "this run alone, since the runs cannot be compared");
            case RunComparison.NO_PREVIOUS_RUN ->
                next.add("get_runtime_insights", "this run alone, until a restart keeps it as the previous run");
            case RunComparison.UNAVAILABLE ->
                next.add("get_config", "query", JOURNAL_SETTINGS, "whether the runtime journal and its history are on");
            default -> {
                // An unknown status names nothing to do next.
            }
        }
        // A new host, file, process, or variable is a fact whatever the behavior rows' status (M5-7b).
        RuntimeSideEffectChangesDto sideEffects = comparison.sideEffects();
        if (sideEffects != null) {
            sideEffects.changes().stream()
                    .filter(change -> RuntimeSideEffectChangeDto.ADDED.equals(change.change()))
                    .findFirst()
                    .ifPresent(change -> next.add(
                            "get_side_effects",
                            "query",
                            change.target(),
                            "the call site and requests behind the new " + change.target()));
        }
        return next.list();
    }

    /**
     * The changed method as change impact resolves exactly one overload, its agent key {@code class#name+descriptor}, or
     * {@code null} for a constructor, an initializer, or a synthetic method, which impact cannot name.
     */
    private static String methodSymbol(RuntimeCodeChangeDto method) {
        String name = method.name();
        if (name == null || name.startsWith("<") || name.contains("$") || method.className() == null) {
            return null;
        }
        return method.className() + "#" + name + (method.descriptor() == null ? "" : method.descriptor());
    }

    /** The path of a route subject such as {@code GET /owners/{id}}, or the subject itself. */
    private static String routePath(String subject) {
        if (subject == null) {
            return null;
        }
        int space = subject.indexOf(' ');
        return space < 0 ? subject : subject.substring(space + 1);
    }

    /** The class or bean name of a symbol such as {@code com.example.OrderService#total(String)} or a route. */
    private static String simpleSymbol(String symbol) {
        String name = symbol;
        int hash = name.indexOf('#');
        if (hash >= 0) {
            name = name.substring(0, hash);
        }
        if (name.contains("/")) {
            return routePath(name);
        }
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    /** Code changes with at most {@value RuntimeRunComparisonAgentDto#MAX_ROWS} methods, the not executed first. */
    static RuntimeCodeChangesDto compact(RuntimeCodeChangesDto changes) {
        if (changes == null || changes.methods().size() <= RuntimeRunComparisonAgentDto.MAX_ROWS) {
            return changes;
        }
        return new RuntimeCodeChangesDto(
                changes.available(),
                changes.unavailableReason(),
                changes.counts(),
                changes.methods().subList(0, RuntimeRunComparisonAgentDto.MAX_ROWS),
                changes.methodsTotal(),
                changes.limitations());
    }

    /** Side effects with at most {@value RuntimeRunComparisonAgentDto#MAX_ROWS} changes, the new first. */
    static RuntimeSideEffectChangesDto compact(RuntimeSideEffectChangesDto sideEffects) {
        if (sideEffects == null || sideEffects.changes().size() <= RuntimeRunComparisonAgentDto.MAX_ROWS) {
            return sideEffects;
        }
        return new RuntimeSideEffectChangesDto(
                sideEffects.available(),
                sideEffects.unavailableReason(),
                sideEffects.partial(),
                sideEffects.sensors(),
                sideEffects.changes().subList(0, RuntimeRunComparisonAgentDto.MAX_ROWS),
                sideEffects.changesTotal(),
                sideEffects.limitations());
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
                        : observation.whatToCheck().get(0),
                observation.listed());
    }

    /**
     * How many rows of each kind the default list leaves out, in report order, and how to list them, or {@code null}
     * when it leaves none out.
     */
    private static String unlisted(List<RuntimeObservationDto> observations) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (RuntimeObservationDto observation : observations) {
            if (!observation.listed()) {
                counts.merge(observation.kind(), 1, Integer::sum);
            }
        }
        if (counts.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        counts.forEach((kind, count) -> parts.add(kind + " " + count));
        return "Not listed by default: " + String.join(", ", parts) + ". Pass the query all, a kind, or a route to"
                + " list them; get_runtime_insight on one says why it is left out.";
    }

    /**
     * What {@code requests} 0 means, decided from the unfiltered report. An observation is evidence that work ran only
     * when it names a request or execution. A run-level row with an empty exemplar list, such as heap growth after one
     * collection, does not. The idle sentence follows eviction and the non-HTTP limitation, and does not call the list
     * empty when a run-level row is present.
     */
    private static String requestsZero(RuntimeInsightsReportDto report) {
        boolean executed = report.observations().stream()
                .anyMatch(observation -> !observation.exemplarRequestIds().isEmpty());
        boolean nonHttp = report.limitations().stream()
                .anyMatch(limitation -> limitation.startsWith(RuntimeInsightsService.NON_HTTP_PREFIX));
        long evicted = report.window() == null ? 0 : report.window().evictedEvents();
        if (executed) {
            return "requests counts completed HTTP exchanges only. Observations here can come from scheduled jobs,"
                    + " messages, or other non-HTTP executions, so requests 0 is not proof nothing ran.";
        }
        boolean leftOut = report.limitations().stream()
                .anyMatch(limitation -> limitation.contains(RuntimeInsightsService.LEFT_OUT_BEFORE_LOSS));
        if (leftOut) {
            return "Requests that " + RuntimeInsightsService.LEFT_OUT_BEFORE_LOSS + " (Clear recording, or the"
                    + " journal's bounds) are left out, so requests 0 is not proof the run was idle: call again once"
                    + " new requests have completed.";
        }
        if (evicted > 0) {
            return "The journal evicted older events, so requests 0 is not proof the run was idle.";
        }
        if (nonHttp) {
            return "requests counts completed HTTP exchanges only. See the limitation that starts with \""
                    + RuntimeInsightsService.NON_HTTP_PREFIX + "\".";
        }
        if (report.observations().isEmpty()) {
            return "No HTTP request completed in this run's retained events, so request-level checks had"
                    + " nothing to judge: an empty list here means not exercised, not healthy. Run the application's"
                    + " tests or send it traffic, then call again.";
        }
        return "No HTTP request completed in this run's retained events, and no observation names a request or"
                + " execution, so request-level checks had nothing to judge: not exercised, not healthy. Run the"
                + " application's tests or send it traffic, then call again.";
    }

    /** How many empty-query rows the floor dropped. Absent unless {@code dropped} is positive. */
    private static String floorDropped(long dropped) {
        return "Left out " + dropped + " repeated-selects " + (dropped == 1 ? "row" : "rows")
                + " under 50 ms of summed measured time, seen in fewer than " + RepeatedSelects.MIN_REQUESTS
                + " requests and fewer than " + RepeatedSelects.HIGH_REPEAT_KEEP
                + " times in any one.";
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
                return observation.listed() && !underFloor(observation);
            }
            case ALL -> {
                return true;
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
     * At most {@code max} of {@code rows}, the listed ones first and each part in report order: every kind's first row
     * before any kind's second, and so on, so the answer stays as broad as the limit allows.
     */
    private static List<RuntimeObservationDto> breadthFirst(List<RuntimeObservationDto> rows, int max) {
        // A query reaching rows the default list leaves out keeps the listed ones first, so a prolific kind's short
        // routes never crowd out its prominent ones.
        Comparator<Integer> listedFirst = Comparator.comparing(i -> !rows.get(i).listed());
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            order.add(i);
        }
        if (rows.size() > max) {
            Map<String, Integer> seen = new HashMap<>();
            int[] rank = new int[rows.size()];
            for (int i = 0; i < rows.size(); i++) {
                rank[i] = seen.merge(rows.get(i).kind() + ":" + rows.get(i).listed(), 1, Integer::sum);
            }
            order.sort(listedFirst
                    .thenComparing(i -> "INSUFFICIENT".equals(rows.get(i).status()))
                    .thenComparingInt(i -> rank[i])
                    .thenComparingInt(i -> i));
            order = new ArrayList<>(order.subList(0, max));
        }
        order.sort(listedFirst.thenComparingInt(i -> i));
        return order.stream().map(rows::get).toList();
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
        return "Left out by limit: " + String.join(", ", parts) + ".";
    }

    /** The first kind that lost rows to {@code limit}, in report order, or {@code null}. */
    private static String firstLeftOutKind(List<RuntimeObservationDto> matching, List<RuntimeObservationDto> listed) {
        for (RuntimeObservationDto observation : matching) {
            if (!listed.contains(observation)) {
                return observation.kind();
            }
        }
        return null;
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
