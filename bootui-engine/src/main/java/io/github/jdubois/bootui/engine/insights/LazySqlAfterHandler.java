package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.engine.correlation.ExecutionIds;
import io.github.jdubois.bootui.engine.correlation.RequestPhase;
import io.github.jdubois.bootui.engine.journal.ApplicationFrames;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.sqltrace.SqlShapes;
import io.github.jdubois.bootui.engine.web.CorrelationTier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code lazy-sql-after-handler} ({@code docs/PLAN-v2.md} §5.5): statements run after the handler returned, while the
 * response was written or the view rendered, outside every transaction, which is how open session in view lazily loads.
 * Reported once three requests show a statement, or one request runs it ten times.
 *
 * <p>A statement whose application frames show a template engine rendering the view, such as Thymeleaf calling a
 * Spring {@code Formatter} to print a select's options, is not lazy loading: the view queried the database itself, so
 * its advice is to load that data in the handler, into the model. Its call site is the application frame the view
 * called, such as the formatter, rather than the template engine's.</p>
 */
public final class LazySqlAfterHandler implements Observation {

    public static final String KIND = "lazy-sql-after-handler";

    static final int MIN_REQUESTS = 3;

    static final int MIN_STATEMENTS_IN_ONE_REQUEST = 10;

    /** Template engines rendering a view: Thymeleaf, FreeMarker, Mustache, and JSP, compiled or by Jasper. */
    static final List<String> VIEW_PREFIXES = List.of(
            "org.thymeleaf.", "freemarker.", "com.samskivert.mustache.", "org.apache.jsp.", "org.apache.jasper.");

    /** Serializers writing a response body, where a lazy association surfaces. */
    static final List<String> SERIALIZER_PREFIXES =
            List.of("com.fasterxml.jackson.", "tools.jackson.", "com.google.gson.");

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String title() {
        return "SQL after the handler returned";
    }

    @Override
    public CorrelationTier minimumTier() {
        return CorrelationTier.REQUEST_ID;
    }

    @Override
    public Set<JournalSource> reads() {
        return Set.of(JournalSource.HTTP, JournalSource.SQL);
    }

    @Override
    public Set<ProjectedRequest.Kind> unitKinds() {
        return Set.of(ProjectedRequest.Kind.HTTP);
    }

    @Override
    public Set<JournalSource> optionalReads() {
        return Set.of(JournalSource.TRANSACTION);
    }

    @Override
    public String notApplicable(InsightsSnapshot snapshot) {
        if (snapshot.stack() == InsightsStack.SPRING_WEBFLUX) {
            return "Spring WebFlux has no open session in view, so no statement runs while the response is written.";
        }
        if (snapshot.stack() == InsightsStack.QUARKUS) {
            return "Quarkus closes the session with the transaction, so a lazy load after the handler fails with"
                    + " LazyInitializationException, which Exception hotspots reports.";
        }
        return null;
    }

    @Override
    public Evaluation evaluate(InsightsSnapshot snapshot) {
        boolean transactions = snapshot.available(JournalSource.TRANSACTION);
        List<Finding> findings = new ArrayList<>();
        long eligible = 0;
        long unplaced = 0;
        for (Map.Entry<String, List<ProjectedRequest>> route :
                snapshot.httpByRoute().entrySet()) {
            List<ProjectedRequest> requests = route.getValue();
            long routeEligible = 0;
            long routeUnplaced = 0;
            Map<String, Statement> statements = new LinkedHashMap<>();
            Map<String, String> shapes = new LinkedHashMap<>();
            for (ProjectedRequest request : requests) {
                TransactionWindows windows = new TransactionWindows(request);
                List<SqlPayload> afterHandler = new ArrayList<>();
                boolean placeable = true;
                for (RuntimeEvent event : request.children(JournalSource.SQL)) {
                    // Work a propagated task did is work-after-response's, not lazy loading in the response.
                    if (!(event.payload() instanceof SqlPayload sql)
                            || sql.phase() != RequestPhase.RESPONSE
                            || ExecutionIds.isAsync(event.executionId())) {
                        continue;
                    }
                    if (transactions && !windows.canPlace(event)) {
                        placeable = false;
                        break;
                    }
                    if (!transactions || windows.innermost(event) == null) {
                        afterHandler.add(sql);
                    }
                }
                if (!placeable) {
                    // Counted apart: whether its statements ran outside a transaction is unknown (M3-2b).
                    routeUnplaced++;
                    continue;
                }
                routeEligible++;
                Map<String, int[]> perRequest = new LinkedHashMap<>();
                Map<String, String> sites = new LinkedHashMap<>();
                Set<String> rendering = new HashSet<>();
                for (SqlPayload sql : afterHandler) {
                    String fingerprint = SqlShapes.fingerprint(sql.sql());
                    perRequest.computeIfAbsent(fingerprint, f -> new int[1])[0]++;
                    sites.putIfAbsent(fingerprint, callSite(sql));
                    shapes.putIfAbsent(fingerprint, InsightText.statement(sql.sql()));
                    if (renderingView(sql.frames())) {
                        rendering.add(fingerprint);
                    }
                }
                perRequest.forEach((fingerprint, count) -> statements
                        .computeIfAbsent(fingerprint, f -> new Statement())
                        .add(request, count[0], sites.get(fingerprint), rendering.contains(fingerprint)));
            }
            eligible += routeEligible;
            unplaced += routeUnplaced;
            long examined = routeEligible;
            long apart = routeUnplaced;
            statements.forEach((fingerprint, statement) -> findings.add(finding(
                    route.getKey(), fingerprint, shapes.get(fingerprint), statement, examined, apart, transactions)));
        }
        return new Evaluation(
                eligible,
                findings,
                unplaced == 0
                        ? null
                        : InsightText.counted(unplaced, "request") + " ran SQL after the handler that could not be"
                                + " placed against " + (unplaced == 1 ? "its" : "their") + " transactions, since a"
                                + " transaction or statement had no monotonic time, so "
                                + (unplaced == 1 ? "it is" : "they are")
                                + " not counted.");
    }

    private Finding finding(
            String route,
            String fingerprint,
            String shown,
            Statement statement,
            long eligible,
            long unplaced,
            boolean transactions) {
        boolean sufficient =
                statement.rows.size() >= MIN_REQUESTS || statement.mostInOneRequest >= MIN_STATEMENTS_IN_ONE_REQUEST;
        String counted = "`" + route + "` ran `" + shown + "` after its handler returned, "
                + InsightText.counted(statement.executions, "time") + " in " + statement.rows.size() + " of "
                + InsightText.counted(eligible, "request");
        String sentence = sufficient
                ? counted + ", outside a transaction, while "
                        + (statement.view ? "the view was rendered." : "the response was written.")
                : counted + "; reported from " + MIN_REQUESTS + " requests, or " + MIN_STATEMENTS_IN_ONE_REQUEST
                        + " executions in one.";
        List<String> limitations = new ArrayList<>();
        if (!transactions) {
            limitations.add("Without recorded transactions, statements are not checked to run outside one.");
        }
        if (unplaced > 0) {
            limitations.add(InsightText.counted(unplaced, "request") + " could not be placed, since a transaction or"
                    + " statement had no monotonic time.");
        }
        return new Finding(
                route + ":" + InsightText.stableHash(fingerprint),
                route,
                sufficient,
                sentence,
                eligible,
                statement.rows.size(),
                statement.view ? viewChecks(statement.site) : LAZY_LOADING_CHECKS,
                statement.rows.stream().limit(3).map(row -> row.get(0)).toList(),
                List.of("Request", "Executions", "Call site"),
                statement.rows,
                limitations);
    }

    private static final List<String> LAZY_LOADING_CHECKS = List.of(
            "If serialization or the view reads a lazy association, fetch it in the handler's query, or build the"
                    + " response inside the transaction.",
            "If this application relies on open session in view, consider spring.jpa.open-in-view=false, which turns"
                    + " these loads into errors you can see.");

    private static List<String> viewChecks(String site) {
        String through = site == null || startsWithAny(className(site), VIEW_PREFIXES)
                ? ""
                : ", through `" + InsightText.simpleName(className(site)) + "." + methodName(site) + "`";
        return List.of(
                "The view ran this query while it was rendered" + through + ": load what the view needs in the"
                        + " handler and add it to the model, instead of querying from a formatter, converter, or"
                        + " template the view calls.",
                "If every request reads the same reference data, such as the options of a select, cache it.");
    }

    /**
     * Whether one of {@code lazy}, this kind's findings, already reports {@code repeated}, a {@code repeated-selects}
     * finding, with its cause (M4-20's adjudication follow-up 1): a finding with the same key, the route and the
     * statement's fingerprint, that shares one of its call sites, or names none when that one names none either, and is
     * at least as sufficient, so the fact is never reported weaker than it was.
     */
    static boolean reports(List<Finding> lazy, Finding repeated) {
        Set<String> repeatedSites = callSites(repeated);
        for (Finding finding : lazy) {
            if (!finding.key().equals(repeated.key()) || (repeated.sufficient() && !finding.sufficient())) {
                continue;
            }
            Set<String> sites = callSites(finding);
            if (sites.isEmpty() && repeatedSites.isEmpty()) {
                return true;
            }
            for (String site : sites) {
                if (repeatedSites.contains(site)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The non-empty values of a finding's {@code Call site} evidence column. */
    private static Set<String> callSites(Finding finding) {
        int column = finding.columns().indexOf("Call site");
        Set<String> sites = new HashSet<>();
        if (column < 0) {
            return sites;
        }
        for (List<String> row : finding.rows()) {
            if (column < row.size() && !row.get(column).isBlank()) {
                sites.add(row.get(column));
            }
        }
        return sites;
    }

    /** Whether {@code frames} show a template engine rendering the view the statement ran under. */
    static boolean renderingView(ApplicationFrames frames) {
        if (frames == null) {
            return false;
        }
        for (String frame : frames.frames()) {
            if (startsWithAny(className(frame), VIEW_PREFIXES)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The statement's call site as shown: its innermost application frame that is neither a template engine's nor a
     * serializer's, such as the formatter a view called, or the recorded call site when every frame is one.
     */
    static String callSite(SqlPayload sql) {
        if (sql.frames() != null) {
            for (String frame : sql.frames().frames()) {
                String className = className(frame);
                if (!startsWithAny(className, VIEW_PREFIXES) && !startsWithAny(className, SERIALIZER_PREFIXES)) {
                    return frame;
                }
            }
        }
        return sql.callSite();
    }

    /** The class of a frame formatted as {@code com.example.Type.method(Type.java:12)}. */
    static String className(String frame) {
        String method = frame.contains("(") ? frame.substring(0, frame.indexOf('(')) : frame;
        int dot = method.lastIndexOf('.');
        return dot < 0 ? method : method.substring(0, dot);
    }

    private static String methodName(String frame) {
        String method = frame.contains("(") ? frame.substring(0, frame.indexOf('(')) : frame;
        return method.substring(method.lastIndexOf('.') + 1);
    }

    private static boolean startsWithAny(String className, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static final class Statement {

        private final List<List<String>> rows = new ArrayList<>();
        private long executions;
        private int mostInOneRequest;
        private boolean view;
        private String site;

        void add(ProjectedRequest request, int count, String site, boolean view) {
            executions += count;
            mostInOneRequest = Math.max(mostInOneRequest, count);
            this.view |= view;
            if (this.site == null) {
                this.site = site;
            }
            rows.add(List.of(request.requestId(), String.valueOf(count), site == null ? "" : site));
        }
    }
}
