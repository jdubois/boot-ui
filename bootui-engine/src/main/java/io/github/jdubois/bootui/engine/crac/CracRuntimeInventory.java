package io.github.jdubois.bootui.engine.crac;

import java.util.List;

/**
 * Live, in-process view of the host application's connection pools, captured once per scan so the
 * readiness checks can reason about auto-configured resources that never appear in the application's
 * own bytecode (JDBC {@code DataSource}s, Redis connection factories, and similar).
 *
 * <p>Connection pools are the most common real-world cause of a failed CRaC checkpoint: if a pooled
 * socket is still open when the checkpoint is taken, CRaC aborts with a
 * {@code CheckpointOpenSocketException}. Because the pools are contributed by framework
 * auto-configuration rather than the application package that the ArchUnit importer scans, the
 * connection-pool check reads this runtime inventory instead of imported classes.</p>
 *
 * @param connectionPoolBeans human-readable {@code beanName : TypeName} entries for detected pool
 *     beans (JDBC {@code DataSource}, Redis/RabbitMQ/Kafka/Mongo/Cassandra/JMS/R2DBC connection
 *     factories and similar pooled clients; remote-backed cache managers such as Redis's {@code
 *     RedisCacheManager} are excluded from {@link #cacheManagerBeans} rather than listed here), empty
 *     when none are present
 * @param cacheManagerBeans human-readable {@code beanName : TypeName} entries for detected cache
 *     manager beans backed by local, in-heap storage, empty when none are present
 * @param cracApiPresent whether {@code org.crac:crac} (org.crac.Core/org.crac.Resource) is present on
 *     the application's classpath; defaults to {@code true} in every convenience constructor and in
 *     {@link #empty()} so that a collection failure or an unavailable runtime never spuriously reports
 *     the dependency as missing
 */
public record CracRuntimeInventory(
        List<String> connectionPoolBeans, List<String> cacheManagerBeans, boolean cracApiPresent) {

    public CracRuntimeInventory {
        connectionPoolBeans = connectionPoolBeans == null ? List.of() : List.copyOf(connectionPoolBeans);
        cacheManagerBeans = cacheManagerBeans == null ? List.of() : List.copyOf(cacheManagerBeans);
    }

    public CracRuntimeInventory(List<String> connectionPoolBeans, List<String> cacheManagerBeans) {
        this(connectionPoolBeans, cacheManagerBeans, true);
    }

    public CracRuntimeInventory(List<String> connectionPoolBeans) {
        this(connectionPoolBeans, List.of(), true);
    }

    public static CracRuntimeInventory empty() {
        return new CracRuntimeInventory(List.of(), List.of(), true);
    }

    boolean hasConnectionPools() {
        return !connectionPoolBeans.isEmpty();
    }

    boolean hasCacheManagers() {
        return !cacheManagerBeans.isEmpty();
    }
}
