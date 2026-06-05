package io.github.jdubois.bootui.autoconfigure.copilotfix;

import io.github.jdubois.bootui.core.dto.CopilotFixEventDto;
import io.github.jdubois.bootui.core.dto.CopilotFixRunDto;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Thread-safe, in-memory state for a single "Fix it with Copilot" run.
 *
 * <p>Holds the ordered progress events and the terminal outcome. Never holds the GitHub token or
 * any other secret. Event history is bounded; once the cap is reached the oldest events are
 * dropped so a chatty agent cannot exhaust memory.
 */
final class CopilotFixRun {

    private final String id;
    private final String findingId;
    private final long startedAt;
    private final int maxEvents;

    private final List<CopilotFixEventDto> events = new ArrayList<>();
    private final List<Consumer<CopilotFixEventDto>> subscribers = new ArrayList<>();
    private long sequence;

    private volatile String status = "PENDING";
    private volatile String branch;
    private volatile String message = "Queued";
    private volatile String diff;
    private volatile int filesChanged;
    private volatile Long finishedAt;

    CopilotFixRun(String id, String findingId, long startedAt, int maxEvents) {
        this.id = id;
        this.findingId = findingId;
        this.startedAt = startedAt;
        this.maxEvents = Math.max(1, maxEvents);
    }

    String id() {
        return id;
    }

    synchronized void setStatus(String status, String message) {
        this.status = status;
        this.message = message;
    }

    synchronized void setBranch(String branch) {
        this.branch = branch;
    }

    synchronized void setDiff(String diff, int filesChanged) {
        this.diff = diff;
        this.filesChanged = filesChanged;
    }

    synchronized void finish(String status, String message) {
        this.status = status;
        this.message = message;
        this.finishedAt = System.currentTimeMillis();
    }

    /** Appends an event and fans it out to current subscribers. */
    synchronized void addEvent(String type, String message) {
        CopilotFixEventDto event = new CopilotFixEventDto(sequence++, System.currentTimeMillis(), type, message);
        events.add(event);
        while (events.size() > maxEvents) {
            events.remove(0);
        }
        for (Consumer<CopilotFixEventDto> subscriber : List.copyOf(subscribers)) {
            try {
                subscriber.accept(event);
            } catch (RuntimeException ignored) {
                // A failing subscriber must not break the run or other subscribers.
            }
        }
    }

    /** Registers a subscriber and replays the events seen so far; returns an unsubscribe handle. */
    synchronized Runnable subscribe(Consumer<CopilotFixEventDto> subscriber) {
        for (CopilotFixEventDto event : events) {
            subscriber.accept(event);
        }
        subscribers.add(subscriber);
        return () -> {
            synchronized (CopilotFixRun.this) {
                subscribers.remove(subscriber);
            }
        };
    }

    synchronized boolean isFinished() {
        return finishedAt != null;
    }

    synchronized CopilotFixRunDto snapshot() {
        return new CopilotFixRunDto(
                id, findingId, status, branch, message, diff, filesChanged, List.copyOf(events), startedAt, finishedAt);
    }
}
