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
 * {@code safe-method-dml} ({@code docs/PLAN-v2.md} §5.5): GET or HEAD requests that executed or, on Quarkus,
 * prepared an INSERT, UPDATE, DELETE, or MERGE, per route and fingerprint. Worded as a question, since an audit write
 * is often intended.
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
            Map<StatementKey, List<String[]>> byFingerprint = new LinkedHashMap<>();
            Map<StatementKey, String> statements = new LinkedHashMap<>();
            for (ProjectedRequest request : safe) {
                Map<StatementKey, int[]> perRequest = new LinkedHashMap<>();
                Map<StatementKey, String> callSites = new LinkedHashMap<>();
                boolean preparationsExecuted = preparationsExecuted(snapshot, request);
                for (RuntimeEvent event : request.children(JournalSource.SQL)) {
                    if (!(event.payload() instanceof SqlPayload sql)
                            || sql.provenance() == SqlPayload.Provenance.UNKNOWN) {
                        continue;
                    }
                    boolean prepared = preparation(event);
                    if (prepared && (hiddenOrm || !preparationsExecuted)) {
                        continue;
                    }
                    if (!sql.failed() && isDml(sql.sql())) {
                        StatementKey key = new StatementKey(SqlShapes.fingerprint(sql.sql()), prepared);
                        perRequest.computeIfAbsent(key, f -> new int[1])[0]++;
                        callSites.putIfAbsent(key, sql.callSite());
                        statements.putIfAbsent(key, InsightText.statement(sql.sql()));
                    }
                }
                perRequest.forEach((key, count) -> byFingerprint
                        .computeIfAbsent(key, f -> new ArrayList<>())
                        .add(new String[] {
                            request.requestId(),
                            String.valueOf(request.status()),
                            String.valueOf(count[0]),
                            callSites.get(key) == null ? "" : callSites.get(key)
                        }));
            }
            byFingerprint.forEach((key, rows) -> findings.add(finding(
                    route.getKey(),
                    key,
                    statements.get(key),
                    rows,
                    safe.size(),
                    limitation(snapshot, key.prepared()))));
        }
        return new Evaluation(
                eligible,
                findings,
                hiddenOrm
                        ? "The hibernate panel is disabled, so prepared statements cannot be verified as executed"
                                + " writes and are not counted; timed JDBC executions still are."
                        : null);
    }

    /** A metered session can exclude preparations, but cannot establish which prepared statement executed. */
    private static String limitation(InsightsSnapshot snapshot, boolean prepared) {
        if (!prepared) {
            return "Counts statements the database executed successfully; a write that failed is not listed.";
        }
        return "Quarkus Hibernate records SQL when it is prepared, not when it executes. Even a session with executed"
                + " statements cannot prove this particular statement ran or changed rows."
                + (snapshot.available(JournalSource.ORM)
                        ? " Requests whose metered sessions executed nothing are left out."
                        : " Record the orm source to leave out requests whose sessions executed nothing.");
    }

    private Finding finding(
            String route, StatementKey key, String statement, List<String[]> rows, long eligible, String limitation) {
        return new Finding(
                route + ":" + InsightText.stableHash(key.fingerprint() + (key.prepared() ? " preparation" : "")),
                route,
                true,
                "`" + route + (key.prepared() ? "` prepared `" : "` executed `") + statement + "` in " + rows.size()
                        + " of "
                        + InsightText.counted(eligible, "request") + ": an incidental write, such as an audit or a"
                        + " counter, or a change the caller asked for?",
                eligible,
                rows.size(),
                List.of(
                        "If the caller asked for this change, expose it as POST, PUT, PATCH, or DELETE, since caches,"
                                + " crawlers, prefetchers, and retries may repeat a GET.",
                        key.prepared()
                                ? "Verify whether this prepared statement actually executed before changing the"
                                        + " route; preparing SQL alone does not change rows."
                                : "If it is an audit or counter write, check that repeating the request is harmless."),
                rows.stream().limit(3).map(row -> row[0]).toList(),
                List.of("Request", "Status", key.prepared() ? "Prepared statements" : "Executions", "Call site"),
                rows.stream().map(List::of).toList(),
                List.of(limitation));
    }

    private record StatementKey(String fingerprint, boolean prepared) {}

    /**
     * Whether the feeder observed preparation only, regardless of stack, statement type, or elapsed time.
     */
    static boolean preparation(RuntimeEvent event) {
        return event.payload() instanceof SqlPayload sql && sql.provenance() == SqlPayload.Provenance.PREPARATION;
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
