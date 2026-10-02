package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.engine.journal.AppEventPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.web.CorrelationTier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code transactional-listener-skipped} ({@code docs/PLAN-v2.md} §5.18, M4-8): a transactional event listener that
 * never ran because its event was published while no transaction was active, per route and listener. Spring skips such
 * a listener silently unless it sets {@code fallbackExecution}. One skipped event is enough.
 */
public final class TransactionalListenerSkipped implements Observation {

    public static final String KIND = "transactional-listener-skipped";

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String title() {
        return "Transactional listeners skipped";
    }

    @Override
    public CorrelationTier minimumTier() {
        return CorrelationTier.REQUEST_ID;
    }

    @Override
    public Set<JournalSource> reads() {
        return Set.of(JournalSource.APP_EVENT);
    }

    @Override
    public String notApplicable(InsightsSnapshot snapshot) {
        return snapshot.stack() == InsightsStack.QUARKUS
                ? "Quarkus: CDI notifies a transactional observer at once when no transaction is active, so none is"
                        + " skipped."
                : null;
    }

    @Override
    public Evaluation evaluate(InsightsSnapshot snapshot) {
        List<Finding> findings = new ArrayList<>();
        long eligible = 0;
        for (Map.Entry<String, List<ProjectedRequest>> route :
                snapshot.byRoute().entrySet()) {
            eligible += route.getValue().size();
            Map<String, Skipped> byListener = new LinkedHashMap<>();
            for (ProjectedRequest request : route.getValue()) {
                for (RuntimeEvent event : request.children(JournalSource.APP_EVENT)) {
                    if (event.payload() instanceof AppEventPayload listener
                            && AppEventPayload.SKIPPED_NO_TRANSACTION.equals(listener.outcome())) {
                        byListener
                                .computeIfAbsent(
                                        listener.listener() == null ? "listener" : listener.listener(),
                                        name -> new Skipped())
                                .add(request, listener);
                    }
                }
            }
            byListener.forEach((listener, skipped) -> findings.add(
                    finding(route.getKey(), listener, skipped, route.getValue().size())));
        }
        return new Evaluation(eligible, findings);
    }

    private Finding finding(String route, String listener, Skipped skipped, long eligible) {
        String event = InsightText.simpleName(skipped.eventType);
        return new Finding(
                route + ":" + InsightText.stableHash(listener),
                route,
                true,
                "`" + route + "` published `" + event + "` with no transaction active in " + skipped.requests.size()
                        + " of " + InsightText.counted(eligible, InsightText.unit(route)) + ", so its transactional"
                        + " listener `" + listener + "` (" + skipped.phase + ") never ran.",
                eligible,
                skipped.requests.size(),
                List.of(
                        "If the listener must run, publish `" + event + "` inside the transaction whose outcome it"
                                + " waits for.",
                        "If it may run without one, set `fallbackExecution = true` on its `@TransactionalEventListener`."),
                skipped.requests.stream().limit(3).toList(),
                List.of("Request", "Event", "Listener", "Phase"),
                skipped.rows,
                List.of("Counts listeners Spring skipped for lack of a transaction; a listener whose event is never"
                        + " published is not seen."));
    }

    private static final class Skipped {

        private final Set<String> requests = new LinkedHashSet<>();
        private final List<List<String>> rows = new ArrayList<>();
        private String eventType;
        private String phase;

        void add(ProjectedRequest request, AppEventPayload listener) {
            requests.add(request.requestId());
            eventType = listener.eventType();
            phase = listener.phase() == null ? "AFTER_COMMIT" : listener.phase();
            rows.add(List.of(
                    request.requestId(),
                    InsightText.simpleName(listener.eventType()),
                    listener.listener() == null ? "" : listener.listener(),
                    phase));
        }
    }
}
