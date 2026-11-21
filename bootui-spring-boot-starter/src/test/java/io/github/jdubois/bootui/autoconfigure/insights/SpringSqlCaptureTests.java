package io.github.jdubois.bootui.autoconfigure.insights;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.insights.SqlCapture;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.web.context.reactive.GenericReactiveWebApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

class SpringSqlCaptureTests {

    /** Stands in for R2DBC's {@code ConnectionFactory}, which this module's tests do not depend on. */
    interface ConnectionFactory {}

    @Test
    void anR2dbcApplicationWithoutATracedDataSourceIsNamedAsSuch() {
        try (GenericReactiveWebApplicationContext context = new GenericReactiveWebApplicationContext()) {
            context.registerBean(SqlTraceRecorder.class, SpringSqlCaptureTests::recorder);
            context.registerBean(ConnectionFactory.class, () -> new ConnectionFactory() {});
            context.refresh();

            assertThat(new SpringSqlCapture(context, ConnectionFactory.class.getName()).get())
                    .isEqualTo(SqlCapture.notRecorded(SqlCapture.R2DBC_ONLY));
        }
    }

    @Test
    void aTracedDataSourceBesideR2dbcRecordsJdbcAndSaysWhatItMisses() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            SqlTraceRecorder recorder = recorder();
            recorder.registerDataSource("dataSource");
            context.registerBean(SqlTraceRecorder.class, () -> recorder);
            context.registerBean(ConnectionFactory.class, () -> new ConnectionFactory() {});
            context.refresh();

            assertThat(new SpringSqlCapture(context, ConnectionFactory.class.getName()).get())
                    .isEqualTo(SqlCapture.recordedExcept(SqlCapture.R2DBC_NOT_RECORDED));
            assertThat(new SpringSqlCapture(context).get())
                    .as("without R2DBC on the classpath, a traced DataSource records everything")
                    .isEqualTo(SqlCapture.capturing());
        }
    }

    @Test
    void lazyAndPrototypeR2dbcFactoriesAreIdentifiedWithoutCreatingThem() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            AtomicInteger callbacks = new AtomicInteger();
            context.registerBean(
                    "lazyReactive",
                    ConnectionFactory.class,
                    () -> {
                        callbacks.incrementAndGet();
                        throw new AssertionError("a lazy connection factory must not be created");
                    },
                    definition -> definition.setLazyInit(true));
            context.registerBean(
                    "prototypeReactive",
                    ConnectionFactory.class,
                    () -> {
                        callbacks.incrementAndGet();
                        throw new AssertionError("a prototype connection factory must not be created");
                    },
                    definition -> definition.setScope("prototype"));
            context.refresh();

            assertThat(new SpringSqlCapture(context, ConnectionFactory.class.getName()).get())
                    .isEqualTo(SqlCapture.notRecorded(SqlCapture.R2DBC_ONLY));
            assertThat(callbacks).hasValue(0);
        }
    }

    @Test
    void aMixedJdbcApplicationQualifiesAPrototypeR2dbcFactory() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            AtomicInteger callbacks = new AtomicInteger();
            SqlTraceRecorder recorder = recorder();
            recorder.registerDataSource("dataSource");
            context.registerBean(SqlTraceRecorder.class, () -> recorder);
            context.registerBean(
                    "prototypeReactive",
                    ConnectionFactory.class,
                    () -> {
                        callbacks.incrementAndGet();
                        throw new AssertionError("a prototype connection factory must not be created");
                    },
                    definition -> definition.setScope("prototype"));
            context.refresh();

            assertThat(new SpringSqlCapture(context, ConnectionFactory.class.getName()).get())
                    .isEqualTo(SqlCapture.recordedExcept(SqlCapture.R2DBC_NOT_RECORDED));
            assertThat(callbacks).hasValue(0);
        }
    }

    @Test
    void aConclusiveNonReactiveFactoryDoesNotQualifyRecordedJdbcCapture() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            AtomicInteger callbacks = new AtomicInteger();
            SqlTraceRecorder recorder = recorder();
            recorder.registerDataSource("dataSource");
            context.getBeanFactory().registerSingleton("recorder", recorder);
            context.getBeanFactory().registerSingleton("unrelatedFactory", new FactoryBean<String>() {
                @Override
                public String getObject() {
                    callbacks.incrementAndGet();
                    return "application";
                }

                @Override
                public Class<?> getObjectType() {
                    callbacks.incrementAndGet();
                    return String.class;
                }
            });
            context.refresh();
            callbacks.set(0);

            assertThat(new SpringSqlCapture(context, ConnectionFactory.class.getName()).get())
                    .isEqualTo(SqlCapture.capturing());
            assertThat(callbacks).hasValue(0);
        }
    }

    @Test
    void aCachedOpaqueReactiveFactoryProductIsRecognizedWithoutAnotherCallback() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            AtomicInteger callbacks = new AtomicInteger();
            SqlTraceRecorder recorder = recorder();
            recorder.registerDataSource("dataSource");
            context.registerBean(SqlTraceRecorder.class, () -> recorder);
            context.registerBeanDefinition("reactiveFactory", new RootBeanDefinition(OpaqueConnectionFactory.class));
            OpaqueConnectionFactory.callbacks = callbacks;
            context.refresh();
            assertThat(context.getBean("reactiveFactory")).isInstanceOf(ConnectionFactory.class);
            callbacks.set(0);

            assertThat(new SpringSqlCapture(context, ConnectionFactory.class.getName()).get())
                    .isEqualTo(SqlCapture.recordedExcept(SqlCapture.R2DBC_NOT_RECORDED));
            assertThat(callbacks).hasValue(0);
        }
    }

    @Test
    void anAbstractReactiveFactoryDefinitionDoesNotEstablishR2dbcUse() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            RootBeanDefinition definition = new RootBeanDefinition(ConnectionFactory.class);
            definition.setAbstract(true);
            context.registerBeanDefinition("abstractReactive", definition);
            context.refresh();

            assertThat(new SpringSqlCapture(context, ConnectionFactory.class.getName()).get())
                    .isEqualTo(SqlCapture.notRecorded(SqlCapture.NOT_RECORDED));
        }
    }

    @Test
    void unresolvedOrdinaryDefinitionsKeepJdbcCoverageIncomplete() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            SqlTraceRecorder recorder = recorder();
            recorder.registerDataSource("dataSource");
            context.getBeanFactory().registerSingleton("recorder", recorder);
            RootBeanDefinition unresolved = new RootBeanDefinition(Object.class);
            unresolved.setLazyInit(true);
            context.registerBeanDefinition("unresolved", unresolved);
            context.refresh();

            assertThat(new SpringSqlCapture(context, ConnectionFactory.class.getName()).get())
                    .satisfies(capture -> {
                        assertThat(capture.recorded()).isTrue();
                        assertThat(capture.reason()).contains("cannot be identified");
                    });
        }
    }

    @Test
    void aParentApplicationContextKeepsSqlCaptureIncomplete() {
        try (GenericApplicationContext parent = new GenericApplicationContext();
                GenericApplicationContext child = new GenericApplicationContext(parent)) {
            child.refresh();

            assertThat(new SpringSqlCapture(child, ConnectionFactory.class.getName())
                            .get()
                            .recorded())
                    .isFalse();
        }
    }

    @Test
    void aLazyReactiveFactoryBeanProductIsIdentifiedFromItsGenericType() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(
                    "reactiveFactory", ConnectionFactoryBean.class, definition -> definition.setLazyInit(true));
            context.refresh();

            assertThat(new SpringSqlCapture(context, ConnectionFactory.class.getName()).get())
                    .isEqualTo(SqlCapture.notRecorded(SqlCapture.R2DBC_ONLY));
            assertThat(ConnectionFactoryBean.creations).hasValue(0);
        }
    }

    @Test
    void withoutSqlTraceNothingIsRecorded() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.refresh();

            assertThat(new SpringSqlCapture(context).get()).isEqualTo(SqlCapture.notRecorded(SqlCapture.NOT_RECORDED));
        }
    }

    @Test
    void passiveCaptureReadsDoNotCreateLazyRecordersOrInspectApplicationFactoryProducts() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            AtomicInteger callbacks = new AtomicInteger();
            context.registerBean(
                    "lazyRecorder",
                    SqlTraceRecorder.class,
                    () -> {
                        callbacks.incrementAndGet();
                        throw new AssertionError("a passive capture read must not create a recorder");
                    },
                    definition -> definition.setLazyInit(true));
            context.refresh();
            assertThat(new SpringSqlCapture(context).get().recorded()).isFalse();
            SqlTraceRecorder recorder = recorder();
            recorder.registerDataSource("dataSource");
            context.getBeanFactory().registerSingleton("nativeRecorder", recorder);
            context.getBeanFactory().registerSingleton("applicationFactory", new FactoryBean<>() {
                @Override
                public Object getObject() {
                    callbacks.incrementAndGet();
                    throw new AssertionError("application factories must not run");
                }

                @Override
                public Class<?> getObjectType() {
                    callbacks.incrementAndGet();
                    throw new AssertionError("application type callbacks must not run");
                }
            });
            SqlCapture capture = new SpringSqlCapture(context, ConnectionFactory.class.getName()).get();
            assertThat(capture.recorded()).isTrue();
            assertThat(capture.reason()).contains("cannot be identified without invoking application callbacks");
            assertThat(callbacks.get()).isZero();
        }
    }

    private static SqlTraceRecorder recorder() {
        return new SqlTraceRecorder(true, true, false, false, 8, 100, 2000, 200, 5);
    }

    static class ConnectionFactoryBean implements FactoryBean<ConnectionFactory> {
        private static final AtomicInteger creations = new AtomicInteger();

        ConnectionFactoryBean() {
            creations.incrementAndGet();
        }

        @Override
        public ConnectionFactory getObject() {
            throw new AssertionError("a passive capture read must not request the product");
        }

        @Override
        public Class<?> getObjectType() {
            return ConnectionFactory.class;
        }
    }

    public static final class OpaqueConnectionFactory implements FactoryBean<Object> {
        static AtomicInteger callbacks = new AtomicInteger();

        @Override
        public Object getObject() {
            callbacks.incrementAndGet();
            return new ConnectionFactory() {};
        }

        @Override
        public Class<?> getObjectType() {
            callbacks.incrementAndGet();
            return Object.class;
        }
    }
}
