package io.github.jdubois.bootui.engine.journal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** Retained evidence visible under one snapshot of the source-panel policy. */
public final class VisibleJournalEntries {

    private VisibleJournalEntries() {}

    public static List<JournalEntry> of(List<JournalEntry> entries, Predicate<RuntimeEvent> visible) {
        Set<String> hidden = new HashSet<>();
        for (JournalEntry entry : entries) {
            RuntimeEvent event = entry.event();
            if (anchorsExecution(event) && !visible.test(event)) {
                hidden.add(unitOf(event));
            }
        }
        List<JournalEntry> result = new ArrayList<>(entries.size());
        for (JournalEntry entry : entries) {
            RuntimeEvent event = entry.event();
            if (!visible.test(event)) {
                continue;
            }
            String unit = unitOf(event);
            if (unit == null || !hidden.contains(unit)) {
                result.add(entry);
            }
        }
        return result;
    }

    private static boolean anchorsExecution(RuntimeEvent event) {
        return (event.source() == JournalSource.HTTP && event.requestId() != null)
                || event.payload() instanceof ScheduledPayload
                || (event.payload() instanceof MessagingPayload message && !message.sent())
                || (event.payload() instanceof WebSocketPayload webSocket && webSocket.opensExecution());
    }

    private static String unitOf(RuntimeEvent event) {
        if (event.requestId() != null) {
            return "request:" + event.requestId();
        }
        return event.executionId() == null ? null : "execution:" + event.executionId();
    }
}
