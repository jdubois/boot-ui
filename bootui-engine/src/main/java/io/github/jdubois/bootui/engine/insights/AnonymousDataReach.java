package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.engine.journal.AuthorizationPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.JournalTextExposure;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.sqltrace.SqlTables;
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
    public Set<JournalSource> optionalReads() {
        return Set.of(JournalSource.ORM);
    }

    @Override
    public Set<ProjectedRequest.Kind> unitKinds() {
        return Set.of(ProjectedRequest.Kind.HTTP);
    }

    @Override
    public Evaluation evaluate(InsightsSnapshot snapshot) {
        List<Finding> findings = new ArrayList<>();
        long eligible = 0;
        long truncated = 0;
        boolean hiddenOrm = snapshot.stack() == InsightsStack.QUARKUS
                && snapshot.records(JournalSource.ORM)
                && !snapshot.visible(JournalSource.ORM);
        for (Map.Entry<String, List<ProjectedRequest>> route :
                snapshot.httpByRoute().entrySet()) {
            long anonymous = 0;
            Map<Target, List<List<String>>> byTable = new LinkedHashMap<>();
            for (ProjectedRequest request : route.getValue()) {
                List<AuthorizationPayload> decisions = AnonymousAccess.decisions(request);
                if (!AnonymousAccess.provenAnonymous(decisions) || !AnonymousAccess.succeeded(request, decisions)) {
                    continue;
                }
                anonymous++;
                Map<Target, int[]> writes = new LinkedHashMap<>();
                Map<Target, String> statements = new LinkedHashMap<>();
                boolean incomplete = false;
                boolean preparationsExecuted = SafeMethodDml.preparationsExecuted(snapshot, request);
                for (RuntimeEvent event : request.children(JournalSource.SQL)) {
                    if (event.payload() instanceof SqlPayload sql && !sql.failed()) {
                        boolean preparation = SafeMethodDml.preparation(snapshot, event);
                        if (preparation && (hiddenOrm || !preparationsExecuted)) {
                            continue;
                        }
                        incomplete |= sql.sql() != null && sql.sql().contains("…");
                        for (SqlTables.WriteTargets targets : SqlTables.writes(sql.sql())) {
                            for (String table : targets.tables()) {
                                Target target = new Target(table, targets.exact(), preparation);
                                writes.computeIfAbsent(target, ignored -> new int[1])[0]++;
                                statements.putIfAbsent(target, JournalTextExposure.displayShape(sql.sql()));
                            }
                        }
                    }
                }
                if (incomplete) {
                    truncated++;
                }
                writes.forEach((target, count) -> byTable.computeIfAbsent(target, ignored -> new ArrayList<>())
                        .add(List.of(
                                request.requestId(),
                                String.valueOf(request.status()),
                                String.valueOf(count[0]),
                                statements.get(target))));
            }
            eligible += anonymous;
            long anonymousRequests = anonymous;
            byTable.forEach((target, rows) -> findings.add(finding(route.getKey(), target, rows, anonymousRequests)));
        }
        return new Evaluation(
                eligible,
                findings,
                truncated == 0 && !hiddenOrm
                        ? null
                        : (truncated == 0
                                        ? ""
                                        : InsightText.counted(truncated, "successful anonymous request")
                                                + " included possibly truncated SQL previews; not every write target"
                                                + " can be identified.")
                                + (hiddenOrm
                                        ? " The hibernate panel is disabled, so unverified Quarkus preparations are"
                                                + " not counted; timed JDBC executions still are."
                                        : ""));
    }

    private static Finding finding(String route, Target target, List<List<String>> rows, long anonymous) {
        String table = target.table();
        boolean exact = target.exact();
        boolean preparation = target.preparation();
        return new Finding(
                route + ":"
                        + InsightText.stableHash(
                                table + (preparation ? " preparation" : "") + (exact ? "" : " candidate")),
                route,
                true,
                "`" + route
                        + (preparation
                                ? exact
                                        ? "` prepared a write statement targeting table `"
                                        : "` prepared a write statement naming lexical candidate `"
                                : exact ? "` wrote table `" : "` executed write statements naming lexical candidate `")
                        + table + "` in " + rows.size() + " of "
                        + InsightText.counted(anonymous, "successful anonymous request") + ".",
                anonymous,
                rows.size(),
                List.of(
                        preparation
                                ? "Verify whether this prepared statement actually executed before changing"
                                        + " authorization; preparing SQL alone does not change the table."
                                : exact
                                        ? "If `" + table
                                                + "` should only change for signed-in callers, check the rule that let these"
                                                + " requests through."
                                        : "Resolve the statement's write targets before changing authorization: `"
                                                + table
                                                + "` is a lexical candidate and may only be read or be an alias.",
                        AnonymousAccess.VERIFY),
                rows.stream().limit(3).map(row -> row.get(0)).toList(),
                List.of("Request", "Status", "Captured DML texts", "Statement"),
                rows,
                List.of(
                        "Tables are read from the statement text, so a write through a view, a procedure, or a trigger"
                                + " names what it called, not what it changed.",
                        preparation
                                ? "Quarkus Hibernate records SQL when it is prepared, not when it executes. Even a"
                                        + " session with executed statements cannot prove this particular statement"
                                        + " ran or changed rows."
                                : exact
                                        ? "Only the target of each captured INSERT INTO, UPDATE, DELETE FROM, or MERGE INTO"
                                                + " statement text is counted, not affected rows or prepared-batch executions."
                                        : "At least one statement has an ambiguous DML target: lexical candidates include"
                                                + " every table name found, which may be read-side tables or aliases, not"
                                                + " proven writes. CTE-headed statements are not parsed.",
                        "Statement batch previews retain at most five statements, each at most 256 characters before"
                                + " a truncation marker; omitted or truncated text can hide other write targets."
                                + " Prepared batches retain one SQL text, not one per parameter set.",
                        "Only requests an authorization decision proved anonymous are counted; a request no rule"
                                + " checked is not."));
    }

    private record Target(String table, boolean exact, boolean preparation) {}
}
