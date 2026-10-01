package io.github.jdubois.bootui.engine.databaseadvisor;

/**
 * A PostgreSQL ordinary table (or leaf partition) with {@code pg_class.relpersistence = 'u'}.
 *
 * @param partition whether the table is a partition of a partitioned table
 */
record PostgresUnloggedTable(String schema, String table, boolean partition) {

    String qualifiedTable() {
        return schema == null || schema.isBlank() ? table : schema + "." + table;
    }
}
