package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.sqltrace.SqlShapes;
import io.github.jdubois.bootui.engine.web.CorrelationTier;
import io.github.jdubois.bootui.spi.ThreadKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code event-loop-blocking} ({@code docs/PLAN-v2.md} §5.5): JDBC statements that started on a thread the adapter
 * identified as an event loop, per route and call site. One confirmed statement is enough; it is said to recur once three
 * requests show it. An asynchronous client completing on an event loop is normal and never counts.
 */
public final class EventLoopBlocking implements Observation {

    public static final String KIND = "event-loop-blocking";

    /** The requests from which it is said to recur. */
    static final int RECURRING_REQUESTS = 3;

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String title() {
        return "Blocking on event loops";
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
    public String notApplicable(InsightsSnapshot snapshot) {
        return snapshot.stack() == InsightsStack.SPRING_MVC
                ? "Spring MVC serves requests on worker threads, not on event loops."
                : null;
    }

    @Override
    public Evaluation evaluate(InsightsSnapshot snapshot) {
        List<Finding> findings = new ArrayList<>();
        long eligible = 0;
        for (Map.Entry<String, List<ProjectedRequest>> route :
                snapshot.byRoute().entrySet()) {
            List<ProjectedRequest> requests = route.getValue();
            eligible += requests.size();
            Map<String, Site> sites = new LinkedHashMap<>();
            for (ProjectedRequest request : requests) {
                Map<String, Integer> perRequest = new LinkedHashMap<>();
                for (RuntimeEvent event : request.children(JournalSource.SQL)) {
                    if (event.threadKind() == ThreadKind.EVENT_LOOP && event.payload() instanceof SqlPayload sql) {
                        String site = sql.callSite() != null ? sql.callSite() : SqlShapes.fingerprint(sql.sql());
                        sites.computeIfAbsent(site, s -> new Site()).add(request, event, sql);
                        perRequest.merge(site, 1, Integer::sum);
                    }
                }
                perRequest.keySet().forEach(site -> sites.get(site).requests++);
            }
            sites.forEach(
                    (site, found) -> findings.add(finding(route.getKey(), site, found, requests.size(), snapshot)));
        }
        return new Evaluation(eligible, findings);
    }

    private Finding finding(String route, String site, Site found, long eligible, InsightsSnapshot snapshot) {
        String sentence = "`" + route + "` started " + InsightText.counted(found.rows.size(), "JDBC statement")
                + " on an event-loop thread, in " + found.requests + " of "
                + InsightText.counted(eligible, InsightText.unit(route))
                + ", at `" + site + "`"
                + (found.requests >= RECURRING_REQUESTS ? "; it recurs." : ".");
        String move = snapshot.stack() == InsightsStack.QUARKUS
                ? "Run the endpoint on a worker or virtual thread with @Blocking or @RunOnVirtualThread, or use a"
                        + " reactive client."
                : "Move the blocking call off the event loop with subscribeOn(Schedulers.boundedElastic()), or use"
                        + " R2DBC.";
        return new Finding(
                route + ":" + InsightText.stableHash(site),
                route,
                true,
                sentence,
                eligible,
                found.requests,
                List.of(
                        move,
                        "While a statement runs, the event loop serves no other request: check its latency under"
                                + " load."),
                found.rows.stream().map(row -> row.get(0)).distinct().limit(3).toList(),
                List.of("Request", "Thread", "Statement", "Time (ms)"),
                found.rows,
                List.of("Counts timed JDBC statements; other blocking calls, such as file or synchronous HTTP I/O,"
                        + " are not recorded."));
    }

    private static final class Site {

        private final List<List<String>> rows = new ArrayList<>();
        private long requests;

        void add(ProjectedRequest request, RuntimeEvent event, SqlPayload sql) {
            rows.add(List.of(
                    request.requestId(),
                    event.thread() == null ? "" : event.thread(),
                    InsightText.quoted(SqlShapes.fingerprint(sql.sql())),
                    InsightText.millis(Math.max(0, event.durationNanos()))));
        }
    }
}
