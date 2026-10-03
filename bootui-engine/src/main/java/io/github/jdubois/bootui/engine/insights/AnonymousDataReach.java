package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.engine.journal.AuthorizationPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.sqltrace.SqlShapes;
import io.github.jdubois.bootui.engine.web.CorrelationTier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code anonymous-data-reach} ({@code docs/PLAN-v2.md} §5.9): successful requests whose caller an authorization
 * decision proved anonymous, and that wrote a table, per route and table. A fact, never a vulnerability verdict:
 * anonymous and protected reads of a table are the normal public-catalog pattern and are never reported.
 */
public final class AnonymousDataReach implements Observation {

    public static final String KIND = "anonymous-data-reach";

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String title() {
        return "Anonymous writes";
    }

    @Override
    public CorrelationTier minimumTier() {
        return CorrelationTier.REQUEST_ID;
    }

    @Override
    public Set<JournalSource> reads() {
        return Set.of(JournalSource.HTTP, JournalSource.SQL, JournalSource.AUTHORIZATION);
    }

    @Override
    public Evaluation evaluate(InsightsSnapshot snapshot) {
        List<Finding> findings = new ArrayList<>();
        long eligible = 0;
        for (Map.Entry<String, List<ProjectedRequest>> route :
                snapshot.httpByRoute().entrySet()) {
            long anonymous = 0;
            Map<String, List<List<String>>> byTable = new LinkedHashMap<>();
            for (ProjectedRequest request : route.getValue()) {
                List<AuthorizationPayload> decisions = AnonymousAccess.decisions(request);
                if (!AnonymousAccess.provenAnonymous(decisions) || !AnonymousAccess.succeeded(request, decisions)) {
                    continue;
                }
                anonymous++;
                Map<String, int[]> writes = new LinkedHashMap<>();
                Map<String, String> statements = new LinkedHashMap<>();
                for (RuntimeEvent event : request.children(JournalSource.SQL)) {
                    if (event.payload() instanceof SqlPayload sql && !sql.failed() && SafeMethodDml.isDml(sql.sql())) {
                        for (String table : SqlShapes.tables(sql.sql())) {
                            writes.computeIfAbsent(table, ignored -> new int[1])[0]++;
                            statements.putIfAbsent(table, SqlShapes.fingerprint(sql.sql()));
                        }
                    }
                }
                writes.forEach((table, count) -> byTable.computeIfAbsent(table, ignored -> new ArrayList<>())
                        .add(List.of(
                                request.requestId(),
                                String.valueOf(request.status()),
                                String.valueOf(count[0]),
                                statements.get(table))));
            }
            eligible += anonymous;
            long anonymousRequests = anonymous;
            byTable.forEach((table, rows) -> findings.add(finding(route.getKey(), table, rows, anonymousRequests)));
        }
        return new Evaluation(eligible, findings);
    }

    private static Finding finding(String route, String table, List<List<String>> rows, long anonymous) {
        return new Finding(
                route + ":" + InsightText.stableHash(table),
                route,
                true,
                "`" + route + "` wrote table `" + table + "` in " + rows.size() + " of "
                        + InsightText.counted(anonymous, "successful anonymous request") + ".",
                anonymous,
                rows.size(),
                List.of(
                        "If `" + table + "` should only change for signed-in callers, check the rule that let these"
                                + " requests through.",
                        AnonymousAccess.VERIFY),
                rows.stream().limit(3).map(row -> row.get(0)).toList(),
                List.of("Request", "Status", "Writes", "Statement"),
                rows,
                List.of(
                        "Tables are read from the statement text, so a write through a view, a procedure, or a trigger"
                                + " names what it called, not what it changed.",
                        "Only requests an authorization decision proved anonymous are counted; a request no rule"
                                + " checked is not."));
    }
}
