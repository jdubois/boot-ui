package io.github.jdubois.bootui.engine.codepaths;

import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalEntry;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.sqltrace.RouteLabel;
import io.github.jdubois.bootui.engine.sqltrace.RouteTemplateResolver;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Names the route and outcome of requests whose call trees settle ({@code docs/PLAN-v2.md} §5.14), from each request's
 * own {@code http} event in the runtime journal, as Live Activity and Runtime Insights name them. Reads only the retained
 * events, and stops once every request asked for is named: a request already evicted, or without an exchange, is
 * {@link RequestOutcome#UNKNOWN}.
 */
public final class JournalRequestOutcomes {

    private JournalRequestOutcomes() {}

    /**
     * @param journal the runtime journal, or {@code null}
     * @param declared the application's declared routes, which name a request's route when the framework recorded no
     *     template, or {@code null}
     */
    public static Function<Set<String>, Map<String, RequestOutcome>> of(
            RuntimeJournal journal, Supplier<RouteTemplateResolver> declared) {
        return requestIds -> {
            Map<String, RequestOutcome> outcomes = new HashMap<>();
            if (journal == null || requestIds == null || requestIds.isEmpty()) {
                return outcomes;
            }
            RouteTemplateResolver resolver;
            try {
                resolver = declared == null ? null : declared.get();
            } catch (RuntimeException ex) {
                resolver = null;
            }
            RouteTemplateResolver routes = resolver == null ? RouteTemplateResolver.empty() : resolver;
            for (JournalEntry entry : journal.entries()) {
                if (outcomes.size() == requestIds.size()) {
                    break;
                }
                RuntimeEvent event = entry.event();
                if (event.source() == JournalSource.HTTP
                        && event.requestId() != null
                        && requestIds.contains(event.requestId())
                        && event.payload() instanceof HttpPayload http) {
                    String route = RouteLabel.of(
                                    http.method(), http.path(), http.routeTemplate(), http.operation(), routes)
                            .id();
                    outcomes.put(event.requestId(), new RequestOutcome(route, http.status(), http.status() >= 500));
                }
            }
            return outcomes;
        };
    }
}
