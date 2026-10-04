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

    /**
     * How much later than the response a handoff's work must read to have surely ended after it. Events start at
     * millisecond precision: the recovered response start can read up to a millisecond early, and a child's end, its
     * start derived from its whole-millisecond duration, up to a millisecond early or late.
     */
    private static final long TIMESTAMP_SLACK_MICROS = 2_000L;

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
                    if (!afterResponse(request, event, handoff)) {
                        continue;
                    }
                    Work work = work(request, event, handoff, responseStartMicros(request, event, handoff));
                    if (work.empty() && !handoff.failed()) {
                        continue;
                    }
                    affected = true;
                    if (handoff.failed()) {
                        failed++;
                    }
                    rows.add(List.of(
                            request.requestId(),
                            InsightText.simpleName(handoff.taskClass()),
                            work.describe(),
                            handoff.failed()
                                    ? "failed: " + InsightText.simpleName(handoff.exceptionClass())
                                    : handoff.capped() ? "still running at the handoff deadline" : "completed",
                            millis(afterResponseMicros(request, event, handoff)) + " ms"));
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
     * From when the handoff's work counts as after the response: the response's start, recovered from the handoff's
     * time after it, or, when that is unknown, the request's end.
     */
    private static long responseStartMicros(ProjectedRequest request, RuntimeEvent event, AsyncHandoffPayload handoff) {
        if (handoff.afterResponse() != null && handoff.afterResponseMicros() != null) {
            return endMicros(event) - handoff.afterResponseMicros();
        }
        return Math.max(event.epochMillis() * 1_000L, requestEndMicros(request));
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
     * What the handoff recorded under its execution id within max-handoff of its start and that ended after
     * {@code afterMicros} by at least {@link #TIMESTAMP_SLACK_MICROS}. A task the handler waited for releases the
     * handler before its handoff closes, so the handoff can end just after the response while all of its work ended
     * before: only the work itself is timed, and work ending within the timestamps' precision of the response cannot be
     * told from work that ended before it.
     */
    private Work work(ProjectedRequest request, RuntimeEvent event, AsyncHandoffPayload handoff, long afterMicros) {
        int sql = 0;
        int rest = 0;
        int messages = 0;
        for (RuntimeEvent child : request.children()) {
            if (child == event
                    || handoff.executionId() == null
                    || !handoff.executionId().equals(child.executionId())
                    || !HandoffWindow.attributed(event.epochMillis(), child.epochMillis(), maxHandoffMillis)
                    || endMicros(child) < afterMicros + TIMESTAMP_SLACK_MICROS) {
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
