package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.engine.correlation.RequestPhase;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.sqltrace.SqlStatementNormalizer;
import io.github.jdubois.bootui.engine.web.CorrelationTier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code lazy-sql-after-handler} ({@code docs/PLAN-v2.md} §5.5): statements run after the handler returned, while the
 * response was written or the view rendered, outside every transaction, which is how open session in view lazily loads.
 * Reported once three requests show a statement, or one request runs it ten times.
 */
public final class LazySqlAfterHandler implements Observation {

    public static final String KIND = "lazy-sql-after-handler";

    static final int MIN_REQUESTS = 3;

    static final int MIN_STATEMENTS_IN_ONE_REQUEST = 10;

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
        return Set.of(JournalSource.SQL);
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
        boolean transactions =
                snapshot.records(JournalSource.TRANSACTION) && snapshot.visible(JournalSource.TRANSACTION);
        List<Finding> findings = new ArrayList<>();
        long eligible = 0;
        for (Map.Entry<String, List<ProjectedRequest>> route :
                snapshot.byRoute().entrySet()) {
            List<ProjectedRequest> requests = route.getValue();
            eligible += requests.size();
            Map<String, Statement> statements = new LinkedHashMap<>();
            for (ProjectedRequest request : requests) {
                TransactionWindows windows = new TransactionWindows(request);
                Map<String, int[]> perRequest = new LinkedHashMap<>();
                Map<String, String> sites = new LinkedHashMap<>();
                for (RuntimeEvent event : request.children(JournalSource.SQL)) {
                    if (!(event.payload() instanceof SqlPayload sql) || sql.phase() != RequestPhase.RESPONSE) {
                        continue;
                    }
                    if (transactions && (!windows.canPlace(event) || windows.innermost(event) != null)) {
                        continue;
                    }
                    String fingerprint = SqlStatementNormalizer.fingerprintOf(sql.sql());
                    perRequest.computeIfAbsent(fingerprint, f -> new int[1])[0]++;
                    sites.putIfAbsent(fingerprint, sql.callSite());
                }
                perRequest.forEach((fingerprint, count) -> statements
                        .computeIfAbsent(fingerprint, f -> new Statement())
                        .add(request, count[0], sites.get(fingerprint)));
            }
            statements.forEach((fingerprint, statement) ->
                    findings.add(finding(route.getKey(), fingerprint, statement, requests.size(), transactions)));
        }
        return new Evaluation(eligible, findings);
    }

    private Finding finding(
            String route, String fingerprint, Statement statement, long eligible, boolean transactions) {
        boolean sufficient =
                statement.rows.size() >= MIN_REQUESTS || statement.mostInOneRequest >= MIN_STATEMENTS_IN_ONE_REQUEST;
        String counted = "`" + route + "` ran `" + InsightText.quoted(fingerprint) + "` after its handler returned, "
                + InsightText.counted(statement.executions, "time") + " in " + statement.rows.size() + " of "
                + InsightText.counted(eligible, "request");
        String sentence = sufficient
                ? counted + ", outside a transaction, while the response was written."
                : counted + "; reported from " + MIN_REQUESTS + " requests, or " + MIN_STATEMENTS_IN_ONE_REQUEST
                        + " executions in one.";
        List<String> limitations = new ArrayList<>();
        if (!transactions) {
            limitations.add("Without recorded transactions, statements are not checked to run outside one.");
        }
        return new Finding(
                route + ":" + InsightText.stableHash(fingerprint),
                route,
                sufficient,
                sentence,
                eligible,
                statement.rows.size(),
                List.of(
                        "If serialization or the view reads a lazy association, fetch it in the handler's query, or"
                                + " build the response inside the transaction.",
                        "If this application relies on open session in view, consider spring.jpa.open-in-view=false,"
                                + " which turns these loads into errors you can see."),
                statement.rows.stream().limit(3).map(row -> row.get(0)).toList(),
                List.of("Request", "Executions", "Call site"),
                statement.rows,
                limitations);
    }

    private static final class Statement {

        private final List<List<String>> rows = new ArrayList<>();
        private long executions;
        private int mostInOneRequest;

        void add(ProjectedRequest request, int count, String site) {
            executions += count;
            mostInOneRequest = Math.max(mostInOneRequest, count);
            rows.add(List.of(request.requestId(), String.valueOf(count), site == null ? "" : site));
        }
    }
}
