package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.engine.hibernate.HibernateAdvisorObservation;
import io.github.jdubois.bootui.engine.hibernate.HibernateAdvisorObservationSource;
import io.github.jdubois.bootui.engine.hibernate.HibernateObservationDiagnostic;
import io.github.jdubois.bootui.engine.hibernate.HibernatePersistenceUnitObservation;
import io.github.jdubois.bootui.engine.hibernate.HibernatePoolObservation;
import io.github.jdubois.bootui.spi.ConnectionPoolInfo;
import io.github.jdubois.bootui.spi.ConnectionPoolProvider;
import io.github.jdubois.bootui.spi.DatabaseAdvisorEvidenceProvider;
import io.github.jdubois.bootui.spi.NamedDataSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The framework-neutral {@link DatabaseAdvisorEvidenceProvider}: the Spring and Quarkus adapters only say how to find
 * the datasources, the (optional) pool provider and the (optional) Hibernate observation source, and this class does
 * the rest, so both stacks attribute pools and aggregate batching identically.
 *
 * <p>It never imports a pool library: pools are reached through {@link ConnectionPoolProvider}, which an adapter
 * supplies only when its pool library is present. Each datasource is attributed to its pool with
 * {@link ConnectionPoolProvider#poolFor(javax.sql.DataSource)}, which matches by identity through SQL Trace proxies
 * and framework wrappers, so a wrapper bean named differently from the pool behind it is still attributed. A
 * datasource whose pool cannot be matched with certainty, or whose name is shared by another datasource, gets no pool
 * and no guessed batching.</p>
 *
 * <p>Nothing here keeps a JDBC URL, user name or bean name: the pool is reduced to its numbers, and only allow-listed
 * driver properties pass.</p>
 */
public final class DefaultDatabaseAdvisorEvidenceProvider implements DatabaseAdvisorEvidenceProvider {

    private final Supplier<List<NamedDataSource>> dataSources;
    private final Supplier<ConnectionPoolProvider> pools;
    private final Supplier<HibernateAdvisorObservationSource> hibernate;

    /**
     * @param dataSources the datasources the advisor may scan
     * @param pools the pool provider, or a supplier of {@code null} when the application has no supported pool
     * @param hibernate the Hibernate observation source, or a supplier of {@code null} when Hibernate is absent
     */
    public DefaultDatabaseAdvisorEvidenceProvider(
            Supplier<List<NamedDataSource>> dataSources,
            Supplier<ConnectionPoolProvider> pools,
            Supplier<HibernateAdvisorObservationSource> hibernate) {
        this.dataSources = dataSources;
        this.pools = pools;
        this.hibernate = hibernate;
    }

    @Override
    public List<DataSourceConfiguration> dataSourceConfigurations() {
        ConnectionPoolProvider provider = pools.get();
        Map<String, Map<String, String>> driverProperties = driverProperties(provider);
        List<DataSourceConfiguration> configurations = new ArrayList<>();
        for (NamedDataSource dataSource : uniquelyNamed()) {
            Optional<ConnectionPoolInfo> pool = poolOf(provider, dataSource);
            Map<String, String> properties =
                    pool.map(info -> driverProperties.get(info.beanName())).orElse(null);
            configurations.add(new DataSourceConfiguration(
                    dataSource.name(),
                    pool.map(DefaultDatabaseAdvisorEvidenceProvider::settings).orElse(null),
                    properties == null ? Map.of() : properties,
                    properties != null));
        }
        return configurations;
    }

    @Override
    public Map<String, JdbcBatching> jdbcBatching() {
        HibernateAdvisorObservationSource source = hibernate.get();
        if (source == null) {
            return Map.of();
        }
        Map<String, JdbcBatching> byPool = batchingByPool(source.observe());
        if (byPool.isEmpty()) {
            return Map.of();
        }
        ConnectionPoolProvider provider = pools.get();
        Map<String, JdbcBatching> byDataSource = new LinkedHashMap<>();
        for (NamedDataSource dataSource : uniquelyNamed()) {
            poolOf(provider, dataSource)
                    .map(ConnectionPoolInfo::beanName)
                    .map(byPool::get)
                    .ifPresent(batching -> byDataSource.put(dataSource.name(), batching));
        }
        return byDataSource;
    }

    @Override
    public Map<String, String> entityDataSources() {
        HibernateAdvisorObservationSource source = hibernate.get();
        if (source == null) {
            return Map.of();
        }
        Map<String, String> byPool = entityPools(source.observe());
        if (byPool.isEmpty()) {
            return Map.of();
        }
        ConnectionPoolProvider provider = pools.get();
        Map<String, String> dataSourceByPool = new HashMap<>();
        Set<String> ambiguousPools = new HashSet<>();
        for (NamedDataSource dataSource : uniquelyNamed()) {
            poolOf(provider, dataSource).map(ConnectionPoolInfo::beanName).ifPresent(pool -> {
                String previous = dataSourceByPool.put(pool, dataSource.name());
                if (previous != null && !previous.equals(dataSource.name())) {
                    ambiguousPools.add(pool);
                }
            });
        }
        ambiguousPools.forEach(dataSourceByPool::remove);
        if (dataSourceByPool.isEmpty()) {
            return Map.of();
        }
        Map<String, String> byEntity = new LinkedHashMap<>();
        byPool.forEach((entityName, poolName) -> {
            String dataSourceName = dataSourceByPool.get(poolName);
            if (dataSourceName != null) {
                byEntity.put(entityName, dataSourceName);
            }
        });
        return Map.copyOf(byEntity);
    }

    /**
     * The attributed pool name per entity name, when the entity's persistence unit is attributed to a single pool and
     * no other unit (including an unattributed unit or an unreadable factory) could map that entity elsewhere.
     */
    static Map<String, String> entityPools(HibernateAdvisorObservation observation) {
        if (observation == null
                || observation.diagnostics().stream()
                        .anyMatch(diagnostic ->
                                diagnostic.reason() == HibernateObservationDiagnostic.Reason.FACTORY_UNAVAILABLE)) {
            return Map.of();
        }
        Map<String, String> byEntity = new LinkedHashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (HibernatePersistenceUnitObservation unit : observation.units()) {
            HibernatePoolObservation pool = unit.pool();
            String poolName = pool == null ? null : pool.dataSourceName();
            for (io.github.jdubois.bootui.engine.hibernate.HibernateEntityModel entity : unit.entities()) {
                if (entity == null) {
                    continue;
                }
                for (String key : entityKeys(entity)) {
                    if (poolName == null || poolName.isBlank()) {
                        ambiguous.add(key);
                        byEntity.remove(key);
                    } else if (!ambiguous.contains(key)) {
                        String existing = byEntity.put(key, poolName);
                        if (existing != null && !existing.equals(poolName)) {
                            ambiguous.add(key);
                            byEntity.remove(key);
                        }
                    }
                }
            }
        }
        return Map.copyOf(byEntity);
    }

    private static List<String> entityKeys(io.github.jdubois.bootui.engine.hibernate.HibernateEntityModel entity) {
        List<String> keys = new ArrayList<>(2);
        if (entity.name() != null && !entity.name().isBlank()) {
            keys.add(entity.name());
        }
        if (entity.javaType() != null
                && !entity.javaType().getName().isBlank()
                && !keys.contains(entity.javaType().getName())) {
            keys.add(entity.javaType().getName());
        }
        return keys;
    }

    /**
     * The effective batching per pool, keyed like {@link HibernatePoolObservation#dataSourceName()}. A pool is
     * {@code ENABLED} (with the smallest batch size among its batching units) as soon as one unit on it batches,
     * because that unit benefits from the driver setting; {@code DISABLED} only when every unit on it is known not to
     * batch <em>and</em> no other unit could be on it. A unit that is not attributed to any pool, or a factory that
     * could not be read, might be using this pool, so it turns {@code DISABLED} into {@code UNKNOWN}: batching is
     * never reported off on a guess. A pool no unit uses is absent.
     */
    static Map<String, JdbcBatching> batchingByPool(HibernateAdvisorObservation observation) {
        boolean hiddenUnits = observation.diagnostics().stream()
                        .anyMatch(diagnostic ->
                                diagnostic.reason() == HibernateObservationDiagnostic.Reason.FACTORY_UNAVAILABLE)
                || observation.units().stream()
                        .anyMatch(unit -> unit.pool() == null || unit.pool().dataSourceName() == null);
        Map<String, Integer> smallestEnabled = new HashMap<>();
        Set<String> unknown = new HashSet<>();
        Set<String> known = new HashSet<>();
        for (HibernatePersistenceUnitObservation unit : observation.units()) {
            HibernatePoolObservation pool = unit.pool();
            if (pool == null || pool.dataSourceName() == null) {
                continue;
            }
            known.add(pool.dataSourceName());
            Integer batchSize = unit.settings().jdbcBatchSize();
            if (batchSize == null) {
                unknown.add(pool.dataSourceName());
            } else if (batchSize > 1) {
                smallestEnabled.merge(pool.dataSourceName(), batchSize, Math::min);
            }
        }
        Map<String, JdbcBatching> batching = new HashMap<>();
        for (String pool : known) {
            Integer enabled = smallestEnabled.get(pool);
            if (enabled != null) {
                batching.put(pool, JdbcBatching.enabled(enabled));
            } else if (unknown.contains(pool) || hiddenUnits) {
                batching.put(pool, JdbcBatching.unknown());
            } else {
                batching.put(pool, JdbcBatching.disabled());
            }
        }
        return batching;
    }

    private static Map<String, Map<String, String>> driverProperties(ConnectionPoolProvider provider) {
        if (provider == null) {
            return Map.of();
        }
        Map<String, Map<String, String>> reported = provider.driverProperties();
        if (reported == null) {
            return Map.of();
        }
        Map<String, Map<String, String>> allowListed = new HashMap<>();
        reported.forEach((pool, properties) -> {
            if (pool != null) {
                allowListed.put(pool, DriverSettingCatalog.allowListed(properties));
            }
        });
        return allowListed;
    }

    private List<NamedDataSource> uniquelyNamed() {
        List<NamedDataSource> all = dataSources.get();
        Set<String> seen = new HashSet<>();
        Set<String> ambiguous = new HashSet<>();
        for (NamedDataSource dataSource : all) {
            if (!seen.add(dataSource.name())) {
                ambiguous.add(dataSource.name());
            }
        }
        return all.stream()
                .filter(dataSource -> !ambiguous.contains(dataSource.name()))
                .toList();
    }

    private static Optional<ConnectionPoolInfo> poolOf(ConnectionPoolProvider provider, NamedDataSource dataSource) {
        if (provider == null || dataSource.dataSource() == null) {
            return Optional.empty();
        }
        try {
            Optional<ConnectionPoolInfo> pool = provider.poolFor(dataSource.dataSource());
            return pool == null ? Optional.empty() : pool;
        } catch (RuntimeException | LinkageError ex) {
            return Optional.empty();
        }
    }

    private static PoolSettings settings(ConnectionPoolInfo pool) {
        return new PoolSettings(
                pool.implementation(),
                pool.maximumPoolSize(),
                pool.minimumIdle(),
                pool.idleTimeoutMs(),
                pool.maxLifetimeMs(),
                pool.keepaliveTimeMs(),
                pool.connectionTimeoutMs());
    }
}
