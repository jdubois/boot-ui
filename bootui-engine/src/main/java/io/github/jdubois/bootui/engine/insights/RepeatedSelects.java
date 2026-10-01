package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.sqltrace.SqlStatementNormalizer;
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
 */
public final class RepeatedSelects implements Observation {

    public static final String KIND = "repeated-selects";
    static final int MIN_REPEATS = 5;
    static final int MIN_REQUESTS = 3;

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
    public Evaluation evaluate(InsightsSnapshot snapshot) {
        List<Finding> findings = new ArrayList<>();
        for (Map.Entry<String, List<ProjectedRequest>> route :
                snapshot.byRoute().entrySet()) {
            Map<String, List<Repeat>> byFingerprint = new LinkedHashMap<>();
            for (ProjectedRequest request : route.getValue()) {
                for (Repeat repeat : repeats(request)) {
                    byFingerprint
                            .computeIfAbsent(repeat.fingerprint(), f -> new ArrayList<>())
                            .add(repeat);
                }
            }
            byFingerprint.forEach((fingerprint, repeats) -> findings.add(finding(
                    route.getKey(), fingerprint, repeats, route.getValue().size())));
        }
        return new Evaluation(snapshot.requests().size(), findings);
    }

    /** The SELECTs one request repeated after a different statement, one per fingerprint. */
    static List<Repeat> repeats(ProjectedRequest request) {
        Map<String, Repeat> counts = new LinkedHashMap<>();
        String firstFingerprint = null;
        for (RuntimeEvent event : request.children(JournalSource.SQL)) {
            if (!(event.payload() instanceof SqlPayload sql)) {
                continue;
            }
            String fingerprint = SqlStatementNormalizer.fingerprintOf(sql.sql());
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
                            ? new Repeat(request, fingerprint, 1, Math.max(0, event.durationNanos()), sql.callSite())
                            : current.plus(Math.max(0, event.durationNanos())));
        }
        return counts.values().stream()
                .filter(repeat -> repeat.executions() >= MIN_REPEATS)
                .toList();
    }

    private Finding finding(String route, String fingerprint, List<Repeat> repeats, long eligible) {
        repeats.sort(Comparator.comparingInt(Repeat::executions).reversed());
        int most = repeats.get(0).executions();
        boolean sufficient = repeats.size() >= MIN_REQUESTS;
        String sentence = sufficient
                ? "`" + route + "` ran `" + InsightText.quoted(fingerprint) + "` " + MIN_REPEATS
                        + " or more times after"
                        + " another statement in " + repeats.size() + " of "
                        + InsightText.counted(eligible, "request") + ", up to " + most + " times in one."
                : "`" + route + "`: " + repeats.size() + " of " + MIN_REQUESTS + " requests needed to report `"
                        + InsightText.quoted(fingerprint) + "` repeated " + MIN_REPEATS + " or more times.";
        List<List<String>> rows = new ArrayList<>();
        for (Repeat repeat : repeats) {
            rows.add(List.of(
                    repeat.request().requestId(),
                    String.valueOf(repeat.executions()),
                    InsightText.millis(repeat.nanos()),
                    repeat.callSite() == null ? "" : repeat.callSite()));
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
                List.of("Request", "Executions", "Time (ms)", "Call site"),
                rows,
                List.of("Counts statements the request ran; a statement Hibernate batches counts once."));
    }

    private static boolean isSelect(String fingerprint) {
        return fingerprint != null && fingerprint.stripLeading().regionMatches(true, 0, "select", 0, 6);
    }

    record Repeat(ProjectedRequest request, String fingerprint, int executions, long nanos, String callSite) {

        Repeat plus(long moreNanos) {
            return new Repeat(request, fingerprint, executions + 1, nanos + moreNanos, callSite);
        }
    }
}
