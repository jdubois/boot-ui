package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.engine.journal.AppEventPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.journal.TransactionPayload;
import io.github.jdubois.bootui.engine.sqltrace.SqlShapes;
import io.github.jdubois.bootui.engine.web.CorrelationTier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@code after-commit-writes} ({@code docs/PLAN-v2.md} §5.18, M4-8): INSERT, UPDATE, or DELETE statements run by a
 * listener of an {@code AFTER_COMMIT}, {@code AFTER_ROLLBACK}, or {@code AFTER_COMPLETION} phase, outside every
 * transaction that began within the listener. Spring runs such a listener while the finished transaction's resources
 * are still bound, so its writes still join that transaction, and no commit follows anymore. One request is enough.
 */
public final class AfterCommitWrites implements Observation {

    public static final String KIND = "after-commit-writes";

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String title() {
        return "Writes after commit";
    }

    @Override
    public CorrelationTier minimumTier() {
        return CorrelationTier.REQUEST_ID;
    }

    @Override
    public Set<JournalSource> reads() {
        return Set.of(JournalSource.APP_EVENT, JournalSource.SQL, JournalSource.TRANSACTION);
    }

    @Override
    public String notApplicable(InsightsSnapshot snapshot) {
        return snapshot.stack() == InsightsStack.QUARKUS
                ? "Quarkus records no transactions, so a write cannot be placed after one."
                : null;
    }

    @Override
    public Evaluation evaluate(InsightsSnapshot snapshot) {
        List<Finding> findings = new ArrayList<>();
        long eligible = 0;
        for (Map.Entry<String, List<ProjectedRequest>> route :
                snapshot.byRoute().entrySet()) {
            eligible += route.getValue().size();
            Map<String, Writes> byListener = new LinkedHashMap<>();
            for (ProjectedRequest request : route.getValue()) {
                for (RuntimeEvent event : request.children(JournalSource.APP_EVENT)) {
                    if (event.payload() instanceof AppEventPayload listener
                            && listener.afterCompletion()
                            && listener.startNanos() >= 0
                            && event.durationNanos() > 0) {
                        for (RuntimeEvent statement : writesDuring(request, event, listener)) {
                            byListener
                                    .computeIfAbsent(
                                            listener.listener() == null ? "listener" : listener.listener(),
                                            name -> new Writes(listener.phase()))
                                    .add(request, statement);
                        }
                    }
                }
            }
            byListener.forEach((listener, writes) -> findings.add(
                    finding(route.getKey(), listener, writes, route.getValue().size())));
        }
        return new Evaluation(eligible, findings);
    }

    /** The writes {@code listener} ran on its thread, outside every transaction that began within it. */
    private static List<RuntimeEvent> writesDuring(
            ProjectedRequest request, RuntimeEvent listenerEvent, AppEventPayload listener) {
        long from = listener.startNanos();
        long to = from + listenerEvent.durationNanos();
        List<RuntimeEvent> writes = new ArrayList<>();
        for (RuntimeEvent statement : request.children(JournalSource.SQL)) {
            if (!(statement.payload() instanceof SqlPayload sql)
                    || sql.completedNanos() < from
                    || sql.completedNanos() > to
                    || !SafeMethodDml.isDml(sql.sql())
                    || !sameThread(listenerEvent, statement)
                    || inTransactionBegunWithin(request, statement, sql.completedNanos(), from, to)) {
                continue;
            }
            writes.add(statement);
        }
        return writes;
    }

    private static boolean inTransactionBegunWithin(
            ProjectedRequest request, RuntimeEvent statement, long completed, long from, long to) {
        for (RuntimeEvent event : request.children(JournalSource.TRANSACTION)) {
            if (event.payload() instanceof TransactionPayload transaction
                    && transaction.startNanos() >= from
                    && transaction.startNanos() <= to
                    && completed >= transaction.startNanos()
                    && completed <= transaction.startNanos() + Math.max(0, event.durationNanos())
                    && sameThread(event, statement)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameThread(RuntimeEvent a, RuntimeEvent b) {
        return a.thread() == null || b.thread() == null || Objects.equals(a.thread(), b.thread());
    }

    private Finding finding(String route, String listener, Writes writes, long eligible) {
        return new Finding(
                route + ":" + InsightText.stableHash(listener),
                route,
                true,
                "`" + route + "`'s " + writes.phase + " listener `" + listener + "` ran "
                        + InsightText.counted(writes.rows.size(), "write") + " in " + writes.requests.size() + " of "
                        + InsightText.counted(eligible, InsightText.unit(route))
                        + " after its transaction completed, in no transaction of its own.",
                eligible,
                writes.requests.size(),
                List.of(
                        "Spring runs this listener while the finished transaction's resources are still bound: its"
                                + " writes join that transaction, and no commit follows. Check that they reach the"
                                + " database.",
                        "To commit them, run the write in its own transaction, such as"
                                + " `@Transactional(propagation = REQUIRES_NEW)` on the method it calls."),
                writes.requests.stream().limit(3).toList(),
                List.of("Request", "Statement", "Time (ms)"),
                writes.rows,
                List.of("Counts timed JDBC statements on the listener's thread while it ran."));
    }

    private static final class Writes {

        private final String phase;
        private final Set<String> requests = new LinkedHashSet<>();
        private final List<List<String>> rows = new ArrayList<>();

        Writes(String phase) {
            this.phase = phase;
        }

        void add(ProjectedRequest request, RuntimeEvent statement) {
            requests.add(request.requestId());
            rows.add(List.of(
                    request.requestId(),
                    InsightText.quoted(SqlShapes.fingerprint(((SqlPayload) statement.payload()).sql())),
                    InsightText.millis(Math.max(0, statement.durationNanos()))));
        }
    }
}
