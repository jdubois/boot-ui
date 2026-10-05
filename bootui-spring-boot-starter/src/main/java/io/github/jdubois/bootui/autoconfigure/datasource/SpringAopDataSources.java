package io.github.jdubois.bootui.autoconfigure.datasource;

import javax.sql.DataSource;

/**
 * Reflective, optional-dependency-safe access to Spring AOP's {@code Advised} proxy shape, used to decide
 * whether a proxied {@code DataSource} can be looked behind <em>without side effects</em>.
 *
 * <p>Spring AOP is an optional dependency of this module, so — like {@link DelegatingDataSources} — everything
 * here is done by class name and never by import.</p>
 *
 * <p>The distinction that matters is the proxy's {@code TargetSource}:</p>
 *
 * <ul>
 *   <li><strong>Static</strong> ({@code SingletonTargetSource} and friends): the target already exists and
 *       {@code getTarget()} only hands it back. Reading it is free of side effects, and it is strictly better
 *       than keeping the proxy — every subsequent question is then asked of the real pool rather than routed
 *       through an interceptor chain.</li>
 *   <li><strong>Dynamic</strong> ({@code LazyInitTargetSource}, prototype and pooling target sources, ...):
 *       {@code getTarget()} creates or fetches a target, which can instantiate a bean or open a resource.
 *       Worse, <em>any</em> method invoked on such a proxy does the same — so a URL getter that looks like a
 *       harmless read initializes the target. These proxies must be left untouched on the page-load path and
 *       reported as unobserved instead.</li>
 * </ul>
 */
public final class SpringAopDataSources {

    private static final String ADVISED = "org.springframework.aop.framework.Advised";
    private static final String TARGET_SOURCE = "org.springframework.aop.TargetSource";

    private SpringAopDataSources() {}

    /** Whether this object is a Spring AOP proxy. False whenever Spring AOP is not on the classpath. */
    public static boolean isProxy(DataSource dataSource) {
        Class<?> advised = type(ADVISED);
        return advised != null && advised.isInstance(dataSource);
    }

    /**
     * The already-created target of a <em>static</em> target source, or {@code null} when the proxy has a
     * dynamic target source, exposes no usable {@code DataSource} target, or refuses to describe itself. A
     * dynamic target source is never resolved.
     */
    public static DataSource staticTarget(DataSource dataSource) {
        return target(dataSource, true);
    }

    /**
     * The proxy's target, resolving a dynamic target source when necessary. Only for explicitly user-triggered
     * paths that are already allowed to create beans; never for the panel manifest.
     */
    public static DataSource resolvedTarget(DataSource dataSource) {
        return target(dataSource, false);
    }

    private static DataSource target(DataSource dataSource, boolean staticOnly) {
        Class<?> advised = type(ADVISED);
        Class<?> targetSourceType = type(TARGET_SOURCE);
        if (advised == null || targetSourceType == null || !advised.isInstance(dataSource)) {
            return null;
        }
        try {
            // Advised methods are answered by the proxy's own configuration and never reach the target.
            Object targetSource = advised.getMethod("getTargetSource").invoke(dataSource);
            if (targetSource == null) {
                return null;
            }
            if (staticOnly
                    && !Boolean.TRUE.equals(
                            targetSourceType.getMethod("isStatic").invoke(targetSource))) {
                return null;
            }
            Object target = targetSourceType.getMethod("getTarget").invoke(targetSource);
            return target != dataSource && target instanceof DataSource targetDataSource ? targetDataSource : null;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
            return null;
        }
    }

    private static Class<?> type(String name) {
        try {
            return Class.forName(name, false, SpringAopDataSources.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError absent) {
            return null;
        }
    }
}
