package io.github.jdubois.bootui.engine.activity;

import java.util.function.Supplier;
import javax.sql.DataSource;

/**
 * Builds the {@link ActivityStore} a running BootUI instance uses for Live Activity, from {@link
 * ActivityPersistenceSettings} plus an adapter-supplied way to obtain the host application's own {@code
 * DataSource} for {@link ActivityPersistenceSettings.DataSourceMode#SHARED} mode — or, via the
 * three-argument {@link #create(ActivityPersistenceSettings, ActivityForwardingSettings, Supplier)}
 * overload, from {@link ActivityForwardingSettings} instead, when this instance forwards its captured
 * entries to a peer over HTTP rather than persisting them locally.
 *
 * <p>This is the single place that turns configuration into a concrete store composition, so both
 * adapters (and tests) build the same shape consistently: {@link InMemoryActivityStore} alone when
 * neither backend is enabled, a {@link BufferedActivityStore} wrapping a {@link JdbcActivityStore} when
 * JDBC persistence is enabled, or a {@link BufferedActivityStore} wrapping an {@link HttpActivityStore}
 * when HTTP forwarding is enabled instead — always wrapped in a {@link SwitchableActivityStore} so the
 * "Use a database" runtime switch (see {@code ActivitySwitchService}) can later replace the delegate
 * without any consumer needing a new reference.
 */
public final class ActivityStoreFactory {

    private ActivityStoreFactory() {}

    /**
     * @param settings the resolved persistence configuration
     * @param sharedDataSourceSupplier resolves the host application's own {@code DataSource} bean, only
     *     invoked (and only required to return non-null) when {@code settings.dataSourceMode() ==
     *     SHARED}; pass {@code () -> null} when the adapter has no such notion
     */
    public static SwitchableActivityStore create(
            ActivityPersistenceSettings settings, Supplier<DataSource> sharedDataSourceSupplier) {
        InMemoryActivityStore hotCache = new InMemoryActivityStore(Math.max(1, settings.bufferMaxEntries()));
        if (!settings.enabled()) {
            return new SwitchableActivityStore(hotCache);
        }

        DataSource dataSource = resolveDataSource(settings, sharedDataSourceSupplier);
        JdbcActivityStore durable = new JdbcActivityStore(dataSource, settings.tableName());
        BufferedActivityStore buffered = new BufferedActivityStore(
                hotCache,
                durable,
                settings.flushInterval(),
                settings.bufferMaxEntries(),
                settings.instanceId(),
                settings.retention());
        return new SwitchableActivityStore(buffered);
    }

    /**
     * The forwarding-aware twin of {@link #create(ActivityPersistenceSettings, Supplier)}: also
     * considers {@code forwardingSettings} and, when it is enabled, builds an HTTP-forwarding store
     * instead of a JDBC one. The two-arg overload above is kept completely unchanged (and this one
     * delegates to it whenever forwarding is not in play) since {@code ActivitySwitchService} and
     * existing tests call it directly and have no notion of forwarding at all.
     *
     * @param persistenceSettings the resolved JDBC-persistence configuration
     * @param forwardingSettings the resolved HTTP-forwarding configuration, or {@code null} when the
     *     calling adapter has no notion of forwarding at all (treated identically to a settings instance
     *     with {@code enabled=false})
     * @param sharedDataSourceSupplier resolves the host application's own {@code DataSource} bean; see
     *     {@link #create(ActivityPersistenceSettings, Supplier)}. Not invoked at all when forwarding is
     *     what ends up enabled, since forwarding needs no {@code DataSource}
     * @throws ActivityStoreException if both {@code persistenceSettings.enabled()} and {@code
     *     forwardingSettings.enabled()} are {@code true} — the two backends are mutually exclusive for a
     *     single instance, and failing fast at startup is safer than silently prioritizing one
     */
    public static SwitchableActivityStore create(
            ActivityPersistenceSettings persistenceSettings,
            ActivityForwardingSettings forwardingSettings,
            Supplier<DataSource> sharedDataSourceSupplier) {
        boolean forwardingEnabled = forwardingSettings != null && forwardingSettings.enabled();
        if (persistenceSettings.enabled() && forwardingEnabled) {
            throw new ActivityStoreException(
                    "bootui.activity.persistence.enabled and bootui.activity.forwarding.enabled cannot both be"
                            + " true; pick exactly one Live Activity durability backend for this instance",
                    null);
        }
        if (forwardingEnabled) {
            return createForwarding(forwardingSettings);
        }
        return create(persistenceSettings, sharedDataSourceSupplier);
    }

    /**
     * Builds a {@link BufferedActivityStore} wrapping an {@link HttpActivityStore} as its durable tier —
     * the write-behind buffering/scheduled-flush/retry-requeue/bounded-drop/shutdown-bounding behavior is
     * inherited entirely from {@link BufferedActivityStore}, unmodified. The four-arg constructor is used
     * deliberately (no {@code instanceId}/{@code retention} pair, so no periodic prune is scheduled):
     * there is no local durable data on this instance to prune — the entries live, and age out, on the
     * peer's own durable store under the peer's own retention configuration.
     */
    private static SwitchableActivityStore createForwarding(ActivityForwardingSettings settings) {
        InMemoryActivityStore hotCache = new InMemoryActivityStore(Math.max(1, settings.bufferMaxEntries()));
        HttpActivityStore durable = new HttpActivityStore(
                settings.peerBaseUrl(), settings.sharedSecret(), settings.connectTimeout(), settings.requestTimeout());
        BufferedActivityStore buffered =
                new BufferedActivityStore(hotCache, durable, settings.flushInterval(), settings.bufferMaxEntries());
        return new SwitchableActivityStore(buffered);
    }

    /**
     * Builds a durable {@link BufferedActivityStore} over {@code dataSource} and eagerly verifies its
     * schema (see {@link JdbcActivityStore#verifySchema()}) before returning, so a broken or unreachable
     * database is rejected immediately rather than surfacing later on the first background flush. Used
     * only by the "Use the existing datasource" runtime switch (see {@code ActivitySwitchService}) —
     * the startup path above stays lazy-verify-on-first-write via {@link #create}, unchanged.
     *
     * @throws ActivityStoreException if the schema cannot be verified/created
     */
    public static BufferedActivityStore createAndVerifyDurable(
            ActivityPersistenceSettings settings, DataSource dataSource) {
        JdbcActivityStore durable = new JdbcActivityStore(dataSource, settings.tableName());
        durable.verifySchema();
        InMemoryActivityStore hotCache = new InMemoryActivityStore(Math.max(1, settings.bufferMaxEntries()));
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
