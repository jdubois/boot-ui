package io.github.jdubois.bootui.engine.activity;

import java.util.function.Supplier;
import javax.sql.DataSource;

/**
 * Builds the {@link ActivityStore} a running BootUI instance uses for Live Activity, from {@link
 * ActivityPersistenceSettings} plus an adapter-supplied way to obtain the host application's own {@code
 * DataSource} for {@link ActivityPersistenceSettings.DataSourceMode#SHARED} mode.
 *
 * <p>This is the single place that turns configuration into a concrete store composition, so both
 * adapters (and tests) build the same shape consistently: {@link InMemoryActivityStore} alone when
 * persistence is disabled, or a {@link BufferedActivityStore} wrapping a {@link JdbcActivityStore} when
 * enabled.
 */
public final class ActivityStoreFactory {

    private ActivityStoreFactory() {}

    /**
     * @param settings the resolved persistence configuration
     * @param sharedDataSourceSupplier resolves the host application's own {@code DataSource} bean, only
     *     invoked (and only required to return non-null) when {@code settings.dataSourceMode() ==
     *     SHARED}; pass {@code () -> null} when the adapter has no such notion
     */
    public static ActivityStore create(
            ActivityPersistenceSettings settings, Supplier<DataSource> sharedDataSourceSupplier) {
        InMemoryActivityStore hotCache = new InMemoryActivityStore(Math.max(1, settings.bufferMaxEntries()));
        if (!settings.enabled()) {
            return hotCache;
        }

        DataSource dataSource = resolveDataSource(settings, sharedDataSourceSupplier);
        JdbcActivityStore durable = new JdbcActivityStore(dataSource, settings.tableName());
        return new BufferedActivityStore(
                hotCache,
                durable,
                settings.flushInterval(),
                settings.bufferMaxEntries(),
                settings.instanceId(),
                settings.retention());
    }

    private static DataSource resolveDataSource(
            ActivityPersistenceSettings settings, Supplier<DataSource> sharedDataSourceSupplier) {
        if (settings.dataSourceMode() == ActivityPersistenceSettings.DataSourceMode.DEDICATED) {
            return new SimpleDriverDataSource(
                    settings.dedicatedJdbcUrl(),
                    settings.dedicatedUsername(),
                    settings.dedicatedPassword(),
                    settings.dedicatedDriverClassName());
        }
        DataSource shared = sharedDataSourceSupplier == null ? null : sharedDataSourceSupplier.get();
        if (shared == null) {
            throw new ActivityStoreException(
                    "bootui.activity.persistence.enabled=true with datasource=shared, but no DataSource bean was"
                            + " found to reuse; either expose one or switch to datasource=dedicated with a jdbc-url",
                    null);
        }
        return shared;
    }
}
