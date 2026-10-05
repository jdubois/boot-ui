package io.github.jdubois.bootui.autoconfigure.datasource;

import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;

/**
 * Reflective access to Spring's two {@code DataSource} wrapper shapes — the single-target
 * {@code org.springframework.jdbc.datasource.DelegatingDataSource} (whose best-known subclass is
 * {@code LazyConnectionDataSourceProxy}) and the multi-target
 * {@code org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource}.
 *
 * <p>Everything here is done <em>by class name</em>, never by import: {@code spring-boot-jdbc} (and therefore
 * {@code spring-jdbc}) is an optional dependency of this module, so an always-loaded class must not link those
 * types.</p>
 *
 * <p>Target resolution deliberately does not use the JDBC {@code unwrap} contract. {@code
 * DelegatingDataSource.unwrap(DataSource.class)} returns the <em>wrapper</em> — {@code iface.isInstance(this)}
 * holds — so it can never reveal the pool behind it. Only the public {@code getTargetDataSource()} and
 * {@code getResolvedDataSources()} accessors can, and neither of them opens a connection.</p>
 */
public final class DelegatingDataSources {

    private static final String DELEGATING = "org.springframework.jdbc.datasource.DelegatingDataSource";
    private static final String ROUTING = "org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource";

    private DelegatingDataSources() {}

    /** Whether {@code type} is either of Spring's delegating or routing {@code DataSource} wrappers. */
    public static boolean isWrapper(Class<?> type) {
        return isSingleTarget(type) || isRouting(type);
    }

    /** Whether {@code type} forwards to exactly one target {@code DataSource} it exposes. */
    public static boolean isSingleTarget(Class<?> type) {
        return declaringClass(type, DELEGATING) != null;
    }

    /** Whether {@code type} routes to one of several targets chosen per call from a lookup key. */
    public static boolean isRouting(Class<?> type) {
        return declaringClass(type, ROUTING) != null;
    }

    /** The single target of a delegating wrapper, or {@code null} when it cannot be read. */
    public static DataSource target(DataSource wrapper) {
        return invokeDataSource(wrapper, DELEGATING, "getTargetDataSource");
    }

    /**
     * Replaces the single target of a delegating wrapper in place, reporting whether it worked. Used by SQL Trace
     * to insert its proxy underneath a wrapper that owns the only reference to a pool.
     */
    public static boolean replaceTarget(DataSource wrapper, DataSource replacement) {
        Class<?> declaring = declaringClass(wrapper.getClass(), DELEGATING);
        if (declaring == null) {
            return false;
        }
        try {
            Method setter = declaring.getMethod("setTargetDataSource", DataSource.class);
            setter.invoke(wrapper, replacement);
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
            return false;
        }
    }

    /**
     * The already-resolved targets of a routing wrapper, keyed by lookup key and ordered by the key's string form
     * so the report is stable (Spring resolves them into a plain {@code HashMap}), with the default target added
     * last under the {@code "default"} key when it is not one of them. Never calls the wrapper's own
     * {@code determineCurrentLookupKey()}, which would need a live request thread.
     */
    public static Map<Object, DataSource> routingTargets(DataSource wrapper) {
        Map<Object, DataSource> targets = new LinkedHashMap<>();
        Class<?> declaring = declaringClass(wrapper.getClass(), ROUTING);
        if (declaring == null) {
            return targets;
        }
        try {
            Object resolved = declaring.getMethod("getResolvedDataSources").invoke(wrapper);
            if (resolved instanceof Map<?, ?> map) {
                map.entrySet().stream()
                        .filter(entry -> entry.getKey() != null && entry.getValue() instanceof DataSource)
                        .sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                        .forEach(entry -> targets.put(entry.getKey(), (DataSource) entry.getValue()));
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
            // A routing wrapper that will not describe its targets is reported through its own bean instead.
        }
        DataSource defaultTarget = invokeDataSource(wrapper, ROUTING, "getResolvedDefaultDataSource");
        if (defaultTarget != null && !targets.containsValue(defaultTarget)) {
            targets.put("default", defaultTarget);
        }
        return targets;
    }

    private static DataSource invokeDataSource(DataSource wrapper, String wrapperType, String method) {
        Class<?> declaring = declaringClass(wrapper.getClass(), wrapperType);
        if (declaring == null) {
            return null;
        }
        try {
            Object value = declaring.getMethod(method).invoke(wrapper);
            return value instanceof DataSource dataSource && dataSource != wrapper ? dataSource : null;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
            return null;
        }
    }

    /**
     * The class named {@code wrapperType} in {@code type}'s hierarchy, so reflective calls are made against the
     * public Spring class rather than a possibly package-private application subclass.
     */
    private static Class<?> declaringClass(Class<?> type, String wrapperType) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            if (wrapperType.equals(current.getName())) {
                return current;
            }
        }
        return null;
    }
}
