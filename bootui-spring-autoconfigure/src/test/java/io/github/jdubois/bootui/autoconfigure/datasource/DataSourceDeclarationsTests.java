package io.github.jdubois.bootui.autoconfigure.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.zaxxer.hikari.HikariDataSource;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.target.AbstractLazyCreationTargetSource;
import org.springframework.context.support.GenericApplicationContext;

/**
 * The shared declaration walk is the single gate in front of every page-load datasource reader — the pool
 * check and the URL-based MySQL/PostgreSQL detectors alike. What it emits must be safe to interrogate, and
 * what it cannot reduce to such an object must be reported through {@code incomplete()}.
 */
class DataSourceDeclarationsTests {

    @Test
    void anApplicationWithNoDataSourceBeanIsAbsentRatherThanIncomplete() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.refresh();

            DataSourceDeclarations.Snapshot snapshot = DataSourceDeclarations.inspect(context);

            assertThat(snapshot.present()).isFalse();
            assertThat(snapshot.incomplete()).isFalse();
            assertThat(snapshot.sources()).isEmpty();
        }
    }

    @Test
    void anUncreatedLazyBeanIsDeclaredButUnobservedAndIsNeverCreated() {
        AtomicBoolean supplierCalled = new AtomicBoolean();
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(
                    "dataSource",
                    DataSource.class,
                    () -> {
                        supplierCalled.set(true);
                        return new HikariDataSource();
                    },
                    definition -> definition.setLazyInit(true));
            context.refresh();

            DataSourceDeclarations.Snapshot snapshot = DataSourceDeclarations.inspect(context);

            assertThat(snapshot.present()).isTrue();
            assertThat(snapshot.incomplete()).isTrue();
            assertThat(snapshot.sources()).isEmpty();
            assertThat(supplierCalled).isFalse();
        }
    }

    @Test
    void aStaticallyProxiedPoolIsEmittedAsTheAlreadyCreatedPoolItself() {
        HikariDataSource pool = mock(HikariDataSource.class);
        ProxyFactory proxyFactory = new ProxyFactory();
        proxyFactory.setInterfaces(DataSource.class);
        proxyFactory.setTarget(pool);
        DataSource proxy = (DataSource) proxyFactory.getProxy();

        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean("dataSource", DataSource.class, () -> proxy);
            context.refresh();

            DataSourceDeclarations.Snapshot snapshot = DataSourceDeclarations.inspect(context);

            assertThat(snapshot.sources())
                    .as("readers get the pool, not a proxy that routes every later question through advice")
                    .containsExactly(pool);
            assertThat(snapshot.incomplete()).isFalse();
        }
    }

    /**
     * The regression this gate exists for: a dynamic target source builds its target on any invocation, so
     * emitting the proxy would let a URL getter — a read, as far as the detector knows — create the bean.
     */
    @Test
    void aDynamicallyProxiedDatasourceIsWithheldFromReadersAndNeverResolved() {
        AtomicBoolean resolved = new AtomicBoolean();
        ProxyFactory proxyFactory = new ProxyFactory();
        proxyFactory.setInterfaces(DataSource.class);
        proxyFactory.setTargetSource(new AbstractLazyCreationTargetSource() {
            @Override
            public Class<?> getTargetClass() {
                return DataSource.class;
            }

            @Override
            protected Object createObject() {
                resolved.set(true);
                return mock(HikariDataSource.class);
            }
        });
        DataSource proxy = (DataSource) proxyFactory.getProxy();

        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean("dataSource", DataSource.class, () -> proxy);
            context.refresh();

            DataSourceDeclarations.Snapshot snapshot = DataSourceDeclarations.inspect(context);

            assertThat(snapshot.present()).isTrue();
            assertThat(snapshot.sources())
                    .as("a datasource that initializes on touch must not be handed to any reader")
                    .isEmpty();
            assertThat(snapshot.incomplete())
                    .as("and it must be reported as unobserved, not as nothing")
                    .isTrue();
            assertThat(resolved).isFalse();
        }
    }
}
