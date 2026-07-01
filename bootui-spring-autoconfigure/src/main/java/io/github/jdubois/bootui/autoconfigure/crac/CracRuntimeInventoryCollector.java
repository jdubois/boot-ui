package io.github.jdubois.bootui.autoconfigure.crac;

import io.github.jdubois.bootui.engine.crac.CracRuntimeInventory;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.util.ClassUtils;

/**
 * Collects the host application's live connection-pool and cache-manager beans into a
 * {@link CracRuntimeInventory}.
 *
 * <p>The collector inspects the running {@link ApplicationContext} for the bean types that hold the
 * OS sockets CRaC cares about — JDBC {@code DataSource}s, Redis/RabbitMQ/Kafka/Mongo/Cassandra/JMS/
 * R2DBC connection factories and similar pooled clients — plus Spring {@code CacheManager} beans
 * whose contents are frozen into the checkpoint. Every type is resolved lazily and bounded to types
 * that are actually on the classpath, so a missing optional dependency never fails the scan. All
 * access is read-only and never triggers a checkpoint.</p>
 */
public final class CracRuntimeInventoryCollector {

    /**
     * Bean types that own pooled OS sockets and therefore matter for a clean checkpoint. Each entry
     * is resolved lazily so an absent optional dependency (for example Spring Data Redis) is simply
     * skipped rather than failing the scan.
     */
    private static final List<String> POOL_TYPE_NAMES = List.of(
            "javax.sql.DataSource",
            "io.r2dbc.spi.ConnectionFactory",
            "org.springframework.data.redis.connection.RedisConnectionFactory",
            "org.springframework.amqp.rabbit.connection.ConnectionFactory",
            "org.springframework.kafka.core.ProducerFactory",
            "org.springframework.kafka.core.ConsumerFactory",
            "com.mongodb.client.MongoClient",
            "com.mongodb.reactivestreams.client.MongoClient",
            "com.datastax.oss.driver.api.core.CqlSession",
            "co.elastic.clients.elasticsearch.ElasticsearchClient",
            "jakarta.jms.ConnectionFactory");

    /**
     * Spring {@code CacheManager} type whose caches are populated in-process and therefore captured
     * into the checkpoint image. Resolved lazily so applications without the cache abstraction are
     * skipped.
     */
    private static final String CACHE_MANAGER_TYPE_NAME = "org.springframework.cache.CacheManager";

    /** A no-op cache manager holds no entries, so it is irrelevant to checkpoint/restore. */
    private static final String NO_OP_CACHE_MANAGER_TYPE_NAME = "org.springframework.cache.support.NoOpCacheManager";

    private CracRuntimeInventoryCollector() {}

    public static CracRuntimeInventory collect(ApplicationContext applicationContext) {
        if (applicationContext == null) {
            return CracRuntimeInventory.empty();
        }
        try {
            List<String> poolBeans = detectBeans(applicationContext, POOL_TYPE_NAMES, ignored -> false);
            List<String> cacheBeans = detectBeans(
                    applicationContext,
                    List.of(CACHE_MANAGER_TYPE_NAME),
                    type -> NO_OP_CACHE_MANAGER_TYPE_NAME.equals(type.getName()));
            return new CracRuntimeInventory(poolBeans, cacheBeans);
        } catch (RuntimeException ex) {
            return CracRuntimeInventory.empty();
        }
    }

    private static List<String> detectBeans(
            ListableBeanFactory beanFactory, List<String> typeNames, java.util.function.Predicate<Class<?>> exclude) {
        ClassLoader classLoader = CracRuntimeInventoryCollector.class.getClassLoader();
        List<String> entries = new ArrayList<>();
        Set<String> seenBeanNames = new HashSet<>();
        for (String typeName : typeNames) {
            if (!ClassUtils.isPresent(typeName, classLoader)) {
                continue;
            }
            Class<?> type;
            try {
                type = ClassUtils.forName(typeName, classLoader);
            } catch (ClassNotFoundException | LinkageError ex) {
                continue;
            }
            for (String beanName : beanFactory.getBeanNamesForType(type, false, false)) {
                if (!seenBeanNames.add(beanName)) {
                    continue;
                }
                Class<?> beanType = beanFactory.getType(beanName);
                if (beanType != null && exclude.test(beanType)) {
                    continue;
                }
                String resolved = beanType != null ? beanType.getName() : typeName;
                entries.add(beanName + " : " + resolved);
            }
        }
        return entries;
    }
}
