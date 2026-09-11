package io.github.jdubois.bootui.autoconfigure.sqltrace;

import io.github.jdubois.bootui.autoconfigure.datasource.DelegatingDataSources;
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
 * startup.</p>
 *
 * <p>Spring's {@code DataSource} wrappers are never replaced, because the bean's
 * concrete type is part of the application's contract and because their targets
 * are usually beans that get wrapped on their own — wrapping both would
 * double-count executions. A wrapper that owns the only reference to a pool is
 * still traced, though: this post-processor runs after Spring's own
 * {@code LazyConnectionDataSourceBeanPostProcessor}, so under
 * {@code spring.datasource.connection-fetch=lazy} the single {@code dataSource}
 * bean <em>is</em> a {@code LazyConnectionDataSourceProxy} and the pool inside it
 * is not a bean at all. For a single-target wrapper whose target is neither
 * already traced nor itself a wrapper, the target is wrapped and re-injected in
 * place with {@code setTargetDataSource}, and the untouched wrapper bean is
 * returned. Routing wrappers are left alone entirely: replacing their resolved
 * targets means re-running {@code initialize()}, and those targets are in
 * practice beans that are traced individually (issue #924).</p>
 */
public final class SqlTraceDataSourceBeanPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(SqlTraceDataSourceBeanPostProcessor.class);

    private final ObjectProvider<SqlTraceRecorder> recorderProvider;

    public SqlTraceDataSourceBeanPostProcessor(ObjectProvider<SqlTraceRecorder> recorderProvider) {
        this.recorderProvider = recorderProvider;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof DataSource dataSource) || bean instanceof SqlTracedDataSource) {
            return bean;
        }
        SqlTraceRecorder recorder = recorderProvider.getIfAvailable();
        if (recorder == null || !recorder.isEnabled()) {
            return bean;
        }
        if (DelegatingDataSources.isRouting(dataSource.getClass())) {
            return bean;
        }
        if (DelegatingDataSources.isSingleTarget(dataSource.getClass())) {
            traceTargetInPlace(dataSource, beanName, recorder);
            return bean;
        }
        try {
            DataSource traced = trace(dataSource, recorder, beanName);
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
     * Traces the pool a single-target wrapper forwards to, when that pool is not a {@code DataSource} bean of its
     * own and so would otherwise never be wrapped. A target that is already traced, or is itself one of Spring's
     * wrappers, is left alone so no execution is recorded twice.
     */
    private static void traceTargetInPlace(DataSource wrapper, String beanName, SqlTraceRecorder recorder) {
        DataSource target = DelegatingDataSources.target(wrapper);
        if (target == null
                || target instanceof SqlTracedDataSource
                || DelegatingDataSources.isWrapper(target.getClass())) {
            return;
        }
        try {
            if (DelegatingDataSources.replaceTarget(wrapper, trace(target, recorder, beanName))) {
                recorder.registerDataSource(beanName);
            }
        } catch (Throwable ex) {
            if (ex instanceof VirtualMachineError vme) {
                throw vme;
            }
            log.warn(
                    "BootUI could not enable SQL tracing for the DataSource behind bean '{}'; leaving it unwrapped",
                    beanName,
                    ex);
        }
    }

    private static DataSource trace(DataSource dataSource, SqlTraceRecorder recorder, String beanName) {
        Class<?>[] vendorInterfaces = vendorInterfaces(dataSource.getClass());
        return vendorInterfaces.length == 0
                ? SqlTracingProxies.wrapNamed(dataSource, recorder, beanName)
                : SqlTracingProxies.wrapNamed(
                        dataSource, recorder, beanName, SqlTracingProxies.dataSourceInterfaces(vendorInterfaces));
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
}
