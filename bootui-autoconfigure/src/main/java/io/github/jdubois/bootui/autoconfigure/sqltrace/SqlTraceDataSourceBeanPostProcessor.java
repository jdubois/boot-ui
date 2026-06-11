package io.github.jdubois.bootui.autoconfigure.sqltrace;

import javax.sql.DataSource;
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
 */
public final class SqlTraceDataSourceBeanPostProcessor implements BeanPostProcessor {

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
        return SqlTracingProxies.wrap(dataSource, recorder);
    }
}
