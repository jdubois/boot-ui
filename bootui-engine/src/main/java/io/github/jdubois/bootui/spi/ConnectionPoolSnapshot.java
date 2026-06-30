package io.github.jdubois.bootui.spi;

/**
 * Framework-neutral live connection counts for one JDBC connection pool at a point in time, supplied by a
 * {@link ConnectionPoolProvider}. The engine maps it onto the wire {@code HikariPoolSnapshotDto} unchanged.
 *
 * @param timestamp the sample time, in epoch millis
 * @param active connections currently in use
 * @param idle idle connections available to be acquired
 * @param total total connections currently managed by the pool ({@code active + idle})
 * @param pending threads currently waiting to acquire a connection
 */
public record ConnectionPoolSnapshot(long timestamp, int active, int idle, int total, int pending) {}
