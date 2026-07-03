package io.github.jdubois.bootui.engine.activity;

import java.time.Duration;

/**
 * Static configuration for the Live Activity persistence option, mapped once from {@code
 * bootui.activity.persistence.*} by each adapter's factory — see the codebase's "static settings record
 * vs. live policy interface" convention: at startup, none of these values has a runtime-override/UI-toggle
 * path, so a settings record (not an SPI policy interface) is the right shape. The one exception is the
 * "Use the existing datasource" panel action (see {@code ActivitySwitchService}), which does not mutate
 * this record or re-read it live — it builds one fresh, derived copy via {@link #withEnabledSharedMode()}
 * and hands it directly to the new store/poller it starts, leaving every other consumer's already-injected
 * settings instance untouched.
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

    /**
     * A copy of these settings with {@code enabled=true} and {@code dataSourceMode=SHARED}; every other
     * field (table name, flush interval, buffer capacity, retention, instance id, capture interval) is
     * carried over unchanged, since those are always correctly resolved by each adapter's config-binding
     * code regardless of whether persistence starts out enabled. Used only by the "Use the existing
     * datasource" runtime switch (see {@code ActivitySwitchService}) to build the settings its new
     * durable store and capture poller run with.
     */
    public ActivityPersistenceSettings withEnabledSharedMode() {
        return new ActivityPersistenceSettings(
                true,
                DataSourceMode.SHARED,
                dedicatedJdbcUrl,
                dedicatedUsername,
                dedicatedPassword,
                dedicatedDriverClassName,
                tableName,
                flushInterval,
                bufferMaxEntries,
                retention,
                instanceId,
                captureInterval);
    }
}
