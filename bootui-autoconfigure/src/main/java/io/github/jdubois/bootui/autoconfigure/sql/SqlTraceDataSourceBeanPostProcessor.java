package io.github.jdubois.bootui.autoconfigure.sql;

import javax.sql.DataSource;
import net.ttddyy.dsproxy.support.ProxyDataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Wraps each application {@code DataSource} bean in a datasource-proxy {@link ProxyDataSource} so
 * that every JDBC statement is reported to the {@link SqlTraceStore}.
 *
 * <p>This is the generic, ORM-agnostic interception point: JPA/Hibernate, {@code JdbcTemplate},
 * MyBatis, jOOQ and plain JDBC all obtain connections from the {@code DataSource}, so wrapping it
 * captures their SQL uniformly.</p>
 *
 * <p>The processor is only registered while BootUI is active (typically the {@code dev}/{@code local}
 * profiles) and can be disabled with {@code bootui.sql-trace.enabled=false}. It fails open: if
 * wrapping a {@code DataSource} throws, the original bean is returned unchanged so the application's
 * database access is never compromised.</p>
 */
public class SqlTraceDataSourceBeanPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(SqlTraceDataSourceBeanPostProcessor.class);

    // Spring's delegating DataSource wrappers route to another DataSource bean; wrapping them would
    // double-count executions, so they are skipped and only the underlying pool is wrapped.
    private static final String[] DELEGATING_WRAPPERS = {
        "org.springframework.jdbc.datasource.DelegatingDataSource",
        "org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource"
    };

    private final ObjectProvider<SqlTraceStore> storeProvider;
    private final boolean enabled;

    private volatile SqlTraceQueryListener listener;

    public SqlTraceDataSourceBeanPostProcessor(ObjectProvider<SqlTraceStore> storeProvider, boolean enabled) {
        this.storeProvider = storeProvider;
        this.enabled = enabled;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!enabled || !(bean instanceof DataSource dataSource)) {
            return bean;
        }
        if (bean instanceof ProxyDataSource || isDelegatingWrapper(bean.getClass())) {
            return bean;
        }
        try {
            SqlTraceStore store = storeProvider.getObject();
            store.registerDataSource(beanName);
            return ProxyDataSourceBuilder.create(beanName, dataSource)
                    .listener(listener(store))
                    .build();
        } catch (RuntimeException ex) {
            log.warn(
                    "BootUI could not enable SQL tracing for DataSource bean '{}'; leaving it unwrapped", beanName, ex);
            return bean;
        }
    }

    private SqlTraceQueryListener listener(SqlTraceStore store) {
        SqlTraceQueryListener current = listener;
        if (current == null) {
            synchronized (this) {
                current = listener;
                if (current == null) {
                    current = new SqlTraceQueryListener(store);
                    listener = current;
                }
            }
        }
        return current;
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
