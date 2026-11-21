package io.github.jdubois.bootui.engine.journal;

import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Supplies immutable exception snapshots from the exceptions recorder without persisting any message, throwable, or
 * in-memory identity mark.
 */
public final class ExceptionStoreDurableJournalDetailProvider implements DurableJournalDetailProvider {

    private final ExceptionStore store;

    public ExceptionStoreDurableJournalDetailProvider(ExceptionStore store) {
        this.store = Objects.requireNonNull(store, "store must not be null");
    }

    @Override
    public List<DurableJournalDetail> details(DurableJournalScope scope, List<JournalEntry> entries) {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(entries, "entries must not be null");
        List<DurableJournalDetail> details = new ArrayList<>();
        for (JournalEntry entry : entries) {
            if (entry == null
                    || entry.event() == null
                    || !(entry.event().payload() instanceof ExceptionPayload exception)
                    || exception.groupId() == null) {
                continue;
            }
            ExceptionStore.GroupDetail group = store.find(exception.groupId());
            if (group == null) {
                continue;
            }
            details.add(DurableJournalDetail.exception(
                    scope,
                    entry.sequence(),
                    exception.groupId(),
                    bounded(group.summary().exceptionClassName()),
                    bounded(exception.signature()),
                    boundedItems(exception.types()),
                    applicationFrames(group),
                    causes(group),
                    1));
        }
        return List.copyOf(details);
    }

    private static List<String> applicationFrames(ExceptionStore.GroupDetail group) {
        return boundedItems(group.frames().stream()
                .filter(ExceptionStore.Frame::applicationFrame)
                .map(ExceptionStoreDurableJournalDetailProvider::frame)
                .toList());
    }

    private static List<String> causes(ExceptionStore.GroupDetail group) {
        return boundedItems(group.causes().stream()
                .map(ExceptionStore.Cause::exceptionClassName)
                .toList());
    }

    private static String frame(ExceptionStore.Frame frame) {
        String file = frame.fileName();
        String position = file == null ? "Unknown Source" : file;
        if (frame.lineNumber() >= 0) {
            position += ":" + frame.lineNumber();
        }
        return bounded(frame.declaringClass() + "." + frame.methodName() + "(" + position + ")");
    }

    private static List<String> boundedItems(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return List.copyOf(values.stream()
                .filter(Objects::nonNull)
                .limit(DurableJournalDetail.MAX_ITEMS)
                .map(ExceptionStoreDurableJournalDetailProvider::bounded)
                .toList());
    }

    private static String bounded(String value) {
        if (value == null || value.length() <= DurableJournalDetail.MAX_TEXT_LENGTH) {
            return value;
        }
        return value.substring(0, DurableJournalDetail.MAX_TEXT_LENGTH);
    }
}
