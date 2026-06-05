package io.github.jdubois.bootui.autoconfigure.web;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.util.ClassUtils;

final class HikariDataSourceDiscovery {

    private HikariDataSourceDiscovery() {}

    static boolean hasAny(ListableBeanFactory factory) {
        return !discover(factory).isEmpty();
    }

    static List<PoolEntry> discover(ListableBeanFactory factory) {
        List<PoolEntry> entries = new ArrayList<>();
        Set<String> seenBeanNames = new HashSet<>();
        Set<HikariDataSource> seenDataSources = Collections.newSetFromMap(new IdentityHashMap<>());
        addDirectHikariBeans(factory, entries, seenBeanNames, seenDataSources);
        addDataSourceBeans(factory, entries, seenBeanNames, seenDataSources);
        return entries;
    }

    private static void addDirectHikariBeans(
            ListableBeanFactory factory,
            List<PoolEntry> entries,
            Set<String> seenBeanNames,
            Set<HikariDataSource> seenDataSources) {
        for (String beanName : beanNamesForType(factory, HikariDataSource.class)) {
            HikariDataSource dataSource = bean(factory, beanName, HikariDataSource.class);
            add(entries, seenBeanNames, seenDataSources, beanName, dataSource);
        }
    }

    private static void addDataSourceBeans(
            ListableBeanFactory factory,
            List<PoolEntry> entries,
            Set<String> seenBeanNames,
            Set<HikariDataSource> seenDataSources) {
        for (String beanName : beanNamesForType(factory, DataSource.class)) {
            DataSource dataSource = bean(factory, beanName, DataSource.class);
            if (dataSource == null) {
                continue;
            }
            add(entries, seenBeanNames, seenDataSources, beanName, hikariTarget(dataSource));
        }
    }

    private static <T> T bean(ListableBeanFactory factory, String beanName, Class<T> type) {
        try {
            return factory.getBean(beanName, type);
        } catch (BeansException ex) {
            return null;
        }
    }

    private static HikariDataSource hikariTarget(DataSource dataSource) {
        if (dataSource instanceof HikariDataSource hikariDataSource) {
            return hikariDataSource;
        }
        HikariDataSource advisedTarget = advisedTarget(dataSource);
        if (advisedTarget != null) {
            return advisedTarget;
        }
        return wrapperTarget(dataSource);
    }

    private static HikariDataSource advisedTarget(DataSource dataSource) {
        ClassLoader classLoader = HikariDataSourceDiscovery.class.getClassLoader();
        if (!ClassUtils.isPresent("org.springframework.aop.framework.Advised", classLoader)) {
            return null;
        }
        try {
            Class<?> advisedType = ClassUtils.forName("org.springframework.aop.framework.Advised", classLoader);
            if (!advisedType.isInstance(dataSource)) {
                return null;
            }
            Object targetSource = advisedType.getMethod("getTargetSource").invoke(dataSource);
            if (targetSource == null) {
                return null;
            }
            Class<?> targetSourceType = ClassUtils.forName("org.springframework.aop.TargetSource", classLoader);
            Object target = targetSourceType.getMethod("getTarget").invoke(targetSource);
            if (target == dataSource || !(target instanceof DataSource targetDataSource)) {
                return null;
            }
            return hikariTarget(targetDataSource);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
            return null;
        }
    }

    private static HikariDataSource wrapperTarget(DataSource dataSource) {
        try {
            if (dataSource.isWrapperFor(HikariDataSource.class)) {
                return dataSource.unwrap(HikariDataSource.class);
            }
        } catch (SQLException ex) {
            // Try unwrap below; some proxies only implement one side of Wrapper.
        }
        try {
            return dataSource.unwrap(HikariDataSource.class);
        } catch (SQLException ex) {
            return null;
        }
    }

    private static void add(
            List<PoolEntry> entries,
            Set<String> seenBeanNames,
            Set<HikariDataSource> seenDataSources,
            String beanName,
            HikariDataSource dataSource) {
        if (dataSource == null) {
            return;
        }
        String normalizedBeanName = strip(beanName);
        if (!seenBeanNames.add(normalizedBeanName) || !seenDataSources.add(dataSource)) {
            return;
        }
        entries.add(new PoolEntry(normalizedBeanName, dataSource));
    }

    private static String[] beanNamesForType(ListableBeanFactory factory, Class<?> type) {
        try {
            String[] beanNames = factory.getBeanNamesForType(type);
            return beanNames == null ? new String[0] : beanNames;
        } catch (BeansException ex) {
            return new String[0];
        }
    }

    private static String strip(String beanName) {
        return beanName.startsWith("&") ? beanName.substring(1) : beanName;
    }

    record PoolEntry(String beanName, HikariDataSource dataSource) {}
}
