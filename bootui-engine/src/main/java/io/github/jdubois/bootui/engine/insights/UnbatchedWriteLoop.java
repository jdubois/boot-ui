package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.sqltrace.SqlShapes;
import io.github.jdubois.bootui.engine.web.CorrelationTier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code unbatched-write-loop}: requests that ran one write statement shape ten times or more, each as its own execution
 * with a JDBC batch size of one, so each paid its own round trip. A write whose batch size is unknown, such as one
 * Hibernate on Quarkus only prepared, is never counted as unbatched. Worded as what was counted, since a loop that
 * must not be batched is rare but possible.
 */
public final class UnbatchedWriteLoop implements Observation {

    public static final String KIND = "unbatched-write-loop";

    /** The executions of one shape in one request that make a loop. */
    static final int MIN_STATEMENTS = 10;

    /** The requests of a route that must show a loop before it is reported without reservation. */
    static final int MIN_REQUESTS = 2;

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String title() {
        return "Unbatched write loops";
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
    public Set<JournalSource> optionalReads() {
        return Set.of(JournalSource.TRANSACTION);
    }

    @Override
    public Evaluation evaluate(InsightsSnapshot snapshot) {
        boolean transactionsRecorded = snapshot.available(JournalSource.TRANSACTION);
        boolean transactionsDropped = transactionsRecorded && snapshot.dropped(JournalSource.TRANSACTION) > 0;
        List<Finding> findings = new ArrayList<>();
        long eligible = 0;
        long prepared = 0;
        long unknown = 0;
        for (Map.Entry<String, List<ProjectedRequest>> route :
                snapshot.byRoute().entrySet()) {
            long routeEligible = 0;
            Map<String, List<Loop>> byShape = new LinkedHashMap<>();
            for (ProjectedRequest request : route.getValue()) {
                Tally tally = tally(request, transactionsRecorded, transactionsDropped);
                prepared += tally.prepared();
                unknown += tally.unknown();
                if (tally.known() == 0) {
                    continue;
                }
                routeEligible++;
                tally.loops()
                        .forEach(loop ->
                                byShape.computeIfAbsent(loop.fingerprint(), f -> new ArrayList<>())
                                        .add(loop));
            }
            eligible += routeEligible;
            long routeRequests = routeEligible;
            byShape.forEach((fingerprint, loops) ->
                    findings.add(finding(route.getKey(), fingerprint, loops, routeRequests)));
        }
        return new Evaluation(eligible, findings, uncounted(prepared, unknown));
    }

    private static String uncounted(long prepared, long unknown) {
        List<String> parts = new ArrayList<>();
        if (prepared > 0) {
            parts.add(InsightText.counted(prepared, "write statement")
                    + (prepared == 1 ? " was" : " were")
                    + " only prepared, not executed, which is all Hibernate on Quarkus records, so whether "
                    + (prepared == 1 ? "it was" : "they were") + " batched is unknown and "
                    + (prepared == 1 ? "it is" : "they are") + " not counted.");
        }
        if (unknown > 0) {
            parts.add(InsightText.counted(unknown, "executed write")
                    + " had no recorded batch size, so " + (unknown == 1 ? "it is" : "they are")
                    + " not counted as unbatched.");
        }
        return parts.isEmpty() ? null : String.join(" ", parts);
    }

    /** What one request ran: the write loops it shows and the writes it could not judge. */
    private static Tally tally(ProjectedRequest request, boolean transactionsRecorded, boolean transactionsDropped) {
        long prepared = 0;
        long unknown = 0;
        long known = 0;
        List<RuntimeEvent> singles = new ArrayList<>();
        for (RuntimeEvent event : request.children(JournalSource.SQL)) {
            if (!(event.payload() instanceof SqlPayload sql) || sql.failed() || SqlShapes.writes(sql.sql()).isEmpty()) {
                continue;
            }
            if (sql.provenance() == SqlPayload.Provenance.PREPARATION) {
                prepared++;
            } else if (!sql.executed() || sql.batchSize() == 0) {
                unknown++;
            } else {
                known++;
                if (sql.batchSize() == 1) {
                    singles.add(event);
                }
            }
        }
        if (singles.size() < MIN_STATEMENTS) {
            return new Tally(prepared, unknown, known, List.of());
        }
        TransactionWindows windows = transactionsRecorded && !transactionsDropped ? new TransactionWindows(request) : null;
        Grouping grouping = grouping(windows, singles, transactionsRecorded, transactionsDropped);
        Map<Key, Loop> loops = new LinkedHashMap<>();
        int index = 0;
        for (RuntimeEvent event : singles) {
            SqlPayload sql = (SqlPayload) event.payload();
            String fingerprint = SqlShapes.fingerprint(sql.sql());
            // Outside a transaction, a statement autocommits on its own, so it shares a unit with no other.
            int unit = grouping == Grouping.TRANSACTION ? unitOf(windows, event, index) : 0;
            index++;
            loops.computeIfAbsent(new Key(fingerprint, unit), key -> new Loop(request, fingerprint, grouping))
                    .add(event, sql);
        }
        Map<String, Loop> largest = new LinkedHashMap<>();
        for (Loop loop : loops.values()) {
            largest.merge(loop.fingerprint(), loop, (a, b) -> b.executions() > a.executions() ? b : a);
        }
        return new Tally(
                prepared,
                unknown,
                known,
                largest.values().stream()
                        .filter(loop -> loop.executions() >= MIN_STATEMENTS)
                        .toList());
    }

    private static Grouping grouping(
            TransactionWindows windows,
            List<RuntimeEvent> singles,
            boolean transactionsRecorded,
            boolean transactionsDropped) {
        if (!transactionsRecorded) {
            return Grouping.NOT_RECORDED;
        }
        if (transactionsDropped) {
            return Grouping.DROPPED;
        }
        if (!windows.complete()) {
            return Grouping.UNPLACED;
        }
        if (windows.windows().isEmpty()) {
            return Grouping.OUTSIDE;
        }
        return singles.stream().allMatch(windows::canPlace) ? Grouping.TRANSACTION : Grouping.UNPLACED;
    }

    /** The unit a statement belongs to: its physical transaction, or a unit of its own when it ran outside every one. */
    private static int unitOf(TransactionWindows windows, RuntimeEvent event, int index) {
        TransactionWindows.Window committing = windows.committing(event);
        if (committing == null) {
            return -(index + 1);
        }
        List<TransactionWindows.Window> all = windows.windows();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i) == committing) {
                return i + 1;
            }
        }
        return -(index + 1);
    }

    private Finding finding(String route, String fingerprint, List<Loop> loops, long eligible) {
        List<Loop> ordered = new ArrayList<>(loops);
        ordered.sort(Comparator.comparingInt(Loop::executions).reversed());
        int most = ordered.get(0).executions();
        String statement = ordered.get(0).statement();
        boolean sufficient = ordered.size() >= MIN_REQUESTS;
        String unit = InsightText.unit(route);
        String sentence = sufficient
                ? "`" + route + "` ran `" + statement + "` " + MIN_STATEMENTS
                        + " or more times, each without a JDBC batch, in " + ordered.size() + " of "
                        + InsightText.counted(eligible, unit) + ", up to " + most + " times in one."
                : "`" + route + "`: " + ordered.size() + " of " + MIN_REQUESTS + " " + unit + "s needed to report `"
                        + statement + "` run " + MIN_STATEMENTS + " or more times without a JDBC batch.";
        List<List<String>> rows = new ArrayList<>();
        long totalNanos = 0;
        boolean unknownTime = false;
        boolean measured = false;
        Set<Grouping> groupings = new LinkedHashSet<>();
        for (Loop loop : ordered) {
            rows.add(List.of(
                    loop.request().requestId(),
                    statement,
                    String.valueOf(loop.executions()),
                    loop.unknownTime() ? "unknown" : InsightText.millis(loop.nanos()),
                    loop.callSite() == null ? "" : loop.callSite()));
            unknownTime |= loop.unknownTime();
            measured |= loop.nanos() > 0;
            totalNanos += loop.nanos();
            groupings.add(loop.grouping());
        }
        List<String> limitations = new ArrayList<>();
        limitations.add("Counts executions the database ran with a batch size of one: a statement sent in a JDBC batch is"
                + " not counted, and neither is one whose batch size is unknown.");
        for (Grouping grouping : groupings) {
            limitations.add(grouping.limitation);
        }
        if (unknownTime) {
            limitations.add("Total time is unknown.");
        } else if (!measured) {
            limitations.add("Total time is unmeasured: every execution was recorded as 0.");
        } else {
            limitations.add("Total time is " + InsightText.millis(totalNanos) + " ms, summed across affected "
                    + unit + "s.");
        }
        return new Finding(
                route + ":" + InsightText.stableHash(fingerprint),
                route,
                sufficient,
                sentence,
                eligible,
                ordered.size(),
                List.of(
                        "If the writes go through Hibernate, check `hibernate.jdbc.batch_size`, ordered inserts and"
                                + " updates (`hibernate.order_inserts`, `hibernate.order_updates`; Hibernate checks"
                                + " `HIB-CONFIG-004` and `HIB-CONFIG-005`), and the id generator: an `IDENTITY` id"
                                + " disables insert batching (`HIB-ID-006`).",
                        "With PostgreSQL or MySQL, check whether the driver rewrites a batch into fewer statements,"
                                + " such as `reWriteBatchedInserts` or `rewriteBatchedStatements`.",
                        "If the writes go through JDBC, send them as one batch, such as `JdbcTemplate.batchUpdate`."),
                ordered.stream().limit(3).map(loop -> loop.request().requestId()).toList(),
                List.of("Request", "Statement", "Executions", "Time (ms)", "Call site"),
                rows,
                limitations);
    }

    /** What a request's writes show, and what it could not judge. */
    private record Tally(long prepared, long unknown, long known, List<Loop> loops) {}

    private record Key(String fingerprint, int unit) {}

    /** How a loop's executions were grouped, and what that leaves unsaid. */
    private enum Grouping {
        TRANSACTION("Executions are counted within one transaction; a statement outside every transaction autocommits on its"
                + " own and is not grouped."),
        NOT_RECORDED("Transactions are not recorded, so executions are counted per request, not per transaction."),
        DROPPED("Transaction events were dropped, so executions are counted per request, not per transaction."),
        OUTSIDE("Executions ran outside any recorded transaction, so they are counted per request, not per"
                + " transaction."),
        UNPLACED("A transaction or statement had no monotonic time, so executions are counted per request, not per"
                + " transaction.");

        private final String limitation;

        Grouping(String limitation) {
            this.limitation = limitation;
        }
    }

    /** The executions of one shape in one request, within one unit. */
    private static final class Loop {

        private final ProjectedRequest request;
        private final String fingerprint;
        private final Grouping grouping;
        private final Map<String, Integer> sites = new LinkedHashMap<>();
        private String statement;
        private int executions;
        private long nanos;
        private boolean unknownTime;

        Loop(ProjectedRequest request, String fingerprint, Grouping grouping) {
            this.request = request;
            this.fingerprint = fingerprint;
            this.grouping = grouping;
        }

        void add(RuntimeEvent event, SqlPayload sql) {
            if (statement == null) {
                statement = InsightText.statement(sql.sql());
            }
            executions++;
            long duration = event.durationNanos();
            if (duration < 0) {
                unknownTime = true;
            } else {
                nanos += duration;
            }
            String site = sql.callSite();
            if (site != null) {
                sites.merge(site, 1, Integer::sum);
            }
        }

        ProjectedRequest request() {
            return request;
        }

        String fingerprint() {
            return fingerprint;
        }

        Grouping grouping() {
            return grouping;
        }

        String statement() {
            return statement;
        }

        int executions() {
            return executions;
        }

        long nanos() {
            return nanos;
        }

        boolean unknownTime() {
            return unknownTime;
        }

        /** The call site that issued most of its executions, or {@code null} when none was recorded. */
        String callSite() {
            return sites.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(null);
        }
    }
}
