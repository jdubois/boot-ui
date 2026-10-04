package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.engine.journal.ExceptionPayload;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.ExceptionGroupStats;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.ExecutionStats;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.RouteStats;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RunSummary;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.web.CorrelationTier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code exception-hotspots} ({@code docs/PLAN-v2.md} §5.5): the exception groups each route recorded, by their cross-run
 * signature, marked when the previous run served the route without them. Well-known framework exceptions carry a
 * specific check.
 *
 * <p>The default list ({@code docs/PLAN-v2.md} M4-19) shows a group when one of its requests failed, answering 5xx or,
 * for a scheduled run or consumed message, ending with an exception nothing caught; when it was not observed in the
 * previous run; or when one of its responses was neither 2xx nor 4xx, such as a redirect, which no other check reports.
 * Groups seen only behind 4xx responses are collapsed into one counted row, and groups caught in scheduled runs or
 * messages that completed into another, both listed last; groups behind 2xx responses are reported by Errors behind 2xx
 * responses. Every group stays in the report.</p>
 */
public final class ExceptionHotspots implements Observation {

    public static final String KIND = "exception-hotspots";

    static final String GENERIC_CHECK = "Open the exception group to read its message and stack trace.";

    /** The key and subject of the row counting the groups seen only behind 4xx responses. */
    static final String BEHIND_4XX = "Behind 4xx responses";

    /** Why a group seen only behind 4xx responses is left out of the default list. */
    static final String ONLY_4XX = "It was recorded only behind 4xx responses, which are usually intended, and is"
            + " counted in the row Behind 4xx responses.";

    /** Why a group seen behind 2xx responses, and otherwise only 4xx, is left out of the default list. */
    static final String ONLY_2XX_OR_4XX =
            "It was recorded only behind 2xx and 4xx responses: Errors behind 2xx" + " responses reports the 2xx ones.";

    /** The key and subject of the row counting the groups caught in scheduled runs or messages that completed. */
    static final String CAUGHT_IN_RUNS = "Caught in completed runs or messages";

    /** Why a group recorded in runs or messages that all completed is left out of the default list. */
    static final String CAUGHT_IN_EXECUTION = "Every scheduled run or message that recorded it completed, so the"
            + " exception was caught; it is counted in the row Caught in completed runs or messages, and listed when it is"
            + " new since the previous run.";

    /** Specific checks, by fully qualified class name. */
    static final Map<String, String> CHECKS = checks();

    private static final Set<String> EXACT_ONLY = Set.of(
            "com.fasterxml.jackson.databind.JsonMappingException",
            "tools.jackson.databind.DatabindException",
            "org.springframework.http.converter.HttpMessageNotWritableException");

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String title() {
        return "Exception hotspots";
    }

    @Override
    public CorrelationTier minimumTier() {
        return CorrelationTier.REQUEST_ID;
    }

    @Override
    public Set<JournalSource> reads() {
        return Set.of(JournalSource.EXCEPTION);
    }

    @Override
    public Evaluation evaluate(InsightsSnapshot snapshot) {
        Previous previous = Previous.of(snapshot.previousRun().orElse(null));
        List<Finding> findings = new ArrayList<>();
        long eligible = 0;
        for (Map.Entry<String, List<ProjectedRequest>> route :
                snapshot.byRoute().entrySet()) {
            List<ProjectedRequest> requests = route.getValue();
            eligible += requests.size();
            Map<String, Group> groups = new LinkedHashMap<>();
            for (ProjectedRequest request : requests) {
                Map<String, int[]> perRequest = new LinkedHashMap<>();
                for (RuntimeEvent event : request.children(JournalSource.EXCEPTION)) {
                    if (event.payload() instanceof ExceptionPayload exception) {
                        String key = exception.signature() != null ? exception.signature() : exception.groupId();
                        groups.computeIfAbsent(key, k -> new Group(exception)).observe(exception);
                        perRequest.computeIfAbsent(key, k -> new int[1])[0]++;
                    }
                }
                perRequest.forEach((key, count) -> groups.get(key).add(request, count[0]));
            }
            groups.forEach((key, group) ->
                    findings.add(finding(route.getKey(), key, group, requests.size(), previous, snapshot)));
        }
        Finding behind4xx = counted(findings, ONLY_4XX);
        Finding caught = counted(findings, CAUGHT_IN_EXECUTION);
        if (behind4xx != null) {
            findings.add(behind4xx);
        }
        if (caught != null) {
            findings.add(caught);
        }
        return new Evaluation(eligible, findings);
    }

    /**
     * One row counting the groups the default list leaves out for {@code reason}, {@link #ONLY_4XX} or {@link
     * #CAUGHT_IN_EXECUTION}, or {@code null} when there are none. It names no requests as eligible or affected, so it
     * sorts after the groups it does not collapse, and its counts are in its sentence.
     */
    private static Finding counted(List<Finding> findings, String reason) {
        boolean http = ONLY_4XX.equals(reason);
        List<Finding> collapsed = findings.stream()
                .filter(finding -> reason.equals(finding.unlisted()))
                .toList();
        if (collapsed.isEmpty()) {
            return null;
        }
        Set<String> units = new LinkedHashSet<>();
        Set<String> requests = new LinkedHashSet<>();
        long occurrences = 0;
        List<List<String>> rows = new ArrayList<>();
        List<String> exemplars = new ArrayList<>();
        for (Finding finding : collapsed) {
            units.add(finding.subject());
            Set<String> statuses = new TreeSet<>();
            long groupOccurrences = 0;
            for (List<String> row : finding.rows()) {
                requests.add(row.get(0));
                statuses.add(row.get(1));
                groupOccurrences += Long.parseLong(row.get(2));
            }
            occurrences += groupOccurrences;
            if (exemplars.size() < 3 && !finding.exemplarRequestIds().isEmpty()) {
                exemplars.add(finding.exemplarRequestIds().get(0));
            }
            List<String> row = new ArrayList<>(List.of(
                    finding.subject(),
                    exceptionOf(finding),
                    String.valueOf(finding.affected()),
                    String.valueOf(groupOccurrences)));
            if (http) {
                row.add(String.join(", ", statuses));
            }
            rows.add(List.copyOf(row));
        }
        rows.sort(Comparator.comparingLong((List<String> row) -> Long.parseLong(row.get(3)))
                .reversed());
        String groups = InsightText.counted(collapsed.size(), "exception group") + " "
                + (collapsed.size() == 1 ? "was" : "were");
        if (http) {
            return new Finding(
                    BEHIND_4XX,
                    BEHIND_4XX,
                    true,
                    InsightText.counted(collapsed.size(), "exception group") + " on "
                            + InsightText.counted(units.size(), "route") + " "
                            + (collapsed.size() == 1 ? "was" : "were") + " recorded only behind 4xx responses: "
                            + InsightText.counted(occurrences, "occurrence") + " in "
                            + InsightText.counted(requests.size(), "request") + ".",
                    0,
                    0,
                    List.of(
                            "Check that each of these 4xx responses is the one the caller should get, such as 400 for"
                                    + " invalid input; Show all routes lists each group with its requests.",
                            GENERIC_CHECK),
                    exemplars,
                    List.of("Route", "Exception", "Requests", "Occurrences", "Statuses"),
                    rows,
                    List.of("A group also seen behind a 5xx response, a redirect, or a 2xx response, or not observed"
                            + " in the previous run, is not counted here."));
        }
        return new Finding(
                CAUGHT_IN_RUNS,
                CAUGHT_IN_RUNS,
                true,
                groups + " recorded in "
                        + InsightText.counted(units.size(), "scheduled job or listener", "scheduled jobs or listeners")
                        + " whose runs or messages completed: "
                        + InsightText.counted(occurrences, "occurrence") + " in "
                        + InsightText.counted(requests.size(), "run or message", "runs or messages") + ".",
                0,
                0,
                List.of(
                        "Check that each job or listener meant to catch these exceptions, and that it logs or"
                                + " retries what it caught; Show all routes lists each group with its runs.",
                        GENERIC_CHECK),
                exemplars,
                List.of("Job or listener", "Exception", "Runs or messages", "Occurrences"),
                rows,
                List.of("A group also seen in a run or message that failed, or not observed in the previous run, is"
                        + " not counted here. Errors behind 2xx responses reads HTTP requests only, so this row is"
                        + " where these exceptions are counted."));
    }

    private Finding finding(
            String route, String key, Group group, long eligible, Previous previous, InsightsSnapshot snapshot) {
        String type = InsightText.simpleName(group.exception.exceptionClass());
        Integer servedBefore = previous.newOnRoute(group.exception.signature(), route);
        StringBuilder sentence = new StringBuilder("`" + route + "` recorded `" + type + "` in "
                + group.rows.size() + " of " + InsightText.counted(eligible, InsightText.unit(route)) + " ("
                + InsightText.counted(group.occurrences, "occurrence") + ")");
        if (servedBefore != null) {
            sentence.append(", not observed in the previous run, which served this route ")
                    .append(InsightText.counted(servedBefore, "time"));
        }
        sentence.append('.');
        List<String> checks = new ArrayList<>();
        checks.addAll(group.checks.stream().limit(2).toList());
        checks.add(GENERIC_CHECK);
        List<String> limitations = new ArrayList<>();
        if (group.checks.size() > 2) {
            limitations.add("This exception group recorded differing causes; only the first two specific checks are"
                    + " listed. Open its occurrences to inspect the other causes.");
        }
        if (snapshot.stack() == InsightsStack.SPRING_WEBFLUX) {
            limitations.add(
                    "Exceptions a WebFlux handler handles itself, such as with onErrorResume, are not" + " recorded.");
        }
        if (previous.comparable() && group.exception.signature() == null) {
            limitations.add("This group has no cross-run signature, so it is not compared with the previous run.");
        } else if (previous.reason() != null) {
            limitations.add(previous.reason());
        }
        Finding finding = new Finding(
                route + ":" + InsightText.stableHash(key),
                route,
                true,
                sentence.toString(),
                eligible,
                group.rows.size(),
                checks,
                group.rows.stream().limit(3).map(row -> row.get(0)).toList(),
                List.of("Request", "Status", "Occurrences", "Exception group"),
                group.rows,
                limitations);
        return servedBefore != null ? finding : finding.unlisted(group.unlisted());
    }

    /** The exception a group's sentence names, between its first pair of backticks after the route's. */
    private static String exceptionOf(Finding finding) {
        String[] parts = finding.sentence().split("`");
        return parts.length > 3 ? parts[3] : "";
    }

    private static Map<String, String> checks() {
        Map<String, String> checks = new LinkedHashMap<>();
        checks.put(
                "org.hibernate.LazyInitializationException",
                "If a lazy association is read after its session closed, fetch it in the query, with a join fetch or"
                        + " an entity graph, or build the response inside the transaction.");
        String rollback = "If a participating transactional method failed and the caller caught the exception, the"
                + " shared transaction was already marked rollback-only: rethrow it, or run that method with"
                + " REQUIRES_NEW if it may fail alone.";
        checks.put("org.springframework.transaction.UnexpectedRollbackException", rollback);
        String recursion = "If serialization recursed through a bidirectional association, break the cycle with"
                + " @JsonManagedReference and @JsonBackReference, @JsonIgnore, or a response DTO.";
        checks.put("com.fasterxml.jackson.databind.JsonMappingException", recursion);
        checks.put("tools.jackson.databind.DatabindException", recursion);
        checks.put("org.springframework.http.converter.HttpMessageNotWritableException", recursion);
        String optimistic = "Two requests updated the same version of a row: decide whether the caller retries,"
                + " merges, or receives a 409 Conflict.";
        checks.put("jakarta.persistence.OptimisticLockException", optimistic);
        checks.put("org.hibernate.StaleObjectStateException", optimistic);
        checks.put("org.springframework.orm.ObjectOptimisticLockingFailureException", optimistic);
        String integrity = "A database constraint rejected the write: validate the input first, or map the violation"
                + " to a 4xx response.";
        checks.put("org.springframework.dao.DataIntegrityViolationException", integrity);
        checks.put("org.hibernate.exception.ConstraintViolationException", integrity);
        checks.put("java.sql.SQLIntegrityConstraintViolationException", integrity);
        checks.put(
                "java.sql.SQLTransientConnectionException",
                "A connection could not be obtained: if the pool timed out, compare Connections per request with"
                        + " the pool size and look for connections held across slow work; also check database"
                        + " connectivity.");
        String lock = "A lock wait timed out or deadlocked: check which requests write the same rows, and in which"
                + " order.";
        checks.put("org.springframework.dao.CannotAcquireLockException", lock);
        checks.put("org.springframework.dao.PessimisticLockingFailureException", lock);
        checks.put("jakarta.persistence.PessimisticLockException", lock);
        String nonUnique = "A query expected one row and found several: check the uniqueness the query assumes.";
        checks.put("org.springframework.dao.IncorrectResultSizeDataAccessException", nonUnique);
        checks.put("jakarta.persistence.NonUniqueResultException", nonUnique);
        checks.put("org.hibernate.NonUniqueResultException", nonUnique);
        String noResult = "A query expected one row and found none: check whether absence should answer 404.";
        checks.put("org.springframework.dao.EmptyResultDataAccessException", noResult);
        checks.put("jakarta.persistence.NoResultException", noResult);
        checks.put(
                "reactor.blockhound.BlockingOperationError",
                "A blocking call ran on a non-blocking thread: see Blocking on event loops.");
        return Map.copyOf(checks);
    }

    private static final class Group {

        private final ExceptionPayload exception;
        private final List<List<String>> rows = new ArrayList<>();
        private final List<String> checks = new ArrayList<>();
        private long occurrences;
        private boolean failed;
        private boolean other;
        private boolean success;
        private boolean clientError;
        private boolean completedExecution;

        Group(ExceptionPayload exception) {
            this.exception = exception;
        }

        void observe(ExceptionPayload occurrence) {
            String specific = occurrence.types().stream()
                    .filter(type -> !EXACT_ONLY.contains(type))
                    .map(CHECKS::get)
                    .filter(java.util.Objects::nonNull)
                    .findFirst()
                    .orElse(CHECKS.get(occurrence.exceptionClass()));
            if (specific != null && !checks.contains(specific)) {
                checks.add(specific);
            }
        }

        void add(ProjectedRequest request, int count) {
            occurrences += count;
            int status = request.status();
            if (request.failed()) {
                failed = true;
            } else if (!request.http()) {
                completedExecution = true;
            } else if (status >= 400 && status < 500) {
                clientError = true;
            } else if (status >= 200 && status < 300) {
                success = true;
            } else {
                other = true;
            }
            rows.add(List.of(
                    request.requestId(),
                    String.valueOf(request.status()),
                    String.valueOf(count),
                    exception.groupId() == null ? "" : exception.groupId()));
        }

        /** Why the default list leaves this group out when it is not new, or {@code null} when it is listed. */
        String unlisted() {
            // HTTP status 0, no response recorded, counts as other: listed, since nothing says it was intended.
            if (failed || other) {
                return null;
            }
            if (completedExecution) {
                return CAUGHT_IN_EXECUTION;
            }
            if (success) {
                return ONLY_2XX_OR_4XX;
            }
            return clientError ? ONLY_4XX : null;
        }
    }

    /** What the previous run's summary can tell about the signatures each route raised. */
    private record Previous(boolean comparable, Set<String> signatures, Map<String, Long> routes, String reason) {

        static Previous of(RunSummary summary) {
            if (summary == null) {
                return new Previous(false, Set.of(), Map.of(), null);
            }
            if (summary.header().omittedEntries() > 0) {
                return new Previous(
                        false,
                        Set.of(),
                        Map.of(),
                        "The previous run's summary left out its least-used entries, so it is not compared.");
            }
            Set<String> signatures = new HashSet<>();
            for (ExceptionGroupStats group : summary.aggregates().exceptionGroups()) {
                if (group.signature() != null) {
                    signatures.add(group.signature());
                }
            }
            Map<String, Long> routes = new LinkedHashMap<>();
            for (RouteStats route : summary.aggregates().routes()) {
                routes.put(route.route(), route.requests());
            }
            // Scheduled runs and consumed messages are kept apart from routes, by the same names as their units.
            if (summary.aggregates().executionsRecorded()) {
                for (ExecutionStats execution : summary.aggregates().executions()) {
                    String name = unitName(execution);
                    if (name != null) {
                        routes.merge(name, execution.stats().requests(), Long::sum);
                    }
                }
            }
            return new Previous(true, signatures, routes, null);
        }

        /**
         * A kept execution's name as the snapshot names its unit ({@link InsightsSnapshot#executionName}), such as
         * {@code @Scheduled Jobs.sync} for {@code scheduled Jobs.sync} or {@code consume orders} for {@code messaging
         * ?:orders}, or {@code null} when its source is not a kind of execution the snapshot projects.
         */
        static String unitName(ExecutionStats execution) {
            String name = execution.stats().route();
            JournalSource source = execution.source();
            if (name == null || source == null || !name.startsWith(source.propertyName() + " ")) {
                return null;
            }
            String key = name.substring(source.propertyName().length() + 1);
            if (source == JournalSource.SCHEDULED) {
                return "@Scheduled " + key;
            }
            if (source == JournalSource.MESSAGING) {
                return "consume " + (key.startsWith("?:") ? key.substring(2) : key);
            }
            return source == JournalSource.WEBSOCKET ? "consume " + key : null;
        }

        /** How many times the previous run served {@code route} without raising {@code signature}, or {@code null}. */
        Integer newOnRoute(String signature, String route) {
            if (!comparable || signature == null || signatures.contains(signature)) {
                return null;
            }
            Long served = routes.get(route);
            return served == null || served == 0 ? null : Math.toIntExact(Math.min(served, Integer.MAX_VALUE));
        }
    }
}
