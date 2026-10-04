package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceVocabulary;
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
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code errors-behind-2xx} ({@code docs/PLAN-v2.md} §5.5): 2xx responses whose own request rolled back its root
 * transaction, recorded an exception, wrote an {@code ERROR} log, or received a failed downstream call, strongest
 * evidence first. Requests where a retry or fallback recovered are reported apart, as recovered.
 */
public final class ErrorsBehind2xx implements Observation {

    public static final String KIND = "errors-behind-2xx";

    /** The evidence, strongest first. */
    enum Evidence {
        ROLLED_BACK("whose transaction rolled back", "rolled-back transaction"),
        EXCEPTION("that recorded an exception", "exception"),
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
                Behind behind = behind(request, readable, snapshot.exposure());
                if (behind == null) {
                    continue;
                }
                (behind.recovered() ? recovered : unrecovered).add(behind);
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
                JournalSource.FAULT_TOLERANCE)) {
            if (snapshot.records(source) && snapshot.visible(source)) {
                readable.add(source);
            }
        }
        return readable;
    }

    static Behind behind(ProjectedRequest request, Set<JournalSource> readable) {
        return behind(request, readable, JournalTextExposure.masked());
    }

    static Behind behind(ProjectedRequest request, Set<JournalSource> readable, JournalTextExposure text) {
        Map<Evidence, List<String>> evidence = new EnumMap<>(Evidence.class);
        boolean retried = false;
        boolean recoveredByPolicy = false;
        for (RuntimeEvent event : request.children()) {
            if (!readable.contains(event.source())) {
                continue;
            }
            Object payload = event.payload();
            if (payload instanceof TransactionPayload transaction) {
                if (transaction.rolledBack() && !transaction.nested()) {
                    add(
                            evidence,
                            Evidence.ROLLED_BACK,
                            transaction.method() == null ? "transaction" : transaction.method());
                }
            } else if (payload instanceof ExceptionPayload exception) {
                add(evidence, Evidence.EXCEPTION, InsightText.simpleName(exception.exceptionClass()));
            } else if (payload instanceof LogPayload log) {
                if ("ERROR".equalsIgnoreCase(log.level())) {
                    add(evidence, Evidence.ERROR_LOG, log.logger() == null ? "?" : log.logger());
                }
            } else if (payload instanceof RestClientPayload call) {
                if (call.status() != null ? call.status() >= 500 : call.failed()) {
                    add(evidence, Evidence.DOWNSTREAM, downstream(call, text));
                }
            } else if (payload instanceof FaultTolerancePayload policy) {
                if (FaultToleranceVocabulary.OUTCOME_FALLBACK.equals(policy.outcome())) {
                    recoveredByPolicy = true;
                } else if (FaultToleranceVocabulary.OUTCOME_RETRY.equals(policy.outcome())) {
                    retried = true;
                } else if (retried && FaultToleranceVocabulary.OUTCOME_SUCCESS.equals(policy.outcome())) {
                    recoveredByPolicy = true;
                }
            }
        }
        if (evidence.isEmpty()) {
            return null;
        }
        boolean recovered = recoveredByPolicy && !evidence.containsKey(Evidence.ROLLED_BACK);
        return new Behind(request, evidence, recovered);
    }

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
                List.of("Only work that carries the request's id is counted; work handed to another thread without"
                        + " BootUI's correlation is not."));
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
