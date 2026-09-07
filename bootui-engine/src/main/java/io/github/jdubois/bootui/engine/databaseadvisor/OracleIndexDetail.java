package io.github.jdubois.bootui.engine.databaseadvisor;

/**
 * One Oracle index's {@code all_indexes} attributes that JDBC's {@code getIndexInfo} cannot report: whether
 * it is usable, visible to the optimizer, has a generated name, and whether its
 * per-partition status must be checked separately because the index itself is partitioned.
 *
 * @param indexType {@code all_indexes.index_type} (e.g. {@code NORMAL}, {@code BITMAP},
 *     {@code FUNCTION-BASED NORMAL}, {@code DOMAIN}, {@code IOT - TOP}, {@code LOB})
 * @param unique {@code all_indexes.uniqueness = 'UNIQUE'}
 * @param status {@code all_indexes.status} ({@code VALID}, {@code UNUSABLE}, or {@code N/A} when
 *     {@link #partitioned()} — a partitioned index's real status lives on its partitions/subpartitions
 *     instead, in {@link OracleIndexPartitionStatus})
 * @param visibility {@code all_indexes.visibility} ({@code VISIBLE}/{@code INVISIBLE})
 * @param automatic {@code all_indexes.generated = 'Y'} means a generated name, not constraint ownership
 * @param partitioned {@code all_indexes.partitioned = 'YES'}
 */
record OracleIndexDetail(
        String schema,
        String table,
        String index,
        String indexType,
        boolean unique,
        String status,
        String visibility,
        boolean automatic,
        boolean partitioned,
        String tableOwner,
        boolean uniquenessKnown) {

    OracleIndexDetail(
            String schema,
            String table,
            String index,
            String indexType,
            boolean unique,
            String status,
            String visibility,
            boolean automatic,
            boolean partitioned,
            String tableOwner) {
        this(schema, table, index, indexType, unique, status, visibility, automatic, partitioned, tableOwner, true);
    }

    OracleIndexDetail(
            String schema,
            String table,
            String index,
            String indexType,
            boolean unique,
            String status,
            String visibility,
            boolean automatic,
            boolean partitioned) {
        this(schema, table, index, indexType, unique, status, visibility, automatic, partitioned, schema, true);
    }

    String qualifiedTable() {
        return tableOwner == null || tableOwner.isBlank() ? table : tableOwner + "." + table;
    }

    boolean unusable() {
        return "UNUSABLE".equalsIgnoreCase(status);
    }

    boolean usable() {
        return "VALID".equalsIgnoreCase(status);
    }

    boolean invisible() {
        return "INVISIBLE".equalsIgnoreCase(visibility);
    }

    /** Function-based, domain, bitmap, LOB and index-organized-table indexes need dedicated semantics. */
    boolean normal() {
        return indexType != null && indexType.toUpperCase(java.util.Locale.ROOT).startsWith("NORMAL");
    }

    boolean functionBased() {
        return indexType != null && indexType.toUpperCase(java.util.Locale.ROOT).contains("FUNCTION-BASED");
    }

    boolean domain() {
        return "DOMAIN".equalsIgnoreCase(indexType);
    }

    boolean bitmap() {
        return indexType != null && indexType.toUpperCase(java.util.Locale.ROOT).contains("BITMAP");
    }

    boolean lobOrIot() {
        return indexType != null
                && (indexType.toUpperCase(java.util.Locale.ROOT).contains("LOB")
                        || indexType.toUpperCase(java.util.Locale.ROOT).contains("IOT"));
    }
}
