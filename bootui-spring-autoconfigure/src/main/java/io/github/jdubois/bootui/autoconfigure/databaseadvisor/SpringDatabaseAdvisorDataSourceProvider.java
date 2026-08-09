package io.github.jdubois.bootui.autoconfigure.databaseadvisor;

import io.github.jdubois.bootui.spi.DatabaseAdvisorDataSourceProvider;
import io.github.jdubois.bootui.spi.NamedDataSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Spring binding of the {@link DatabaseAdvisorDataSourceProvider} SPI: discovers the application's
 * {@code DataSource} beans directly, skipping Spring's delegating/routing {@code DataSource} wrappers
 * (which forward to another {@code DataSource} bean that is discovered on its own) the same way SQL
 * Trace's {@code SqlTraceDataSourceBeanPostProcessor} skips them, so a wrapped datasource is never
 * introspected twice under two different names.
 */
public final class SpringDatabaseAdvisorDataSourceProvider implements DatabaseAdvisorDataSourceProvider {

    private static final String[] DELEGATING_WRAPPERS = {
        "org.springframework.jdbc.datasource.DelegatingDataSource",
        "org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource"
    };

    private final ObjectProvider<ListableBeanFactory> beanFactoryProvider;

    public SpringDatabaseAdvisorDataSourceProvider(ObjectProvider<ListableBeanFactory> beanFactoryProvider) {
        this.beanFactoryProvider = beanFactoryProvider;
    }

    @Override
    public List<NamedDataSource> dataSources() {
        ListableBeanFactory factory = beanFactoryProvider.getIfAvailable();
        if (factory == null) {
            return List.of();
        }
        List<NamedDataSource> dataSources = new ArrayList<>();
        Set<DataSource> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (String beanName : beanNamesForType(factory)) {
            DataSource dataSource = bean(factory, beanName);
            if (dataSource == null || isDelegatingWrapper(dataSource.getClass()) || !seen.add(dataSource)) {
                continue;
            }
            dataSources.add(new NamedDataSource(strip(beanName), dataSource));
        }
        return dataSources;
    }

    private static String[] beanNamesForType(ListableBeanFactory factory) {
        try {
            String[] beanNames = factory.getBeanNamesForType(DataSource.class);
            return beanNames == null ? new String[0] : beanNames;
        } catch (BeansException ex) {
            return new String[0];
        }
    }

    private static DataSource bean(ListableBeanFactory factory, String beanName) {
        try {
            return factory.getBean(beanName, DataSource.class);
        } catch (BeansException ex) {
            return null;
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

    private static String strip(String beanName) {
        return beanName.startsWith("&") ? beanName.substring(1) : beanName;
    }
}
