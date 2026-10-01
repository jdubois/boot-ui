package io.github.jdubois.bootui.engine.model;

import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalEntry;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeEventPayload;
import io.github.jdubois.bootui.engine.journal.ScheduledPayload;
import java.util.ArrayList;
import java.util.List;

/** Builds journal entries for runtime model fixtures, children first as recorders publish them. */
final class JournalFixture {

    private final List<JournalEntry> entries = new ArrayList<>();
    private long sequence;
    private int requests;

    /** A completed request of {@code method template}, with its children. */
    String request(String method, String template, Child... children) {
        return request(method, template, null, null, children);
    }

    String request(String method, String template, String operation, String traceId, Child... children) {
        String requestId = "r" + (++requests);
        for (Child child : children) {
            add(new RuntimeEvent(
                    child.source(),
                    child.at(),
                    1,
                    requestId,
                    null,
                    null,
                    null,
                    "http-1",
                    null,
                    false,
                    child.payload()));
        }
        add(new RuntimeEvent(
                JournalSource.HTTP,
                1_000L * requests,
                1,
                requestId,
                null,
                traceId,
                null,
                "http-1",
                null,
                false,
                new HttpPayload(method, template.replace("{id}", "42"), template, operation, 200)));
        return requestId;
    }

    /** A scheduled run of {@code task}, with its children. */
    void job(String task, Child... children) {
        String executionId = "e" + (++requests);
        for (Child child : children) {
            add(new RuntimeEvent(
                    child.source(),
                    child.at(),
                    1,
                    null,
                    executionId,
                    null,
                    null,
                    "sched-1",
                    null,
                    false,
                    child.payload()));
        }
        add(new RuntimeEvent(
                JournalSource.SCHEDULED,
                1_000L * requests,
                1,
                null,
                executionId,
                null,
                null,
                "sched-1",
                null,
                false,
                new ScheduledPayload(task, null)));
    }

    /** An event of {@code source} owned by no request or execution, or by the given ids. */
    void event(
            JournalSource source, String requestId, String executionId, String traceId, RuntimeEventPayload payload) {
        add(new RuntimeEvent(source, 5_000, 1, requestId, executionId, traceId, null, "t", null, false, payload));
    }

    List<JournalEntry> entries() {
        return entries;
    }

    private void add(RuntimeEvent event) {
        entries.add(new JournalEntry(++sequence, event, event.estimatedBytes()));
    }

    static Child child(JournalSource source, RuntimeEventPayload payload) {
        return new Child(source, 2_000, payload);
    }

    static Child child(JournalSource source, long at, RuntimeEventPayload payload) {
        return new Child(source, at, payload);
    }

    record Child(JournalSource source, long at, RuntimeEventPayload payload) {}
}
