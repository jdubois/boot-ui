package io.github.jdubois.bootui.engine.sideeffects;

import io.github.jdubois.bootui.engine.journal.JournalEntry;
import io.github.jdubois.bootui.engine.journal.MessagingPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.ScheduledPayload;
import io.github.jdubois.bootui.engine.journal.WebSocketPayload;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Names an execution no request owns by its id, as the runtime journal records it ({@code docs/PLAN-v2.md} §5.16): a
 * scheduled run by its task ({@code scheduled OrderJob.report}), a consumed message by its broker and destination
 * ({@code consumed kafka orders}), a WebSocket message by its endpoint ({@code websocket /ws}), and any other by its
 * journal source. Reads only the retained events, and stops once every execution asked for is named.
 */
public final class JournalExecutions {

    private JournalExecutions() {}

    /** The labels of the retained executions among those asked for; {@code journal} may be {@code null}. */
    public static Function<Set<String>, Map<String, String>> of(RuntimeJournal journal) {
        return executionIds -> {
            Map<String, String> labels = new HashMap<>();
            if (journal == null || executionIds == null || executionIds.isEmpty()) {
                return labels;
            }
            Map<String, String> fallbacks = new HashMap<>();
            for (JournalEntry entry : journal.entries()) {
                if (labels.size() == executionIds.size()) {
                    break;
                }
                RuntimeEvent event = entry.event();
                String id = event.executionId();
                if (id == null || event.requestId() != null || !executionIds.contains(id) || labels.containsKey(id)) {
                    continue;
                }
                String label = label(event);
                if (label != null) {
                    labels.put(id, label);
                } else {
                    fallbacks.putIfAbsent(id, event.source().propertyName());
                }
            }
            for (Map.Entry<String, String> fallback : fallbacks.entrySet()) {
                labels.putIfAbsent(fallback.getKey(), fallback.getValue());
            }
            return labels;
        };
    }

    /** What started the execution, from its event, or {@code null} when the event does not say. */
    static String label(RuntimeEvent event) {
        if (event.payload() instanceof ScheduledPayload scheduled && scheduled.task() != null) {
            return "scheduled " + scheduled.task();
        }
        if (event.payload() instanceof MessagingPayload message && !message.sent()) {
            return "consumed " + (message.broker() == null ? "message" : message.broker())
                    + (message.destination() == null ? "" : " " + message.destination());
        }
        if (event.payload() instanceof WebSocketPayload socket && socket.endpoint() != null) {
            return "websocket " + socket.endpoint();
        }
        return null;
    }
}
