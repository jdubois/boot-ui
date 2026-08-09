package io.github.jdubois.bootui.engine.databaseadvisor;

import java.util.List;

/**
 * One physical table (or view) read from {@code DatabaseMetaData}, with its columns, primary-key
 * column names, foreign keys, and indexes.
 */
record TableModel(
        String catalog,
        String schema,
        String name,
        List<ColumnModel> columns,
        List<String> primaryKeyColumns,
        List<ForeignKeyModel> foreignKeys,
        List<IndexModel> indexes) {

    TableModel {
        columns = List.copyOf(columns);
        primaryKeyColumns = List.copyOf(primaryKeyColumns);
        foreignKeys = List.copyOf(foreignKeys);
        indexes = List.copyOf(indexes);
    }

    String qualifiedName() {
        return schema == null || schema.isBlank() ? name : schema + "." + name;
    }

    boolean hasColumn(String columnName) {
        return columns.stream().anyMatch(column -> column.name().equalsIgnoreCase(columnName));
    }

    ColumnModel column(String columnName) {
        return columns.stream()
                .filter(column -> column.name().equalsIgnoreCase(columnName))
                .findFirst()
                .orElse(null);
    }

    /** True when at least one index has {@code columnName} as its leading column. */
    boolean hasLeadingIndexOn(String columnName) {
        return indexes.stream()
                .anyMatch(index -> columnName != null && columnName.equalsIgnoreCase(index.leadingColumn()));
    }
}
