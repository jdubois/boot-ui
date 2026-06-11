package io.github.jdubois.bootui.autoconfigure.crac;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.util.ClassUtils;

/**
 * Collects the host application's live connection-pool beans into a {@link CracRuntimeInventory}.
 *
 * <p>The collector inspects the running {@link ApplicationContext} for the bean types that hold the
 * OS sockets CRaC cares about — JDBC {@code DataSource}s and Redis connection factories — bounded to
 * types that are actually on the classpath so a missing optional dependency never fails the scan.
 * All access is read-only and never triggers a checkpoint.</p>
 */
final class CracRuntimeInventoryCollector {

    /**
     * Bean types that own pooled OS sockets and therefore matter for a clean checkpoint. Each entry
     * is resolved lazily so an absent optional dependency (for example Spring Data Redis) is simply
     * skipped rather than failing the scan.
     */
    private static final List<String> POOL_TYPE_NAMES =
            List.of("javax.sql.DataSource", "org.springframework.data.redis.connection.RedisConnectionFactory");

    private CracRuntimeInventoryCollector() {}

    static CracRuntimeInventory collect(ApplicationContext applicationContext) {
        if (applicationContext == null) {
            return CracRuntimeInventory.empty();
        }
        try {
            List<String> poolBeans = detectPoolBeans(applicationContext);
            return new CracRuntimeInventory(poolBeans, applicationContext.getEnvironment());
        } catch (RuntimeException ex) {
            return CracRuntimeInventory.empty();
        }
    }

    private static List<String> detectPoolBeans(ListableBeanFactory beanFactory) {
        ClassLoader classLoader = CracRuntimeInventoryCollector.class.getClassLoader();
        List<String> entries = new ArrayList<>();
        for (String typeName : POOL_TYPE_NAMES) {
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
                Class<?> beanType = beanFactory.getType(beanName);
                String resolved = beanType != null ? beanType.getName() : typeName;
                entries.add(beanName + " : " + resolved);
            }
        }
        return entries;
    }
}
