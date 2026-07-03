package io.github.jdubois.bootui.engine.activity;

import java.time.Duration;

/**
 * Static configuration for the Live Activity persistence option, mapped once from {@code
 * bootui.activity.persistence.*} by each adapter's factory — see the codebase's "static settings record
 * vs. live policy interface" convention: none of these values has a runtime-override/UI-toggle path, so
 * a settings record (not an SPI policy interface) is the right shape.
 *
 * @param enabled whether captured entries are also durably persisted; {@code false} keeps today's
 *     in-memory-only default behavior with no persistence machinery constructed at all
 * @param dataSourceMode whether to reuse the host application's own {@code DataSource} bean ({@link
 *     DataSourceMode#SHARED}) or open a small dedicated, non-pooled connection of BootUI's own ({@link
 *     DataSourceMode#DEDICATED})
 * @param dedicatedJdbcUrl JDBC URL for {@link DataSourceMode#DEDICATED}, otherwise ignored
 * @param dedicatedUsername username for {@link DataSourceMode#DEDICATED}, otherwise ignored
 * @param dedicatedPassword password for {@link DataSourceMode#DEDICATED}, otherwise ignored
 * @param dedicatedDriverClassName optional explicit JDBC driver class to load for {@link
 *     DataSourceMode#DEDICATED}; blank lets the driver auto-register itself
 * @param tableName the shared table name every BootUI instance pointed at the same database uses
 * @param flushInterval how often buffered entries are flushed to durable storage
 * @param bufferMaxEntries capacity of both the hot read cache and the pending-flush queue
 * @param retention how long persisted rows are kept before being pruned; entries older than this are
 *     eligible for deletion on the instance's own next prune pass
 * @param instanceId the multi-tenant partition key this instance writes/reads under
 * @param captureInterval how often the capture coordinator polls the merged feed for new entries
 */
public record ActivityPersistenceSettings(
        boolean enabled,
        DataSourceMode dataSourceMode,
        String dedicatedJdbcUrl,
        String dedicatedUsername,
        String dedicatedPassword,
        String dedicatedDriverClassName,
        String tableName,
        Duration flushInterval,
        int bufferMaxEntries,
        Duration retention,
        String instanceId,
        Duration captureInterval) {

    /** Where the durable store gets its JDBC connections from. */
    public enum DataSourceMode {
        SHARED,
        DEDICATED
    }
}
