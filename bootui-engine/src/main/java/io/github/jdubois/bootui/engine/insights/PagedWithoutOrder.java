package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.sqltrace.SqlRowLimits;
import io.github.jdubois.bootui.engine.sqltrace.SqlShapes;
import io.github.jdubois.bootui.engine.web.CorrelationTier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code paged-without-order}: a SELECT that limits its rows without an {@code ORDER BY}, which returns an arbitrary
 * subset, so pages can repeat or skip rows between requests. Reported per route and literal-free statement once
 * {@value #MIN_REQUESTS} requests ran it; fewer are reported as insufficient.
 *
 * <p>A candidate is an executed, readable SELECT ({@link SqlRowLimits}) that limits its rows to more than one, has no
 * {@code ORDER BY} in its outermost query block, is no set operation, and does not project a constant, a {@code
 * count}, or an {@code exists}; and that either skips rows, which is always a later page, or filters on more than
 * equalities. A limit over an equality-only filter is far more likely a key lookup, as {@code existsBy...} and {@code
 * findFirstBy...} render {@code where a = ? and b = ? limit ?}, so it is left out to protect precision. Statements that
 * could not be read, such as truncated SQL, are never treated as lacking an {@code ORDER BY}; they are counted in the
 * check's reason, as are set operations and, on Quarkus, Hibernate statements, which are only prepared.</p>
 */
public final class PagedWithoutOrder implements Observation {

    public static final String KIND = "paged-without-order";

    /** Requests that must run a statement before it is reported rather than shown as insufficient. */
    static final int MIN_REQUESTS = 2;

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String title() {
        return "Paged without ORDER BY";
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
        Tally tally = new Tally();
        Map<String, SqlRowLimits.Analysis> analyses = new HashMap<>();
        List<Finding> findings = new ArrayList<>();
        for (Map.Entry<String, List<ProjectedRequest>> route :
                snapshot.byRoute().entrySet()) {
            Map<String, Group> groups = new LinkedHashMap<>();
            for (ProjectedRequest request : route.getValue()) {
                Map<String, Group.Run> perRequest = new LinkedHashMap<>();
                for (RuntimeEvent event : request.children(JournalSource.SQL)) {
                    if (!(event.payload() instanceof SqlPayload sql)
                            || sql.failed()
                            || sql.provenance() == SqlPayload.Provenance.UNKNOWN) {
                        continue;
                    }
                    SqlRowLimits.Analysis analysis = analyses.computeIfAbsent(sql.sql(), SqlRowLimits::analyze);
                    boolean prepared = sql.provenance() == SqlPayload.Provenance.PREPARATION;
                    if (!candidate(analysis, tally, prepared)) {
                        continue;
                    }
                    String fingerprint = SqlShapes.fingerprint(sql.sql());
                    groups.computeIfAbsent(fingerprint, f -> new Group(InsightText.statement(sql.sql())));
                    perRequest
                            .computeIfAbsent(fingerprint, f -> new Group.Run(request.requestId()))
                            .add(sql.callSite());
                }
                perRequest.forEach(
                        (fingerprint, run) -> groups.get(fingerprint).runs.add(run));
            }
            groups.forEach((fingerprint, group) -> findings.add(
                    finding(route.getKey(), fingerprint, group, route.getValue().size())));
        }
        return new Evaluation(snapshot.requests().size(), findings, tally.reason());
    }

    /** Whether the statement is a candidate, counting what is left out in {@code tally}. */
    private static boolean candidate(SqlRowLimits.Analysis analysis, Tally tally, boolean prepared) {
        if (!analysis.complete()) {
            if (analysis.select() && !prepared) {
                tally.unreadable(analysis.incompleteReason());
            }
            return false;
        }
        if (!analysis.select()) {
            return false;
        }
        if (analysis.setOperation()) {
            if (!prepared) {
                tally.setOperations++;
            }
            return false;
        }
        boolean paged = analysis.rowLimit()
                && !analysis.orderBy()
                && !analysis.constantOrAggregateProjection()
                && (analysis.offset() || !analysis.equalityOnlyWhere());
        if (paged && prepared) {
            // Quarkus Hibernate records a statement when it is prepared, which does not show it ran or returned rows.
            tally.prepared++;
            return false;
        }
        return paged;
    }

    private Finding finding(String route, String fingerprint, Group group, long eligible) {
        boolean sufficient = group.runs.size() >= MIN_REQUESTS;
        long executions = group.runs.stream().mapToLong(run -> run.executions).sum();
        String unit = InsightText.unit(route);
        String sentence = sufficient
                ? "`" + route + "` ran a statement that limits rows without an `ORDER BY`, "
                        + InsightText.counted(executions, "time") + " in "
                        + InsightText.counted(group.runs.size(), unit)
                        + "."
                : "`" + route + "`: " + group.runs.size() + " of " + MIN_REQUESTS + " " + unit
                        + "s needed to report a statement that limits rows without an `ORDER BY`.";
        group.runs.sort((a, b) -> Integer.compare(b.executions, a.executions));
        return new Finding(
                route + ":" + InsightText.stableHash(fingerprint),
                route,
                sufficient,
                sentence,
                eligible,
                group.runs.size(),
                List.of(
                        "Add a deterministic `ORDER BY`, with a unique key last, to paged queries; with Spring Data,"
                                + " pass a sorted `Pageable` or a `Sort`.",
                        "If the rows are meant to be arbitrary, such as a sample, this is expected."),
                group.runs.stream().limit(3).map(run -> run.requestId).toList(),
                List.of("Request", "Executions", "Call site", "Statement"),
                group.runs.stream()
                        .map(run -> List.of(
                                run.requestId,
                                String.valueOf(run.executions),
                                run.callSite == null ? "" : run.callSite,
                                group.statement))
                        .toList(),
                List.of(
                        "A statement that limits its rows over an equality-only filter, such as `where a = ? and b = ?"
                                + " limit ?`, is read as a key lookup and not reported, which includes the first"
                                + " page of a pageable query filtered only by equalities.",
                        "Statements that are truncated, or that this check cannot read, are left out; Oracle `ROWNUM`"
                                + " wrappers are not read.",
                        "The rows may be intentionally arbitrary: verify against the query before changing it."));
    }

    /** One literal-free statement's runs on a route. */
    private static final class Group {

        final String statement;
        final List<Run> runs = new ArrayList<>();

        Group(String statement) {
            this.statement = statement;
        }

        /** One request's executions of the statement. */
        static final class Run {

            final String requestId;
            int executions;
            String callSite;

            Run(String requestId) {
                this.requestId = requestId;
            }

            void add(String site) {
                executions++;
                if (callSite == null) {
                    callSite = site;
                }
            }
        }
    }

    /** What the check left out, for its reason. */
    private static final class Tally {

        int unreadable;
        String firstReason;
        int setOperations;
        int prepared;

        void unreadable(String reason) {
            unreadable++;
            if (firstReason == null) {
                firstReason = reason;
            }
        }

        String reason() {
            List<String> parts = new ArrayList<>();
            if (unreadable > 0) {
                parts.add(InsightText.counted(unreadable, "SELECT") + " could not be read and "
                        + (unreadable == 1 ? "is" : "are") + " not counted, the first because: " + firstReason);
            }
            if (setOperations > 0) {
                parts.add(InsightText.counted(setOperations, "statement")
                        + " with a UNION, INTERSECT, or EXCEPT " + (setOperations == 1 ? "is" : "are")
                        + " not read.");
            }
            if (prepared > 0) {
                parts.add(InsightText.counted(prepared, "prepared Hibernate statement") + " that limits rows without"
                        + " an `ORDER BY` is not counted: Quarkus records a statement when it is prepared, which does"
                        + " not show it ran.");
            }
            return parts.isEmpty() ? null : String.join(" ", parts);
        }
    }
}
