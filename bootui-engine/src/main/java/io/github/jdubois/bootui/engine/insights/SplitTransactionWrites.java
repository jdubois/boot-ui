package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.sqltrace.SqlShapes;
import io.github.jdubois.bootui.engine.web.CorrelationTier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code split-transaction-writes} ({@code docs/PLAN-v2.md} §5.5): executed writes across named data sources or
 * recorded transaction boundaries. Temporal placement does not establish resource ownership or atomicity.
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
        return "Write atomicity to verify";
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
                boolean placed = writes.stream().allMatch(windows::canPlace);
                boolean multipleNamedSources = writes.stream()
                                .map(write -> dataSource((SqlPayload) write.payload()))
                                .filter(name -> name != null)
                                .distinct()
                                .count()
                        >= 2;
                Map<GroupKey, Unit> units = new LinkedHashMap<>();
                Set<Object> splitCandidates = new LinkedHashSet<>();
                List<TransactionWindows.Window> boundaries = windows.windows();
                boolean boundaryPlacementKnown = placed;
                for (RuntimeEvent write : writes) {
                    TransactionWindows.Window boundary = placed ? boundary(windows, write) : null;
                    if (boundary != null && !boundary.transaction().independent()) {
                        boundaryPlacementKnown = false;
                    }
                    boolean rolledBack =
                            boundary != null && boundary.transaction().rolledBack();
                    if (rolledBack && !multipleNamedSources) {
                        continue;
                    }
                    Object position = boundary != null ? boundary : placed ? write : null;
                    if (placed
                            && !rolledBack
                            && (boundary == null || boundary.transaction().independent())) {
                        splitCandidates.add(position);
                    }
                    GroupKey key = new GroupKey(dataSource((SqlPayload) write.payload()), position);
                    units.computeIfAbsent(
                                    key,
                                    k -> new Unit(
                                            k.dataSource(),
                                            boundary,
                                            boundary == null ? 0 : boundaries.indexOf(boundary) + 1,
                                            placed))
                            .add(write);
                }
                boolean split = multipleNamedSources || splitCandidates.size() >= 2;
                if (!boundaryPlacementKnown && !split) {
                    unplaced++;
                    continue;
                }
                routeEligible++;
                if (split) {
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
                        : InsightText.counted(uncounted, "request")
                                + " had writes whose transaction timing or boundary ownership could not be established"
                                + " and are not counted. Captured multi-data-source and known split-boundary facts"
                                + " remain included.");
    }

    private static String dataSource(SqlPayload sql) {
        return sql.dataSource() == null || sql.dataSource().isBlank() ? null : sql.dataSource();
    }

    private static TransactionWindows.Window boundary(TransactionWindows windows, RuntimeEvent write) {
        TransactionWindows.Window inner = windows.innermost(write);
        if (inner != null && !inner.transaction().boundaryKnown()) {
            return inner;
        }
        TransactionWindows.Window committing = windows.committing(write);
        return committing == null ? inner : committing;
    }

    static boolean committedWrite(RuntimeEvent event) {
        return event.payload() instanceof SqlPayload sql
                && sql.executed()
                && !sql.failed()
                && !SqlShapes.writes(sql.sql()).isEmpty();
    }

    private Finding finding(
            String route, List<List<String>> rows, long eligible, long unplaced, InsightsSnapshot snapshot) {
        int most = rows.stream()
                .mapToInt(row -> Integer.parseInt(row.get(1)))
                .max()
                .orElse(2);
        String sentence = "`" + route + "` recorded writes in " + (most == 2 ? "2" : "up to " + most)
                + " groups across data sources or recorded transaction boundaries in " + rows.size() + " of "
                + InsightText.counted(eligible, InsightText.unit(route))
                + " that wrote; their required atomicity needs verification.";
        List<String> limitations = new ArrayList<>();
        limitations.add("Groups use captured data-source names and recorded boundaries matched by monotonic time and"
                + " thread name, not proven physical-resource or transaction-manager ownership. Shared resources"
                + " and JTA/XA coordination are not established or excluded.");
        limitations.add("A write outside recorded transactions is an autocommit candidate, not proof; manual JDBC"
                + " transactions and uncaptured boundaries are not recognized. A recorded commit or rollback does"
                + " not establish the outcome of every resource's writes.");
        if (unplaced > 0) {
            limitations.add(InsightText.counted(unplaced, "request")
                    + " could not be judged from transaction timing or boundary ownership and "
                    + (unplaced == 1 ? "is" : "are") + " not counted.");
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
                        "If these writes must succeed together, verify which resources and transaction managers own"
                                + " them and whether one transaction or coordinated JTA/XA work covers them.",
                        "If one is meant to commit alone, such as an audit or outbox write, check that a failure"
                                + " after it leaves consistent data."),
                rows.stream().limit(3).map(row -> row.get(0)).toList(),
                List.of("Request", "Recorded groups", "Boundaries"),
                rows,
                limitations);
    }

    private record GroupKey(String dataSource, Object boundary) {}

    private static final class Unit {

        private final String dataSource;
        private final TransactionWindows.Window transaction;
        private final int boundaryNumber;
        private final boolean placed;
        private final List<String> statements = new ArrayList<>();

        Unit(String dataSource, TransactionWindows.Window transaction, int boundaryNumber, boolean placed) {
            this.dataSource = dataSource;
            this.transaction = transaction;
            this.boundaryNumber = boundaryNumber;
            this.placed = placed;
        }

        void add(RuntimeEvent write) {
            statements.add(InsightText.statement(((SqlPayload) write.payload()).sql()));
        }

        String describe() {
            String position;
            if (!placed) {
                position = "transaction placement unavailable";
            } else if (transaction == null) {
                position = "outside recorded transactions (" + AUTOCOMMIT + " candidate)";
            } else {
                position = "recorded "
                        + (transaction.transaction().independent()
                                ? "physical boundary "
                                : transaction.transaction().savepoint() ? "savepoint " : "boundary ")
                        + boundaryNumber
                        + (transaction.transaction().rolledBack() ? " (rollback recorded)" : " (no rollback reported)");
            }
            return (dataSource == null ? "unknown data source" : "`" + dataSource + "`") + ", " + position + ": "
                    + InsightText.counted(statements.size(), "write") + ", first `" + statements.get(0) + "`";
        }
    }
}
