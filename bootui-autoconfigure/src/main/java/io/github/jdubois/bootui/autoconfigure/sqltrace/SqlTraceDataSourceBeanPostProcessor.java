package io.github.jdubois.bootui.autoconfigure.sqltrace;

import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

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
            DataSource traced = SqlTracingProxies.wrap(dataSource, recorder);
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
