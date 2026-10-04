package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.engine.correlation.ExecutionIds;
import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceVocabulary;
import io.github.jdubois.bootui.engine.journal.AsyncHandoffPayload;
import io.github.jdubois.bootui.engine.journal.ExceptionPayload;
import io.github.jdubois.bootui.engine.journal.FaultTolerancePayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.JournalTextExposure;
import io.github.jdubois.bootui.engine.journal.LogPayload;
import io.github.jdubois.bootui.engine.journal.RestClientPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.TransactionPayload;
import io.github.jdubois.bootui.engine.web.CorrelationTier;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code errors-behind-2xx} ({@code docs/PLAN-v2.md} §5.5): 2xx responses whose own request rolled back its root
 * transaction, recorded an exception, had a task fail, wrote an {@code ERROR} log, or received a failed downstream call,
 * strongest evidence first. Matching exceptions a retry or fallback recovered are reported apart; one request can belong
 * to both groups.
 *
 * <p>A request's tasks are the work it handed to a framework-managed executor ({@code task-} executions, M4-15, M4-22)
 * or that the BootUI agent propagated ({@code async-}, M5-2). Their failures are reported as the task's, never as the
 * request thread's: an exception, an {@code ERROR} log, a {@code WARN} log carrying an exception, which is how a task
 * such as JHipster's {@code @Async} mail sender reports what it swallowed, or a task the agent saw fail after the
 * response had started. A failed task the request waited for and could have handled, such as a joined
 * {@code CompletableFuture}, is not counted from the agent's outcome alone.</p>
 *
 * <p>On Quarkus, a {@code ManagedExecutor} task that runs where its request's context is already current keeps the
 * request's execution, so its failures count as the request's own exception or {@code ERROR} log.</p>
 */
public final class ErrorsBehind2xx implements Observation {

    public static final String KIND = "errors-behind-2xx";

    /** The evidence, strongest first. */
    enum Evidence {
        ROLLED_BACK("whose transaction rolled back", "rolled-back transaction"),
        EXCEPTION("that recorded an exception", "exception"),
        TASK_FAILURE("whose own task failed", "task failure"),
        ERROR_LOG("that wrote an ERROR log", "ERROR log"),
        DOWNSTREAM("that received a 5xx or failed downstream call", "downstream 5xx or failure");

        /** What a request showing it did, after {@code 3 requests}. */
        private final String clause;

        private final String noun;

        Evidence(String clause, String noun) {
            this.clause = clause;
            this.noun = noun;
        }
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String title() {
        return "Errors behind 2xx responses";
    }

    @Override
    public CorrelationTier minimumTier() {
        return CorrelationTier.REQUEST_ID;
    }

    @Override
    public Set<JournalSource> reads() {
        return Set.of(JournalSource.HTTP);
    }

    @Override
    public Set<ProjectedRequest.Kind> unitKinds() {
        return Set.of(ProjectedRequest.Kind.HTTP);
    }

    @Override
    public Set<JournalSource> optionalReads() {
        return Set.of(
                JournalSource.TRANSACTION,
                JournalSource.EXCEPTION,
                JournalSource.LOG,
                JournalSource.REST_CLIENT,
                JournalSource.FAULT_TOLERANCE);
    }

    /** The agent's task outcomes are read only when the agent records them, so a run without it names no gap. */
    @Override
    public Set<JournalSource> optionalReads(InsightsSnapshot snapshot) {
        if (!snapshot.records(JournalSource.AGENT_EXECUTORS)) {
            return optionalReads();
        }
        Set<JournalSource> sources = EnumSet.copyOf(optionalReads());
        sources.add(JournalSource.AGENT_EXECUTORS);
        return sources;
    }

    @Override
    public String notApplicable(InsightsSnapshot snapshot) {
        for (JournalSource source : List.of(
                JournalSource.TRANSACTION, JournalSource.EXCEPTION, JournalSource.LOG, JournalSource.REST_CLIENT)) {
            if (snapshot.records(source) && snapshot.visible(source)) {
                return null;
            }
        }
        return "The runtime journal records none of the transaction, exception, log, and rest-client sources, or"
                + " their panels are disabled.";
    }

    @Override
    public Evaluation evaluate(InsightsSnapshot snapshot) {
        Set<JournalSource> readable = readable(snapshot);
        List<Finding> findings = new ArrayList<>();
        long eligible = 0;
        for (Map.Entry<String, List<ProjectedRequest>> route :
                snapshot.httpByRoute().entrySet()) {
            List<ProjectedRequest> successes = route.getValue().stream()
                    .filter(request -> request.status() >= 200 && request.status() < 300)
                    .toList();
            eligible += successes.size();
            List<Behind> unrecovered = new ArrayList<>();
            List<Behind> recovered = new ArrayList<>();
            for (ProjectedRequest request : successes) {
                for (Behind behind : behind(request, readable, snapshot.exposure())) {
                    (behind.recovered() ? recovered : unrecovered).add(behind);
                }
            }
            if (!unrecovered.isEmpty()) {
                findings.add(finding(route.getKey(), unrecovered, successes.size(), false));
            }
            if (!recovered.isEmpty()) {
                findings.add(finding(route.getKey(), recovered, successes.size(), true));
            }
        }
        return new Evaluation(eligible, findings);
    }

    private static Set<JournalSource> readable(InsightsSnapshot snapshot) {
        Set<JournalSource> readable = EnumSet.noneOf(JournalSource.class);
        for (JournalSource source : List.of(
                JournalSource.TRANSACTION,
                JournalSource.EXCEPTION,
                JournalSource.LOG,
                JournalSource.REST_CLIENT,
                JournalSource.FAULT_TOLERANCE,
                JournalSource.AGENT_EXECUTORS)) {
            if (snapshot.records(source) && snapshot.visible(source)) {
                readable.add(source);
            }
        }
        return readable;
    }

    static List<Behind> behind(ProjectedRequest request, Set<JournalSource> readable) {
        return behind(request, readable, JournalTextExposure.masked());
    }

    static List<Behind> behind(ProjectedRequest request, Set<JournalSource> readable, JournalTextExposure text) {
        Map<Evidence, List<String>> evidence = new EnumMap<>(Evidence.class);
        Map<Evidence, List<String>> recovered = new EnumMap<>(Evidence.class);
        Set<Integer> recoveredExceptions = recoveredExceptions(request, readable);
        // By execution, as recovery is matched on one thread: a retry in one task never excuses another's warning.
        Map<String, Set<String>> recoveredClasses = new java.util.HashMap<>();
        for (int index : recoveredExceptions) {
            RuntimeEvent recoveredEvent = request.children().get(index);
            if (recoveredEvent.payload() instanceof ExceptionPayload exception) {
                recoveredClasses
                        .computeIfAbsent(String.valueOf(recoveredEvent.executionId()), id -> new java.util.HashSet<>())
                        .add(InsightText.simpleName(exception.exceptionClass()));
            }
        }
        for (int i = 0; i < request.children().size(); i++) {
            RuntimeEvent event = request.children().get(i);
            if (!readable.contains(event.source())) {
                continue;
            }
            Object payload = event.payload();
            boolean task = ExecutionIds.isTask(event.executionId()) || ExecutionIds.isAsync(event.executionId());
            if (task && !recoveredExceptions.contains(i)) {
                String failure = taskFailure(payload);
                // A task's WARN log of an exception a retry or fallback then recovered is that retry, not a failure.
                boolean recoveredWarning = failure != null
                        && payload instanceof LogPayload log
                        && "WARN".equalsIgnoreCase(log.level())
                        && recoveredClasses
                                .getOrDefault(String.valueOf(event.executionId()), Set.of())
                                .contains(failure);
                if (failure != null && !recoveredWarning) {
                    add(evidence, Evidence.TASK_FAILURE, failure);
                    continue;
                }
            }
            if (payload instanceof TransactionPayload transaction) {
                if (transaction.rolledBack() && !transaction.nested()) {
                    add(
                            evidence,
                            Evidence.ROLLED_BACK,
                            transaction.method() == null ? "transaction" : transaction.method());
                }
            } else if (payload instanceof ExceptionPayload exception) {
                add(
                        recoveredExceptions.contains(i) ? recovered : evidence,
                        Evidence.EXCEPTION,
                        InsightText.simpleName(exception.exceptionClass()));
            } else if (payload instanceof LogPayload log) {
                if ("ERROR".equalsIgnoreCase(log.level())) {
                    add(evidence, Evidence.ERROR_LOG, log.logger() == null ? "?" : log.logger());
                }
            } else if (payload instanceof RestClientPayload call) {
                if (call.status() != null ? call.status() >= 500 : call.failed()) {
                    add(evidence, Evidence.DOWNSTREAM, downstream(call, text));
                }
            }
        }
        List<Behind> groups = new ArrayList<>();
        if (!evidence.isEmpty()) {
            groups.add(new Behind(request, evidence, false));
        }
        if (!recovered.isEmpty()) {
            groups.add(new Behind(request, recovered, true));
        }
        return groups;
    }

    /**
     * What failed in one of the request's tasks, or {@code null} when {@code payload} is not a failure: an exception, an
     * {@code ERROR} log, a {@code WARN} log carrying an exception, or a task the agent saw fail after the response. A {@code WARN} log without
     * an exception is a warning, not a failure.
     */
    private static String taskFailure(Object payload) {
        if (payload instanceof ExceptionPayload exception) {
            return InsightText.simpleName(exception.exceptionClass());
        }
        if (payload instanceof LogPayload log) {
            if ("ERROR".equalsIgnoreCase(log.level())) {
                return log.exceptionClass() != null
                        ? InsightText.simpleName(log.exceptionClass())
                        : log.logger() == null ? "?" : log.logger();
            }
            if ("WARN".equalsIgnoreCase(log.level()) && log.exceptionClass() != null) {
                return InsightText.simpleName(log.exceptionClass());
            }
            return null;
        }
        if (payload instanceof AsyncHandoffPayload handoff && handoff.failed() && failedAfterResponse(handoff)) {
            return handoff.exceptionClass() == null ? "task" : InsightText.simpleName(handoff.exceptionClass());
        }
        return null;
    }

    /**
     * Whether a failed task the agent followed failed once its request's response had started, so the request could not
     * have handled it: its confirmed failure time first, then its body's, then its run's.
     */
    private static boolean failedAfterResponse(AsyncHandoffPayload handoff) {
        if (handoff.failureAfterResponse() != null) {
            return handoff.failureAfterResponse();
        }
        if (handoff.bodyAfterResponse() != null) {
            return handoff.bodyAfterResponse();
        }
        return Boolean.TRUE.equals(handoff.afterResponse());
    }

    private static Set<Integer> recoveredExceptions(ProjectedRequest request, Set<JournalSource> readable) {
        Set<Integer> recovered = new java.util.HashSet<>();
        if (!readable.contains(JournalSource.FAULT_TOLERANCE) || !readable.contains(JournalSource.EXCEPTION)) {
            return recovered;
        }
        Map<Policy, List<Integer>> retries = new LinkedHashMap<>();
        for (int i = 0; i < request.children().size(); i++) {
            RuntimeEvent event = request.children().get(i);
            if (event.source() != JournalSource.FAULT_TOLERANCE
                    || !(event.payload() instanceof FaultTolerancePayload policy)
                    || policy.policy() == null) {
                continue;
            }
            Policy key = new Policy(policy.policy(), policy.policyType(), policy.target());
            if (FaultToleranceVocabulary.OUTCOME_RETRY.equals(policy.outcome())) {
                retries.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
            } else if (FaultToleranceVocabulary.OUTCOME_SUCCESS.equals(policy.outcome()) && !policy.failure()) {
                List<Integer> attempts = retries.remove(key);
                if (attempts != null) {
                    for (int attempt : attempts) {
                        recoverException(request, attempt, recovered);
                    }
                }
            } else if (FaultToleranceVocabulary.OUTCOME_FALLBACK.equals(policy.outcome()) && !policy.failure()) {
                recoverException(request, i, recovered);
                retries.remove(key);
            } else {
                retries.remove(key);
            }
        }
        return recovered;
    }

    private static void recoverException(ProjectedRequest request, int attempt, Set<Integer> recovered) {
        RuntimeEvent event = request.children().get(attempt);
        FaultTolerancePayload policy = (FaultTolerancePayload) event.payload();
        if (policy.failureCategory() == null) {
            return;
        }
        // A failed attempt can account for at most one earlier exception, never every error in the request.
        for (int i = attempt - 1; i >= 0; i--) {
            RuntimeEvent candidate = request.children().get(i);
            if (!recovered.contains(i)
                    && candidate.source() == JournalSource.EXCEPTION
                    && java.util.Objects.equals(candidate.thread(), event.thread())
                    && candidate.payload() instanceof ExceptionPayload exception
                    && policy.failureCategory().equals(failureCategory(exception.exceptionClass()))) {
                recovered.add(i);
                return;
            }
        }
    }

    private static String failureCategory(String type) {
        String simple = InsightText.simpleName(type);
        return simple.substring(simple.lastIndexOf('$') + 1);
    }

    private record Policy(String name, String type, String target) {}

    private static void add(Map<Evidence, List<String>> evidence, Evidence kind, String what) {
        evidence.computeIfAbsent(kind, k -> new ArrayList<>()).add(what);
    }

    private static String downstream(RestClientPayload call, JournalTextExposure text) {
        StringBuilder described = new StringBuilder(call.status() == null ? "failed" : String.valueOf(call.status()));
        if (call.method() != null) {
            described.append(' ').append(call.method());
        }
        described.append(' ');
        if (call.authority() != null) {
            described.append(call.authority());
        }
        if (call.path() != null) {
            described.append(text.path(call.path()));
        }
        return described.toString().trim();
    }

    private Finding finding(String route, List<Behind> requests, long eligible, boolean recovered) {
        Map<Evidence, Integer> counts = new EnumMap<>(Evidence.class);
        for (Behind behind : requests) {
            behind.evidence().keySet().forEach(kind -> counts.merge(kind, 1, Integer::sum));
        }
        List<String> parts = new ArrayList<>();
        counts.forEach((kind, count) -> parts.add(InsightText.counted(count, "request") + " " + kind.clause));
        List<Behind> ordered = new ArrayList<>(requests);
        ordered.sort(
                (a, b) -> Integer.compare(a.strongest().ordinal(), b.strongest().ordinal()));
        String sentence = "`" + route + "` answered 2xx in " + requests.size() + " of "
                + InsightText.counted(eligible, "successful request") + ": "
                + String.join(", ", parts)
                + (recovered ? "; a retry or fallback recovered." : ".");
        List<String> checks = new ArrayList<>();
        if (recovered) {
            checks.add("Verify the fallback contract: should the caller know that it received a fallback?");
        } else {
            checks.add("Verify the response contract: should the caller learn that part of this request failed?");
            if (counts.containsKey(Evidence.TASK_FAILURE)) {
                checks.add("A task this request handed to another thread failed: verify its failure is handled,"
                        + " retried or alerted, or that the response does not promise what the task was to do.");
            }
            if (counts.containsKey(Evidence.ROLLED_BACK)) {
                checks.add("If the transaction rolled back, the response may describe data that was never saved.");
            }
        }
        return new Finding(
                route + (recovered ? ":recovered" : ":unrecovered"),
                route,
                true,
                sentence,
                eligible,
                requests.size(),
                checks,
                ordered.stream().limit(3).map(b -> b.request().requestId()).toList(),
                List.of("Request", "Status", "Evidence"),
                ordered.stream()
                        .map(b -> List.of(
                                b.request().requestId(),
                                String.valueOf(b.request().status()),
                                b.describe()))
                        .toList(),
                List.of(
                        "Only work that carries the request's id is counted: its own thread's, and its tasks' on"
                                + " executors BootUI decorates or the BootUI agent follows; work handed to another"
                                + " thread without BootUI's correlation is not.",
                        "Recovery matches at most one earlier exception of the reported failure class on the same"
                                + " thread per failed attempt, and requires a terminal success for the same policy,"
                                + " type, and target, or a successful fallback. It is not causal proof.",
                        "Logs, downstream failures, and root rollbacks are never inferred recovered. Providers that"
                                + " report no terminal success or fallback cannot establish recovery.",
                        "A task's exception is counted whatever level it was logged at, since a recorded exception"
                                + " does not keep its log level."));
    }

    /** One 2xx request and the errors it recorded. */
    record Behind(ProjectedRequest request, Map<Evidence, List<String>> evidence, boolean recovered) {

        Evidence strongest() {
            return evidence.keySet().iterator().next();
        }

        String describe() {
            List<String> parts = new ArrayList<>();
            evidence.forEach((kind, what) -> parts.add(kind.noun + ": "
                    + String.join(", ", what.stream().distinct().limit(3).toList())));
            return String.join("; ", parts);
        }
    }
}
