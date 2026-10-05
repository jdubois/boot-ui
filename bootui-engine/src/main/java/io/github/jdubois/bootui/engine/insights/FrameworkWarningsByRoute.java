package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.JournalTextExposure;
import io.github.jdubois.bootui.engine.journal.LogPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.web.CorrelationTier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code framework-warnings-by-route} ({@code docs/PLAN-v2.md} §5.5): {@code WARN} and {@code ERROR} events that
 * framework loggers wrote while serving a request, grouped by logger, message template, and route. Well-known codes
 * carry a specific check. The default list ({@code docs/PLAN-v2.md} M4-19) shows every {@code ERROR} group and the
 * {@code WARN} groups with a specific check, except Spring MVC's {@code Resolved [...]} when every request it was written
 * in answered 4xx, which Exception hotspots counts. Framework {@code ERROR} events that carried no request or execution
 * id, such as a container's errors while it parsed requests, are counted in one row of their own, so they are not
 * missed for having no route.
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

    /** The key and subject of the row counting framework {@code ERROR} events that carried no request id. */
    static final String NO_REQUEST = "No request";

    /** How long after a request ended an error on its thread without an id is taken as the request's. */
    static final long AFTER_REQUEST_MILLIS = 1_000;

    /** Why a {@code WARN} message without a specific check is left out of the default list. */
    static final String UNKNOWN_MESSAGE = "It is a WARN message BootUI has no specific check for.";

    /** Why Spring MVC's note that it resolved an exception to a client error is left out when every request was 4xx. */
    static final String RESOLVED_4XX = "Spring MVC resolved an exception to a 4xx response in every request, which"
            + " Exception hotspots counts behind 4xx responses.";

    /** The fragment of Spring MVC's note that it resolved an exception into a response. */
    static final String RESOLVED = "Resolved [";

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
                        String key = log.logger() + "\n" + log.level() + "\n" + messageKey(log.template());
                        groups.computeIfAbsent(key, k -> new Group(log));
                        perRequest.merge(key, 1, Integer::sum);
                    }
                }
                perRequest.forEach((key, count) -> groups.get(key).add(request, count));
            }
            groups.forEach((key, group) ->
                    findings.add(finding(route.getKey(), key, group, requests.size(), snapshot.exposure())));
        }
        Finding unowned = unowned(snapshot);
        if (unowned != null) {
            findings.add(unowned);
        }
        return new Evaluation(eligible, findings);
    }

    /**
     * One row counting the framework {@code ERROR} events that carried no request or execution id, by logger and
     * template, or {@code null} when there were none. It names no request, so its tier is the kind's only nominally.
     */
    private static Finding unowned(InsightsSnapshot snapshot) {
        Map<String, long[]> counts = new LinkedHashMap<>();
        Map<String, LogPayload> logs = new LinkedHashMap<>();
        Map<String, List<ProjectedRequest>> byThread = new LinkedHashMap<>();
        for (ProjectedRequest request : snapshot.httpRequests()) {
            // A blank name, as an unnamed virtual thread has, cannot tell one request's thread from another's.
            if (request.thread() != null && !request.thread().isBlank()) {
                byThread.computeIfAbsent(request.thread(), thread -> new ArrayList<>())
                        .add(request);
            }
        }
        long afterRequest = 0;
        for (RuntimeEvent event : snapshot.unownedErrorLogs()) {
            if (event.payload() instanceof LogPayload log && isFramework(log.logger())) {
                if (justAfterARequest(event, byThread)) {
                    afterRequest++;
                    continue;
                }
                String key = log.logger() + "\n" + messageKey(log.template());
                counts.computeIfAbsent(key, k -> new long[1])[0]++;
                logs.putIfAbsent(key, log);
            }
        }
        if (counts.isEmpty()) {
            return null;
        }
        List<String> limitations = new ArrayList<>(List.of(
                "Counts framework ERROR events written while no request or execution id was current, including at"
                        + " startup; WARN events without one are not counted. Its affected count is those events,"
                        + " not requests.",
                "Not yet compared with the previous run, whose summary does not keep log messages."));
        if (afterRequest > 0) {
            limitations.add(InsightText.counted(afterRequest, "more event") + " written on the thread of a request that"
                    + " failed, within " + AFTER_REQUEST_MILLIS + " ms after it ended, as a container or error handler"
                    + " logs a failure once the request's filters returned, " + (afterRequest == 1 ? "is" : "are")
                    + " left out: Exception hotspots and Errors behind 2xx responses report the request.");
        }
        List<Map.Entry<String, long[]>> ranked = new ArrayList<>(counts.entrySet());
        ranked.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
        long total = 0;
        List<List<String>> rows = new ArrayList<>();
        List<String> checks = new ArrayList<>();
        JournalTextExposure text = snapshot.exposure();
        for (Map.Entry<String, long[]> entry : ranked) {
            LogPayload log = logs.get(entry.getKey());
            String template = log.template() == null ? "" : log.template();
            String shown = text.message(messageKey(template));
            total += entry.getValue()[0];
            rows.add(List.of(
                    log.logger(), shown == null ? "" : InsightText.quoted(shown), String.valueOf(entry.getValue()[0])));
            KNOWN.forEach((fragment, check) -> {
                if (template.contains(fragment) && !checks.contains(check) && checks.size() < 2) {
                    checks.add(check);
                }
            });
        }
        LogPayload top = logs.get(ranked.get(0).getKey());
        String sentence = "Framework loggers wrote " + InsightText.counted(total, "`ERROR` event")
                + " that carried no request or execution id, from " + InsightText.counted(ranked.size(), "message")
                + "; the most frequent from `" + InsightText.simpleName(top.logger()) + "`, "
                + ranked.get(0).getValue()[0] + " times.";
        checks.add("Open Log Tail and filter on the logger to read these errors with their stack traces: a container"
                + " error while parsing requests, a failure at startup, or work on a thread BootUI does not follow.");
        return new Finding(
                NO_REQUEST,
                NO_REQUEST,
                true,
                sentence,
                0,
                // No request is eligible, so affected counts the errors, which ranks the row among its kind's.
                total,
                checks,
                List.of(),
                List.of("Logger", "Message", "Events"),
                rows,
                limitations);
    }

    /**
     * Whether an event without a request id was written on a failed request's thread, one that answered 5xx or recorded
     * an exception, while it ran or within {@value #AFTER_REQUEST_MILLIS} ms after it ended, before the thread served
     * another: a container or error handler logging the failure once the request's filters, and so its id, were gone.
     */
    private static boolean justAfterARequest(RuntimeEvent event, Map<String, List<ProjectedRequest>> byThread) {
        List<ProjectedRequest> requests = byThread.get(event.thread());
        if (requests == null) {
            return false;
        }
        ProjectedRequest last = null;
        for (ProjectedRequest request : requests) {
            if (request.startMillis() <= event.epochMillis()) {
                last = request;
            }
        }
        // Only a failed request is taken to own it, as a container logs the failure it answered 5xx for.
        if (last == null
                || !(last.failed() || !last.children(JournalSource.EXCEPTION).isEmpty())) {
            return false;
        }
        long end = last.startMillis() + last.durationNanos() / 1_000_000;
        return event.epochMillis() <= end + AFTER_REQUEST_MILLIS;
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

    private Finding finding(String route, String key, Group group, long eligible, JournalTextExposure text) {
        LogPayload log = group.log;
        String template = log.template() == null ? "" : log.template();
        // The raw template only matches known fragments; what is quoted is the group's message, without the ids that
        // differ between its events, under the live exposure policy (§8).
        String shown = text.message(messageKey(template));
        String sentence = "`" + route + "` logged `" + log.level() + "` from `" + InsightText.simpleName(log.logger())
                + "` in " + group.rows.size() + " of " + InsightText.counted(eligible, InsightText.unit(route)) + " ("
                + InsightText.counted(group.events, "event") + ")"
                + (shown == null ? "." : ": \"" + InsightText.quoted(shown) + "\".");
        List<String> checks = new ArrayList<>();
        KNOWN.forEach((fragment, check) -> {
            if (template.contains(fragment) && !checks.contains(check)) {
                checks.add(check);
            }
        });
        boolean known = !checks.isEmpty();
        boolean error = InsightsSnapshot.isError(log.level());
        boolean only4xx = group.rows.stream().allMatch(row -> row.get(1).startsWith("4"));
        String unlisted = known && template.contains(RESOLVED) && only4xx
                ? RESOLVED_4XX
                : known || error ? null : UNKNOWN_MESSAGE;
        checks.add("Open one of these requests in Live Activity to read the message with its context.");
        Finding finding = new Finding(
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
        return finding.unlisted(unlisted);
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

    /** A UUID, with the request counter some frameworks append to it, as Quarkus's error handler does. */
    private static final Pattern UUID = Pattern.compile(
            "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}(?:-\\d+)?\\b");

    /** A hexadecimal id of 16 characters or more, such as a trace id, holding both letters and digits. */
    private static final Pattern HEX_ID =
            Pattern.compile("\\b(?=[0-9a-fA-F]*[a-fA-F])(?=[0-9a-fA-F]*\\d)[0-9a-fA-F]{16,}\\b");

    /** A path segment that is a number, such as the id in {@code /api/villains/50}. */
    private static final Pattern NUMERIC_SEGMENT = Pattern.compile("(?<=/)\\d+(?=[/?#\\s,;:]|$)");

    /**
     * A message without the parts that differ between the events of one message: ids and numeric path segments. A
     * logger that formats the message itself, as Quarkus's error handler does with a per-request error id and the
     * request path, would otherwise make each failed request a group of its own.
     */
    static String messageKey(String template) {
        if (template == null) {
            return null;
        }
        String key = UUID.matcher(template).replaceAll("<id>");
        key = HEX_ID.matcher(key).replaceAll("<id>");
        return NUMERIC_SEGMENT.matcher(key).replaceAll("<n>");
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
