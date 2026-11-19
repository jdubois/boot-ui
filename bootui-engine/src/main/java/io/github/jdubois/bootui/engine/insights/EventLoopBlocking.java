package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.JournalTextExposure;
import io.github.jdubois.bootui.engine.journal.RestClientPayload;
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
 * {@code event-loop-blocking} ({@code docs/PLAN-v2.md} §5.5): JDBC executions and native synchronous Spring HTTP calls
 * issued on a thread the adapter identified as an event loop, per route and call site. One confirmed call is enough;
 * it is said to recur once three requests show it. An asynchronous client completing on an event loop never counts.
 */
public final class EventLoopBlocking implements Observation {

    public static final String KIND = "event-loop-blocking";

    /** The requests from which it is said to recur. */
    static final int RECURRING_REQUESTS = 3;

    private static final JournalTextExposure SITE_IDENTITY = new JournalTextExposure(ValueExposure.METADATA_ONLY, true);

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
        return Set.of();
    }

    @Override
    public Set<JournalSource> optionalReads() {
        return Set.of(JournalSource.SQL, JournalSource.REST_CLIENT);
    }

    @Override
    public Set<JournalSource> optionalReads(InsightsSnapshot snapshot) {
        return snapshot.stack() == InsightsStack.QUARKUS ? Set.of(JournalSource.SQL) : optionalReads();
    }

    @Override
    public String notApplicable(InsightsSnapshot snapshot) {
        if (snapshot.stack() == InsightsStack.SPRING_MVC) {
            return "Spring MVC serves requests on worker threads, not on event loops.";
        }
        return optionalReads(snapshot).stream().noneMatch(snapshot::available)
                ? "No supported blocking-call source is recorded and visible."
                : null;
    }

    @Override
    public Evaluation evaluate(InsightsSnapshot snapshot) {
        List<Finding> findings = new ArrayList<>();
        long eligible = 0;
        long unverified = 0;
        long unclassified = 0;
        boolean sqlAvailable = snapshot.available(JournalSource.SQL);
        boolean httpAvailable =
                snapshot.stack() != InsightsStack.QUARKUS && snapshot.available(JournalSource.REST_CLIENT);
        for (Map.Entry<String, List<ProjectedRequest>> route :
                snapshot.byRoute().entrySet()) {
            Map<String, Site> sites = new LinkedHashMap<>();
            for (ProjectedRequest request : route.getValue()) {
                Map<String, Boolean> perRequest = new LinkedHashMap<>();
                for (RuntimeEvent event : request.children()) {
                    String site;
                    Site found;
                    String work;
                    if (sqlAvailable && event.payload() instanceof SqlPayload sql) {
                        if (!sql.executed()) {
                            if (event.threadKind() == ThreadKind.EVENT_LOOP) {
                                unverified++;
                            }
                            continue;
                        }
                        site = sql.callSite() != null ? sql.callSite() : SqlShapes.fingerprint(sql.sql());
                        found = sites.computeIfAbsent(
                                site,
                                s -> new Site(
                                        sql.callSite() != null ? sql.callSite() : InsightText.statement(sql.sql()),
                                        null));
                        work = InsightText.statement(sql.sql());
                    } else if (httpAvailable
                            && event.payload() instanceof RestClientPayload call
                            && ("RestClient".equals(call.clientType()) || "RestTemplate".equals(call.clientType()))) {
                        work = describe(call, snapshot);
                        String callSite =
                                call.frames() == null ? null : call.frames().callSite();
                        site = "http:" + call.clientType() + ":"
                                + (callSite == null
                                        ? call.method() + " " + call.authority() + SITE_IDENTITY.path(call.path())
                                        : callSite);
                        found = sites.computeIfAbsent(
                                site, s -> new Site(callSite == null ? work : callSite, call.clientType()));
                    } else {
                        continue;
                    }
                    if (event.threadKind() == null || event.threadKind() == ThreadKind.OTHER) {
                        unclassified++;
                        continue;
                    }
                    boolean onLoop = event.threadKind() == ThreadKind.EVENT_LOOP;
                    if (onLoop) {
                        found.add(request, event, work);
                    }
                    perRequest.merge(site, onLoop, Boolean::logicalOr);
                }
                if (!perRequest.isEmpty()) {
                    eligible++;
                }
                perRequest.forEach((site, onLoop) -> {
                    sites.get(site).eligible++;
                    if (onLoop) {
                        sites.get(site).requests++;
                    }
                });
            }
            sites.forEach((site, found) -> {
                if (!found.rows.isEmpty()) {
                    findings.add(finding(route.getKey(), site, found, snapshot));
                }
            });
        }
        List<String> uncounted = new ArrayList<>();
        if (unverified > 0) {
            uncounted.add(InsightText.counted(unverified, "SQL capture")
                    + " on event-loop threads did not establish JDBC execution and are not counted:"
                    + " preparation alone does not prove blocking.");
        }
        if (unclassified > 0) {
            uncounted.add(InsightText.counted(unclassified, "blocking-capable call")
                    + " had no classified issuing thread and could not be assessed.");
        }
        return new Evaluation(eligible, findings, uncounted.isEmpty() ? null : String.join(" ", uncounted));
    }

    private static String describe(RestClientPayload call, InsightsSnapshot snapshot) {
        return ((call.method() == null ? "" : call.method() + " ")
                        + (call.authority() == null ? "" : call.authority())
                        + (call.path() == null ? "" : snapshot.exposure().path(call.path())))
                .trim();
    }

    private Finding finding(String route, String site, Site found, InsightsSnapshot snapshot) {
        boolean jdbc = found.clientType == null;
        String sentence = "`" + route + "` started "
                + InsightText.counted(found.rows.size(), jdbc ? "JDBC statement" : found.clientType + " call")
                + " on an event-loop thread, in " + found.requests + " of "
                + InsightText.counted(found.eligible, InsightText.unit(route))
                + " that captured this " + (jdbc ? "JDBC site" : "client site")
                + ", at `" + found.label + "`"
                + (found.requests >= RECURRING_REQUESTS ? "; it recurs." : ".");
        String move = snapshot.stack() == InsightsStack.QUARKUS
                ? "Run the endpoint on a worker or virtual thread with @Blocking or @RunOnVirtualThread, or use a"
                        + " reactive client."
                : "Move the blocking call off the event loop with subscribeOn(Schedulers.boundedElastic()), or use"
                        + (jdbc ? " R2DBC." : " a non-blocking WebClient pipeline.");
        return new Finding(
                route + ":" + InsightText.stableHash(site),
                route,
                true,
                sentence,
                found.eligible,
                found.requests,
                List.of(
                        move,
                        "While a blocking call runs, the event loop serves no other request: check its latency under"
                                + " load."),
                found.rows.stream().map(row -> row.get(0)).distinct().limit(3).toList(),
                List.of("Request", "Thread", jdbc ? "Statement" : "HTTP call", "Time (ms)"),
                found.rows,
                List.of("Counts observed JDBC executions, including a measured zero duration; SQL preparation alone"
                        + " does not count. On Spring, native RestClient and RestTemplate interceptors record synchronous"
                        + " calls on their issuing thread. WebClient completions, thread names and duration alone never"
                        + " prove blocking. Other client types and file I/O are not assessed; Quarkus HTTP clients"
                        + " do not establish this synchronous Spring-client evidence."));
    }

    private static final class Site {

        private final List<List<String>> rows = new ArrayList<>();
        private final String label;
        private final String clientType;
        private long requests;
        private long eligible;

        /** @param label the call site, or the statement as a sentence quotes it when none was recorded */
        Site(String label, String clientType) {
            this.label = label;
            this.clientType = clientType;
        }

        void add(ProjectedRequest request, RuntimeEvent event, String work) {
            rows.add(List.of(
                    request.requestId(),
                    event.thread() == null ? "" : event.thread(),
                    work,
                    InsightText.millis(Math.max(0, event.durationNanos()))));
        }
    }
}
