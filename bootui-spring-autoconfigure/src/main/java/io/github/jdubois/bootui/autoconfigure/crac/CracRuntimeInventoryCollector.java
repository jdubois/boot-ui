package io.github.jdubois.bootui.autoconfigure.crac;

import io.github.jdubois.bootui.autoconfigure.web.HikariDataSourceDiscovery;
import io.github.jdubois.bootui.engine.crac.CracRuntimeInventory;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.core.SpringProperties;
import org.springframework.util.ClassUtils;

/**
 * Collects bounded, read-only Spring runtime evidence for CRaC readiness checks.
 *
 * <p>Optional types are resolved by name through the host application's class loader. Bean discovery
 * never permits eager initialization. Hikari's public suspension flag is read only when the
 * {@code HikariDataSource} already exists as a singleton; a lazy pool is reported as unknown rather
 * than initialized for inspection.</p>
 */
public final class CracRuntimeInventoryCollector {

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

    private static final List<String> LOCAL_CACHE_MANAGER_TYPE_NAMES = List.of(
            "org.springframework.cache.concurrent.ConcurrentMapCacheManager",
            "org.springframework.cache.caffeine.CaffeineCacheManager");

    private static final List<String> PARTIAL_TASK_LIFECYCLE_TYPE_NAMES = List.of(
            "org.springframework.core.task.SimpleAsyncTaskExecutor",
            "org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler");

    private static final String HIKARI_DATA_SOURCE_TYPE_NAME = "com.zaxxer.hikari.HikariDataSource";
    private static final String HIKARI_LIFECYCLE_TYPE_NAME =
            "org.springframework.boot.jdbc.HikariCheckpointRestoreLifecycle";
    private static final String CRAC_CORE_TYPE_NAME = "org.crac.Core";
    private static final String RESTORE_FROM_PREFIX = "-XX:CRaCRestoreFrom=";

    private CracRuntimeInventoryCollector() {}

    public static CracRuntimeInventory collect(ApplicationContext applicationContext) {
        return collect(applicationContext, CracRuntimeInventoryCollector::jvmArguments);
    }

    static CracRuntimeInventory collect(
            ApplicationContext applicationContext, Supplier<List<String>> jvmArgumentsSupplier) {
        if (applicationContext == null) {
            return CracRuntimeInventory.empty();
        }

        ClassLoader applicationClassLoader = applicationContext.getClassLoader();
        ClassLoader classLoader =
                applicationClassLoader != null ? applicationClassLoader : ClassUtils.getDefaultClassLoader();

        List<BeanObservation> poolBeans = detectBeans(applicationContext, POOL_TYPE_NAMES, classLoader);
        boolean hikariPresent = ClassUtils.isPresent(HIKARI_DATA_SOURCE_TYPE_NAME, classLoader);
        List<BeanObservation> hikariPools = poolBeans.stream()
                .filter(observation -> isHikariPool(applicationContext, observation, hikariPresent))
                .toList();
        List<String> nonHikariPools = poolBeans.stream()
                .filter(observation -> !isHikariPool(applicationContext, observation, hikariPresent))
                .map(BeanObservation::display)
                .toList();

        List<BeanObservation> hikariLifecycleBeans =
                detectBeans(applicationContext, List.of(HIKARI_LIFECYCLE_TYPE_NAME), classLoader);
        List<String> hikariPoolIssues = inspectHikariPools(applicationContext, hikariPools, hikariLifecycleBeans);
        List<String> cacheBeans = detectBeans(applicationContext, LOCAL_CACHE_MANAGER_TYPE_NAMES, classLoader).stream()
                .map(BeanObservation::display)
                .toList();
        List<String> taskBeans =
                detectBeans(applicationContext, PARTIAL_TASK_LIFECYCLE_TYPE_NAMES, classLoader).stream()
                        .map(BeanObservation::display)
                        .toList();

        boolean cracApiPresent = ClassUtils.isPresent(CRAC_CORE_TYPE_NAME, classLoader);
        boolean checkpointOnRefresh =
                "onRefresh".equalsIgnoreCase(SpringProperties.getProperty("spring.context.checkpoint"));
        boolean restoredProcess = safeArguments(jvmArgumentsSupplier).stream()
                .anyMatch(argument -> argument != null && argument.startsWith(RESTORE_FROM_PREFIX));
        return new CracRuntimeInventory(
                nonHikariPools,
                cacheBeans,
                hikariPoolIssues,
                taskBeans,
                cracApiPresent,
                checkpointOnRefresh,
                restoredProcess);
    }

    private static List<String> inspectHikariPools(
            ApplicationContext applicationContext,
            List<BeanObservation> hikariPools,
            List<BeanObservation> lifecycleBeans) {
        if (hikariPools.isEmpty()) {
            return List.of();
        }

        List<String> issues = new ArrayList<>();
        // Boot's auto-configuration only wires a HikariCheckpointRestoreLifecycle bean automatically for the
        // single-candidate DataSource case; a multi-pool application must register the remaining lifecycle
        // beans itself, typically one per pool. Equal counts are therefore treated as sufficient bean-count
        // evidence for coverage, not a verified 1:1 pairing (e.g. two lifecycle beans could still wrap the
        // same pool while another pool goes uncovered). Any other count keeps the conservative "can't verify"
        // stance rather than guessing which bean covers which pool.
        boolean poolAndLifecycleCountsMatch = hikariPools.size() == lifecycleBeans.size();
        for (BeanObservation pool : hikariPools) {
            String issue = null;
            ExistingHikariPool existingPool = existingHikariPool(applicationContext, pool.name());
            if (lifecycleBeans.isEmpty()) {
                issue = "Spring Boot HikariCheckpointRestoreLifecycle bean is missing";
            } else if (!poolAndLifecycleCountsMatch) {
                issue = "checkpoint lifecycle coverage cannot be matched across " + hikariPools.size()
                        + " Hikari pool(s) and " + lifecycleBeans.size() + " lifecycle bean(s)";
            } else {
                if (existingPool != null && !existingPool.allowsSuspension()) {
                    issue = "allowPoolSuspension=false";
                } else if (existingPool == null) {
                    issue = "allowPoolSuspension could not be verified without initializing the bean";
                }
            }
            if (issue != null) {
                issues.add((existingPool != null ? existingPool.display() : pool.display()) + " - " + issue);
            }
        }
        return List.copyOf(issues);
    }

    private static ExistingHikariPool existingHikariPool(ApplicationContext applicationContext, String beanName) {
        if (!(applicationContext.getAutowireCapableBeanFactory() instanceof ConfigurableListableBeanFactory beanFactory)
                || !beanFactory.containsSingleton(beanName)) {
            return null;
        }
        Object singleton = beanFactory.getSingleton(beanName);
        try {
            return HikariSupport.inspect(beanName, singleton);
        } catch (LinkageError ex) {
            return null;
        }
    }

    private static boolean isHikariPool(
            ApplicationContext applicationContext, BeanObservation observation, boolean hikariPresent) {
        if (!hikariPresent) {
            return false;
        }
        try {
            return HikariSupport.isHikariType(observation.type())
                    || existingHikariPool(applicationContext, observation.name()) != null;
        } catch (LinkageError ex) {
            return false;
        }
    }

    private static List<BeanObservation> detectBeans(
            ListableBeanFactory beanFactory, List<String> typeNames, ClassLoader classLoader) {
        List<BeanObservation> observations = new ArrayList<>();
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
                Class<?> beanType = beanFactory.getType(beanName, false);
                observations.add(new BeanObservation(beanName, beanType != null ? beanType : type));
            }
        }
        return observations.stream()
                .sorted(java.util.Comparator.comparing(BeanObservation::display))
                .toList();
    }

    private static List<String> safeArguments(Supplier<List<String>> supplier) {
        try {
            List<String> arguments = supplier == null ? null : supplier.get();
            return arguments == null ? List.of() : List.copyOf(arguments);
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private static List<String> jvmArguments() {
        try {
            RuntimeMXBean runtimeMxBean = ManagementFactory.getRuntimeMXBean();
            return runtimeMxBean == null ? List.of() : runtimeMxBean.getInputArguments();
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private record BeanObservation(String name, Class<?> type) {

        String display() {
            return name + " : " + type.getName();
        }
    }

    private record ExistingHikariPool(String display, boolean allowsSuspension) {}

    /**
     * Kept behind a class-name presence gate so applications without optional HikariCP never link it.
     */
    private static final class HikariSupport {

        private HikariSupport() {}

        static boolean isHikariType(Class<?> type) {
            return com.zaxxer.hikari.HikariDataSource.class.isAssignableFrom(type);
        }

        static ExistingHikariPool inspect(String beanName, Object bean) {
            if (!(bean instanceof javax.sql.DataSource candidate)) {
                return null;
            }
            com.zaxxer.hikari.HikariDataSource dataSource = HikariDataSourceDiscovery.existingHikariTarget(candidate);
            return dataSource == null
                    ? null
                    : new ExistingHikariPool(
                            beanName + " : " + dataSource.getClass().getName(), dataSource.isAllowPoolSuspension());
        }
    }
}
