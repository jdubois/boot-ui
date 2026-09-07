package io.github.jdubois.bootui.autoconfigure.spring;

import static io.github.jdubois.bootui.autoconfigure.spring.SpringObservations.Fact.*;
import static org.assertj.core.api.Assertions.assertThat;

import app.advisoraudit.ApplicationFixtures;
import com.zaxxer.hikari.HikariDataSource;
import io.github.jdubois.bootui.autoconfigure.spring.SpringObservations.AsyncSelection;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.http.codec.CodecCustomizer;
import org.springframework.boot.http.codec.autoconfigure.CodecsAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

class SpringInventoryTests {
    private final ApplicationContextRunner tasks = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TaskExecutionAutoConfiguration.class))
            .withUserConfiguration(ApplicationFixtures.AsyncConfiguration.class);

    @Test
    void realContextScanNeverConstructsLazyFactoryBeansOrRequestsTheirProducts() {
        Factory.constructed.set(0);
        Factory.products.set(0);
        new ReactiveWebApplicationContextRunner()
                .withBean("factory", Factory.class, Factory::new, definition -> definition.setLazyInit(true))
                .withBean(ApplicationFixtures.Handler.class)
                .run(context -> {
                    assertThat(Factory.constructed).hasValue(0);
                    SpringContext snapshot =
                            SpringInventory.discover(context.getBeanFactory(), context.getEnvironment(), true);
                    assertThat(Factory.constructed).hasValue(0);
                    assertThat(Factory.products).hasValue(0);
                    assertThat(snapshot.reactiveHandlerMethodCount()).isEqualTo(1);
                    new SpringScanner(context.getBeanFactory(), context.getEnvironment(), true, Clock.systemUTC())
                            .scan();
                    assertThat(Factory.constructed).hasValue(0);
                    assertThat(Factory.products).hasValue(0);
                });
    }

    @Test
    void manualSingletonFlagsAreUnknownAndAliasesDoNotDuplicateCandidates() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("manual", new HikariDataSource());
        factory.registerAlias("manual", "alias");
        SpringContext snapshot = SpringInventory.discover(factory, new MockEnvironment(), false);
        assertThat(snapshot.dataSources()).hasSize(1);
        assertThat(snapshot.dataSources().get(0).metadataKnown()).isFalse();
        assertThat(snapshot.dataSources().get(0).aliases()).contains("alias");
        assertThat(new AmbiguousDataSourceRule().evaluate(snapshot).status()).isEqualTo("SKIPPED");
    }

    @Test
    void effectiveFactoryFlagsOverrideContradictoryEnvironment() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.setAllowBeanDefinitionOverriding(false);
        factory.setAllowCircularReferences(false);
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.main.allow-bean-definition-overriding", "true")
                .withProperty("spring.main.allow-circular-references", "true");
        SpringContext snapshot = SpringInventory.discover(factory, env, false);
        assertThat(new BeanDefinitionOverridingRule().evaluate(snapshot).status())
                .isEqualTo("PASS");
        assertThat(new CircularReferencesAllowedRule().evaluate(snapshot).status())
                .isEqualTo("PASS");
        factory.setAllowBeanDefinitionOverriding(true);
        factory.setAllowCircularReferences(true);
        snapshot = SpringInventory.discover(factory, env, false);
        assertThat(new BeanDefinitionOverridingRule().evaluate(snapshot).status())
                .isEqualTo("VIOLATION");
        assertThat(new CircularReferencesAllowedRule().evaluate(snapshot).status())
                .isEqualTo("VIOLATION");
    }

    @Test
    void beanProductTypesInheritedFieldsBindingAndScopesAreObservedNonEagerly() throws Exception {
        new ApplicationContextRunner()
                .withUserConfiguration(Class.forName("DefaultPackageAdvisorConfiguration"))
                .withBean(
                        "counter",
                        ApplicationFixtures.Counter.class,
                        ApplicationFixtures.Counter::new,
                        definition -> definition.setLazyInit(true))
                .withBean(
                        "bound",
                        ApplicationFixtures.BoundCounter.class,
                        ApplicationFixtures.BoundCounter::new,
                        definition -> definition.setLazyInit(true))
                .withBean(
                        "prototype",
                        ApplicationFixtures.Counter.class,
                        ApplicationFixtures.Counter::new,
                        definition -> definition.setScope("prototype"))
                .run(context -> {
                    SpringContext snapshot =
                            SpringInventory.discover(context.getBeanFactory(), context.getEnvironment(), false);
                    assertThat(context.getBeanFactory().containsSingleton("counter"))
                            .isFalse();
                    assertThat(snapshot.defaultPackageBeans()).contains("advisorProduct");
                    assertThat(snapshot.mutableSingletonFields())
                            .hasSize(2)
                            .anyMatch(field -> field.endsWith("#count"))
                            .anyMatch(field -> field.endsWith("#inherited"))
                            .noneMatch(field -> field.contains("injected")
                                    || field.contains("configured")
                                    || field.contains("global"));
                });
    }

    @Test
    void bootUiControllersNeverCountAsApplicationReactiveHandlers() {
        new ReactiveWebApplicationContextRunner()
                .withBean(InternalHandler.class)
                .run(context -> {
                    SpringContext snapshot =
                            SpringInventory.discover(context.getBeanFactory(), context.getEnvironment(), true);
                    assertThat(snapshot.reactiveHandlerMethodCount()).isZero();
                });
    }

    @Test
    void kotlinAccessorBackedFieldsAreExcludedButExposedFieldsRemainVisible() {
        // Model Kotlin's emitted shape in an isolated loader; do not put a partial Kotlin runtime
        // on the application's classpath (which would change Boot's own optional-Kotlin conditions).
        var marker = new net.bytebuddy.ByteBuddy()
                .makeAnnotation()
                .name("kotlin.Metadata")
                .annotateType(net.bytebuddy.description.annotation.AnnotationDescription.Builder.ofType(
                                java.lang.annotation.Retention.class)
                        .define("value", java.lang.annotation.RetentionPolicy.RUNTIME)
                        .build())
                .make()
                .load(getClass().getClassLoader())
                .getLoaded()
                .asSubclass(java.lang.annotation.Annotation.class);
        Class<?> type = new net.bytebuddy.ByteBuddy()
                .subclass(Object.class)
                .name("app.advisoraudit.KotlinFields")
                .annotateType(net.bytebuddy.description.annotation.AnnotationDescription.Builder.ofType(marker)
                        .build())
                .defineField("lateinit", String.class, java.lang.reflect.Modifier.PUBLIC)
                .defineField("exposed", String.class, java.lang.reflect.Modifier.PUBLIC)
                .defineMethod("getLateinit", String.class, java.lang.reflect.Modifier.PUBLIC)
                .intercept(net.bytebuddy.implementation.FieldAccessor.ofField("lateinit"))
                .defineMethod("setLateinit", void.class, java.lang.reflect.Modifier.PUBLIC)
                .withParameters(String.class)
                .intercept(net.bytebuddy.implementation.FieldAccessor.ofField("lateinit"))
                .make()
                .load(marker.getClassLoader())
                .getLoaded();
        var factory = new DefaultListableBeanFactory();
        factory.registerBeanDefinition("kotlin", new RootBeanDefinition(type));
        var snapshot = SpringInventory.discover(factory, new MockEnvironment(), false);
        assertThat(snapshot.mutableSingletonFields()).singleElement().asString().endsWith("#exposed");
    }

    @Test
    void frameworkAsyncFallbackAndPlainExecutorAliasFollowDifferentPaths() {
        new ApplicationContextRunner()
                .withUserConfiguration(ApplicationFixtures.AsyncConfiguration.class)
                .run(context -> {
                    SpringContext snapshot =
                            SpringInventory.discover(context.getBeanFactory(), context.getEnvironment(), false);
                    assertThat(snapshot.observations().get(ASYNC_SELECTION, AsyncSelection.class))
                            .isEqualTo(AsyncSelection.FRAMEWORK_FALLBACK);
                    assertThat(new AsyncWithoutCustomExecutorRule()
                                    .evaluate(snapshot)
                                    .status())
                            .isEqualTo("VIOLATION");
                });
        new ApplicationContextRunner()
                .withUserConfiguration(ApplicationFixtures.AsyncConfiguration.class)
                .withBean("plain", Executor.class, () -> command -> {})
                .withInitializer(context -> context.getBeanFactory().registerAlias("plain", "taskExecutor"))
                .run(context -> {
                    SpringContext snapshot =
                            SpringInventory.discover(context.getBeanFactory(), context.getEnvironment(), false);
                    assertThat(snapshot.observations().get(ASYNC_SELECTION, AsyncSelection.class))
                            .isEqualTo(AsyncSelection.SELECTED);
                    assertThat(new AsyncWithoutCustomExecutorRule()
                                    .evaluate(snapshot)
                                    .status())
                            .isEqualTo("PASS");
                });
    }

    @Test
    void lazyExecutorIsUnknownRatherThanAnUnreviewedPool() {
        new ApplicationContextRunner()
                .withUserConfiguration(ApplicationFixtures.AsyncConfiguration.class)
                .withBean(
                        "lazy",
                        org.springframework.core.task.SimpleAsyncTaskExecutor.class,
                        org.springframework.core.task.SimpleAsyncTaskExecutor::new,
                        definition -> definition.setLazyInit(true))
                .run(context -> {
                    assertThat(context.getBeanFactory().containsSingleton("lazy"))
                            .isFalse();
                    SpringContext snapshot =
                            SpringInventory.discover(context.getBeanFactory(), context.getEnvironment(), false);
                    assertThat(new AsyncWithoutCustomExecutorRule()
                                    .evaluate(snapshot)
                                    .status())
                            .isEqualTo("PASS");
                    assertThat(new UnboundedAsyncQueueRule().evaluate(snapshot).status())
                            .isEqualTo("SKIPPED");
                    assertThat(context.getBeanFactory().containsSingleton("lazy"))
                            .isFalse();
                });
    }

    @Test
    void protectedAsyncQualifierAndCustomProcessorDoNotInventFrameworkFallback() {
        new ApplicationContextRunner()
                .withUserConfiguration(ApplicationFixtures.AsyncConfiguration.class)
                .withBean(ApplicationFixtures.ProtectedQualifiedAsync.class)
                .run(context -> assertThat(new AsyncWithoutCustomExecutorRule()
                                .evaluate(SpringInventory.discover(
                                        context.getBeanFactory(), context.getEnvironment(), false))
                                .status())
                        .isEqualTo("SKIPPED"));
        AtomicInteger calls = new AtomicInteger();
        new ApplicationContextRunner()
                .withBean(
                        "org.springframework.context.annotation.internalAsyncAnnotationProcessor",
                        org.springframework.scheduling.annotation.AsyncAnnotationBeanPostProcessor.class,
                        () -> {
                            var processor =
                                    new org.springframework.scheduling.annotation.AsyncAnnotationBeanPostProcessor();
                            processor.setExecutor(command -> calls.incrementAndGet());
                            return processor;
                        })
                .run(context -> {
                    assertThat(new AsyncWithoutCustomExecutorRule()
                                    .evaluate(SpringInventory.discover(
                                            context.getBeanFactory(), context.getEnvironment(), false))
                                    .status())
                            .isEqualTo("SKIPPED");
                    assertThat(calls).hasValue(0);
                });
    }

    @Test
    void initializedDefaultQueueUsesEffectiveCapacityIncludingExplicitMaxInt() {
        for (int capacity : new int[] {17, Integer.MAX_VALUE}) {
            tasks.withPropertyValues("spring.task.execution.pool.queue-capacity=" + capacity)
                    .run(context -> {
                        // The application initializes its executor before the scan; scanning must not do so.
                        context.getBean("applicationTaskExecutor");
                        SpringContext snapshot =
                                SpringInventory.discover(context.getBeanFactory(), context.getEnvironment(), false);
                        assertThat(snapshot.observations().get(ASYNC_QUEUE_CAPACITY, Integer.class))
                                .isEqualTo(capacity);
                        assertThat(new UnboundedAsyncQueueRule()
                                        .evaluate(snapshot)
                                        .status())
                                .isEqualTo(capacity == Integer.MAX_VALUE ? "VIOLATION" : "PASS");
                    });
        }
    }

    @Test
    void customBoundedExecutorWithoutEnvironmentSettingsIsNotFlagged() {
        tasks.withBean("custom", ThreadPoolTaskExecutor.class, () -> {
                    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
                    executor.setQueueCapacity(5);
                    return executor;
                })
                .run(context -> {
                    SpringContext snapshot =
                            SpringInventory.discover(context.getBeanFactory(), context.getEnvironment(), false);
                    assertThat(new UnboundedAsyncQueueRule().evaluate(snapshot).status())
                            .isEqualTo("PASS");
                });
    }

    @Test
    void customAsyncConfigurerAndExplicitQualifierNeverGetInvokedToResolveSelection() {
        AtomicInteger calls = new AtomicInteger();
        tasks.withBean("customConfigurer", AsyncConfigurer.class, () -> new AsyncConfigurer() {
                    @Override
                    public Executor getAsyncExecutor() {
                        calls.incrementAndGet();
                        throw new IllegalStateException("credential");
                    }
                })
                .run(context -> {
                    SpringContext snapshot =
                            SpringInventory.discover(context.getBeanFactory(), context.getEnvironment(), false);
                    assertThat(calls).hasValue(0);
                    assertThat(new UnboundedAsyncQueueRule().evaluate(snapshot).status())
                            .isEqualTo("SKIPPED");
                    assertThat(new AsyncWithoutCustomExecutorRule()
                                    .evaluate(snapshot)
                                    .status())
                            .isEqualTo("SKIPPED");
                });
        tasks.withBean("exceptionOnly", AsyncConfigurer.class, () -> new AsyncConfigurer() {})
                .run(context -> assertThat(new AsyncWithoutCustomExecutorRule()
                                .evaluate(SpringInventory.discover(
                                        context.getBeanFactory(), context.getEnvironment(), false))
                                .status())
                        .isEqualTo("SKIPPED"));
        tasks.withBean(ApplicationFixtures.QualifiedAsync.class)
                .run(context -> assertThat(
                                SpringInventory.discover(context.getBeanFactory(), context.getEnvironment(), false)
                                        .observations()
                                        .known(ASYNC_SELECTION))
                        .isFalse());
    }

    @Test
    void bootForceModeUsesApplicationExecutorDespiteUnrelatedExecutor() {
        tasks.withPropertyValues("spring.task.execution.mode=force")
                .withBean("other", Executor.class, () -> command -> {})
                .run(context -> {
                    context.getBean("applicationTaskExecutor");
                    SpringContext snapshot =
                            SpringInventory.discover(context.getBeanFactory(), context.getEnvironment(), false);
                    assertThat(snapshot.observations().get(ASYNC_SELECTION, AsyncSelection.class))
                            .isEqualTo(AsyncSelection.SELECTED);
                    assertThat(new UnboundedAsyncQueueRule().evaluate(snapshot).status())
                            .isEqualTo("VIOLATION");
                });
    }

    @Test
    void frameworkByTypeSelectionPrecedesDefaultCandidateFiltering() {
        for (String selection : new String[] {"primary", "non-fallback", "default"}) {
            new ApplicationContextRunner()
                    .withUserConfiguration(ApplicationFixtures.AsyncConfiguration.class)
                    .withBean(
                            "bounded",
                            ThreadPoolTaskExecutor.class,
                            () -> {
                                var executor = new ThreadPoolTaskExecutor();
                                executor.setQueueCapacity(5);
                                return executor;
                            },
                            definition -> {
                                definition.setPrimary(selection.equals("primary"));
                                ((org.springframework.beans.factory.support.AbstractBeanDefinition) definition)
                                        .setDefaultCandidate(false);
                            })
                    .withBean(
                            "ordinary",
                            ThreadPoolTaskExecutor.class,
                            ThreadPoolTaskExecutor::new,
                            definition -> definition.setFallback(selection.equals("non-fallback")))
                    .run(context -> {
                        boolean bounded = !selection.equals("default");
                        // This is the actual Framework lookup used by default @Async, not an
                        // injection-point assertion or the advisor's model used as its own oracle.
                        assertThat(context.getBeanFactory().getBean(org.springframework.core.task.TaskExecutor.class))
                                .isSameAs(context.getBean(bounded ? "bounded" : "ordinary"));
                        var snapshot =
                                SpringInventory.discover(context.getBeanFactory(), context.getEnvironment(), false);
                        assertThat(snapshot.observations().get(ASYNC_QUEUE_CAPACITY, Integer.class))
                                .isEqualTo(bounded ? 5 : Integer.MAX_VALUE);
                        assertThat(new UnboundedAsyncQueueRule()
                                        .evaluate(snapshot)
                                        .status())
                                .isEqualTo(bounded ? "PASS" : "VIOLATION");
                    });
        }
    }

    @Test
    void schedulerByTypePrimaryWinsEvenWhenNotADefaultCandidate() {
        new ApplicationContextRunner()
                .withUserConfiguration(ApplicationFixtures.SchedulingConfiguration.class)
                .withBean(
                        "primaryScheduler",
                        org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler.class,
                        () -> {
                            var scheduler = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
                            scheduler.setPoolSize(2);
                            return scheduler;
                        },
                        definition -> {
                            definition.setPrimary(true);
                            ((org.springframework.beans.factory.support.AbstractBeanDefinition) definition)
                                    .setDefaultCandidate(false);
                        })
                .run(context -> {
                    assertThat(context.getBeanFactory().getBean(org.springframework.scheduling.TaskScheduler.class))
                            .isSameAs(context.getBean("primaryScheduler"));
                    var snapshot = SpringInventory.discover(context.getBeanFactory(), context.getEnvironment(), false);
                    assertThat(snapshot.observations().get(SCHEDULED_TASK_COUNT, Integer.class))
                            .isEqualTo(2);
                    assertThat(snapshot.observations().get(SCHEDULER_POOL_SIZE, Integer.class))
                            .isEqualTo(2);
                    assertThat(new SchedulerPoolTooSmallRule()
                                    .evaluate(snapshot)
                                    .status())
                            .isEqualTo("PASS");
                });
    }

    @Test
    void actualRegisteredTasksAndSchedulerSizeAreRequired() {
        new ApplicationContextRunner()
                .withUserConfiguration(ApplicationFixtures.SchedulingConfiguration.class)
                .run(context -> {
                    SpringContext snapshot =
                            SpringInventory.discover(context.getBeanFactory(), context.getEnvironment(), false);
                    assertThat(snapshot.observations().incomplete()).isEmpty();
                    assertThat(snapshot.observations().get(SCHEDULED_TASK_COUNT, Integer.class))
                            .isEqualTo(2);
                    assertThat(snapshot.observations().get(SCHEDULER_POOL_SIZE, Integer.class))
                            .isEqualTo(1);
                    assertThat(new SchedulerPoolTooSmallRule()
                                    .evaluate(snapshot)
                                    .status())
                            .isEqualTo("VIOLATION");
                });
        new ApplicationContextRunner()
                .withUserConfiguration(ApplicationFixtures.SchedulingConfiguration.class)
                .withBean("custom", SchedulingConfigurer.class, () -> registrar -> {})
                .run(context -> assertThat(new SchedulerPoolTooSmallRule()
                                .evaluate(SpringInventory.discover(
                                        context.getBeanFactory(), context.getEnvironment(), false))
                                .status())
                        .isEqualTo("SKIPPED"));
    }

    @Test
    void bootCodecMetadataUsesCurrentNamespaceAndExistingBoundStateOnWebFlux() {
        new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        CodecsAutoConfiguration.class,
                        org.springframework.boot.webflux.autoconfigure.WebFluxAutoConfiguration.class))
                .withPropertyValues("spring.http.codecs.max-in-memory-size=-1")
                .run(context -> {
                    SpringContext snapshot =
                            SpringInventory.discover(context.getBeanFactory(), context.getEnvironment(), true);
                    assertThat(snapshot.observations().yes(BOOT_CODEC_CONFIGURATION))
                            .isTrue();
                    assertThat(new UnlimitedCodecAggregationRule()
                                    .evaluate(snapshot)
                                    .status())
                            .isEqualTo("VIOLATION");
                });
        new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        CodecsAutoConfiguration.class,
                        org.springframework.boot.webflux.autoconfigure.WebFluxAutoConfiguration.class))
                .withPropertyValues("spring.codec.max-in-memory-size=-1")
                .run(context -> {
                    SpringContext snapshot =
                            SpringInventory.discover(context.getBeanFactory(), context.getEnvironment(), true);
                    assertThat(new UnlimitedCodecAggregationRule()
                                    .evaluate(snapshot)
                                    .status())
                            .isEqualTo("PASS");
                    assertThat(new RemovedOrRenamedPropertyRule()
                                    .evaluate(snapshot)
                                    .status())
                            .isEqualTo("VIOLATION");
                });
        new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CodecsAutoConfiguration.class))
                .withPropertyValues("spring.http.codecs.max-in-memory-size=-1")
                .withBean("custom", CodecCustomizer.class, () -> codecs -> {
                    throw new AssertionError("must not invoke");
                })
                .run(context -> assertThat(new UnlimitedCodecAggregationRule()
                                .evaluate(SpringInventory.discover(
                                        context.getBeanFactory(), context.getEnvironment(), true))
                                .status())
                        .isEqualTo("SKIPPED"));
        AtomicInteger customizations = new AtomicInteger();
        new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        CodecsAutoConfiguration.class,
                        org.springframework.boot.webflux.autoconfigure.WebFluxAutoConfiguration.class))
                .withPropertyValues("spring.http.codecs.max-in-memory-size=-1")
                .withBean("custom", CodecCustomizer.class, () -> codecs -> {
                    customizations.incrementAndGet();
                    codecs.defaultCodecs().maxInMemorySize(2048);
                })
                .run(context -> {
                    int before = customizations.get();
                    assertThat(new UnlimitedCodecAggregationRule()
                                    .evaluate(SpringInventory.discover(
                                            context.getBeanFactory(), context.getEnvironment(), true))
                                    .status())
                            .isEqualTo("SKIPPED");
                    assertThat(customizations).hasValue(before);
                });
    }

    @Test
    void safeExistingHikariMetadataWinsWithoutOpeningConnectionsOrLeakingUrl() {
        new ApplicationContextRunner()
                .withBean("dataSource", HikariDataSource.class, () -> {
                    HikariDataSource dataSource = new HikariDataSource();
                    dataSource.setJdbcUrl("jdbc:h2:mem:orders;PASSWORD=do-not-display");
                    return dataSource;
                })
                .withPropertyValues("spring.datasource.url=jdbc:postgresql://db/different")
                .run(context -> {
                    context.getEnvironment().setDefaultProfiles("prod");
                    SpringContext snapshot =
                            SpringInventory.discover(context.getBeanFactory(), context.getEnvironment(), false);
                    assertThat(snapshot.observations().get(JDBC_KIND, String.class))
                            .isEqualTo("H2 memory");
                    assertThat(context.getBean(HikariDataSource.class).getHikariPoolMXBean())
                            .isNull();
                    var result = new InMemoryDatasourceInProductionRule().evaluate(snapshot);
                    assertThat(result.status()).isEqualTo("VIOLATION");
                    assertThat(result.toString()).doesNotContain("do-not-display", "jdbc:h2:mem:");
                });
    }

    @Test
    void inventoryAndPropertyEnumerationHaveActualWorkLimitsAndSanitizedReporting() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        for (int i = 0; i < SpringInventory.MAX_BEANS + 1; i++)
            factory.registerBeanDefinition("bean" + i, new RootBeanDefinition(Object.class));
        var report = new SpringScanner(factory, new MockEnvironment(), false, Clock.systemUTC()).scan();
        assertThat(report.inspected()).anyMatch(text -> text.contains("incomplete"));
        Map<String, Object> large = new java.util.LinkedHashMap<>();
        for (int i = 0; i <= SpringProperties.MAX_NAMES; i++) large.put("key" + i, "credential");
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("oversized", large));
        report = new SpringScanner(new DefaultListableBeanFactory(), environment, false, Clock.systemUTC()).scan();
        assertThat(report.analysisErrors()).isNotEmpty();
        assertThat(report.toString()).doesNotContain("credential");
    }

    @Test
    void propertySourceExceptionsNeverReachReports() {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new org.springframework.core.env.PropertySource<>("failing") {
            @Override
            public Object getProperty(String key) {
                throw new IllegalStateException("jdbc:secret password=private");
            }
        });
        var report = new SpringScanner(new DefaultListableBeanFactory(), environment, false, Clock.systemUTC()).scan();
        assertThat(report.analysisErrors()).isNotEmpty();
        assertThat(report.toString()).doesNotContain("jdbc:secret", "password=private");
    }

    static class Factory implements FactoryBean<Object> {
        static final AtomicInteger constructed = new AtomicInteger();
        static final AtomicInteger products = new AtomicInteger();

        Factory() {
            constructed.incrementAndGet();
        }

        @Override
        public Object getObject() {
            products.incrementAndGet();
            throw new AssertionError("must not create");
        }

        @Override
        public Class<?> getObjectType() {
            return Object.class;
        }
    }

    @RestController
    static class InternalHandler {
        @GetMapping("/internal")
        public Mono<String> handle() {
            return Mono.just("internal");
        }
    }
}
