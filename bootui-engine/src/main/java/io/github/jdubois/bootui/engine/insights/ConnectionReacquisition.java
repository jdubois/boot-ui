package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.engine.correlation.ExecutionIds;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.OrmPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.web.CorrelationTier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code connection-reacquisition}: one Hibernate session borrowing a JDBC connection two times or more, one after the
 * other, in at least three requests of a route. It counts Hibernate's own acquisition callbacks, so lazy loading or
 * queries run outside a transaction, which release the connection each time, show up where
 * {@code connections-per-request} reports only connections held at the same time. When the request's transactions and
 * statements can be placed, it names the application call sites of the statements that ran after its first
 * transaction ended.
 */
public final class ConnectionReacquisition implements Observation {

    public static final String KIND = "connection-reacquisition";
    static final int MIN_ACQUISITIONS = 2;
    static final int MIN_REQUESTS = 3;
    private static final int MAX_CALL_SITES = 3;

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String title() {
        return "Connections re-acquired in a session";
    }

    @Override
    public CorrelationTier minimumTier() {
        return CorrelationTier.REQUEST_ID;
    }

    @Override
    public Set<JournalSource> reads() {
        return Set.of(JournalSource.ORM);
    }

    @Override
    public Set<JournalSource> optionalReads() {
        return Set.of(JournalSource.SQL, JournalSource.TRANSACTION);
    }

    @Override
    public Evaluation evaluate(InsightsSnapshot snapshot) {
        boolean placeable = snapshot.stack() != InsightsStack.QUARKUS
                && snapshot.available(JournalSource.SQL)
                && snapshot.available(JournalSource.TRANSACTION)
                && snapshot.dropped(JournalSource.SQL) == 0
                && snapshot.dropped(JournalSource.TRANSACTION) == 0;
        List<Finding> findings = new ArrayList<>();
        long eligible = 0;
        for (Map.Entry<String, List<ProjectedRequest>> route :
                snapshot.byRoute().entrySet()) {
            List<Reacquired> reacquired = new ArrayList<>();
            long measured = 0;
            for (ProjectedRequest request : route.getValue()) {
                Reacquired worst = worstSession(request, placeable);
                if (worst == null) {
                    continue;
                }
                measured++;
                if (worst.acquisitions >= MIN_ACQUISITIONS) {
                    reacquired.add(worst);
                }
            }
            eligible += measured;
            if (!reacquired.isEmpty()) {
                findings.add(finding(snapshot, route.getKey(), reacquired, measured, placeable));
            }
        }
        return new Evaluation(eligible, findings);
    }

    private Finding finding(
            InsightsSnapshot snapshot, String route, List<Reacquired> reacquired, long measured, boolean placeable) {
        reacquired.sort(Comparator.comparingInt((Reacquired r) -> r.acquisitions)
                .reversed()
                .thenComparing(Comparator.comparingLong((Reacquired r) -> r.acquisitionNanos)
                        .reversed()));
        String unit = InsightText.unit(route);
        boolean sufficient = reacquired.size() >= MIN_REQUESTS;
        long withSites = reacquired.stream().filter(r -> !r.callSites.isEmpty()).count();
        long unplaced = reacquired.stream().filter(r -> !r.placed).count();
        boolean sites = withSites > 0;
        List<String> columns =
                new ArrayList<>(List.of("Request", "Acquisitions", "Acquisition time (ms)", "Session SQL"));
        if (sites) {
            columns.add("Call sites after the first transaction");
        }
        List<List<String>> rows = new ArrayList<>();
        for (Reacquired r : reacquired) {
            List<String> row = new ArrayList<>(List.of(
                    r.requestId,
                    String.valueOf(r.acquisitions),
                    InsightText.millis(r.acquisitionNanos),
                    String.valueOf(Math.max(0, r.statements))));
            if (sites) {
                row.add(!r.placed ? "not placed" : r.callSites.isEmpty() ? "-" : String.join(", ", r.callSites));
            }
            rows.add(row);
        }
        List<String> limitations = new ArrayList<>(List.of(
                "Counts Hibernate's connection acquisition callbacks, not the pool's borrow count or how long a"
                        + " connection was held.",
                "JTA and DELAYED_ACQUISITION_AND_RELEASE_AFTER_STATEMENT connection handling legitimately re-acquire"
                        + " a connection for each statement, and work Hibernate runs on a connection of its own, such"
                        + " as a table-based identifier generator, is counted as an acquisition too."));
        if (snapshot.stack() != InsightsStack.SPRING_WEBFLUX && snapshot.stack() != InsightsStack.QUARKUS) {
            limitations.add("See `lazy-sql-after-handler` when the extra acquisitions happen while the response is"
                    + " written.");
        }
        if (!placeable) {
            limitations.add("Call sites are omitted: they need recorded SQL and transactions, and this run's"
                    + " transaction or SQL evidence is missing or incomplete.");
        } else if (unplaced > 0) {
            limitations.add("Call sites are omitted for " + InsightText.counted(unplaced, unit) + " whose"
                    + " statements could not be placed against a transaction: none was recorded, or a transaction or"
                    + " statement had no monotonic time.");
        }
        if (sites) {
            limitations.add("Call sites are the statements the " + unit + " ran after its first transaction ended, up"
                    + " to " + MAX_CALL_SITES + " for each, whichever Hibernate session or JDBC client issued them.");
        }
        boolean openInView =
                snapshot.stack() != InsightsStack.SPRING_WEBFLUX && snapshot.stack() != InsightsStack.QUARKUS;
        int most = reacquired.get(0).acquisitions;
        return new Finding(
                route,
                route,
                sufficient,
                "`" + route + "` borrowed a JDBC connection up to " + most + " times in one Hibernate session, in "
                        + reacquired.size() + " of " + InsightText.counted(measured, unit) + "."
                        + (sufficient
                                ? ""
                                : " " + MIN_REQUESTS + " " + unit + "s with " + MIN_ACQUISITIONS
                                        + " acquisitions or more are needed to call it a pattern."),
                measured,
                reacquired.size(),
                List.of(
                        openInView
                                ? "Run the reads in one transaction (or fetch what the view needs in the handler);"
                                        + " with open-in-view the view's lazy loading re-borrows connections."
                                : "Run the reads in one transaction (or fetch what the response needs in the"
                                        + " handler); reads outside a transaction re-borrow a connection each time.",
                        "With `hibernate.connection.provider_disables_autocommit=true` and a pool that disables"
                                + " auto-commit, Hibernate delays acquisition until the first statement."),
                reacquired.stream().limit(3).map(r -> r.requestId).toList(),
                columns,
                rows,
                limitations);
    }

    /**
     * The session of the request that acquired the most connections, or {@code null} when no Hibernate session of it
     * was recorded.
     */
    private static Reacquired worstSession(ProjectedRequest request, boolean placeable) {
        OrmPayload worst = null;
        String thread = null;
        for (RuntimeEvent event : request.children(JournalSource.ORM)) {
            if (event.payload() instanceof OrmPayload orm
                    && (worst == null
                            || orm.connectionAcquisitions() > worst.connectionAcquisitions()
                            || orm.connectionAcquisitions() == worst.connectionAcquisitions()
                                    && orm.acquisitionNanos() > worst.acquisitionNanos())) {
                worst = orm;
                thread = event.thread();
            }
        }
        if (worst == null) {
            return null;
        }
        Reacquired result = new Reacquired(
                request.requestId(),
                worst.connectionAcquisitions(),
                Math.max(0, worst.acquisitionNanos()),
                worst.statements());
        if (placeable && worst.connectionAcquisitions() >= MIN_ACQUISITIONS) {
            callSitesAfterFirstTransaction(request, thread, result);
        }
        return result;
    }

    /**
     * Fills {@code result} with the call sites of the statements that completed after the first transaction of the
     * session's thread ended; leaves it unplaced when that thread has no transaction, or any transaction or executed
     * statement of the request cannot be placed.
     */
    private static void callSitesAfterFirstTransaction(ProjectedRequest request, String thread, Reacquired result) {
        TransactionWindows windows = new TransactionWindows(request);
        TransactionWindows.Window first = windows.first(thread);
        if (first == null || !windows.complete()) {
            return;
        }
        Set<String> sites = new LinkedHashSet<>();
        for (RuntimeEvent event : request.children(JournalSource.SQL)) {
            // A propagated task has its own session and transactions, and is work-after-response's to report.
            if (!(event.payload() instanceof SqlPayload sql)
                    || !sql.executed()
                    || ExecutionIds.isAsync(event.executionId())) {
                continue;
            }
            if (!windows.canPlace(event)) {
                return;
            }
            if (sql.completedNanos() > first.endNanos() && sql.callSite() != null) {
                sites.add(sql.callSite());
            }
        }
        result.placed = true;
        sites.stream().limit(MAX_CALL_SITES).forEach(result.callSites::add);
    }

    /** One request's most re-acquiring Hibernate session. */
    private static final class Reacquired {

        final String requestId;
        final int acquisitions;
        final long acquisitionNanos;
        final int statements;
        final List<String> callSites = new ArrayList<>();
        boolean placed;

        Reacquired(String requestId, int acquisitions, long acquisitionNanos, int statements) {
            this.requestId = requestId;
            this.acquisitions = acquisitions;
            this.acquisitionNanos = acquisitionNanos;
            this.statements = statements;
        }
    }
}
