package io.github.jdubois.bootui.engine.databaseadvisor;

/** Declaration validation and native RI-trigger coverage in the observed connection's replication role. */
record PostgresForeignKeyDetail(
        String schema,
        String table,
        String constraint,
        Boolean enforced,
        Boolean validated,
        Boolean deleteActionEnabled,
        Boolean updateActionEnabled) {

    PostgresForeignKeyDetail(String schema, String table, String constraint, Boolean enforced, Boolean validated) {
        this(schema, table, constraint, enforced, validated, null, null);
    }
}
