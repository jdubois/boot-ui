package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.OrmPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.sqltrace.SqlShapes;
import io.github.jdubois.bootui.engine.web.CorrelationTier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code safe-method-dml} ({@code docs/PLAN-v2.md} §5.5): GET or HEAD requests that successfully executed an INSERT,
 * UPDATE, DELETE, or MERGE, per route and fingerprint. Worded as a question, since an audit write is often intended.
 */
public final class SafeMethodDml implements Observation {

    public static final String KIND = "safe-method-dml";

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String title() {
        return "Writes in GET requests";
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
    public Set<JournalSource> optionalReads(InsightsSnapshot snapshot) {
        return snapshot.stack() == InsightsStack.QUARKUS ? Set.of(JournalSource.ORM) : Set.of();
    }

    @Override
    public Evaluation evaluate(InsightsSnapshot snapshot) {
        List<Finding> findings = new ArrayList<>();
        long eligible = 0;
        boolean hiddenOrm = snapshot.stack() == InsightsStack.QUARKUS
                && snapshot.records(JournalSource.ORM)
                && !snapshot.visible(JournalSource.ORM);
        for (Map.Entry<String, List<ProjectedRequest>> route :
                snapshot.httpByRoute().entrySet()) {
            List<ProjectedRequest> safe = route.getValue().stream()
                    .filter(ProjectedRequest::safeMethod)
                    .toList();
            eligible += safe.size();
            Map<String, List<String[]>> byFingerprint = new LinkedHashMap<>();
            Map<String, String> statements = new LinkedHashMap<>();
            for (ProjectedRequest request : safe) {
                Map<String, int[]> perRequest = new LinkedHashMap<>();
                Map<String, String> callSites = new LinkedHashMap<>();
                boolean preparationsExecuted = preparationsExecuted(snapshot, request);
                for (RuntimeEvent event : request.children(JournalSource.SQL)) {
                    if (preparation(snapshot, event) && (hiddenOrm || !preparationsExecuted)) {
                        continue;
                    }
                    if (event.payload() instanceof SqlPayload sql && !sql.failed() && isDml(sql.sql())) {
                        String fingerprint = SqlShapes.fingerprint(sql.sql());
                        perRequest.computeIfAbsent(fingerprint, f -> new int[1])[0]++;
                        callSites.putIfAbsent(fingerprint, sql.callSite());
                        statements.putIfAbsent(fingerprint, InsightText.statement(sql.sql()));
                    }
                }
                perRequest.forEach((fingerprint, count) -> byFingerprint
                        .computeIfAbsent(fingerprint, f -> new ArrayList<>())
                        .add(new String[] {
                            request.requestId(),
                            String.valueOf(request.status()),
                            String.valueOf(count[0]),
                            callSites.get(fingerprint) == null ? "" : callSites.get(fingerprint)
                        }));
            }
            byFingerprint.forEach((fingerprint, rows) -> findings.add(finding(
                    route.getKey(),
                    fingerprint,
                    statements.get(fingerprint),
                    rows,
                    safe.size(),
                    limitation(snapshot))));
        }
        return new Evaluation(
                eligible,
                findings,
                hiddenOrm
                        ? "The hibernate panel is disabled, so prepared statements cannot be verified as executed"
                                + " writes and are not counted; timed JDBC executions still are."
                        : null);
    }

    /** What a Quarkus finding counts: executions proven by Hibernate's sessions, or preparations without them. */
    private static String limitation(InsightsSnapshot snapshot) {
        if (snapshot.stack() != InsightsStack.QUARKUS) {
            return "Counts statements the database executed successfully; a write that failed is not listed.";
        }
        return snapshot.available(JournalSource.ORM)
                ? "Counts Hibernate statements when they are prepared, leaving out a request whose Hibernate sessions"
                        + " executed no statement at all."
                : snapshot.records(JournalSource.ORM)
                        ? "Counts timed JDBC executions only: the hibernate panel is disabled, so unverified"
                                + " preparations are left out."
                        : "Counts Hibernate statements when they are prepared: record the orm source to leave out a"
                                + " request whose sessions executed none.";
    }

    private Finding finding(
            String route, String fingerprint, String statement, List<String[]> rows, long eligible, String limitation) {
        return new Finding(
                route + ":" + InsightText.stableHash(fingerprint),
                route,
                true,
                "`" + route + "` executed `" + statement + "` in " + rows.size() + " of "
                        + InsightText.counted(eligible, "request") + ": an incidental write, such as an audit or a"
                        + " counter, or a change the caller asked for?",
                eligible,
                rows.size(),
                List.of(
                        "If the caller asked for this change, expose it as POST, PUT, PATCH, or DELETE, since caches,"
                                + " crawlers, prefetchers, and retries may repeat a GET.",
                        "If it is an audit or counter write, check that repeating the request is harmless."),
                rows.stream().limit(3).map(row -> row[0]).toList(),
                List.of("Request", "Status", "Executions", "Call site"),
                rows.stream().map(List::of).toList(),
                List.of(limitation));
    }

    /**
     * Whether {@code event} is a statement Quarkus's Hibernate statement inspector saw when it was prepared, with no
     * duration because the inspector cannot see it execute.
     */
    static boolean preparation(InsightsSnapshot snapshot, RuntimeEvent event) {
        return snapshot.stack() == InsightsStack.QUARKUS && event.durationNanos() <= 0;
    }

    /**
     * Whether the request's prepared statements may have executed (M4-9). On Quarkus, with the {@code orm} source, they
     * did not only when the request's Hibernate sessions were metered and executed no statement at all. Fewer
     * executions than preparations prove nothing, since a JDBC batch over several tables counts once, and a request
     * with no metered session, as when the application names its own session listener, is not evidence either.
     */
    static boolean preparationsExecuted(InsightsSnapshot snapshot, ProjectedRequest request) {
        if (snapshot.stack() != InsightsStack.QUARKUS || !snapshot.available(JournalSource.ORM)) {
            return true;
        }
        boolean metered = false;
        long executed = 0;
        for (RuntimeEvent event : request.children(JournalSource.ORM)) {
            if (event.payload() instanceof OrmPayload orm) {
                metered = true;
                executed += orm.statements();
            }
        }
        return !metered || executed > 0;
    }

    static boolean isDml(String sql) {
        if (sql == null) {
            return false;
        }
        String head = sql.stripLeading().toLowerCase(Locale.ROOT);
        return head.startsWith("insert")
                || head.startsWith("update")
                || head.startsWith("delete")
                || head.startsWith("merge");
    }
}
