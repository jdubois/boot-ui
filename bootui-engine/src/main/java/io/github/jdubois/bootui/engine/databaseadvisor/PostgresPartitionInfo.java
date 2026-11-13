package io.github.jdubois.bootui.engine.databaseadvisor;

/**
 * How one PostgreSQL relation participates in declarative partitioning: a partitioned parent
 * ({@code relkind = 'p'}, the table users actually maintain and the one JDBC's {@code getTables} only
 * returns under the {@code PARTITIONED TABLE} type), a child partition ({@code relispartition}), or both for
 * a sub-partitioned level.
 */
record PostgresPartitionInfo(
        String schema,
        String table,
        boolean partitionedParent,
        boolean partitionChild,
        boolean emptyPartitionedParent) {

    PostgresPartitionInfo(String schema, String table, boolean partitionedParent, boolean partitionChild) {
        this(schema, table, partitionedParent, partitionChild, false);
    }

    String qualifiedTable() {
        return schema == null || schema.isBlank() ? table : schema + "." + table;
    }
}
