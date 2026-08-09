package io.github.jdubois.bootui.engine.databaseadvisor;

import java.util.List;

/**
 * The physical schema read from one {@code DataSource}, plus any dialect-specific catalog augmentation
 * for {@link Dialect#POSTGRESQL} / {@link Dialect#MYSQL}. A datasource that could not be introspected is
 * represented with an empty {@link #tables()} and a non-null {@link #error()}.
 */
record SchemaSnapshot(
        String dataSourceName,
        Dialect dialect,
        String databaseProductName,
        List<TableModel> tables,
        List<PostgresInvalidIndex> postgresInvalidIndexes,
        List<MySqlNonInnodbTable> mysqlNonInnodbTables,
        List<MySqlNonUtf8mb4Column> mysqlNonUtf8mb4Columns,
        List<PostgresSequenceNearingExhaustion> postgresSequencesNearingExhaustion,
        String error) {

    SchemaSnapshot {
        tables = List.copyOf(tables);
        postgresInvalidIndexes = List.copyOf(postgresInvalidIndexes);
        mysqlNonInnodbTables = List.copyOf(mysqlNonInnodbTables);
        mysqlNonUtf8mb4Columns = List.copyOf(mysqlNonUtf8mb4Columns);
        postgresSequencesNearingExhaustion = List.copyOf(postgresSequencesNearingExhaustion);
    }

    static SchemaSnapshot failed(String dataSourceName, String error) {
        return new SchemaSnapshot(
                dataSourceName, Dialect.GENERIC, null, List.of(), List.of(), List.of(), List.of(), List.of(), error);
    }

    boolean available() {
        return error == null;
    }

    TableModel table(String tableName) {
        return tables.stream()
                .filter(table -> table.name().equalsIgnoreCase(tableName))
                .findFirst()
                .orElse(null);
    }
}
