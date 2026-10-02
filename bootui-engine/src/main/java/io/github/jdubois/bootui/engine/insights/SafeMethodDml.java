package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.engine.journal.JournalSource;
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
        return Set.of(JournalSource.SQL);
    }

    @Override
    public Evaluation evaluate(InsightsSnapshot snapshot) {
        List<Finding> findings = new ArrayList<>();
        long eligible = 0;
        for (Map.Entry<String, List<ProjectedRequest>> route :
                snapshot.byRoute().entrySet()) {
            List<ProjectedRequest> safe = route.getValue().stream()
                    .filter(ProjectedRequest::safeMethod)
                    .toList();
            eligible += safe.size();
            Map<String, List<String[]>> byFingerprint = new LinkedHashMap<>();
            for (ProjectedRequest request : safe) {
                Map<String, int[]> perRequest = new LinkedHashMap<>();
                Map<String, String> callSites = new LinkedHashMap<>();
                for (RuntimeEvent event : request.children(JournalSource.SQL)) {
                    if (event.payload() instanceof SqlPayload sql && !sql.failed() && isDml(sql.sql())) {
                        String fingerprint = SqlShapes.fingerprint(sql.sql());
                        perRequest.computeIfAbsent(fingerprint, f -> new int[1])[0]++;
                        callSites.putIfAbsent(fingerprint, sql.callSite());
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
            byFingerprint.forEach(
                    (fingerprint, rows) -> findings.add(finding(route.getKey(), fingerprint, rows, safe.size())));
        }
        return new Evaluation(eligible, findings);
    }

    private Finding finding(String route, String fingerprint, List<String[]> rows, long eligible) {
        return new Finding(
                route + ":" + InsightText.stableHash(fingerprint),
                route,
                true,
                "`" + route + "` executed `" + InsightText.quoted(fingerprint) + "` in " + rows.size() + " of "
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
                List.of("Counts statements the database executed successfully; a write that failed is not listed."));
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
