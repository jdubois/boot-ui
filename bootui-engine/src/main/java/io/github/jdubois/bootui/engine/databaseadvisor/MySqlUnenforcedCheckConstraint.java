package io.github.jdubois.bootui.engine.databaseadvisor;

/**
 * One MySQL 8.0.16+ {@code CHECK} constraint on a base table whose {@code information_schema.table_constraints}
 * row reports {@code ENFORCED = 'NO'}.
 */
record MySqlUnenforcedCheckConstraint(String schema, String table, String constraint) {

    String qualifiedTable() {
        return schema == null || schema.isBlank() ? table : schema + "." + table;
    }
}
