package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.engine.journal.ExceptionPayload;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.ExceptionGroupStats;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.RouteStats;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RunSummary;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.web.CorrelationTier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code exception-hotspots} ({@code docs/PLAN-v2.md} §5.5): the exception groups each route recorded, by their cross-run
 * signature, marked when the previous run served the route without them. Well-known framework exceptions carry a
 * specific check.
 */
public final class ExceptionHotspots implements Observation {

    public static final String KIND = "exception-hotspots";

    static final String GENERIC_CHECK = "Open the exception group to read its message and stack trace.";

    /** Specific checks, by fully qualified class name. */
    static final Map<String, String> CHECKS = checks();

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
                        groups.computeIfAbsent(key, k -> new Group(exception));
                        perRequest.computeIfAbsent(key, k -> new int[1])[0]++;
                    }
                }
                perRequest.forEach((key, count) -> groups.get(key).add(request, count[0]));
            }
            groups.forEach((key, group) ->
                    findings.add(finding(route.getKey(), key, group, requests.size(), previous, snapshot)));
        }
        return new Evaluation(eligible, findings);
    }

    private Finding finding(
            String route, String key, Group group, long eligible, Previous previous, InsightsSnapshot snapshot) {
        String type = InsightText.simpleName(group.exception.exceptionClass());
        Integer servedBefore = previous.newOnRoute(group.exception.signature(), route);
        StringBuilder sentence = new StringBuilder("`" + route + "` recorded `" + type + "` in "
                + group.rows.size() + " of " + InsightText.counted(eligible, "request") + " ("
                + InsightText.counted(group.occurrences, "occurrence") + ")");
        if (servedBefore != null) {
            sentence.append(", not observed in the previous run, which served this route ")
                    .append(InsightText.counted(servedBefore, "time"));
        }
        sentence.append('.');
        List<String> checks = new ArrayList<>();
        String specific = CHECKS.get(group.exception.exceptionClass());
        if (specific != null) {
            checks.add(specific);
        }
        checks.add(GENERIC_CHECK);
        List<String> limitations = new ArrayList<>();
        if (snapshot.stack() == InsightsStack.SPRING_WEBFLUX) {
            limitations.add(
                    "Exceptions a WebFlux handler handles itself, such as with onErrorResume, are not" + " recorded.");
        }
        if (previous.comparable() && group.exception.signature() == null) {
            limitations.add("This group has no cross-run signature, so it is not compared with the previous run.");
        } else if (previous.reason() != null) {
            limitations.add(previous.reason());
        }
        return new Finding(
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
                "The pool had no free connection within its timeout: compare Connections per request with the pool"
                        + " size, and look for connections held across slow work.");
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
        private long occurrences;

        Group(ExceptionPayload exception) {
            this.exception = exception;
        }

        void add(ProjectedRequest request, int count) {
            occurrences += count;
            rows.add(List.of(
                    request.requestId(),
                    String.valueOf(request.status()),
                    String.valueOf(count),
                    exception.groupId() == null ? "" : exception.groupId()));
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
            return new Previous(true, signatures, routes, null);
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
