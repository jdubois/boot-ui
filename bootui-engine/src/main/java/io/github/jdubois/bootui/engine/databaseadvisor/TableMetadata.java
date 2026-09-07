package io.github.jdubois.bootui.engine.databaseadvisor;

import java.util.List;

/**
 * How completely one table's metadata could be read, so a rule can tell "this table genuinely has no
 * primary key" apart from "the driver refused to answer {@code getPrimaryKeys()} for this table".
 *
 * <p>A rule that needs a part which could not be read must skip that table (and the scan reports the
 * failure as a diagnostic) instead of counting the gap as a clean result.</p>
 *
 * @param columnsRead whether the scoped column family was completely and unambiguously read
 * @param primaryKeyRead whether the primary-key family was completely and unambiguously read
 * @param foreignKeysRead whether the foreign-key family was completely and unambiguously read
 * @param indexesRead whether the index family was completely and unambiguously read
 * @param truncated whether any per-table row bound or cooperative deadline cut metadata short
 * @param issues human-readable, already-sanitized reasons for every gap above
 * @param primaryKeyEnforced confirmed primary-key enforcement/validation, or null when JDBC cannot establish it
 */
record TableMetadata(
        boolean columnsRead,
        boolean primaryKeyRead,
        boolean foreignKeysRead,
        boolean indexesRead,
        boolean truncated,
        List<String> issues,
        Boolean primaryKeyEnforced) {

    TableMetadata(
            boolean columnsRead,
            boolean primaryKeyRead,
            boolean foreignKeysRead,
            boolean indexesRead,
            boolean truncated,
            List<String> issues) {
        this(columnsRead, primaryKeyRead, foreignKeysRead, indexesRead, truncated, issues, null);
    }

    static final TableMetadata COMPLETE = new TableMetadata(true, true, true, true, false, List.of());

    TableMetadata {
        issues = List.copyOf(issues);
    }

    boolean complete() {
        return columnsRead && primaryKeyRead && foreignKeysRead && indexesRead && !truncated;
    }

    TableMetadata withPrimaryKeyEnforced(Boolean enforced) {
        return new TableMetadata(
                columnsRead, primaryKeyRead, foreignKeysRead, indexesRead, truncated, issues, enforced);
    }
}
