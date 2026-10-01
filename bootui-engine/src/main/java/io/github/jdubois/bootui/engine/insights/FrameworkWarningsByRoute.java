package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.LogPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.web.CorrelationTier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code framework-warnings-by-route} ({@code docs/PLAN-v2.md} §5.5): {@code WARN} and {@code ERROR} events that
 * framework loggers wrote while serving a request, grouped by logger, message template, and route. Well-known codes
 * carry a specific check.
 */
public final class FrameworkWarningsByRoute implements Observation {

    public static final String KIND = "framework-warnings-by-route";

    /** The logger prefixes counted as framework loggers. */
    static final List<String> FRAMEWORK_LOGGERS = List.of(
            "org.hibernate.",
            "org.springframework.",
            "com.zaxxer.hikari.",
            "io.quarkus.",
            "io.vertx.",
            "io.smallrye.",
            "io.agroal.",
            "org.jboss.",
            "reactor.",
            "io.netty.",
            "com.fasterxml.jackson.",
            "tools.jackson.",
            "org.apache.catalina.",
            "org.apache.coyote.",
            "org.apache.tomcat.");

    /** Known messages and their check, matched by a fragment of the template. */
    static final Map<String, String> KNOWN = known();

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String title() {
        return "Framework warnings by route";
    }

    @Override
    public CorrelationTier minimumTier() {
        return CorrelationTier.REQUEST_ID;
    }

    @Override
    public Set<JournalSource> reads() {
        return Set.of(JournalSource.LOG);
    }

    @Override
    public Evaluation evaluate(InsightsSnapshot snapshot) {
        List<Finding> findings = new ArrayList<>();
        long eligible = 0;
        for (Map.Entry<String, List<ProjectedRequest>> route :
                snapshot.byRoute().entrySet()) {
            List<ProjectedRequest> requests = route.getValue();
            eligible += requests.size();
            Map<String, Group> groups = new LinkedHashMap<>();
            for (ProjectedRequest request : requests) {
                Map<String, Integer> perRequest = new LinkedHashMap<>();
                for (RuntimeEvent event : request.children(JournalSource.LOG)) {
                    if (event.payload() instanceof LogPayload log && isFramework(log.logger())) {
                        String key = log.logger() + "\n" + log.level() + "\n" + log.template();
                        groups.computeIfAbsent(key, k -> new Group(log));
                        perRequest.merge(key, 1, Integer::sum);
                    }
                }
                perRequest.forEach((key, count) -> groups.get(key).add(request, count));
            }
            groups.forEach((key, group) -> findings.add(finding(route.getKey(), key, group, requests.size())));
        }
        return new Evaluation(eligible, findings);
    }

    static boolean isFramework(String logger) {
        if (logger == null) {
            return false;
        }
        for (String prefix : FRAMEWORK_LOGGERS) {
            if (logger.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private Finding finding(String route, String key, Group group, long eligible) {
        LogPayload log = group.log;
        String template = log.template() == null ? "" : log.template();
        String sentence = "`" + route + "` logged `" + log.level() + "` from `" + InsightText.simpleName(log.logger())
                + "` in " + group.rows.size() + " of " + InsightText.counted(eligible, "request") + " ("
                + InsightText.counted(group.events, "event") + "): \"" + InsightText.quoted(template) + "\".";
        List<String> checks = new ArrayList<>();
        KNOWN.forEach((fragment, check) -> {
            if (template.contains(fragment) && !checks.contains(check)) {
                checks.add(check);
            }
        });
        checks.add("Open one of these requests in Live Activity to read the message with its context.");
        return new Finding(
                route + ":" + InsightText.stableHash(key),
                route,
                true,
                sentence,
                eligible,
                group.rows.size(),
                checks,
                group.rows.stream().limit(3).map(row -> row.get(0)).toList(),
                List.of("Request", "Status", "Events", "Logger"),
                group.rows,
                List.of(
                        "Counts WARN and ERROR events written while the request's id was current; a warning logged"
                                + " at startup or on another thread is not counted.",
                        "Not yet compared with the previous run, whose summary does not keep log messages."));
    }

    private static Map<String, String> known() {
        Map<String, String> known = new LinkedHashMap<>();
        String inMemory = "Hibernate paginated in memory because the query fetches a collection: page on ids"
                + " first and fetch them in a second query, or use @BatchSize.";
        known.put("HHH90003004", inMemory);
        known.put("HHH000104", inMemory);
        known.put("applying in memory", inMemory);
        known.put(
                "Connection leak detection triggered",
                "A connection was held longer than leakDetectionThreshold: see Connections per request and the code"
                        + " that held it.");
        known.put(
                "Thread starvation or clock leap detected",
                "HikariCP's housekeeper ran late: check for CPU saturation or long garbage-collection pauses.");
        known.put(
                "has been blocked for",
                "A Vert.x thread was blocked: see Blocking on event loops, and run blocking work with @Blocking.");
        known.put(
                "onErrorDropped",
                "An error was dropped after its sequence completed or was cancelled, so no subscriber handled it.");
        known.put(
                "Resolved [",
                "Spring MVC turned an exception into a client error: check whether the caller's request was"
                        + " expected to fail.");
        known.put(
                "Invalid character found in the request target",
                "Tomcat rejected a URL with unencoded characters: check how the client builds it.");
        return Collections.unmodifiableMap(known);
    }

    private static final class Group {

        private final LogPayload log;
        private final List<List<String>> rows = new ArrayList<>();
        private long events;

        Group(LogPayload log) {
            this.log = log;
        }

        void add(ProjectedRequest request, int count) {
            events += count;
            rows.add(List.of(
                    request.requestId(), String.valueOf(request.status()), String.valueOf(count), log.logger()));
        }
    }
}
