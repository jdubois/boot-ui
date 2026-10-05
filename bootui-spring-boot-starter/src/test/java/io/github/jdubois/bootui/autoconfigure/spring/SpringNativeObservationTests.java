package io.github.jdubois.bootui.autoconfigure.spring;

import static io.github.jdubois.bootui.autoconfigure.spring.SpringObservations.Fact.*;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.cache.CacheActivityAware;
import io.github.jdubois.bootui.autoconfigure.cache.CacheActivityCacheManagerBeanPostProcessor;
import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.autoconfigure.scheduled.BootUiSchedulingConfigurer;
import io.github.jdubois.bootui.engine.cache.CacheActivityRecorder;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.observation.autoconfigure.ScheduledTasksObservationAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewFilter;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.servlet.handler.MappedInterceptor;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;
import org.springframework.web.servlet.handler.WebRequestHandlerInterceptorAdapter;

class SpringNativeObservationTests {
    private final ApplicationContextRunner scheduling = new ApplicationContextRunner()
            .withUserConfiguration(Scheduling.class)
            .withBean(Tasks.class)
            .withBean(ObservationRegistry.class, ObservationRegistry::create)
            .withConfiguration(AutoConfigurations.of(ScheduledTasksObservationAutoConfiguration.class))
            .withBean(
                    BootUiSchedulingConfigurer.class,
                    () -> new BootUiSchedulingConfigurer(
                            new io.micrometer.observation.ObservationHandler<
                                    io.micrometer.observation.Observation.Context>() {
                                @Override
                                public boolean supportsContext(io.micrometer.observation.Observation.Context context) {
                                    return true;
                                }
                            }));

    @Test
    void nativeObservabilityAndTaskWrapperPreserveActualApplicationTasksEvenInBootUiPackage() {
        scheduling
                .withBean("taskScheduler", ThreadPoolTaskScheduler.class, ThreadPoolTaskScheduler::new)
                .run(context -> {
                    assertThat(context.getBeansOfType(SchedulingConfigurer.class)
                                    .values())
                            .anyMatch(bean -> bean.getClass().getName().endsWith("$ObservabilitySchedulingConfigurer"));
                    var snapshot = SpringInventory.discover(context.getBeanFactory(), context.getEnvironment(), false);
                    assertThat(snapshot.observations().get(SCHEDULED_TASK_COUNT, Integer.class))
                            .isEqualTo(2);
                    assertThat(snapshot.observations().get(SCHEDULER_POOL_SIZE, Integer.class))
                            .isEqualTo(1);
                    assertThat(new SchedulerPoolTooSmallRule()
                                    .evaluate(snapshot)
                                    .status())
                            .isEqualTo("VIOLATION");
                    assertThat(SpringScanner.evidence(snapshot).coverageComplete())
                            .isTrue();
                });
    }

    @Test
    void simpleAsyncSchedulerIsNotAThreadPoolAndDoesNotCreateMissingPoolEvidence() {
        scheduling
                .withBean("taskScheduler", SimpleAsyncTaskScheduler.class, SimpleAsyncTaskScheduler::new)
                .run(context -> {
                    var snapshot = SpringInventory.discover(context.getBeanFactory(), context.getEnvironment(), false);
                    assertThat(snapshot.observations().yes(SCHEDULER_NON_POOL)).isTrue();
                    assertThat(new SchedulerPoolTooSmallRule()
                                    .evaluate(snapshot)
                                    .status())
                            .isEqualTo("SKIPPED");
                    assertThat(SpringScanner.evidence(snapshot).coverageComplete())
                            .isTrue();
                    assertThat(SpringScanner.evidence(snapshot).usable()).isFalse();
                });
    }

    @Test
    void customConfigurerIsNotReinvokedAndRemainsUnknown() {
        AtomicInteger calls = new AtomicInteger();
        scheduling
                .withBean("custom", SchedulingConfigurer.class, () -> registrar -> calls.incrementAndGet())
                .withBean("taskScheduler", ThreadPoolTaskScheduler.class, ThreadPoolTaskScheduler::new)
                .run(context -> {
                    int afterStartup = calls.get();
                    var snapshot = SpringInventory.discover(context.getBeanFactory(), context.getEnvironment(), false);
                    assertThat(new SchedulerPoolTooSmallRule()
                                    .evaluate(snapshot)
                                    .status())
                            .isEqualTo("SKIPPED");
                    assertThat(calls).hasValue(afterStartup);
                    assertThat(SpringScanner.evidence(snapshot).limitations())
                            .anyMatch(reason -> reason.contains("Scheduled task registrations"));
                });
    }

    @Test
    void customRegistrarIsNotAskedForTasks() {
        var factory = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        var registrar = new org.springframework.scheduling.config.ScheduledTaskRegistrar() {
            @Override
            public java.util.Set<org.springframework.scheduling.config.ScheduledTask> getScheduledTasks() {
                throw new AssertionError("Must not call custom registrar");
            }
        };
        factory.registerSingleton(
                "org.springframework.scheduling.config.internalScheduledAnnotationProcessor",
                new org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor(registrar));
        var snapshot = SpringInventory.discover(factory, new org.springframework.mock.env.MockEnvironment(), false);
        assertThat(new SchedulerPoolTooSmallRule().evaluate(snapshot).status()).isEqualTo("SKIPPED");
        assertThat(SpringScanner.evidence(snapshot).coverageComplete()).isFalse();
    }

    @Test
    void qualifiedTasksAndAmbiguousSchedulersRemainUnknown() {
        new ApplicationContextRunner()
                .withUserConfiguration(Scheduling.class)
                .withBean(QualifiedTasks.class)
                .withBean("special", ThreadPoolTaskScheduler.class, ThreadPoolTaskScheduler::new)
                .run(context -> {
                    var snapshot = SpringInventory.discover(context.getBeanFactory(), context.getEnvironment(), false);
                    assertThat(new SchedulerPoolTooSmallRule()
                                    .evaluate(snapshot)
                                    .status())
                            .isEqualTo("SKIPPED");
                    assertThat(SpringScanner.evidence(snapshot).coverageComplete())
                            .isFalse();
                });
        scheduling
                .withBean("one", ThreadPoolTaskScheduler.class, ThreadPoolTaskScheduler::new)
                .withBean("two", ThreadPoolTaskScheduler.class, ThreadPoolTaskScheduler::new)
                .run(context -> {
                    var snapshot = SpringInventory.discover(context.getBeanFactory(), context.getEnvironment(), false);
                    assertThat(new SchedulerPoolTooSmallRule()
                                    .evaluate(snapshot)
                                    .status())
                            .isEqualTo("SKIPPED");
                    assertThat(SpringScanner.evidence(snapshot).limitations())
                            .anyMatch(reason -> reason.contains("selected scheduler"));
                });
    }

    @Test
    void nativeActivityDecoratorPreservesProviderIdentityAndEvidence() {
        for (CacheManager manager :
                List.of(new ConcurrentMapCacheManager(), new CaffeineCacheManager(), new NoOpCacheManager())) {
            var bpp = new CacheActivityCacheManagerBeanPostProcessor(
                    provider(new CacheActivityRecorder(true, 10)), provider(BootUiSelfDataFilter.defaults()));
            Object wrapped = bpp.postProcessAfterInitialization(manager, "applicationCache");
            new ApplicationContextRunner()
                    .withBean("applicationCache", CacheManager.class, () -> (CacheManager) wrapped)
                    .withBean("org.springframework.cache.config.internalCacheAdvisor", Object.class, Object::new)
                    .run(context -> {
                        var snapshot =
                                SpringInventory.discover(context.getBeanFactory(), context.getEnvironment(), false);
                        assertThat(snapshot.cacheManagers())
                                .singleElement()
                                .satisfies(ref -> assertThat(ref.className())
                                        .isEqualTo(manager.getClass().getName()));
                        assertThat(new InMemoryCacheManagerRule()
                                        .evaluate(snapshot)
                                        .status())
                                .isEqualTo(manager instanceof ConcurrentMapCacheManager ? "VIOLATION" : "PASS");
                        assertThat(SpringScanner.evidence(snapshot).usable()).isTrue();
                        assertThat(SpringScanner.evidence(snapshot).coverageComplete())
                                .isTrue();
                    });
        }
    }

    @Test
    void customCacheDelegateCallbackIsNeverInvokedAndOptionalCaffeineCanBeAbsent() {
        new ApplicationContextRunner()
                .withClassLoader(
                        new FilteredClassLoader("com.github.benmanes.caffeine", "org.springframework.cache.caffeine"))
                .withBean("custom", CustomCache.class, CustomCache::new)
                .withBean("org.springframework.cache.config.internalCacheAdvisor", Object.class, Object::new)
                .run(context -> {
                    var snapshot = SpringInventory.discover(context.getBeanFactory(), context.getEnvironment(), false);
                    assertThat(new InMemoryCacheManagerRule().evaluate(snapshot).status())
                            .isEqualTo("SKIPPED");
                    assertThat(SpringScanner.evidence(snapshot).limitations())
                            .anyMatch(reason -> reason.contains("Cache manager provider metadata"));
                });
    }

    @Test
    void knownConcurrentMapFindingSurvivesAnotherUnknownProvider() {
        new ApplicationContextRunner()
                .withBean("custom", CustomCache.class, CustomCache::new)
                .withBean("native", ConcurrentMapCacheManager.class)
                .withBean("org.springframework.cache.config.internalCacheAdvisor", Object.class, Object::new)
                .run(context -> {
                    var snapshot = SpringInventory.discover(context.getBeanFactory(), context.getEnvironment(), false);
                    assertThat(new InMemoryCacheManagerRule().evaluate(snapshot).status())
                            .isEqualTo("VIOLATION");
                    assertThat(SpringScanner.evidence(snapshot).usable()).isTrue();
                    assertThat(SpringScanner.evidence(snapshot).coverageComplete())
                            .isFalse();
                });
    }

    @Test
    void nativeEmptyMappingsConfirmAbsenceButPropertyAloneDoesNot() {
        new ApplicationContextRunner()
                .withBean(SimpleUrlHandlerMapping.class)
                .withPropertyValues("spring.jpa.open-in-view=false")
                .run(context -> {
                    var snapshot = SpringInventory.discover(context.getBeanFactory(), context.getEnvironment(), false);
                    var observation = snapshot.observations().get(OSIV, SpringObservations.OsivObservation.class);
                    assertThat(observation.registration()).isNull();
                    assertThat(observation.complete()).isTrue();
                });
        var context = SpringContext.builder(new org.springframework.mock.env.MockEnvironment()
                        .withProperty("spring.jpa.open-in-view", "false"))
                .entityManagerFactoryPresent(true)
                .build();
        assertThat(new OpenSessionInViewEnabledRule().evaluate(context).status())
                .isEqualTo("SKIPPED");
        assertThat(SpringScanner.evidence(context).coverageComplete()).isFalse();
    }

    @Test
    void appliedMappedOsivIsFoundAndUnappliedInterceptorAloneIsNotARegistration() {
        new ApplicationContextRunner()
                .withBean("mapping", SimpleUrlHandlerMapping.class, () -> {
                    var mapping = new SimpleUrlHandlerMapping();
                    mapping.setInterceptors(new MappedInterceptor(
                            new String[] {"/data/**"},
                            new WebRequestHandlerInterceptorAdapter(new OpenEntityManagerInViewInterceptor())));
                    return mapping;
                })
                .withPropertyValues("spring.jpa.open-in-view=false")
                .run(context -> {
                    var snapshot = SpringInventory.discover(context.getBeanFactory(), context.getEnvironment(), false);
                    var result = new OpenSessionInViewEnabledRule().evaluate(snapshot);
                    assertThat(result.status()).isEqualTo("VIOLATION");
                    assertThat(result.severity()).isEqualTo("MEDIUM");
                    assertThat(result.sampleViolations())
                            .anyMatch(reason -> reason.contains("custom servlet interceptor"));
                });
        new ApplicationContextRunner()
                .withBean(SimpleUrlHandlerMapping.class)
                .withBean(
                        jakarta.persistence.EntityManagerFactory.class,
                        () -> org.mockito.Mockito.mock(jakarta.persistence.EntityManagerFactory.class))
                .withBean(OpenEntityManagerInViewInterceptor.class)
                .run(context -> assertThat(
                                SpringInventory.discover(context.getBeanFactory(), context.getEnvironment(), false)
                                        .observations()
                                        .get(OSIV, SpringObservations.OsivObservation.class)
                                        .registration())
                        .isNull());
    }

    @Test
    void enabledFilterRegistrationIsFoundWhileDisabledRegistrationConfirmsAbsence() {
        for (boolean enabled : List.of(true, false)) {
            new ApplicationContextRunner()
                    .withBean(SimpleUrlHandlerMapping.class)
                    .withBean("osiv", FilterRegistrationBean.class, () -> {
                        var registration = new FilterRegistrationBean<>(new OpenEntityManagerInViewFilter());
                        registration.setEnabled(enabled);
                        return registration;
                    })
                    .withPropertyValues("spring.jpa.open-in-view=false")
                    .run(context -> {
                        var snapshot =
                                SpringInventory.discover(context.getBeanFactory(), context.getEnvironment(), false);
                        var observation = snapshot.observations().get(OSIV, SpringObservations.OsivObservation.class);
                        assertThat(observation.complete()).isTrue();
                        assertThat(observation.registration() != null).isEqualTo(enabled);
                        if (enabled)
                            assertThat(new OpenSessionInViewEnabledRule()
                                            .evaluate(snapshot)
                                            .status())
                                    .isEqualTo("VIOLATION");
                    });
        }
    }

    @Test
    void customRegistrationCannotProveAbsenceOrEraseAnIndependentlyObservedFinding() {
        for (boolean positive : List.of(true, false)) {
            new ApplicationContextRunner()
                    .withBean("customMapping", CustomMapping.class)
                    .withBean("customRegistration", CustomFilterRegistration.class)
                    .withBean("nativeRegistration", FilterRegistrationBean.class, () -> {
                        var registration = new FilterRegistrationBean<>(new OpenEntityManagerInViewFilter());
                        registration.setEnabled(positive);
                        return registration;
                    })
                    .run(context -> {
                        var snapshot =
                                SpringInventory.discover(context.getBeanFactory(), context.getEnvironment(), false);
                        assertThat(new OpenSessionInViewEnabledRule()
                                        .evaluate(snapshot)
                                        .status())
                                .isEqualTo(positive ? "VIOLATION" : "SKIPPED");
                        assertThat(SpringScanner.evidence(snapshot).coverageComplete())
                                .isFalse();
                        assertThat(SpringScanner.evidence(snapshot).usable()).isEqualTo(positive);
                    });
        }
    }

    @Test
    void arbitraryServletInitializerIsNotInvokedAndPreventsAbsenceClaim() {
        new ApplicationContextRunner()
                .withBean(SimpleUrlHandlerMapping.class)
                .withBean(org.springframework.boot.web.servlet.ServletContextInitializer.class, () -> context -> {
                    throw new AssertionError("Must not invoke servlet initializer");
                })
                .run(context -> {
                    var snapshot = SpringInventory.discover(context.getBeanFactory(), context.getEnvironment(), false);
                    assertThat(new OpenSessionInViewEnabledRule()
                                    .evaluate(snapshot)
                                    .status())
                            .isEqualTo("SKIPPED");
                    assertThat(SpringScanner.evidence(snapshot).coverageComplete())
                            .isFalse();
                });
    }

    @Test
    void nullBeanIsConfirmedAbsentButLazyMappingAndBareOsivFilterRemainUnknown() {
        new ApplicationContextRunner()
                .withUserConfiguration(NullMapping.class)
                .withBean(SimpleUrlHandlerMapping.class)
                .run(context -> {
                    var observation = SpringInventory.discover(
                                    context.getBeanFactory(), context.getEnvironment(), false)
                            .observations()
                            .get(OSIV, SpringObservations.OsivObservation.class);
                    assertThat(observation.complete()).isTrue();
                    assertThat(observation.registration()).isNull();
                });
        new ApplicationContextRunner()
                .withBean(
                        "lazyMapping",
                        SimpleUrlHandlerMapping.class,
                        () -> {
                            throw new AssertionError("Must not construct lazy mapping");
                        },
                        definition -> definition.setLazyInit(true))
                .run(context -> {
                    var snapshot = SpringInventory.discover(context.getBeanFactory(), context.getEnvironment(), false);
                    assertThat(new OpenSessionInViewEnabledRule()
                                    .evaluate(snapshot)
                                    .status())
                            .isEqualTo("SKIPPED");
                    assertThat(SpringScanner.evidence(snapshot).coverageComplete())
                            .isFalse();
                });
        new ApplicationContextRunner()
                .withBean(SimpleUrlHandlerMapping.class)
                .withBean(OpenEntityManagerInViewFilter.class)
                .run(context -> {
                    var observation = SpringInventory.discover(
                                    context.getBeanFactory(), context.getEnvironment(), false)
                            .observations()
                            .get(OSIV, SpringObservations.OsivObservation.class);
                    assertThat(observation.complete()).isFalse();
                    assertThat(observation.registration()).isNull();
                });
    }

    @Test
    void nativeSecurityFilterRegistrationIsInspectedWithoutCallingCustomChains() {
        for (boolean custom : List.of(true, false)) {
            new ApplicationContextRunner()
                    .withBean(SimpleUrlHandlerMapping.class)
                    .withBean("proxy", org.springframework.security.web.FilterChainProxy.class, () -> {
                        org.springframework.security.web.SecurityFilterChain chain = custom
                                ? new org.springframework.security.web.SecurityFilterChain() {
                                    @Override
                                    public boolean matches(jakarta.servlet.http.HttpServletRequest request) {
                                        throw new AssertionError("Must not evaluate request matchers");
                                    }

                                    @Override
                                    public List<jakarta.servlet.Filter> getFilters() {
                                        throw new AssertionError("Must not inspect a custom chain");
                                    }
                                }
                                : new org.springframework.security.web.DefaultSecurityFilterChain(
                                        request -> {
                                            throw new AssertionError("Must not evaluate request matchers");
                                        },
                                        new OpenEntityManagerInViewFilter());
                        return new org.springframework.security.web.FilterChainProxy(chain);
                    })
                    .withBean(
                            "registration",
                            org.springframework.boot.web.servlet.DelegatingFilterProxyRegistrationBean.class,
                            () -> new org.springframework.boot.web.servlet.DelegatingFilterProxyRegistrationBean(
                                    "proxy"))
                    .run(context -> {
                        var snapshot =
                                SpringInventory.discover(context.getBeanFactory(), context.getEnvironment(), false);
                        assertThat(new OpenSessionInViewEnabledRule()
                                        .evaluate(snapshot)
                                        .status())
                                .isEqualTo(custom ? "SKIPPED" : "VIOLATION");
                        assertThat(SpringScanner.evidence(snapshot).coverageComplete())
                                .isEqualTo(!custom);
                    });
        }
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getIfAvailable() {
                return value;
            }
        };
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class Scheduling {}

    @Configuration(proxyBeanMethods = false)
    static class NullMapping {
        @org.springframework.context.annotation.Bean
        SimpleUrlHandlerMapping optionalMapping() {
            return null;
        }
    }

    static class Tasks {
        @Scheduled(initialDelay = 3_600_000, fixedDelay = 3_600_000)
        void first() {}

        @Scheduled(initialDelay = 3_600_000, fixedDelay = 3_600_000)
        void second() {}
    }

    static class QualifiedTasks {
        @Scheduled(scheduler = "special", initialDelay = 3_600_000, fixedDelay = 3_600_000)
        void task() {}
    }

    static class CustomCache extends NoOpCacheManager implements CacheActivityAware {
        @Override
        public CacheManager getTargetCacheManager() {
            throw new AssertionError("Must not call application delegate");
        }
    }

    static class CustomMapping extends SimpleUrlHandlerMapping {}

    static class CustomFilterRegistration extends FilterRegistrationBean<OpenEntityManagerInViewFilter> {
        @Override
        public OpenEntityManagerInViewFilter getFilter() {
            throw new AssertionError("Must not call custom registration");
        }
    }
}
