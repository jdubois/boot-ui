package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.web.CorrelationTier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code split-transaction-writes} ({@code docs/PLAN-v2.md} §5.5): requests that committed their writes in two or more
 * independent units, each a physical transaction, savepoints included in theirs, or an autocommit statement, with their
 * boundaries. Worded "if these writes must succeed together…", since a deliberate audit or outbox write is common.
 */
public final class SplitTransactionWrites implements Observation {

    public static final String KIND = "split-transaction-writes";

    static final String AUTOCOMMIT = "autocommit";

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String title() {
        return "Writes split across transactions";
    }

    @Override
    public CorrelationTier minimumTier() {
        return CorrelationTier.REQUEST_ID;
    }

    @Override
    public Set<JournalSource> reads() {
        return Set.of(JournalSource.SQL, JournalSource.TRANSACTION);
    }

    @Override
    public String notApplicable(InsightsSnapshot snapshot) {
        return snapshot.stack() == InsightsStack.QUARKUS
                ? "Quarkus transactions are not recorded, so writes cannot be placed in them."
                : null;
    }

    @Override
    public Evaluation evaluate(InsightsSnapshot snapshot) {
        List<Finding> findings = new ArrayList<>();
        long eligible = 0;
        long uncounted = 0;
        for (Map.Entry<String, List<ProjectedRequest>> route :
                snapshot.byRoute().entrySet()) {
            long routeEligible = 0;
            long unplaced = 0;
            List<List<String>> rows = new ArrayList<>();
            for (ProjectedRequest request : route.getValue()) {
                List<RuntimeEvent> writes = request.children(JournalSource.SQL).stream()
                        .filter(SplitTransactionWrites::committedWrite)
                        .toList();
                if (writes.isEmpty()) {
                    continue;
                }
                TransactionWindows windows = new TransactionWindows(request);
                if (!writes.stream().allMatch(windows::canPlace)) {
                    unplaced++;
                    continue;
                }
                routeEligible++;
                Map<Object, Unit> units = new LinkedHashMap<>();
                for (RuntimeEvent write : writes) {
                    TransactionWindows.Window committing = windows.committing(write);
                    if (committing != null && committing.transaction().rolledBack()) {
                        continue;
                    }
                    Object key = committing == null ? write : committing;
                    units.computeIfAbsent(key, k -> new Unit(committing)).add(write);
                }
                if (units.size() >= 2) {
                    rows.add(List.of(
                            request.requestId(),
                            String.valueOf(units.size()),
                            String.join(
                                    "; ",
                                    units.values().stream().map(Unit::describe).toList())));
                }
            }
            eligible += routeEligible;
            uncounted += unplaced;
            if (!rows.isEmpty()) {
                findings.add(finding(route.getKey(), rows, routeEligible, unplaced, snapshot));
            }
        }
        return new Evaluation(
                eligible,
                findings,
                uncounted == 0
                        ? null
                        : InsightText.counted(uncounted, "request") + " wrote with a transaction or statement that had"
                                + " no monotonic time, so " + (uncounted == 1 ? "its writes are" : "their writes are")
                                + " not placed or counted.");
    }

    static boolean committedWrite(RuntimeEvent event) {
        return event.payload() instanceof SqlPayload sql
                && sql.executed()
                && !sql.failed()
                && SafeMethodDml.isDml(sql.sql());
    }

    private Finding finding(
            String route, List<List<String>> rows, long eligible, long unplaced, InsightsSnapshot snapshot) {
        int most = rows.stream()
                .mapToInt(row -> Integer.parseInt(row.get(1)))
                .max()
                .orElse(2);
        String sentence = "`" + route + "` committed its writes in " + (most == 2 ? "2" : "up to " + most)
                + " independent units in " + rows.size() + " of "
                + InsightText.counted(eligible, InsightText.unit(route))
                + " that wrote: if these writes must succeed together, one may persist while another fails.";
        List<String> limitations = new ArrayList<>();
        limitations.add("A unit is a transaction that began its own physical transaction or a statement run outside"
                + " every recorded transaction, which autocommits; a manual JDBC transaction is not recognized.");
        if (unplaced > 0) {
            limitations.add(InsightText.counted(unplaced, "request") + " could not be placed, since a transaction or"
                    + " statement had no monotonic time.");
        }
        if (snapshot.stack() == InsightsStack.SPRING_WEBFLUX) {
            limitations.add("A reactive transaction belongs to a request only when its pipeline carried the request's"
                    + " context to the thread it began on.");
        }
        return new Finding(
                route,
                route,
                true,
                sentence,
                eligible,
                rows.size(),
                List.of(
                        "If these writes must succeed together, run them in one transaction.",
                        "If one is meant to commit alone, such as an audit or outbox write, check that a failure"
                                + " after it leaves consistent data."),
                rows.stream().limit(3).map(row -> row.get(0)).toList(),
                List.of("Request", "Units", "Boundaries"),
                rows,
                limitations);
    }

    private static final class Unit {

        private final TransactionWindows.Window transaction;
        private final List<String> statements = new ArrayList<>();

        Unit(TransactionWindows.Window transaction) {
            this.transaction = transaction;
        }

        void add(RuntimeEvent write) {
            statements.add(InsightText.statement(((SqlPayload) write.payload()).sql()));
        }

        String describe() {
            String name = transaction == null
                    ? AUTOCOMMIT
                    : (transaction.transaction().method() == null
                                    ? "transaction"
                                    : transaction.transaction().method())
                            + (transaction.transaction().nested() ? " (nested)" : "");
            return name + ": " + InsightText.counted(statements.size(), "write") + ", first `" + statements.get(0)
                    + "`";
        }
    }
}
