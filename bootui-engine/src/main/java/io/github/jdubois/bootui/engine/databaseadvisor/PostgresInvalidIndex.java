package io.github.jdubois.bootui.engine.databaseadvisor;

/** A PostgreSQL index whose catalog entry ({@code pg_index.indisvalid}) reports it as invalid/unusable. */
record PostgresInvalidIndex(String tableName, String indexName) {}
