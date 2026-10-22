package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.OrmPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.journal.TransactionPayload;
import io.github.jdubois.bootui.engine.web.CorrelationTier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code readonly-candidate-transaction}: a transactional method that always ran read-write, yet whose transactions
 * never changed an entity and never ran a write. Declared {@code readOnly = true}, Spring puts the Hibernate session in
 * manual flush mode and read-only default, so Hibernate skips dirty checking.
 *
 * <p>Hibernate's evidence belongs to a transaction by the work its thread did while the transaction was active
 * ({@link TransactionPayload#ormFlushes()}), never by the lifetime of a session: a session begins before the
 * transaction and ends after it, even without open-in-view, so no session interval is inside the transaction's. Each
 * execution is judged as one of four things:</p>
 * <ul>
 * <li><b>ignored</b> when Hibernate did no work in it, so it is no Hibernate transaction;</li>
 * <li><b>wrote</b> when Hibernate found an entity dirty in it, or any {@code INSERT}, {@code UPDATE}, {@code DELETE},
 * {@code MERGE}, or other write ran inside it, whoever issued it;</li>
 * <li><b>unjudged</b> when its evidence cannot prove it clean: it rolled back, Hibernate never dirty-checked an entity
 * in it, a transaction or statement of the request cannot be placed in time, or it contains another transaction;</li>
 * <li><b>clean</b> otherwise.</li>
 * </ul>
 *
 * <p>A method is reported only when every execution of it in the retained window was clean, since one that wrote, or
 * could not be judged, means the method may need read-write; its unjudged executions are named in the check's
 * reason.</p>
 */
public final class ReadOnlyCandidateTransaction implements Observation {

    public static final String KIND = "readonly-candidate-transaction";

    static final int MIN_EXECUTIONS = 3;

    /** The name Spring gives a transaction it cannot name, which several methods would share. */
    private static final String UNNAMED = "unknown";

    /** The first words of a statement that reads, or only controls the session or transaction. */
    private static final Set<String> READ_FIRST =
            Set.of("select", "with", "values", "show", "set", "savepoint", "release", "commit", "rollback");

    /**
     * A statement that names one of these, anywhere, may write: the DML verbs and {@code INTO}, which also catch a
     * common table expression that writes, a locking read, and a sequence that advances.
     */
    private static final Pattern WRITE_ANYWHERE = Pattern.compile("\\b(insert|update|delete|merge|into|lock|nextval|"
            + "setval|lastval|next\\s+value|for\\s+share|for\\s+key\\s+share|for\\s+no\\s+key\\s+update)\\b");

    /** What SQL Trace appends when it shortens a statement, or leaves out the statements of a batch. */
    private static final String TRUNCATED = "…";

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String title() {
        return "Read-write transactions that only read";
    }

    @Override
    public CorrelationTier minimumTier() {
        return CorrelationTier.REQUEST_ID;
    }

    @Override
    public Set<JournalSource> reads() {
        return Set.of(JournalSource.ORM, JournalSource.TRANSACTION, JournalSource.SQL);
    }

    @Override
    public String notApplicable(InsightsSnapshot snapshot) {
        return snapshot.stack() == InsightsStack.QUARKUS
                ? "Quarkus transactions are not recorded, so what happened inside them cannot be judged."
                : null;
    }

    @Override
    public Evaluation evaluate(InsightsSnapshot snapshot) {
        Map<String, Boundary> boundaries = new LinkedHashMap<>();
        long eligible = 0;
        for (List<ProjectedRequest> route : snapshot.byRoute().values()) {
            for (ProjectedRequest request : route) {
                eligible += judge(request, boundaries);
            }
        }
        List<Finding> findings = new ArrayList<>();
        long unjudged = 0;
        boolean examined = false;
        for (Map.Entry<String, Boundary> boundary : boundaries.entrySet()) {
            Boundary found = boundary.getValue();
            if (found.wrote > 0 || found.otherWrote > 0 || !found.usedHibernate()) {
                continue;
            }
            examined = true;
            if (found.unjudged > 0 || found.otherUnjudged > 0) {
                unjudged += found.unjudged + found.otherUnjudged;
            } else if (found.clean > 0) {
                findings.add(finding(boundary.getKey(), found, snapshot));
            }
        }
        return new Evaluation(
                eligible,
                findings,
                unjudged == 0
                        ? null
                        : InsightText.counted(unjudged, "execution") + " of read-write transactions could not be"
                                + " judged, since " + (unjudged == 1 ? "it" : "they") + " rolled back, never had"
                                + " Hibernate dirty-check an entity, or could not be placed against the request's"
                                + " statements, so " + (unjudged == 1 ? "its method is" : "their methods are")
                                + " not reported.",
                examined);
    }

    /**
     * Judges the top-level read-write transactions of {@code request}; 1 when it could judge one. An execution in which
     * Hibernate did nothing is no evidence that its method only reads, but a write in it, or one it cannot rule out,
     * still keeps the method from being reported.
     */
    private static long judge(ProjectedRequest request, Map<String, Boundary> boundaries) {
        TransactionWindows windows = null;
        List<RuntimeEvent> statements = null;
        long judged = 0;
        for (RuntimeEvent event : request.children(JournalSource.TRANSACTION)) {
            if (!(event.payload() instanceof TransactionPayload transaction)
                    || transaction.readOnly()
                    || transaction.method() == null
                    || UNNAMED.equals(transaction.method())) {
                continue;
            }
            if (windows == null) {
                windows = new TransactionWindows(request);
                statements = request.children(JournalSource.SQL);
            }
            Boundary boundary = boundaries.computeIfAbsent(transaction.method(), m -> new Boundary());
            Verdict verdict = verdict(event, transaction, windows, statements);
            if (!candidate(transaction) || !hibernateWorked(transaction)) {
                // A nested run, or one in which Hibernate did nothing, is no evidence that its method only reads, but
                // a write in it, or one it cannot rule out, still keeps the method from being reported.
                switch (verdict) {
                    case WROTE -> boundary.otherWrote++;
                    case UNJUDGED -> boundary.otherUnjudged++;
                    case CLEAN -> {}
                }
                continue;
            }
            switch (verdict) {
                case WROTE -> boundary.wrote++;
                case UNJUDGED -> boundary.unjudged++;
                case CLEAN -> {
                    boundary.clean++;
                    boundary.executions.add(new Execution(request, event, ormOf(request)));
                }
            }
            if (verdict != Verdict.UNJUDGED) {
                judged++;
            }
        }
        return judged == 0 ? 0 : 1;
    }

    /** A top-level physical transaction declared read-write, the only one a read-only declaration could change. */
    private static boolean candidate(TransactionPayload transaction) {
        return transaction.independent() && !transaction.nested() && !transaction.readOnly();
    }

    /** Whether Hibernate did any work in it; {@code false} also when unknown, which {@link #verdict} never clears. */
    private static boolean hibernateWorked(TransactionPayload transaction) {
        return transaction.ormStatements() > 0 || transaction.ormFlushes() > 0 || transaction.ormDirtyEntities() > 0;
    }

    private static Verdict verdict(
            RuntimeEvent event, TransactionPayload transaction, TransactionWindows windows, List<RuntimeEvent> sql) {
        TransactionWindows.Window window = windows.of(event);
        // Another transaction's work, its writes included, is mixed into this one's, so none of it is this one's.
        boolean mixed = window != null && containsAnotherTransaction(window, windows);
        if (!mixed && (transaction.ormDirtyEntities() > 0 || writes(window, sql))) {
            return Verdict.WROTE;
        }
        boolean unknownWork = transaction.ormStatements() < 0;
        // A savepoint's flush happens when the transaction that holds it commits, outside it.
        boolean neverFlushed = candidate(transaction) && hibernateWorked(transaction) && transaction.ormFlushes() <= 0;
        if (mixed
                || unknownWork
                || neverFlushed
                || transaction.rolledBack()
                || transaction.rollbackOnly()
                || transaction.failureClass() != null
                || window == null
                || !windows.complete()
                || placedStatements(window, sql) < Math.max(0, transaction.ormStatements())) {
            return Verdict.UNJUDGED;
        }
        for (RuntimeEvent statement : sql) {
            if (!windows.canPlace(statement) || !readable(statement)) {
                return Verdict.UNJUDGED;
            }
        }
        return Verdict.CLEAN;
    }

    /**
     * The executed statements recorded inside {@code window}. Hibernate ran at least as many, so fewer means that
     * statements of the transaction, possibly writes, were not recorded.
     */
    private static long placedStatements(TransactionWindows.Window window, List<RuntimeEvent> sql) {
        long placed = 0;
        for (RuntimeEvent statement : sql) {
            if (statement.payload() instanceof SqlPayload payload
                    && payload.executed()
                    && payload.completedNanos() >= 0
                    && window.contains(statement, payload.completedNanos())) {
                placed++;
            }
        }
        return placed;
    }

    /**
     * Whether another physical transaction of the request ran inside {@code window}, so its work is mixed in, or a
     * savepoint inside it rolled back or failed, so the transaction committed something other than what it asked.
     */
    private static boolean containsAnotherTransaction(TransactionWindows.Window window, TransactionWindows windows) {
        for (TransactionWindows.Window other : windows.windows()) {
            if (other == window || other.startNanos() < window.startNanos() || other.endNanos() > window.endNanos()) {
                continue;
            }
            TransactionPayload inside = other.transaction();
            if (inside.independent() || inside.rolledBack() || inside.rollbackOnly() || inside.failureClass() != null) {
                return true;
            }
        }
        return false;
    }

    /** Whether a statement placed inside {@code window}, whoever issued it, may write. */
    private static boolean writes(TransactionWindows.Window window, List<RuntimeEvent> sql) {
        if (window == null) {
            return false;
        }
        for (RuntimeEvent statement : sql) {
            if (statement.payload() instanceof SqlPayload payload
                    && payload.completedNanos() >= 0
                    && window.contains(statement, payload.completedNanos())
                    && mayWrite(payload.sql())) {
                return true;
            }
        }
        return false;
    }

    /** Whether the statement's whole text was kept: SQL Trace marks a shortened statement or a batch it cut with "…". */
    private static boolean readable(RuntimeEvent statement) {
        return statement.payload() instanceof SqlPayload payload
                && payload.sql() != null
                && !payload.sql().isBlank()
                && !payload.sql().contains(TRUNCATED);
    }

    /**
     * Whether {@code sql} may write. Only a single statement that starts, after comments and parentheses, as a query or
     * as session or transaction control is read, and even then not when it names {@code insert}, {@code update},
     * {@code delete}, {@code merge}, {@code into}, a lock, or a sequence anywhere, which catches {@code SELECT ... FOR
     * UPDATE} or {@code FOR SHARE}, {@code nextval}, and a common table expression that writes. Everything else, a
     * call, a JDBC escape, several statements, or a statement that is not text, may write; a function a query calls
     * cannot be seen. A wrongly suspected write only keeps a method from being reported.
     */
    static boolean mayWrite(String sql) {
        String head = leadingStatement(sql);
        if (head.isEmpty()) {
            return true;
        }
        int end = 0;
        while (end < head.length() && Character.isLetter(head.charAt(end))) {
            end++;
        }
        String body = head.stripTrailing();
        boolean several =
                body.endsWith(";") ? body.substring(0, body.length() - 1).contains(";") : body.contains(";");
        return !READ_FIRST.contains(head.substring(0, end))
                || several
                || WRITE_ANYWHERE.matcher(head).find();
    }

    /** {@code sql} lower-cased, without the whitespace, comments, and parentheses it starts with; empty when opaque. */
    private static String leadingStatement(String sql) {
        if (sql == null) {
            return "";
        }
        String text = sql.toLowerCase(Locale.ROOT);
        int at = 0;
        while (at < text.length()) {
            char c = text.charAt(at);
            if (Character.isWhitespace(c) || c == '(') {
                at++;
            } else if (text.startsWith("/*", at)) {
                int close = text.indexOf("*/", at + 2);
                if (close < 0) {
                    return "";
                }
                at = close + 2;
            } else if (text.startsWith("--", at)) {
                int newline = text.indexOf('\n', at);
                if (newline < 0) {
                    return "";
                }
                at = newline + 1;
            } else {
                break;
            }
        }
        return text.substring(at);
    }

    private static Orm ormOf(ProjectedRequest request) {
        int entities = -1;
        long flushNanos = 0;
        for (RuntimeEvent event : request.children(JournalSource.ORM)) {
            if (event.payload() instanceof OrmPayload orm) {
                entities = Math.max(entities, orm.entitiesInContext());
                flushNanos += orm.hibernateNanos();
            }
        }
        return new Orm(entities, flushNanos);
    }

    private Finding finding(String method, Boundary boundary, InsightsSnapshot snapshot) {
        boolean sufficient = boundary.clean >= MIN_EXECUTIONS;
        String sentence = "`" + method + "` ran " + InsightText.counted(boundary.clean, "read-write transaction")
                + " that changed no entity and issued no write.";
        if (!sufficient) {
            sentence += " Reported from " + MIN_EXECUTIONS + " transactions.";
        }
        List<List<String>> rows = new ArrayList<>();
        for (Execution execution : boundary.executions) {
            rows.add(List.of(
                    execution.request.requestId(),
                    execution.request.route(),
                    execution.orm.entities() < 0 ? "" : String.valueOf(execution.orm.entities()),
                    InsightText.millis(execution.orm.flushNanos()),
                    InsightText.millis(Math.max(0, execution.transaction.durationNanos()))));
        }
        List<String> limitations = new ArrayList<>();
        limitations.add(
                "Based on the retained window only; a method that writes for other inputs must stay read-write.");
        limitations.add(
                "Judged from the Hibernate work and the statements SQL Trace recorded on the transaction's thread while it"
                        + " was active; a write on another thread or connection, through a data source SQL Trace does not"
                        + " wrap, inside a function a query calls, or to a resource that is not a database, such as a"
                        + " message broker, is not seen.");
        if (snapshot.stack() == InsightsStack.SPRING_WEBFLUX) {
            limitations.add("A reactive transaction belongs to a request only when its pipeline carried the request's"
                    + " context to the thread it began on.");
        }
        return new Finding(
                InsightText.stableHash(method),
                method,
                sufficient,
                sentence,
                boundary.clean,
                boundary.clean,
                List.of(
                        "Declare `@Transactional(readOnly = true)` if the method never writes. Spring then puts the"
                                + " Hibernate session in manual flush mode and read-only default, so Hibernate skips"
                                + " dirty checking.",
                        "Keep it read-write if inputs can make it write."),
                boundary.executions.stream()
                        .map(execution -> execution.request.requestId())
                        .distinct()
                        .limit(3)
                        .toList(),
                List.of("Request", "Route", "Entities in the session", "Flush time (ms)", "Transaction (ms)"),
                rows,
                limitations);
    }

    private enum Verdict {
        CLEAN,
        WROTE,
        UNJUDGED
    }

    private record Orm(int entities, long flushNanos) {}

    private record Execution(ProjectedRequest request, RuntimeEvent transaction, Orm orm) {}

    /** What the retained window shows of one transactional method. */
    private static final class Boundary {

        private int clean;
        private int wrote;
        private int unjudged;
        /** Executions in which Hibernate did nothing, or the work is unknown: no evidence, but a veto. */
        private int otherWrote;

        private int otherUnjudged;
        private final List<Execution> executions = new ArrayList<>();

        boolean usedHibernate() {
            return clean + wrote + unjudged > 0;
        }
    }
}
