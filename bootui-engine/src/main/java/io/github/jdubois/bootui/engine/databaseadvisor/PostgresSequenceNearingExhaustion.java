package io.github.jdubois.bootui.engine.databaseadvisor;

/**
 * A PostgreSQL sequence ({@code pg_sequences}) whose current value has consumed a large fraction of the
 * numeric range of its underlying data type (e.g. {@code int4}), risking an out-of-values failure.
 */
record PostgresSequenceNearingExhaustion(String sequenceName, long lastValue, long maxValue, int percentUsed) {}
