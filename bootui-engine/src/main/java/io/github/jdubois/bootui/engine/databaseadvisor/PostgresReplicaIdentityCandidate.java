package io.github.jdubois.bootui.engine.databaseadvisor;

/**
 * One PostgreSQL table that is in scope for logical replication — a member of an explicit publication
 * expanded by {@code pg_publication_tables}, including schema/all-table membership and partition-root
 * behavior — filtered to publications with UPDATE or DELETE enabled, together with its
 * {@code pg_class.relreplident} setting.
 *
 * @param replicaIdentity {@code pg_class.relreplident}: {@code d} (default — use the primary key, or nothing
 *     if there is none), {@code n} (nothing), {@code f} (full row), or {@code i} (a specific unique index)
 */
record PostgresReplicaIdentityCandidate(String schema, String table, String replicaIdentity, Boolean hasIdentityIndex) {

    PostgresReplicaIdentityCandidate(String schema, String table, String replicaIdentity) {
        this(schema, table, replicaIdentity, null);
    }

    String qualifiedTable() {
        return schema == null || schema.isBlank() ? table : schema + "." + table;
    }

    boolean nothing() {
        return "n".equalsIgnoreCase(replicaIdentity);
    }

    boolean usesDefault() {
        return "d".equalsIgnoreCase(replicaIdentity);
    }
}
