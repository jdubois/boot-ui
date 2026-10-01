package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.journal.TransactionPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The transactions of one request, as monotonic intervals per thread, which place each of its statements inside or
 * outside them ({@code docs/PLAN-v2.md} §5.5). A transaction or statement without a monotonic time cannot be placed.
 */
final class TransactionWindows {

    /** One transaction of the request. */
    record Window(RuntimeEvent event, TransactionPayload transaction, long startNanos, long endNanos) {

        boolean contains(RuntimeEvent statement, long completedNanos) {
            return completedNanos >= startNanos
                    && completedNanos <= endNanos
                    && (event.thread() == null
                            || statement.thread() == null
                            || Objects.equals(event.thread(), statement.thread()));
        }

        long span() {
            return endNanos - startNanos;
        }
    }

    private final List<Window> windows = new ArrayList<>();
    private final boolean complete;

    TransactionWindows(ProjectedRequest request) {
        boolean placed = true;
        for (RuntimeEvent event : request.children(JournalSource.TRANSACTION)) {
            if (event.payload() instanceof TransactionPayload transaction) {
                if (transaction.startNanos() < 0 || event.durationNanos() < 0) {
                    placed = false;
                    continue;
                }
                windows.add(new Window(
                        event,
                        transaction,
                        transaction.startNanos(),
                        transaction.startNanos() + event.durationNanos()));
            }
        }
        this.complete = placed;
    }

    /** Whether every transaction of the request has a monotonic interval. */
    boolean complete() {
        return complete;
    }

    /** Whether {@code statement} can be placed: it has a monotonic completion and every transaction an interval. */
    boolean canPlace(RuntimeEvent statement) {
        return complete && statement.payload() instanceof SqlPayload sql && sql.completedNanos() >= 0;
    }

    /** The innermost transaction containing {@code statement}, or {@code null} when it ran outside all of them. */
    Window innermost(RuntimeEvent statement) {
        return innermost(statement, false);
    }

    /**
     * The innermost physical transaction, not a savepoint, that contains {@code statement}, the one that commits it, or
     * {@code null} when it ran outside all of them.
     */
    Window committing(RuntimeEvent statement) {
        return innermost(statement, true);
    }

    private Window innermost(RuntimeEvent statement, boolean independentOnly) {
        long completed = ((SqlPayload) statement.payload()).completedNanos();
        Window found = null;
        for (Window window : windows) {
            if ((!independentOnly || window.transaction().independent())
                    && window.contains(statement, completed)
                    && (found == null || window.span() < found.span())) {
                found = window;
            }
        }
        return found;
    }
}
