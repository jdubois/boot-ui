package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.engine.correlation.HandoffWindow;
import io.github.jdubois.bootui.engine.journal.AsyncHandoffPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.MessagingPayload;
import io.github.jdubois.bootui.engine.journal.RestClientPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.web.CorrelationTier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * {@code work-after-response} ({@code docs/PLAN-v2.md} §5.17): work a request handed to a JDK executor that was still
 * running once its response started, and that ran SQL, called a REST service, sent or received a message, or failed.
 * The caller saw a complete response while the work went on, so a failure after it reached nobody. Needs the BootUI
 * agent, which propagates the request's context into the executor; a task that did nothing BootUI records, such as a
 * library's own housekeeping, is never counted. Reported from one request.
 */
public final class WorkAfterResponse implements Observation {

    public static final String KIND = "work-after-response";

    static final int MIN_REQUESTS = 1;

    static final String REQUIRES_AGENT = "This observation requires the BootUI agent's executors sensor, which"
            + " propagates the work a request hands to an executor as its own: start the application with"
            + " -javaagent:bootui-agent.jar (see the Java Agent panel).";

    private volatile Supplier<String> propagationUnavailable = () -> REQUIRES_AGENT;
    private volatile long maxHandoffMillis = HandoffWindow.DEFAULT_MAX_HANDOFF_MILLIS;

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String title() {
        return "Work after the response";
    }

    @Override
    public CorrelationTier minimumTier() {
        return CorrelationTier.PROPAGATED;
    }

    @Override
    public Set<JournalSource> reads() {
        return Set.of(JournalSource.HTTP, JournalSource.AGENT_EXECUTORS);
    }

    @Override
    public Set<ProjectedRequest.Kind> unitKinds() {
        return Set.of(ProjectedRequest.Kind.HTTP);
    }

    @Override
    public Set<JournalSource> optionalReads() {
        return Set.of(JournalSource.SQL, JournalSource.REST_CLIENT, JournalSource.MESSAGING);
    }

    /**
     * Installs why the BootUI agent does not propagate executor work for this application, {@code null} when it does
     * (attached, armed, its {@code executors} sensor installed and enabled, and the handoffs attached to the claim), and
     * {@code bootui.agent.executors.max-handoff} ({@link HandoffWindow}).
     */
    void setAgent(Supplier<String> propagationUnavailable, Duration maxHandoff) {
        this.propagationUnavailable = propagationUnavailable == null ? () -> REQUIRES_AGENT : propagationUnavailable;
        this.maxHandoffMillis = HandoffWindow.millis(maxHandoff);
    }

    @Override
    public String notApplicable(InsightsSnapshot snapshot) {
        String reason;
        try {
            reason = propagationUnavailable.get();
        } catch (RuntimeException ex) {
            reason = REQUIRES_AGENT;
        }
        if (reason == null) {
            return null;
        }
        // The agent's reasons read "Requires the BootUI agent's executors sensor: …".
        return reason.startsWith("Requires ") ? "This observation r" + reason.substring(1) : reason;
    }

    @Override
    public Evaluation evaluate(InsightsSnapshot snapshot) {
        List<Finding> findings = new ArrayList<>();
        long eligible = 0;
        for (Map.Entry<String, List<ProjectedRequest>> route :
                snapshot.httpByRoute().entrySet()) {
            List<ProjectedRequest> requests = route.getValue();
            eligible += requests.size();
            List<List<String>> rows = new ArrayList<>();
            List<String> exemplars = new ArrayList<>();
            int failed = 0;
            for (ProjectedRequest request : requests) {
                boolean affected = false;
                for (RuntimeEvent event : request.children(JournalSource.AGENT_EXECUTORS)) {
                    // A handoff starting past max-handoff after its request ended no longer belongs to it.
                    if (!(event.payload() instanceof AsyncHandoffPayload handoff)
                            || !HandoffWindow.attaches(
                                    requestEndMillis(request), event.epochMillis(), maxHandoffMillis)) {
                        continue;
                    }
                    if (!Boolean.TRUE.equals(handoff.bodyAfterResponse())
                            && !Boolean.TRUE.equals(handoff.failureAfterResponse())
                            && !afterResponse(request, event, handoff)) {
                        continue;
                    }
                    Work work = work(request, event, handoff);
                    boolean failedAfterResponse = handoff.failed()
                            && (handoff.failureAfterResponse() != null
                                    ? handoff.failureAfterResponse()
                                    : !Boolean.FALSE.equals(handoff.bodyAfterResponse()));
                    if (work.empty() && !failedAfterResponse) {
                        continue;
                    }
                    affected = true;
                    if (failedAfterResponse) {
                        failed++;
                    }
                    rows.add(List.of(
                            request.requestId(),
                            InsightText.simpleName(handoff.taskClass()),
                            work.describe(),
                            handoff.failed()
                                    ? (failedAfterResponse ? "failed: " : "failed before response: ")
                                            + InsightText.simpleName(handoff.exceptionClass())
                                    : handoff.capped() ? "still running at the handoff deadline" : "completed",
                            millis(
                                            Boolean.TRUE.equals(handoff.bodyAfterResponse())
                                                            && handoff.bodyAfterResponseMicros() != null
                                                    ? handoff.bodyAfterResponseMicros()
                                                    : afterResponseMicros(request, event, handoff))
                                    + " ms"));
                }
                if (affected) {
                    exemplars.add(request.requestId());
                }
            }
            if (!rows.isEmpty()) {
                findings.add(finding(route.getKey(), requests.size(), exemplars, rows, failed));
            }
        }
        return new Evaluation(eligible, findings);
    }

    private Finding finding(String route, long eligible, List<String> exemplars, List<List<String>> rows, int failed) {
        boolean sufficient = exemplars.size() >= MIN_REQUESTS;
        String sentence = "`" + route + "` handed work to an executor that was still running after the response in "
                + exemplars.size() + " of " + InsightText.counted(eligible, "request")
                + (failed > 0 ? ", and " + InsightText.counted(failed, "task") + " failed after the response" : "")
                + ".";
        return new Finding(
                route,
                route,
                sufficient,
                sentence,
                eligible,
                exemplars.size(),
                List.of(
                        "If the caller relies on this work, wait for it before answering, or answer 202 Accepted and"
                                + " report its outcome another way.",
                        "If it may run later, hand it to a managed executor or a queue that retries, so a failure after"
                                + " the response is not lost."),
                exemplars.stream().limit(3).toList(),
                List.of("Request", "Task", "What it did", "Outcome", "After the response"),
                rows,
                List.of("Only work the BootUI agent propagated through a JDK executor is seen; parallel-stream subtasks"
                        + " run by other workers are not."));
    }

    /** Whether the handoff still ran once the response started, or, when that is unknown, after the request ended. */
    private static boolean afterResponse(ProjectedRequest request, RuntimeEvent event, AsyncHandoffPayload handoff) {
        if (handoff.afterResponse() != null) {
            return handoff.afterResponse();
        }
        return endMicros(event) > requestEndMicros(request);
    }

    private static long afterResponseMicros(ProjectedRequest request, RuntimeEvent event, AsyncHandoffPayload handoff) {
        if (handoff.afterResponse() != null && handoff.afterResponseMicros() != null) {
            return handoff.afterResponseMicros();
        }
        return Math.max(0, endMicros(event) - Math.max(event.epochMillis() * 1_000L, requestEndMicros(request)));
    }

    /**
     * The actual response boundary, or the request's end when it is unknown. Never recover it by subtracting the
     * handoff's time after the response: for a late-starting task that subtraction gives the task's start instead.
     */
    private static long responseStartMicros(ProjectedRequest request, RuntimeEvent event, AsyncHandoffPayload handoff) {
        if (handoff.responseAtMicros() != null) {
            return handoff.responseAtMicros();
        }
        return requestEndMicros(request);
    }

    private static long endMicros(RuntimeEvent event) {
        return event.epochMillis() * 1_000L + Math.max(0, event.durationNanos()) / 1_000L;
    }

    private static long requestEndMillis(ProjectedRequest request) {
        return request.startMillis() + request.durationNanos() / 1_000_000L;
    }

    private static long requestEndMicros(ProjectedRequest request) {
        return request.startMillis() * 1_000L + request.durationNanos() / 1_000L;
    }

    /**
     * All attributed I/O is evidence when the task body ended after the response, including earlier writes followed
     * by computation. Otherwise only late I/O counts: a waited-for task can close its handoff late, and synchronous
     * dependent stages or FutureTask.done() can perform genuine work after publishing the result.
     */
    private Work work(ProjectedRequest request, RuntimeEvent event, AsyncHandoffPayload handoff) {
        int sql = 0;
        int rest = 0;
        int messages = 0;
        for (RuntimeEvent child : request.children()) {
            if (child == event
                    || handoff.executionId() == null
                    || !handoff.executionId().equals(child.executionId())
                    || !HandoffWindow.attributed(event.epochMillis(), child.epochMillis(), maxHandoffMillis)
                    || (!Boolean.TRUE.equals(handoff.bodyAfterResponse())
                            && endMicros(child)
                                    < responseStartMicros(request, event, handoff)
                                            + HandoffWindow.RESPONSE_TIMESTAMP_SLACK_MICROS)) {
                continue;
            }
            if (child.payload() instanceof SqlPayload) {
                sql++;
            } else if (child.payload() instanceof RestClientPayload) {
                rest++;
            } else if (child.payload() instanceof MessagingPayload) {
                messages++;
            }
        }
        return new Work(sql, rest, messages);
    }

    private static long millis(long micros) {
        return micros / 1_000L;
    }

    private record Work(int sql, int rest, int messages) {

        boolean empty() {
            return sql == 0 && rest == 0 && messages == 0;
        }

        String describe() {
            List<String> parts = new ArrayList<>(3);
            if (sql > 0) {
                parts.add(InsightText.counted(sql, "SQL statement"));
            }
            if (rest > 0) {
                parts.add(InsightText.counted(rest, "REST call"));
            }
            if (messages > 0) {
                parts.add(InsightText.counted(messages, "message"));
            }
            return parts.isEmpty() ? "nothing recorded" : String.join(", ", parts);
        }
    }
}
