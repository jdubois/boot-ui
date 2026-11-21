package io.github.jdubois.bootui.engine.mcp;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.core.dto.ActivityEntryOriginDto;
import io.github.jdubois.bootui.core.dto.ActivityPageInfo;
import io.github.jdubois.bootui.core.dto.ConfigReport;
import io.github.jdubois.bootui.core.dto.CopilotSessionListDto;
import io.github.jdubois.bootui.core.dto.CopilotSessionSummary;
import io.github.jdubois.bootui.core.dto.DependenciesReport;
import io.github.jdubois.bootui.core.dto.DependencyDto;
import io.github.jdubois.bootui.core.dto.DependencyVulnerabilityDto;
import io.github.jdubois.bootui.core.dto.DevToolsActionResult;
import io.github.jdubois.bootui.core.dto.JavaAgentReport;
import io.github.jdubois.bootui.core.dto.JavaAgentSensorDto;
import io.github.jdubois.bootui.core.dto.LiveActivityReport;
import io.github.jdubois.bootui.core.dto.LogLineDto;
import io.github.jdubois.bootui.core.dto.PageMetadata;
import io.github.jdubois.bootui.core.dto.RestClientTraceEntryDto;
import io.github.jdubois.bootui.core.dto.RestClientTraceGroupDto;
import io.github.jdubois.bootui.core.dto.RestClientTraceReport;
import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import io.github.jdubois.bootui.core.dto.SqlTraceGroupDto;
import io.github.jdubois.bootui.core.dto.SqlTraceReport;
import io.github.jdubois.bootui.core.dto.StartupReport;
import io.github.jdubois.bootui.core.dto.StartupStepDto;
import io.github.jdubois.bootui.core.dto.TagDto;
import io.github.jdubois.bootui.engine.journal.JournalActivityReports;
import io.github.jdubois.bootui.engine.sqltrace.SqlShapes;
import io.github.jdubois.bootui.engine.sqltrace.SqlStatementNormalizer;
import io.github.jdubois.bootui.engine.support.PagedList;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.ToLongFunction;

/**
 * Agent-sized projections of panel reports, shared by the MCP tools and the {@code bootui} CLI on every stack.
 *
 * <p>The browser keeps the full panel payloads. An agent pays for every byte it reads, so these reads answer with a
 * short page of the rows that matter most, filtered by the tool's {@code query}, and add a {@code page} object (total,
 * matched, returned, hasMore) whenever rows were left out, so the caller knows to narrow the query or raise the
 * {@code limit} rather than assume it saw everything. Every other field of the report is kept as the panel returns it.
 */
public final class McpAgentViews {

    /** The statements {@code get_sql_traces} lists when the call asks for no limit. */
    public static final int SQL_TRACES_DEFAULT_LIMIT = 20;

    /** The calls {@code get_rest_client_traces} lists when the call asks for no limit. */
    public static final int REST_CLIENT_TRACES_DEFAULT_LIMIT = 20;

    /** The steps {@code get_startup_timeline} lists when the call asks for no limit. */
    public static final int STARTUP_DEFAULT_LIMIT = 25;

    /** The lines {@code get_log_tail} lists when the call asks for no limit. */
    public static final int LOG_TAIL_DEFAULT_LIMIT = 50;

    /** The sessions {@code get_copilot_sessions} and {@code get_claude_code_sessions} list when asked for no limit. */
    public static final int SESSIONS_DEFAULT_LIMIT = 10;

    /** The dependencies {@code get_vulnerabilities_report} lists when the call asks for no limit. */
    public static final int VULNERABILITIES_DEFAULT_LIMIT = 10;

    /** The advisories {@code get_vulnerabilities_report} lists per dependency, unless the query names it exactly. */
    public static final int ADVISORIES_PER_DEPENDENCY = 5;

    /** The references and the advisory symbols a listed advisory keeps, unless the query names it exactly. */
    public static final int ADVISORY_LIST_ITEMS = 3;

    /** Vulnerability severities, most severe first; {@code UNKNOWN} is not zero risk, so it ranks before none. */
    static final List<String> VULNERABILITY_SEVERITIES =
            List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "UNKNOWN", "NONE");

    /** The entries {@code get_live_activity} lists when the call asks for no limit. */
    public static final int LIVE_ACTIVITY_DEFAULT_LIMIT = 25;

    /**
     * The rows a searchable inventory (configuration, beans, metrics, conditions, threads, HTTP exchanges) lists when
     * asked for no limit.
     */
    public static final int INVENTORY_DEFAULT_LIMIT = 25;

    /** The activity types a {@code get_live_activity} query selects by name rather than by text. */
    static final Set<String> ACTIVITY_TYPES = Set.of(
            "AI",
            "APP_EVENT",
            "ASYNC",
            "BULKHEAD",
            "CACHE",
            "CIRCUIT_BREAKER",
            "EXCEPTION",
            "FALLBACK",
            "FAULT_TOLERANCE",
            "LOG",
            "MAIL",
            "MARKER",
            "MESSAGING",
            "ORM",
            "RATE_LIMITER",
            "REQUEST",
            "REST_CLIENT",
            "RETRY",
            "SCHEDULED",
            "SECURITY",
            "SQL",
            "TIME_LIMITER",
            "TRANSACTION",
            "WEBSOCKET");

    /** The severities a {@code get_live_activity} query selects by name rather than by text. */
    static final Set<String> ACTIVITY_SEVERITIES = Set.of("OK", "SLOW", "WARN", "ERROR");

    private McpAgentViews() {}

    /**
     * How a {@code get_live_activity} query filters the stream.
     *
     * @param type the type to keep, or {@code null}
     * @param severity the severity to keep, or {@code null}
     * @param text text to match against an entry's summary, detail, path, and method, or {@code null}
     * @param namespace durable journal namespace to keep, or {@code null}
     * @param application durable journal application to keep, or {@code null}
     * @param run durable journal run to keep, or {@code null}
     * @param instance durable journal instance to keep, or {@code null}
     * @param route route to keep, or {@code null}
     */
    public record ActivityFilter(
            String type,
            String severity,
            String text,
            String namespace,
            String application,
            String run,
            String instance,
            String route) {

        public ActivityFilter(String type, String severity, String text) {
            this(type, severity, text, null, null, null, null, null);
        }

        /** The durable instance identifier, using the DTO field's familiar name. */
        public String instanceId() {
            return instance;
        }

        /** Reads type, severity, scope qualifiers, and free text from one query. */
        public static ActivityFilter of(String query) {
            if (query == null || query.isBlank()) {
                return new ActivityFilter(null, null, null, null, null, null, null, null);
            }
            String type = null;
            String severity = null;
            String namespace = null;
            String application = null;
            String run = null;
            String instance = null;
            String route = null;
            List<String> text = new ArrayList<>();
            for (String word : query.trim().split("\\s+")) {
                String qualifier = qualifierValue(word);
                if (qualifier != null && word.regionMatches(true, 0, "namespace:", 0, "namespace:".length())) {
                    namespace = qualifier;
                    continue;
                }
                if (qualifier != null && word.regionMatches(true, 0, "application:", 0, "application:".length())) {
                    application = qualifier;
                    continue;
                }
                if (qualifier != null && word.regionMatches(true, 0, "run:", 0, "run:".length())) {
                    run = qualifier;
                    continue;
                }
                if (qualifier != null && word.regionMatches(true, 0, "instance:", 0, "instance:".length())) {
                    instance = qualifier;
                    continue;
                }
                if (qualifier != null && word.regionMatches(true, 0, "route:", 0, "route:".length())) {
                    route = qualifier;
                    continue;
                }
                String upper = word.toUpperCase(Locale.ROOT).replace('-', '_');
                if (type == null && ACTIVITY_TYPES.contains(upper)) {
                    type = upper;
                } else if (severity == null && ACTIVITY_SEVERITIES.contains(upper)) {
                    severity = upper;
                } else {
                    text.add(word);
                }
            }
            return new ActivityFilter(
                    type,
                    severity,
                    text.isEmpty() ? null : String.join(" ", text),
                    namespace,
                    application,
                    run,
                    instance,
                    route);
        }

        private static String qualifierValue(String word) {
            int separator = word.indexOf(':');
            if (separator <= 0 || separator == word.length() - 1) {
                return null;
            }
            return word.substring(separator + 1);
        }
    }

    /**
     * At most {@code limit} of the newest Live Activity entries {@code filter} keeps, and a {@code pageInfo} whose
     * {@code hasMore} says whether more entries matched or may match.
     *
     * <p>Read {@code report} with {@link #liveActivityFetch(ActivityFilter, int)} entries and pass the parsed type,
     * severity, text, route, and durable scope qualifiers to the adapter before reapplying type, severity, and text in
     * the compact view. This lets a durable store filter its whole history while the view remains safe for feeds that
     * cannot prefilter every local field. When a filter is set and the window did not reach every retained entry it could
     * match, as {@code typeCounts} counts them, older entries were not searched: {@code hasMore} is then true and a
     * warning names the window, so a miss is never read as "never ran". {@code typeCounts} keeps counting every retained
     * entry by type; agents cannot pass a cursor, so none is returned.
     */
    public static LiveActivityReport liveActivity(LiveActivityReport report, ActivityFilter filter, int limit) {
        List<ActivityEntryDto> window = report.entries();
        String needle = PagedList.normalize(filter.text());
        List<ActivityEntryDto> matched = new ArrayList<>();
        List<ActivityEntryOriginDto> matchedOrigins = new ArrayList<>();
        for (int index = 0; index < window.size(); index++) {
            ActivityEntryDto entry = window.get(index);
            ActivityEntryOriginDto origin = activityOrigin(report, index, entry);
            if ((filter.type() == null || filter.type().equalsIgnoreCase(entry.type()))
                    && (filter.severity() == null || filter.severity().equalsIgnoreCase(entry.severity()))
                    && (PagedList.contains(entry.summary(), needle)
                            || PagedList.contains(entry.detail(), needle)
                            || PagedList.contains(entry.path(), needle)
                            || PagedList.contains(entry.method(), needle))) {
                matched.add(entry);
                if (origin != null) {
                    matchedOrigins.add(origin);
                }
            }
        }
        ActivityPageInfo page = report.pageInfo();
        boolean olderPages = page != null && page.hasMore();
        boolean filtered = filter.type() != null
                || filter.severity() != null
                || filter.text() != null
                || filter.namespace() != null
                || filter.application() != null
                || filter.run() != null
                || filter.instance() != null
                || filter.route() != null;
        long searched;
        long retained;
        if (filter.type() != null) {
            searched = window.stream()
                    .filter(entry -> filter.type().equalsIgnoreCase(entry.type()))
                    .count();
            retained = report.typeCounts().entrySet().stream()
                    .filter(count -> filter.type().equalsIgnoreCase(count.getKey()))
                    .mapToLong(count -> count.getValue() == null ? 0 : count.getValue())
                    .sum();
        } else {
            searched = window.size();
            retained = report.typeCounts().values().stream()
                    .mapToLong(count -> count == null ? 0 : count)
                    .sum();
        }
        boolean persistentPage = page != null && page.persistent();
        boolean adapterPrefiltered = !persistentPage
                && (filter.route() != null
                        || filter.namespace() != null
                        || filter.application() != null
                        || filter.run() != null
                        || filter.instance() != null);
        if (adapterPrefiltered) {
            searched = Math.min(retained, liveActivityFetch(filter, limit));
        }
        boolean partial = filtered && (persistentPage ? olderPages : searched < retained || olderPages);
        List<String> warnings = report.warnings();
        if (partial) {
            warnings = new ArrayList<>(warnings);
            warnings.add(
                    !persistentPage && searched < retained
                            ? "Searched the newest " + searched + " of " + retained + " retained entries"
                                    + (filter.type() == null ? "" : " of type " + filter.type())
                                    + "; older ones may match too. Narrow the query, or open Live Activity."
                            : "Searched the newest " + searched + " entries; older ones are kept but were not searched."
                                    + " Narrow the query, or open Live Activity.");
        }
        boolean hasMore = matched.size() > limit || partial || (!filtered && olderPages);
        int returned = Math.min(limit, matched.size());
        List<ActivityEntryDto> entries = matched.subList(0, returned);
        List<ActivityEntryOriginDto> origins = matchedOrigins.subList(0, Math.min(returned, matchedOrigins.size()));
        if (hasMultipleApplications(origins) || hasMultipleRuns(origins)) {
            warnings = new ArrayList<>(warnings);
            warnings.add(scopeWarning(origins));
        }
        return new LiveActivityReport(
                report.available(),
                entries,
                report.typeCounts(),
                report.kpis(),
                report.sources(),
                warnings,
                new ActivityPageInfo(page != null && page.persistent(), null, hasMore),
                report.persistenceOption(),
                origins);
    }

    /**
     * How many entries to read for {@link #liveActivity(LiveActivityReport, ActivityFilter, int)}: one past
     * {@code limit}, to know whether more remain, or, when a filter must be applied, as many as a report returns.
     */
    public static int liveActivityFetch(ActivityFilter filter, int limit) {
        return filter.type() == null
                        && filter.severity() == null
                        && filter.text() == null
                        && filter.namespace() == null
                        && filter.application() == null
                        && filter.run() == null
                        && filter.instance() == null
                        && filter.route() == null
                ? limit + 1
                : JournalActivityReports.MAX_LIMIT;
    }

    /** The type to pass to the adapter so it can apply that dimension before selecting its window. */
    public static String adapterType(ActivityFilter filter) {
        return filter.type();
    }

    private static ActivityEntryOriginDto activityOrigin(LiveActivityReport report, int index, ActivityEntryDto entry) {
        List<ActivityEntryOriginDto> origins = report.origins();
        if (origins.size() == report.entries().size()
                && entry.id() != null
                && entry.id().equals(origins.get(index).entryId())) {
            return origins.get(index);
        }
        return origins.stream()
                .filter(origin -> entry.id() != null && entry.id().equals(origin.entryId()))
                .findFirst()
                .orElse(null);
    }

    private static boolean hasMultipleApplications(List<ActivityEntryOriginDto> origins) {
        return origins.stream()
                        .map(origin -> origin.namespace() + "\0" + origin.application())
                        .distinct()
                        .count()
                > 1;
    }

    private static boolean hasMultipleRuns(List<ActivityEntryOriginDto> origins) {
        return origins.stream()
                        .map(origin -> origin.namespace() + "\0" + origin.application() + "\0" + origin.run())
                        .distinct()
                        .count()
                > 1;
    }

    private static String scopeWarning(List<ActivityEntryOriginDto> origins) {
        boolean applications = hasMultipleApplications(origins);
        boolean runs = hasMultipleRuns(origins);
        String scopes = applications && runs
                ? "multiple applications and runs"
                : applications ? "multiple applications" : "multiple runs";
        return "Entries span " + scopes
                + "; use namespace:<value>, application:<value>, run:<value>, instance:<value>, or route:<value>"
                + " to narrow the durable scope.";
    }

    /**
     * Configuration without the property-name suggestions the browser's search box completes from: well over a thousand
     * entries of metadata, attached to every answer whatever the query matched.
     */
    public static ConfigReport config(ConfigReport report) {
        return new ConfigReport(
                report.activeProfiles(),
                report.sources(),
                report.properties(),
                List.of(),
                report.page(),
                report.overrideCount());
    }

    /** Whether {@code needle} is the shape id the Database advisor reports for {@code sql}. */
    private static boolean matchesShapeId(String sql, String needle) {
        return sql != null
                && needle != null
                && needle.length() == 16
                && SqlStatementNormalizer.shapeId(SqlShapes.fingerprint(sql)).equals(needle);
    }

    /**
     * The newest {@code limit} SQL statements matching {@code query} (in their SQL, category, call site, error, or
     * request, trace, or execution id), and the top statements matching it, with every other field of the report.
     */
    public static Map<String, Object> sqlTraces(SqlTraceReport report, String query, int limit) {
        String needle = PagedList.normalize(query);
        List<SqlTraceEntryDto> matched = report.entries().stream()
                .filter(entry -> PagedList.contains(entry.sql(), needle)
                        || PagedList.contains(entry.category(), needle)
                        || PagedList.contains(entry.statementType(), needle)
                        || PagedList.contains(entry.callSite(), needle)
                        || PagedList.contains(entry.errorMessage(), needle)
                        || PagedList.contains(entry.requestId(), needle)
                        || PagedList.contains(entry.traceId(), needle)
                        || PagedList.contains(entry.executionId(), needle)
                        || matchesShapeId(entry.sql(), needle))
                .toList();
        List<SqlTraceEntryDto> listed = newest(matched, SqlTraceEntryDto::timestamp, limit);
        List<SqlTraceGroupDto> top = report.topStatements().stream()
                .filter(group -> PagedList.contains(group.sql(), needle)
                        || PagedList.contains(group.category(), needle)
                        || group.callSites().stream().anyMatch(site -> PagedList.contains(site, needle)))
                .toList();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("available", report.available());
        view.put("unavailableReason", report.unavailableReason());
        view.put("capturing", report.capturing());
        view.put("captureParameters", report.captureParameters());
        view.put("bufferSize", report.bufferSize());
        view.put("totalCaptured", report.totalCaptured());
        view.put("slowQueryThresholdMillis", report.slowQueryThresholdMillis());
        view.put("dataSources", report.dataSources());
        view.put("stats", report.stats());
        view.put("entries", listed);
        view.put("topStatements", top);
        view.put("warnings", report.warnings());
        view.put("retention", report.retention());
        view.put("page", page(report.entries().size(), matched.size(), limit, listed.size()));
        return view;
    }

    /**
     * The newest {@code limit} REST-client calls matching {@code query} (in their method, URI, host, path, status,
     * client type, error, call site, or request, trace, or execution id), and the top calls matching it, with every
     * other field of the report.
     */
    public static Map<String, Object> restClientTraces(RestClientTraceReport report, String query, int limit) {
        String needle = PagedList.normalize(query);
        List<RestClientTraceEntryDto> matched = report.entries().stream()
                .filter(entry -> PagedList.contains(entry.method(), needle)
                        || PagedList.contains(entry.uri(), needle)
                        || PagedList.contains(entry.host(), needle)
                        || PagedList.contains(entry.path(), needle)
                        || PagedList.contains(
                                entry.status() == null ? null : entry.status().toString(), needle)
                        || PagedList.contains(entry.clientType(), needle)
                        || PagedList.contains(entry.errorMessage(), needle)
                        || PagedList.contains(entry.callSite(), needle)
                        || PagedList.contains(entry.requestId(), needle)
                        || PagedList.contains(entry.traceId(), needle)
                        || PagedList.contains(entry.executionId(), needle))
                .toList();
        List<RestClientTraceEntryDto> listed = newest(matched, RestClientTraceEntryDto::timestamp, limit);
        List<RestClientTraceGroupDto> top = report.topCalls().stream()
                .filter(group -> PagedList.contains(group.method(), needle)
                        || PagedList.contains(group.host(), needle)
                        || PagedList.contains(group.path(), needle)
                        || group.callSites().stream().anyMatch(site -> PagedList.contains(site, needle)))
                .toList();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("available", report.available());
        view.put("unavailableReason", report.unavailableReason());
        view.put("capturing", report.capturing());
        view.put("captureHeaders", report.captureHeaders());
        view.put("bufferSize", report.bufferSize());
        view.put("totalCaptured", report.totalCaptured());
        view.put("slowCallThresholdMillis", report.slowCallThresholdMillis());
        view.put("clientTypes", report.clientTypes());
        view.put("stats", report.stats());
        view.put("entries", listed);
        view.put("topCalls", top);
        view.put("warnings", report.warnings());
        view.put("retention", report.retention());
        view.put("page", page(report.entries().size(), matched.size(), limit, listed.size()));
        return view;
    }

    /**
     * The {@code limit} slowest startup steps matching {@code query} (in their name or a tag's key or value), slowest
     * first. A parent step's duration includes its children's.
     */
    public static Map<String, Object> startup(StartupReport report, String query, int limit) {
        String needle = PagedList.normalize(query);
        List<StartupStepDto> matched = report.steps().stream()
                .filter(step -> PagedList.contains(step.name(), needle) || tagsMatch(step.tags(), needle))
                .sorted(Comparator.comparingLong(StartupStepDto::durationMs)
                        .reversed()
                        .thenComparingLong(StartupStepDto::id))
                .toList();
        List<StartupStepDto> listed = matched.subList(0, Math.min(limit, matched.size()));
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("steps", List.copyOf(listed));
        view.put("page", page(report.steps().size(), matched.size(), limit, listed.size()));
        return view;
    }

    /** The newest {@code limit} log lines matching {@code query} (in their level, logger, thread, or message). */
    public static Map<String, Object> logTail(List<LogLineDto> lines, String query, int limit) {
        String needle = PagedList.normalize(query);
        List<LogLineDto> matched = lines.stream()
                .filter(line -> PagedList.contains(line.level(), needle)
                        || PagedList.contains(line.logger(), needle)
                        || PagedList.contains(line.thread(), needle)
                        || PagedList.contains(line.message(), needle))
                .toList();
        List<LogLineDto> listed = newest(matched, LogLineDto::timestamp, limit);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("entries", listed);
        view.put("page", page(lines.size(), matched.size(), limit, listed.size()));
        return view;
    }

    /**
     * The first {@code limit} coding-agent sessions matching {@code query} (in their id, model, working directory,
     * status, or last activity), in the inventory's order, with every other field of the inventory.
     */
    public static Map<String, Object> sessions(CopilotSessionListDto list, String query, int limit) {
        String needle = PagedList.normalize(query);
        List<CopilotSessionSummary> matched = list.sessions().stream()
                .filter(session -> PagedList.contains(session.id(), needle)
                        || PagedList.contains(session.model(), needle)
                        || PagedList.contains(session.workingDirectory(), needle)
                        || PagedList.contains(session.status(), needle)
                        || PagedList.contains(session.lastActivitySummary(), needle))
                .toList();
        List<CopilotSessionSummary> listed = matched.subList(0, Math.min(limit, matched.size()));
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("available", list.available());
        view.put("unavailableReason", list.unavailableReason());
        view.put("sessionStateDir", list.sessionStateDir());
        view.put("total", list.total());
        view.put("returned", listed.size());
        view.put("maxSessions", list.maxSessions());
        view.put("sessions", List.copyOf(listed));
        view.put("warnings", list.warnings());
        view.put("page", page(list.sessions().size(), matched.size(), limit, listed.size()));
        return view;
    }

    /**
     * The first {@code limit} dependencies matching {@code query} (in their coordinates, package, highest severity, or
     * a vulnerability's id or alias), vulnerable dependencies first, with every other field of the report.
     *
     * <p>{@code limit} bounds the whole answer, not just the dependency count: one dependency can carry dozens of
     * advisories, each with OSV's full text. So each listed dependency keeps at most {@value #ADVISORIES_PER_DEPENDENCY}
     * advisories, active and most severe first, each without its {@code details} and with at most {@value
     * #ADVISORY_LIST_ITEMS} references and advisory symbols. A query that equals a dependency's {@code group:artifact}
     * (or {@code group:artifact:version}, or its package name) lists all its advisories, still without details, and a
     * query that equals an advisory's id or alias returns that advisory whole. The {@code advisories} object counts
     * what was left out and says how to read it.
     */
    public static Map<String, Object> vulnerabilities(DependenciesReport report, String query, int limit) {
        String needle = PagedList.normalize(query);
        List<DependencyDto> matched = new ArrayList<>();
        for (DependencyDto dependency : report.dependencies()) {
            if (dependencyMatches(dependency, needle)) {
                matched.add(dependency);
            }
        }
        // A stable sort: vulnerable dependencies first, each group in the report's own order.
        matched.sort(Comparator.comparing((DependencyDto dependency) -> dependency.vulnerabilityCount() == 0));
        String exact = query == null ? "" : query.strip();
        List<DependencyDto> listed = new ArrayList<>();
        int advisoriesListed = 0;
        int advisoriesOmitted = 0;
        int detailsOmitted = 0;
        for (DependencyDto dependency : matched.subList(0, Math.min(limit, matched.size()))) {
            boolean named = namesDependency(dependency, exact);
            List<DependencyVulnerabilityDto> ranked = new ArrayList<>(dependency.vulnerabilities());
            ranked.sort(Comparator.comparing((DependencyVulnerabilityDto advisory) -> advisory.dismissed())
                    .thenComparingInt(advisory -> rank(advisory.severity())));
            List<DependencyVulnerabilityDto> kept = new ArrayList<>();
            for (DependencyVulnerabilityDto advisory : ranked) {
                boolean focused = namesAdvisory(advisory, exact);
                if (focused) {
                    kept.add(advisory);
                } else if (named || kept.size() < ADVISORIES_PER_DEPENDENCY) {
                    kept.add(compact(advisory));
                    if (advisory.details() != null && !advisory.details().isBlank()) {
                        detailsOmitted++;
                    }
                }
            }
            advisoriesListed += kept.size();
            advisoriesOmitted += ranked.size() - kept.size();
            listed.add(dependency.withRuntimeReach(dependency.runtimeReach(), kept));
        }
        Map<String, Object> advisories = new LinkedHashMap<>();
        advisories.put("listed", advisoriesListed);
        advisories.put("omitted", advisoriesOmitted);
        advisories.put("detailsOmitted", detailsOmitted);
        advisories.put("perDependency", ADVISORIES_PER_DEPENDENCY);
        advisories.put(
                "hint",
                advisoriesOmitted == 0 && detailsOmitted == 0
                        ? null
                        : "Each dependency lists at most " + ADVISORIES_PER_DEPENDENCY + " advisories, active and most "
                                + "severe first, without details and with at most " + ADVISORY_LIST_ITEMS
                                + " references and symbols; listed and omitted include dismissed advisories, which "
                                + "vulnerabilityCount leaves out. Query a dependency's "
                                + "exact group:artifact to list all its advisories, or an exact advisory id or alias "
                                + "to read it whole.");
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("scanningEnabled", report.scanningEnabled());
        view.put("total", report.total());
        view.put("vulnerable", report.vulnerable());
        view.put("severityCounts", report.severityCounts());
        view.put("scan", report.scan());
        view.put("coverage", report.coverage());
        view.put("dependencies", List.copyOf(listed));
        view.put("evidence", report.evidence());
        view.put("runtimeReach", report.runtimeReach());
        view.put("advisories", advisories);
        view.put("page", page(report.dependencies().size(), matched.size(), limit, listed.size()));
        return view;
    }

    /** An advisory without its free text, and with at most {@value #ADVISORY_LIST_ITEMS} references and symbols. */
    private static DependencyVulnerabilityDto compact(DependencyVulnerabilityDto advisory) {
        return new DependencyVulnerabilityDto(
                advisory.id(),
                io.github.jdubois.bootui.engine.vulnerabilities.OsvAdvisoryInterpreter.summary(
                        io.github.jdubois.bootui.engine.vulnerabilities.OsvAdvisoryInterpreter.maliciousPackage(
                                advisory.id()),
                        advisory.summary()),
                null,
                advisory.severity(),
                advisory.score(),
                advisory.aliases(),
                first(advisory.references()),
                advisory.fixedVersions(),
                advisory.fixAvailable(),
                advisory.epssScore(),
                advisory.epssPercentile(),
                advisory.dismissed(),
                first(advisory.advisorySymbols()),
                advisory.advisorySymbolSource(),
                advisory.runtimeReach());
    }

    private static List<String> first(List<String> values) {
        return values.size() <= ADVISORY_LIST_ITEMS ? values : List.copyOf(values.subList(0, ADVISORY_LIST_ITEMS));
    }

    private static int rank(String severity) {
        int index = severity == null ? -1 : VULNERABILITY_SEVERITIES.indexOf(severity.toUpperCase(Locale.ROOT));
        return index < 0 ? VULNERABILITY_SEVERITIES.size() : index;
    }

    private static boolean namesDependency(DependencyDto dependency, String exact) {
        if (exact.isEmpty()) {
            return false;
        }
        String coordinates = dependency.groupId() + ":" + dependency.artifactId();
        return exact.equalsIgnoreCase(coordinates)
                || exact.equalsIgnoreCase(coordinates + ":" + dependency.version())
                || exact.equalsIgnoreCase(dependency.packageName());
    }

    private static boolean namesAdvisory(DependencyVulnerabilityDto advisory, String exact) {
        return !exact.isEmpty()
                && (exact.equalsIgnoreCase(advisory.id())
                        || advisory.aliases().stream().anyMatch(exact::equalsIgnoreCase));
    }

    /**
     * The agent's status, summary first: every sensor's state and counters without its hooks and self-test steps.
     * A {@code query} keeps only the sensors whose id contains it, with their hooks and self-test steps. Every matching
     * sensor is listed, so the tool takes no {@code limit}: the list is short, and trimming it would hide a state.
     */
    public static JavaAgentReport agentStatus(JavaAgentReport report, String query) {
        String needle = PagedList.normalize(query);
        List<JavaAgentSensorDto> sensors = new ArrayList<>();
        for (JavaAgentSensorDto sensor : report.sensors()) {
            if (needle.isEmpty()) {
                sensors.add(summary(sensor));
            } else if (PagedList.contains(sensor.id(), needle)) {
                sensors.add(sensor);
            }
        }
        return new JavaAgentReport(
                report.state(),
                report.reason(),
                report.agentVersion(),
                report.bootUiVersion(),
                report.protocol(),
                report.expectedProtocol(),
                report.jdk(),
                report.loadMode(),
                report.jarPath(),
                report.startupMicros(),
                report.claim(),
                report.heldBy(),
                sensors,
                report.toggles(),
                report.retransformation(),
                report.counters(),
                report.messages(),
                report.warnings(),
                report.setup());
    }

    private static JavaAgentSensorDto summary(JavaAgentSensorDto sensor) {
        return new JavaAgentSensorDto(
                sensor.id(),
                sensor.state(),
                sensor.active(),
                sensor.instrumentedTypes(),
                sensor.failures(),
                sensor.durationMillis(),
                sensor.installMillis(),
                sensor.selfTestMillis(),
                sensor.retransformMillis(),
                sensor.selfTestPassed(),
                sensor.selfTestError(),
                Map.of(),
                List.of(),
                sensor.failedTypes(),
                sensor.skippedTypes(),
                sensor.transformedTypes(),
                sensor.retransformedTypes(),
                sensor.executors(),
                sensor.inventory(),
                sensor.codePaths());
    }

    private static boolean dependencyMatches(DependencyDto dependency, String needle) {
        if (needle.isEmpty()) {
            return true;
        }
        String coordinates = dependency.groupId() + ":" + dependency.artifactId() + ":" + dependency.version();
        if (PagedList.contains(coordinates, needle)
                || PagedList.contains(dependency.packageName(), needle)
                || PagedList.contains(dependency.highestSeverity(), needle)) {
            return true;
        }
        for (DependencyVulnerabilityDto vulnerability : dependency.vulnerabilities()) {
            if (PagedList.contains(vulnerability.id(), needle)
                    || vulnerability.aliases().stream().anyMatch(alias -> PagedList.contains(alias, needle))) {
                return true;
            }
        }
        return false;
    }

    private static boolean tagsMatch(List<TagDto> tags, String needle) {
        for (TagDto tag : tags) {
            if (PagedList.contains(tag.key(), needle) || PagedList.contains(tag.value(), needle)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A DevTools action result in the availability shape other tools use: {@code available=false} with the reason when
     * the action's capability is absent (status {@code unavailable}), and the action, status, and message unchanged.
     */
    public static Map<String, Object> devToolsAction(DevToolsActionResult result) {
        boolean available = !"unavailable".equals(result.status());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("available", available);
        view.put("unavailableReason", available ? null : result.message());
        view.put("action", result.action());
        view.put("status", result.status());
        view.put("message", result.message());
        return view;
    }

    /** The {@code limit} newest of {@code rows}, kept in their original order. */
    private static <T> List<T> newest(List<T> rows, ToLongFunction<T> timestamp, int limit) {
        if (rows.size() <= limit) {
            return List.copyOf(rows);
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingLong((Integer i) -> timestamp.applyAsLong(rows.get(i)))
                .reversed()
                .thenComparing(Comparator.reverseOrder()));
        List<Integer> kept = new ArrayList<>(order.subList(0, limit));
        kept.sort(Comparator.naturalOrder());
        List<T> listed = new ArrayList<>(kept.size());
        for (int i : kept) {
            listed.add(rows.get(i));
        }
        return List.copyOf(listed);
    }

    private static PageMetadata page(int total, int matched, int limit, int returned) {
        return new PageMetadata(total, matched, 0, limit, returned, returned < matched);
    }
}
