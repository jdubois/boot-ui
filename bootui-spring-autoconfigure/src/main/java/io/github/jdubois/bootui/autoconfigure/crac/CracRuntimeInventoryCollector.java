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
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.Lifecycle;
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

    // Exact implementations only: generic interfaces and overriding subclasses do not establish
    // the documented stop/restart behavior. No optional client API is linked here.
    private static final Set<String> MANAGED_POOL_TYPE_NAMES = Set.of(
            "org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory",
            "org.springframework.amqp.rabbit.connection.CachingConnectionFactory",
            "org.springframework.kafka.core.DefaultKafkaProducerFactory",
            "org.springframework.jms.connection.SingleConnectionFactory");

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
        return collect(
                applicationContext,
                jvmArgumentsSupplier,
                () -> CracRuntimeStatusCollector.restoreTime(applicationContext.getClassLoader()));
    }

    static CracRuntimeInventory collect(
            ApplicationContext applicationContext,
            Supplier<List<String>> jvmArgumentsSupplier,
            Supplier<Long> restoreTimeSupplier) {
        if (applicationContext == null) {
            return CracRuntimeInventory.unavailable("Spring application context is unavailable.");
        }

        try {
            return collectAvailable(applicationContext, jvmArgumentsSupplier, restoreTimeSupplier);
        } catch (RuntimeException | LinkageError ex) {
            return CracRuntimeInventory.unavailable(
                    "Spring runtime inventory could not be inspected; resource readiness is unknown.");
        }
    }

    private static CracRuntimeInventory collectAvailable(
            ApplicationContext applicationContext,
            Supplier<List<String>> jvmArgumentsSupplier,
            Supplier<Long> restoreTimeSupplier) {
        ClassLoader applicationClassLoader = applicationContext.getClassLoader();
        ClassLoader classLoader =
                applicationClassLoader != null ? applicationClassLoader : ClassUtils.getDefaultClassLoader();

        List<BeanObservation> poolBeans = detectBeans(applicationContext, POOL_TYPE_NAMES, classLoader);
        boolean hikariPresent = isPresent(HIKARI_DATA_SOURCE_TYPE_NAME, classLoader);
        List<BeanObservation> hikariPools = poolBeans.stream()
                .filter(observation -> isHikariPool(applicationContext, observation, hikariPresent))
                .toList();
        List<BeanObservation> nonHikariPools = poolBeans.stream()
                .filter(observation -> !isHikariPool(applicationContext, observation, hikariPresent))
                .toList();
        List<String> managedPools = new ArrayList<>();
        List<String> unverifiedPools = new ArrayList<>();
        for (BeanObservation pool : nonHikariPools) {
            Object singleton = existingSingleton(applicationContext, pool.name());
            boolean managed = singleton != null && isKnownManagedType(singleton.getClass());
            (managed ? managedPools : unverifiedPools).add(pool.display());
        }

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

        boolean cracApiPresent = isPresent(CRAC_CORE_TYPE_NAME, classLoader);
        boolean checkpointOnRefresh = "onRefresh".equals(SpringProperties.getProperty("spring.context.checkpoint"));
        List<String> warnings = new ArrayList<>();
        boolean restoredProcess = false;
        try {
            Long restoreTime = restoreTimeSupplier.get();
            restoredProcess = restoreTime != null && restoreTime >= 0;
        } catch (RuntimeException | LinkageError ex) {
            warnings.add("Public CRaC restore-time observation failed; restore state is unknown.");
        }
        try {
            List<String> arguments = jvmArgumentsSupplier.get();
            if (arguments == null) {
                warnings.add("JVM argument observations are unavailable.");
            } else if (!restoredProcess
                    && arguments.stream()
                            .anyMatch(argument -> argument != null && argument.startsWith(RESTORE_FROM_PREFIX))) {
                warnings.add("A restore JVM argument is only a launch hint, not evidence of a successful restore.");
            }
        } catch (RuntimeException | LinkageError ex) {
            warnings.add("JVM argument observations are unavailable.");
        }
        boolean applicationRunning =
                applicationContext instanceof ConfigurableApplicationContext configurable && configurable.isRunning();
        return new CracRuntimeInventory(
                unverifiedPools,
                cacheBeans,
                hikariPoolIssues,
                taskBeans,
                cracApiPresent,
                checkpointOnRefresh,
                restoredProcess,
                applicationRunning,
                managedPools,
                true,
                warnings);
    }

    private static List<String> inspectHikariPools(
            ApplicationContext applicationContext,
            List<BeanObservation> hikariPools,
            List<BeanObservation> lifecycleBeans) {
        if (hikariPools.isEmpty()) {
            return List.of();
        }

        List<String> issues = new ArrayList<>();
        // Even a single pool and lifecycle definition do not prove the adapter's actual target.
        // Inspect no private lifecycle fields and read suspension independently of pairing evidence.
        for (BeanObservation pool : hikariPools) {
            ExistingHikariPool existingPool = existingHikariPool(applicationContext, pool.name());
            String issue = lifecycleBeans.isEmpty()
                    ? "Spring Boot HikariCheckpointRestoreLifecycle bean is missing"
                    : "checkpoint lifecycle pairing is unverified across " + hikariPools.size()
                            + " Hikari pool(s) and " + lifecycleBeans.size()
                            + " lifecycle bean definition(s); counts do not establish target identity";
            if (existingPool == null) {
                issue += "; allowPoolSuspension could not be verified without initializing the bean";
            } else if (!existingPool.allowsSuspension()) {
                issue += "; allowPoolSuspension=false";
            }
            issues.add((existingPool != null ? existingPool.display() : pool.display()) + " - " + issue);
        }
        return List.copyOf(issues);
    }

    private static ExistingHikariPool existingHikariPool(ApplicationContext applicationContext, String beanName) {
        return HikariSupport.inspect(beanName, existingSingleton(applicationContext, beanName));
    }

    private static Object existingSingleton(ApplicationContext applicationContext, String beanName) {
        if (!(applicationContext.getAutowireCapableBeanFactory() instanceof ConfigurableListableBeanFactory beanFactory)
                || !beanFactory.containsSingleton(beanName)) {
            return null;
        }
        return beanFactory.getSingleton(beanName);
    }

    static boolean isKnownManagedType(Class<?> type) {
        return MANAGED_POOL_TYPE_NAMES.contains(type.getName()) && Lifecycle.class.isAssignableFrom(type);
    }

    private static boolean isHikariPool(
            ApplicationContext applicationContext, BeanObservation observation, boolean hikariPresent) {
        if (!hikariPresent) {
            return false;
        }
        return HikariSupport.isHikariType(observation.type())
                || existingHikariPool(applicationContext, observation.name()) != null;
    }

    private static List<BeanObservation> detectBeans(
            ListableBeanFactory beanFactory, List<String> typeNames, ClassLoader classLoader) {
        List<BeanObservation> observations = new ArrayList<>();
        Set<String> seenBeanNames = new HashSet<>();
        for (String typeName : typeNames) {
            Class<?> type;
            try {
                type = ClassUtils.forName(typeName, classLoader);
            } catch (ClassNotFoundException ex) {
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

    private static List<String> jvmArguments() {
        RuntimeMXBean runtimeMxBean = ManagementFactory.getRuntimeMXBean();
        return runtimeMxBean == null ? null : runtimeMxBean.getInputArguments();
    }

    private static boolean isPresent(String name, ClassLoader classLoader) {
        try {
            ClassUtils.forName(name, classLoader);
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
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
