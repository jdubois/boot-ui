package io.github.jdubois.bootui.autoconfigure.spring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jpa.autoconfigure.JpaProperties;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewFilter;

/** Spring-only seam requiring the sample's existing spring-orm dependency; no application or DB starts. */
class SpringOsivObservationTests {
    @Test
    void bootOsivRequiresMvcToApplyItsConfigurer() throws Exception {
        var runner = new WebApplicationContextRunner()
                .withBean(JpaProperties.class)
                .withBean(
                        jakarta.persistence.EntityManagerFactory.class,
                        () -> org.mockito.Mockito.mock(jakarta.persistence.EntityManagerFactory.class))
                .withConfiguration(
                        AutoConfigurations.of(
                                org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration.class,
                                Class.forName(
                                        "org.springframework.boot.jpa.autoconfigure.JpaBaseConfiguration$JpaWebConfiguration")));
        runner.run(context -> {
            assertThat(context)
                    .hasBean("openEntityManagerInViewInterceptor")
                    .hasBean("openEntityManagerInViewInterceptorConfigurer");
            var snapshot = SpringInventory.discover(context.getBeanFactory(), context.getEnvironment(), false);
            assertThat(new OpenSessionInViewEnabledRule().evaluate(snapshot).status())
                    .isEqualTo("VIOLATION");
            assertThat(new OpenSessionInViewEnabledRule().evaluate(snapshot).severity())
                    .isEqualTo("MEDIUM");
            assertThat(new OpenSessionInViewEnabledRule().evaluate(snapshot).sampleViolations())
                    .anyMatch(detail -> detail.contains("Boot servlet interceptor applied"));
            assertThat(context.getBean(
                                    org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
                                            .class)
                            .getAdaptedInterceptors())
                    .anyMatch(
                            org.springframework.web.servlet.handler.WebRequestHandlerInterceptorAdapter.class
                                    ::isInstance);
        });
        runner.withPropertyValues("spring.jpa.open-in-view=false").run(context -> {
            assertThat(context).doesNotHaveBean("openEntityManagerInViewInterceptor");
            assertConfirmedAbsence(SpringInventory.discover(context.getBeanFactory(), context.getEnvironment(), false));
        });
        runner.withUserConfiguration(NonDelegatingMvc.class).run(context -> {
            assertThat(context)
                    .hasNotFailed()
                    .hasBean("openEntityManagerInViewInterceptor")
                    .hasBean("openEntityManagerInViewInterceptorConfigurer");
            assertThat(context.getBean(
                                    org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
                                            .class)
                            .getAdaptedInterceptors())
                    .noneMatch(
                            org.springframework.web.servlet.handler.WebRequestHandlerInterceptorAdapter.class
                                    ::isInstance);
            assertConfirmedAbsence(SpringInventory.discover(context.getBeanFactory(), context.getEnvironment(), false));
        });
    }

    private static void assertConfirmedAbsence(SpringContext snapshot) {
        var observation =
                snapshot.observations().get(SpringObservations.Fact.OSIV, SpringObservations.OsivObservation.class);
        assertThat(observation.registration()).isNull();
        assertThat(observation.complete()).isTrue();
        assertThat(snapshot.entityManagerFactoryPresent()).isTrue();
        var result = new OpenSessionInViewEnabledRule().evaluate(snapshot);
        assertThat(result.status()).isEqualTo("PASS");
        assertThat(result.violationCount()).isZero();
        assertThat(SpringScanner.evidence(snapshot).usable()).isTrue();
        assertThat(SpringScanner.evidence(snapshot).coverageComplete()).isTrue();
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class NonDelegatingMvc
            extends org.springframework.web.servlet.config.annotation.WebMvcConfigurationSupport {}

    @Test
    void registeredOsivFilterCountsButStandaloneFilterDoesNotAndWebFluxIsInapplicable() {
        new WebApplicationContextRunner()
                .withBean(
                        "registration",
                        FilterRegistrationBean.class,
                        () -> new FilterRegistrationBean<>(new OpenEntityManagerInViewFilter()))
                .run(context -> {
                    var snapshot = SpringInventory.discover(context.getBeanFactory(), context.getEnvironment(), false);
                    assertThat(new OpenSessionInViewEnabledRule()
                                    .evaluate(snapshot)
                                    .status())
                            .isEqualTo("VIOLATION");
                    assertThat(new OpenSessionInViewEnabledRule()
                                    .evaluate(snapshot)
                                    .sampleViolations()
                                    .get(0))
                            .contains("mappings");
                });
        new WebApplicationContextRunner()
                .withBean(OpenEntityManagerInViewFilter.class)
                .run(context -> assertThat(new OpenSessionInViewEnabledRule()
                                .evaluate(SpringInventory.discover(
                                        context.getBeanFactory(), context.getEnvironment(), false))
                                .status())
                        .isEqualTo("SKIPPED"));
        new ReactiveWebApplicationContextRunner()
                .run(context -> assertThat(new OpenSessionInViewEnabledRule()
                                .evaluate(SpringInventory.discover(
                                        context.getBeanFactory(), context.getEnvironment(), true))
                                .status())
                        .isEqualTo("SKIPPED"));
    }

    @Test
    void actualCustomMvcInterceptorMappingsAreObservedWithoutCallingTheConfigurer() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration.class))
                .withBean(
                        "customMvc",
                        org.springframework.web.servlet.config.annotation.WebMvcConfigurer.class,
                        () -> new org.springframework.web.servlet.config.annotation.WebMvcConfigurer() {
                            @Override
                            public void addInterceptors(
                                    org.springframework.web.servlet.config.annotation.InterceptorRegistry registry) {
                                calls.incrementAndGet();
                                registry.addWebRequestInterceptor(
                                                new org.springframework.orm.jpa.support
                                                        .OpenEntityManagerInViewInterceptor())
                                        .addPathPatterns("/app/**");
                            }
                        })
                .run(context -> {
                    int before = calls.get();
                    var result = new OpenSessionInViewEnabledRule()
                            .evaluate(SpringInventory.discover(
                                    context.getBeanFactory(), context.getEnvironment(), false));
                    assertThat(result.status()).isEqualTo("VIOLATION");
                    assertThat(result.sampleViolations().get(0)).contains("mappings");
                    assertThat(calls).hasValue(before);
                });
    }
}
