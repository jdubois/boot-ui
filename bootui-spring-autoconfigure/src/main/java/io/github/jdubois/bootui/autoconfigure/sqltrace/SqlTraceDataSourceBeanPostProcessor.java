package io.github.jdubois.bootui.autoconfigure.sqltrace;

import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.engine.sqltrace.SqlTracedDataSource;
import io.github.jdubois.bootui.engine.sqltrace.SqlTracingProxies;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.NativeDetector;
import org.springframework.util.ClassUtils;

/**
 * Wraps every {@link DataSource} bean with BootUI's hand-written SQL tracing
 * proxy after initialization, so JDBC statements are recorded without any
 * third-party database-proxy library.
 *
 * <p>The recorder is resolved lazily through an {@link ObjectProvider} so this
 * post-processor does not force early creation of unrelated beans, and wrapping
 * is skipped entirely when tracing is disabled. The returned proxy delegates
 * {@code unwrap}/{@code isWrapperFor} to the target, so connection-pool
 * discovery still resolves the underlying pool implementation.</p>
 *
 * <p>On the JVM (not a GraalVM native image) the proxy also advertises every interface the
 * original bean's concrete class implements — beyond the standard {@code DataSource}/
 * {@code AutoCloseable}/{@code SqlTracedDataSource} set — so vendor-specific contracts such as
 * Oracle UCP's {@code PoolDataSource} survive wrapping and by-type injection of the vendor
 * interface keeps resolving to the traced proxy. In a native image the interface set must be
 * known at build time (see {@code SqlTraceRuntimeHints}), so only the fixed, pre-registered set
 * is used there.</p>
 *
 * <p>It fails open: if wrapping a {@code DataSource} throws, the original bean is
 * returned unchanged so the application's database access is never compromised.
 * This includes GraalVM native images, where creating a JDK proxy for an
 * unregistered interface set throws an {@link Error} rather than a
 * {@code RuntimeException}; the catch is deliberately broad (only re-throwing
 * {@link VirtualMachineError}) so tracing simply stays off instead of breaking
 * startup. Spring's delegating/routing {@code DataSource} wrappers are skipped
 * because they forward to another {@code DataSource} bean that is wrapped on its
 * own, which would otherwise double-count executions.</p>
 */
public final class SqlTraceDataSourceBeanPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(SqlTraceDataSourceBeanPostProcessor.class);

    private static final String[] DELEGATING_WRAPPERS = {
        "org.springframework.jdbc.datasource.DelegatingDataSource",
        "org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource"
    };

    private final ObjectProvider<SqlTraceRecorder> recorderProvider;

    public SqlTraceDataSourceBeanPostProcessor(ObjectProvider<SqlTraceRecorder> recorderProvider) {
        this.recorderProvider = recorderProvider;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof DataSource dataSource)
                || bean instanceof SqlTracedDataSource
                || isDelegatingWrapper(bean.getClass())) {
            return bean;
        }
        SqlTraceRecorder recorder = recorderProvider.getIfAvailable();
        if (recorder == null || !recorder.isEnabled()) {
            return bean;
        }
        try {
            Class<?>[] vendorInterfaces = vendorInterfaces(dataSource.getClass());
            DataSource traced = vendorInterfaces.length == 0
                    ? SqlTracingProxies.wrap(dataSource, recorder)
                    : SqlTracingProxies.wrap(
                            dataSource, recorder, SqlTracingProxies.dataSourceInterfaces(vendorInterfaces));
            recorder.registerDataSource(beanName);
            return traced;
        } catch (Throwable ex) {
            if (ex instanceof VirtualMachineError vme) {
                throw vme;
            }
            log.warn(
                    "BootUI could not enable SQL tracing for DataSource bean '{}'; leaving it unwrapped", beanName, ex);
            return bean;
        }
    }

    /**
     * Returns the interfaces implemented by {@code dataSourceClass} (including those inherited from
     * superclasses) beyond the standard {@code DataSource}/{@code AutoCloseable}/
     * {@code SqlTracedDataSource} set already covered by {@link SqlTracingProxies#dataSourceInterfaces},
     * so the proxy keeps satisfying by-type injection of a vendor-specific contract such as Oracle
     * UCP's {@code PoolDataSource}. In a GraalVM native image the interface set must be known and
     * registered at build time (see {@code SqlTraceRuntimeHints}), so no extra interfaces are added
     * there and only the fixed, pre-registered set is used.
     */
    private static Class<?>[] vendorInterfaces(Class<?> dataSourceClass) {
        if (NativeDetector.inNativeImage()) {
            return new Class<?>[0];
        }
        Set<Class<?>> extra = new LinkedHashSet<>(ClassUtils.getAllInterfacesForClassAsSet(dataSourceClass));
        extra.removeAll(Arrays.asList(SqlTracingProxies.dataSourceInterfaces()));
        return extra.toArray(new Class<?>[0]);
    }

    private static boolean isDelegatingWrapper(Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            String name = current.getName();
            for (String wrapper : DELEGATING_WRAPPERS) {
                if (wrapper.equals(name)) {
                    return true;
                }
            }
        }
        return false;
    }
}
