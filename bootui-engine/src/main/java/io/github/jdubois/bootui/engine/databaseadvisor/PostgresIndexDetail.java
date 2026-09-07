package io.github.jdubois.bootui.engine.databaseadvisor;

import java.util.List;

/**
 * The {@code pg_index}/{@code pg_am} facts JDBC's {@code getIndexInfo} cannot report on PostgreSQL: whether
 * the index is valid, whether it is partial ({@code indpred}), whether it has expression key parts
 * ({@code indexprs}), which access method backs it, how many of its key parts are genuine key columns rather
 * than {@code INCLUDE} (non-key, covering) columns, and whether it treats {@code NULL} as distinct. Merged
 * onto {@link IndexModel} so the index rules can tell a usable index from one that only looks usable.
 *
 * @param keyColumnCount {@code pg_index.indnkeyatts} (PostgreSQL 11+), the number of leading key parts that
 *     are genuine key columns — the rest, if any, are {@code INCLUDE} columns pgjdbc's {@code getIndexInfo}
 *     cannot distinguish from real key parts, so they are trimmed from {@link IndexModel#keyParts()} during
 *     merge. {@code null} on PostgreSQL 10 and earlier, where every key part is a genuine key column.
 * @param nullsNotDistinct {@code pg_index.indnullsnotdistinct} (PostgreSQL 15+): {@code true} when a unique
 *     index was declared {@code NULLS NOT DISTINCT} and therefore rejects more than one {@code NULL}, as
 *     opposed to the default where {@code NULL} is never considered equal to another {@code NULL}.
 */
record PostgresIndexDetail(
        String schema,
        String table,
        String index,
        boolean valid,
        boolean partial,
        String predicate,
        boolean expression,
        String method,
        Integer keyColumnCount,
        boolean nullsNotDistinct,
        Boolean ready,
        Boolean live,
        Boolean unique,
        Boolean primary,
        String constraintName,
        String constraintType,
        List<IndexKeyPart> keyParts,
        List<String> includedColumns,
        List<String> comparisonSemantics,
        boolean definitionComplete) {

    PostgresIndexDetail {
        keyParts = List.copyOf(keyParts);
        includedColumns = List.copyOf(includedColumns);
        comparisonSemantics = List.copyOf(comparisonSemantics);
    }

    PostgresIndexDetail(
            String schema,
            String table,
            String index,
            boolean valid,
            boolean partial,
            String predicate,
            boolean expression,
            String method,
            Integer keyColumnCount,
            boolean nullsNotDistinct) {
        this(
                schema,
                table,
                index,
                valid,
                partial,
                predicate,
                expression,
                method,
                keyColumnCount,
                nullsNotDistinct,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                false);
    }
}
