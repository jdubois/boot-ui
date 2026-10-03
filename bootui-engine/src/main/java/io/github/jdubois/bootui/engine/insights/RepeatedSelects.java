package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.engine.correlation.RequestPhase;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.sqltrace.SqlShapes;
import io.github.jdubois.bootui.engine.web.CorrelationTier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code repeated-selects} ({@code docs/PLAN-v2.md} §5.5): one literal-free SELECT fingerprint executed at least
 * {@value #MIN_REPEATS} times in a request after a different statement ran, the shape of a suspected N+1. Reported per
 * route and fingerprint once {@value #MIN_REQUESTS} requests show it; fewer are reported as insufficient.
 *
 * <p>The default agent list omits a finding whose summed measured repeat time is under {@value #DEFAULT_LIST_FLOOR_NANOS}
 * nanoseconds. Unmeasured time, including Quarkus ORM preparations recorded as {@code 0}, is not treated as cheap.
 */
public final class RepeatedSelects implements Observation {

    public static final String KIND = "repeated-selects";
    static final int MIN_REPEATS = 5;
    static final int MIN_REQUESTS = 3;

    /** Summed measured repeat time below which the default agent list leaves the finding out. Exactly this stays. */
    static final long DEFAULT_LIST_FLOOR_NANOS = 50_000_000L;

    /**
     * Stable limitation the agent view matches, decided from the exact nanoseconds rather than the displayed total.
     */
    static final String UNDER_DEFAULT_FLOOR = "Repeated SELECTs under 50 ms of total time are left out of the default"
            + " agent list; ask for them with the query repeated-selects.";

    static final String UNMEASURED_REPEAT_TIME = "Total repeat time is unmeasured: every execution was recorded as 0.";

    static final String UNKNOWN_REPEAT_TIME = "Total repeat time is unknown.";

    static final String RESULT_SIZE_UNRECORDED =
            "Whether the repeat count tracks a parent result size is not" + " recorded: statements carry no row count.";

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String title() {
        return "Repeated SELECTs";
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
        boolean transactionsPlaceable =
                snapshot.available(JournalSource.TRANSACTION) && snapshot.dropped(JournalSource.TRANSACTION) == 0;
        List<Finding> findings = new ArrayList<>();
        for (Map.Entry<String, List<ProjectedRequest>> route :
                snapshot.byRoute().entrySet()) {
            Map<String, List<Repeat>> byFingerprint = new LinkedHashMap<>();
            for (ProjectedRequest request : route.getValue()) {
                for (Repeat repeat : repeats(request, transactionsPlaceable)) {
                    byFingerprint
                            .computeIfAbsent(repeat.fingerprint(), f -> new ArrayList<>())
                            .add(repeat);
                }
            }
            byFingerprint.forEach((fingerprint, repeats) -> findings.add(finding(
                    route.getKey(), fingerprint, repeats, route.getValue().size(), snapshot)));
        }
        return new Evaluation(snapshot.requests().size(), findings);
    }

    /** The SELECTs one request repeated after a different statement, one per fingerprint. */
    static List<Repeat> repeats(ProjectedRequest request, boolean transactionsPlaceable) {
        TransactionWindows windows = transactionsPlaceable ? new TransactionWindows(request) : null;
        Map<String, Repeat> counts = new LinkedHashMap<>();
        String firstFingerprint = null;
        for (RuntimeEvent event : request.children(JournalSource.SQL)) {
            if (!(event.payload() instanceof SqlPayload sql)) {
                continue;
            }
            String fingerprint = SqlShapes.fingerprint(sql.sql());
            if (firstFingerprint == null) {
                firstFingerprint = fingerprint;
            }
            if (!isSelect(fingerprint) || fingerprint.equals(firstFingerprint)) {
                // A repeat counts only after a different statement ran first, as a parent query would.
                continue;
            }
            Repeat current = counts.get(fingerprint);
            counts.put(
                    fingerprint,
                    current == null
                            ? Repeat.first(request, fingerprint, event, sql, windows)
                            : current.plus(event, sql, windows));
        }
        return counts.values().stream()
                .filter(repeat -> repeat.executions() >= MIN_REPEATS)
                .toList();
    }

    private Finding finding(
            String route, String fingerprint, List<Repeat> repeats, long eligible, InsightsSnapshot snapshot) {
        repeats.sort(Comparator.comparingInt(Repeat::executions).reversed());
        int most = repeats.get(0).executions();
        String statement = repeats.get(0).statement();
        boolean sufficient = repeats.size() >= MIN_REQUESTS;
        String sentence = sufficient
                ? "`" + route + "` ran `" + statement + "` " + MIN_REPEATS
                        + " or more times after"
                        + " another statement in " + repeats.size() + " of "
                        + InsightText.counted(eligible, InsightText.unit(route)) + ", up to " + most + " times in one."
                : "`" + route + "`: " + repeats.size() + " of " + MIN_REQUESTS + " " + InsightText.unit(route)
                        + "s needed to report `" + statement + "` repeated " + MIN_REPEATS
                        + " or more times.";
        List<List<String>> rows = new ArrayList<>();
        long totalNanos = 0;
        boolean unknownTime = false;
        boolean measured = false;
        for (Repeat repeat : repeats) {
            rows.add(List.of(
                    repeat.request().requestId(),
                    String.valueOf(repeat.executions()),
                    repeat.unknownTime() ? "unknown" : InsightText.millis(repeat.nanos()),
                    repeat.callSite() == null ? "" : repeat.callSite(),
                    repeat.phase(),
                    repeat.inTransaction()));
            unknownTime |= repeat.unknownTime();
            measured |= repeat.measured();
            totalNanos += repeat.nanos();
        }
        List<String> limitations = new ArrayList<>();
        limitations.add("Counts statements the request ran; a statement Hibernate batches counts once.");
        limitations.add(RESULT_SIZE_UNRECORDED);
        if (unknownTime) {
            limitations.add(UNKNOWN_REPEAT_TIME);
        } else if (!measured) {
            // Quarkus ORM capture records preparations as 0: StatementInspector has no execution-end hook, and
            // SqlTraceRecorder clamps a negative duration to 0, so the journal cannot tell that from a timed 0.
            limitations.add(UNMEASURED_REPEAT_TIME);
        } else {
            limitations.add(
                    "Total repeat time is " + InsightText.millis(totalNanos) + " ms, summed across affected requests.");
            if (totalNanos < DEFAULT_LIST_FLOOR_NANOS) {
                limitations.add(UNDER_DEFAULT_FLOOR);
            }
        }
        if (snapshot.available(JournalSource.TRANSACTION) && snapshot.dropped(JournalSource.TRANSACTION) > 0) {
            limitations.add("Transaction events were dropped, so whether repeats ran inside one is unknown.");
        }
        return new Finding(
                route + ":" + InsightText.stableHash(fingerprint),
                route,
                sufficient,
                sentence,
                eligible,
                repeats.size(),
                List.of(
                        "Open an exemplar request in Live Activity and check whether each repeat loads the children"
                                + " of one row the first statement returned.",
                        "If it does, load them with one statement, such as a join or an IN list, in SQL Trace's"
                                + " call site."),
                repeats.stream()
                        .limit(3)
                        .map(repeat -> repeat.request().requestId())
                        .toList(),
                List.of("Request", "Executions", "Time (ms)", "Call site", "Phase", "In transaction"),
                rows,
                limitations);
    }

    private static boolean isSelect(String fingerprint) {
        return fingerprint != null && fingerprint.stripLeading().regionMatches(true, 0, "select", 0, 6);
    }

    private static String phaseLabel(RequestPhase phase) {
        if (phase == null) {
            return "unknown";
        }
        return switch (phase) {
            case HANDLER -> "handler";
            case RESPONSE -> "response write";
            case FILTERS -> "filters";
        };
    }

    /**
     * {@code yes} inside a recorded transaction, {@code no} when transactions were recorded and the statement is
     * placeable outside every window, otherwise {@code unknown}. Absence of the transaction source, a dropped
     * transaction event, or a statement that cannot be placed is never reported as {@code no}.
     */
    private static String transactionLabel(RuntimeEvent event, TransactionWindows windows) {
        if (windows == null || !windows.canPlace(event)) {
            return "unknown";
        }
        return windows.innermost(event) == null ? "no" : "yes";
    }

    private static String merge(String current, String next) {
        return current.equals(next) ? current : "mixed";
    }

    /**
     * @param statement the statement as a sentence quotes it, which {@code fingerprint} only groups
     */
    record Repeat(
            ProjectedRequest request,
            String fingerprint,
            String statement,
            int executions,
            long nanos,
            boolean unknownTime,
            boolean measured,
            String callSite,
            String phase,
            String inTransaction) {

        static Repeat first(
                ProjectedRequest request,
                String fingerprint,
                RuntimeEvent event,
                SqlPayload sql,
                TransactionWindows windows) {
            long duration = event.durationNanos();
            boolean unknown = duration < 0;
            return new Repeat(
                    request,
                    fingerprint,
                    InsightText.statement(sql.sql()),
                    1,
                    unknown ? 0 : duration,
                    unknown,
                    duration > 0,
                    sql.callSite(),
                    phaseLabel(sql.phase()),
                    transactionLabel(event, windows));
        }

        Repeat plus(RuntimeEvent event, SqlPayload sql, TransactionWindows windows) {
            long duration = event.durationNanos();
            boolean unknown = unknownTime || duration < 0;
            return new Repeat(
                    request,
                    fingerprint,
                    statement,
                    executions + 1,
                    unknown ? nanos : nanos + Math.max(0, duration),
                    unknown,
                    measured || duration > 0,
                    callSite,
                    merge(phase, phaseLabel(sql.phase())),
                    merge(inTransaction, transactionLabel(event, windows)));
        }
    }
}
