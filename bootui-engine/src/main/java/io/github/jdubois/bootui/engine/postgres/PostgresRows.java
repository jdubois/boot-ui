package io.github.jdubois.bootui.engine.postgres;

import java.util.List;

/**
 * The outcome of one bounded statistics query: the rows it retained, whether a bound cut it short, and — when
 * it could not run at all — the redacted reason.
 *
 * <p>A failed read is never an empty successful read. That distinction is the whole point: "the role cannot
 * see this view" must not render as "this subsystem is clean".</p>
 */
record PostgresRows<T>(boolean available, List<T> rows, boolean truncated, String reason) {

    static <T> PostgresRows<T> available(List<T> rows, boolean truncated) {
        return new PostgresRows<>(true, List.copyOf(rows), truncated, null);
    }

    static <T> PostgresRows<T> failed(String reason) {
        return new PostgresRows<>(false, List.of(), false, reason);
    }

    boolean empty() {
        return rows.isEmpty();
    }
}
