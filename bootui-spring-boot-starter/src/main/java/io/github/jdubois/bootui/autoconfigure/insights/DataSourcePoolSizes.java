package io.github.jdubois.bootui.autoconfigure.insights;

import java.lang.reflect.Method;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.context.ApplicationContext;

/**
 * The maximum size of a data source's connection pool, by the bean name SQL Trace gives its connections
 * ({@code docs/PLAN-v2.md} §5.5). HikariCP is read through {@code HikariConfigMXBean}, resolved reflectively so the
 * adapter loads without it; any other pool, or a bean that is not a data source, is unknown.
 */
final class DataSourcePoolSizes implements Function<String, Integer> {

    static final String HIKARI_CONFIG = "com.zaxxer.hikari.HikariConfigMXBean";

    private final ApplicationContext context;

    DataSourcePoolSizes(ApplicationContext context) {
        this.context = context;
    }

    @Override
    public Integer apply(String beanName) {
        if (beanName == null || !context.containsBean(beanName)) {
            return null;
        }
        try {
            if (!(context.getBean(beanName) instanceof DataSource dataSource)) {
                return null;
            }
            Class<?> hikari =
                    Class.forName(HIKARI_CONFIG, false, dataSource.getClass().getClassLoader());
            if (!dataSource.isWrapperFor(hikari)) {
                return null;
            }
            Object config = dataSource.unwrap(hikari);
            Method maximum = hikari.getMethod("getMaximumPoolSize");
            return maximum.invoke(config) instanceof Integer size && size > 0 ? size : null;
        } catch (ReflectiveOperationException | LinkageError | java.sql.SQLException | RuntimeException ex) {
            return null;
        }
    }
}
