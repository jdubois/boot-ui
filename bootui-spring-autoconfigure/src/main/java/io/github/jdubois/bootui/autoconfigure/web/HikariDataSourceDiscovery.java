package io.github.jdubois.bootui.autoconfigure.web;

import com.zaxxer.hikari.HikariDataSource;
import io.github.jdubois.bootui.autoconfigure.datasource.SpringAopDataSources;
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

public final class HikariDataSourceDiscovery {

    private HikariDataSourceDiscovery() {}

    public static boolean hasAny(ListableBeanFactory factory) {
        return !discover(factory).isEmpty();
    }

    public static List<PoolEntry> discover(ListableBeanFactory factory) {
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

    /**
     * Returns the Hikari pool behind a DataSource proxy or wrapper, if one is exposed through Spring AOP
     * or the JDBC unwrap contract.
     */
    public static HikariDataSource hikariTarget(DataSource dataSource) {
        return hikariTarget(dataSource, false);
    }

    /**
     * Returns an already-created Hikari target without resolving a dynamic Spring AOP target or an unknown
     * wrapper. BootUI's tracing proxy is safe to unwrap because it can only wrap an existing DataSource.
     */
    public static HikariDataSource existingHikariTarget(DataSource dataSource) {
        return hikariTarget(dataSource, true);
    }

    /** What an <em>already-created</em> datasource object says about a Hikari pool behind it. */
    public enum HikariPresence {
        /** A Hikari pool was reached. */
        PRESENT,
        /** This is a known connection pool that is not Hikari and cannot contain one. */
        ABSENT,
        /** Nothing could be decided without creating a bean, resolving a dynamic proxy, or calling into the pool. */
        UNKNOWN
    }

    /**
     * Connection-pool implementations that are terminal by construction: each builds its own connections from a
     * driver or its own internal pool, so it can neither be nor contain a Hikari pool. Recognising them by type
     * is the only way to <em>prove</em> absence without calling into the object.
     *
     * <p>Checking for a URL getter would not do: a wrapper can expose {@code getJdbcUrl()}/{@code getUrl()} just
     * as readily as a pool, so a URL is evidence of a datasource, never evidence of a terminal one.</p>
     */
    private static final Set<String> TERMINAL_NON_HIKARI_POOLS = Set.of(
            "org.apache.tomcat.jdbc.pool.DataSource",
            "org.apache.commons.dbcp2.BasicDataSource",
            "com.alibaba.druid.pool.DruidDataSource",
            "oracle.ucp.jdbc.PoolDataSourceImpl",
            "org.springframework.jdbc.datasource.DriverManagerDataSource",
            "org.springframework.jdbc.datasource.SimpleDriverDataSource",
            "org.springframework.jdbc.datasource.SingleConnectionDataSource");

    /**
     * Classifies an already-created {@code DataSource} for the panel manifest, which must render without I/O or
     * side effects. Nothing here creates a bean, resolves a dynamic proxy target, or invokes a single method on
     * a datasource whose implementation BootUI does not control.
     *
     * <p>Only three things can settle the question safely, and they are all type inspection: the object
     * <em>is</em> a Hikari pool; a <em>static</em> AOP target source or BootUI's own tracing proxy already holds
     * the real object, so the same question can be asked of that; or the object is a known terminal pool that
     * is not Hikari.</p>
     *
     * <p>Everything else is {@link HikariPresence#UNKNOWN} — deliberately, not as an oversight. An unrecognised
     * wrapper might delegate to Hikari, but the only way to ask is {@code isWrapperFor}/{@code unwrap}, which is
     * arbitrary third-party code on the page-load path; a lazily created delegate can start a pool there. So the
     * manifest treats unknown as a candidate: the panel's own read is allowed to resolve fully and reports the
     * honest result, whereas hiding a configured pool leaves the user no way to find out it exists.</p>
     */
    public static HikariPresence inspectExisting(DataSource dataSource) {
        if (dataSource == null) {
            return HikariPresence.UNKNOWN;
        }
        if (dataSource instanceof HikariDataSource) {
            return HikariPresence.PRESENT;
        }
        if (SpringAopDataSources.isProxy(dataSource)) {
            DataSource target = SpringAopDataSources.staticTarget(dataSource);
            return target == null ? HikariPresence.UNKNOWN : inspectExisting(target);
        }
        if (isBootUiTracingProxy(dataSource)) {
            // BootUI's own proxy: it can only ever wrap a DataSource that already exists, and its unwrap is
            // this codebase's code, not a third party's.
            DataSource target = tracedTarget(dataSource);
            return target == null ? HikariPresence.UNKNOWN : inspectExisting(target);
        }
        return isTerminalNonHikariPool(dataSource.getClass()) ? HikariPresence.ABSENT : HikariPresence.UNKNOWN;
    }

    private static boolean isTerminalNonHikariPool(Class<?> type) {
        // A user subclass may override acquisition to delegate to a different pool.
        return TERMINAL_NON_HIKARI_POOLS.contains(type.getName());
    }

    private static DataSource tracedTarget(DataSource dataSource) {
        try {
            DataSource target = dataSource.unwrap(DataSource.class);
            return target == dataSource ? null : target;
        } catch (SQLException | RuntimeException | LinkageError ex) {
            return null;
        }
    }

    private static HikariDataSource hikariTarget(DataSource dataSource, boolean existingOnly) {
        if (dataSource instanceof HikariDataSource hikariDataSource) {
            return hikariDataSource;
        }
        HikariDataSource advisedTarget = advisedTarget(dataSource, existingOnly);
        if (advisedTarget != null) {
            return advisedTarget;
        }
        return !existingOnly || isBootUiTracingProxy(dataSource) ? wrapperTarget(dataSource) : null;
    }

    private static HikariDataSource advisedTarget(DataSource dataSource, boolean existingOnly) {
        if (!SpringAopDataSources.isProxy(dataSource)) {
            return null;
        }
        DataSource target = existingOnly
                ? SpringAopDataSources.staticTarget(dataSource)
                : SpringAopDataSources.resolvedTarget(dataSource);
        return target == null ? null : hikariTarget(target, existingOnly);
    }

    private static boolean isBootUiTracingProxy(DataSource dataSource) {
        for (Class<?> interfaceType : dataSource.getClass().getInterfaces()) {
            if ("io.github.jdubois.bootui.engine.sqltrace.SqlTracedDataSource".equals(interfaceType.getName())) {
                return true;
            }
        }
        return false;
    }

    private static HikariDataSource wrapperTarget(DataSource dataSource) {
        try {
            if (dataSource.isWrapperFor(HikariDataSource.class)) {
                return dataSource.unwrap(HikariDataSource.class);
            }
        } catch (SQLException | RuntimeException ex) {
            // Try unwrap below; some proxies only implement one side of Wrapper.
        }
        try {
            return dataSource.unwrap(HikariDataSource.class);
        } catch (SQLException | RuntimeException ex) {
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

    public record PoolEntry(String beanName, HikariDataSource dataSource) {}
}
