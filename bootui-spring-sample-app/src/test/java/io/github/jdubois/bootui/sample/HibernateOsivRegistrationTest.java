package io.github.jdubois.bootui.sample;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.jdubois.bootui.autoconfigure.hibernate.SpringHibernateAdvisorObservationSource;
import io.github.jdubois.bootui.engine.hibernate.HibernateApplicationFacts;
import jakarta.persistence.EntityManagerFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jpa.autoconfigure.JpaProperties;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurationSupport;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.handler.MappedInterceptor;
import org.springframework.web.servlet.handler.WebRequestHandlerInterceptorAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Boot's OSIV configurer is not activation evidence when an application bypasses delegating MVC. */
class HibernateOsivRegistrationTest {
    @Test
    void bootOsivBeansAreNotActiveWithDirectWebMvcConfigurationSupport() throws Exception {
        runner().withUserConfiguration(DirectMvcConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(OpenEntityManagerInViewInterceptor.class);
            assertThat(context).hasBean("openEntityManagerInViewInterceptorConfigurer");
            assertThat(context.getBean(RequestMappingHandlerMapping.class).getAdaptedInterceptors())
                    .noneMatch(WebRequestHandlerInterceptorAdapter.class::isInstance);
            var source = new SpringHibernateAdvisorObservationSource(
                    context.getBeanFactory(), context.getEnvironment(), context.getSourceApplicationContext());
            assertThat(source.observe().application().openInView())
                    .isEqualTo(HibernateApplicationFacts.OpenInView.UNKNOWN);
            verify(context.getBean(EntityManagerFactory.class), never()).createEntityManager();
        });
    }

    @Test
    void bootOsivConfigurerIsAppliedByDelegatingMvc() throws Exception {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(OpenEntityManagerInViewInterceptor.class);
            assertThat(context).hasBean("openEntityManagerInViewInterceptorConfigurer");
            assertThat(context.getBean(RequestMappingHandlerMapping.class).getAdaptedInterceptors())
                    .anyMatch(WebRequestHandlerInterceptorAdapter.class::isInstance);
            var source = new SpringHibernateAdvisorObservationSource(
                    context.getBeanFactory(), context.getEnvironment(), context.getSourceApplicationContext());
            assertThat(source.observe().application().openInView())
                    .isEqualTo(HibernateApplicationFacts.OpenInView.ENABLED);
            verify(context.getBean(EntityManagerFactory.class), never()).createEntityManager();
        });
    }

    @Test
    void explicitBootOffDoesNotCreateOsivBeansOrProveGlobalActivation() throws Exception {
        runner().withPropertyValues("spring.jpa.open-in-view=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(OpenEntityManagerInViewInterceptor.class);
            assertThat(context).doesNotHaveBean("openEntityManagerInViewInterceptorConfigurer");
            assertThat(context.getBean(RequestMappingHandlerMapping.class).getAdaptedInterceptors())
                    .noneMatch(WebRequestHandlerInterceptorAdapter.class::isInstance);
            assertThat(context.getServletContext().getFilterRegistrations()).isEmpty();
            var source = new SpringHibernateAdvisorObservationSource(
                    context.getBeanFactory(), context.getEnvironment(), context.getSourceApplicationContext());
            assertThat(source.observe().application().openInView())
                    .isEqualTo(HibernateApplicationFacts.OpenInView.DISABLED);
        });
    }

    @Test
    void customMappedOsivRegistrationIsReadWithoutCallingItsConfigurerAgain() throws Exception {
        runner().withPropertyValues("spring.jpa.open-in-view=false")
                .withUserConfiguration(MappedOsivConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean("openEntityManagerInViewInterceptorConfigurer");
                    assertThat(context.getBean(RequestMappingHandlerMapping.class)
                                    .getAdaptedInterceptors())
                            .anyMatch(MappedInterceptor.class::isInstance);
                    AtomicInteger calls = context.getBean(MappedOsivConfiguration.class).calls;
                    int afterRefresh = calls.get();
                    assertThat(afterRefresh).isPositive();
                    var source = new SpringHibernateAdvisorObservationSource(
                            context.getBeanFactory(), context.getEnvironment(), context.getSourceApplicationContext());
                    assertThat(source.observe().application().openInView())
                            .isEqualTo(HibernateApplicationFacts.OpenInView.ENABLED);
                    assertThat(calls).hasValue(afterRefresh);
                    verify(context.getBean(EntityManagerFactory.class), never()).createEntityManager();
                });
    }

    private WebApplicationContextRunner runner() throws ClassNotFoundException {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        WebMvcAutoConfiguration.class,
                        Class.forName(
                                "org.springframework.boot.jpa.autoconfigure.JpaBaseConfiguration$JpaWebConfiguration")))
                .withBean(JpaProperties.class, JpaProperties::new)
                .withBean(EntityManagerFactory.class, () -> mock(EntityManagerFactory.class))
                .withPropertyValues("spring.jpa.open-in-view=true");
    }

    @Configuration(proxyBeanMethods = false)
    static class DirectMvcConfiguration extends WebMvcConfigurationSupport {}

    @Configuration(proxyBeanMethods = false)
    static class MappedOsivConfiguration implements WebMvcConfigurer {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public void addInterceptors(InterceptorRegistry registry) {
            calls.incrementAndGet();
            registry.addWebRequestInterceptor(new OpenEntityManagerInViewInterceptor())
                    .addPathPatterns("/app/**");
        }
    }
}
