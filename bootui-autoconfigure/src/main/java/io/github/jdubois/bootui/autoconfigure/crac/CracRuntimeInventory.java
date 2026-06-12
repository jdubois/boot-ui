package io.github.jdubois.bootui.autoconfigure.crac;

import java.util.List;
import org.springframework.core.env.Environment;

/**
 * Live, in-process view of the host application's connection pools, captured once per scan so the
 * readiness checks can reason about auto-configured resources that never appear in the application's
 * own bytecode (JDBC {@code DataSource}s, Redis connection factories, and similar).
 *
 * <p>Connection pools are the most common real-world cause of a failed CRaC checkpoint: if a pooled
 * socket is still open when the checkpoint is taken, CRaC aborts with a
 * {@code CheckpointOpenSocketException}. Because the pools are contributed by Spring Boot
 * auto-configuration rather than the application package that the ArchUnit importer scans, the
 * connection-pool check reads this runtime inventory instead of imported classes.</p>
 *
 * @param connectionPoolBeans human-readable {@code beanName : TypeName} entries for detected pool
 *     beans (JDBC {@code DataSource}, Redis/RabbitMQ/Kafka/Mongo/Cassandra/JMS/R2DBC connection
 *     factories and similar pooled clients), empty when none are present
 * @param cacheManagerBeans human-readable {@code beanName : TypeName} entries for detected Spring
 *     {@code CacheManager} beans, empty when none are present
 * @param environment the live Spring {@link Environment}, or {@code null} when unavailable, used to
 *     surface pool tuning that influences checkpoint readiness (for example HikariCP idle settings)
 */
record CracRuntimeInventory(List<String> connectionPoolBeans, List<String> cacheManagerBeans, Environment environment) {

    CracRuntimeInventory {
        connectionPoolBeans = connectionPoolBeans == null ? List.of() : List.copyOf(connectionPoolBeans);
        cacheManagerBeans = cacheManagerBeans == null ? List.of() : List.copyOf(cacheManagerBeans);
    }

    CracRuntimeInventory(List<String> connectionPoolBeans, Environment environment) {
        this(connectionPoolBeans, List.of(), environment);
    }

    static CracRuntimeInventory empty() {
        return new CracRuntimeInventory(List.of(), List.of(), null);
    }

    boolean hasConnectionPools() {
        return !connectionPoolBeans.isEmpty();
    }

    boolean hasCacheManagers() {
        return !cacheManagerBeans.isEmpty();
    }
}
