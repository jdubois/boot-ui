package io.github.jdubois.bootui.autoconfigure;

import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveBootUiShellGuardFilter;
import io.github.jdubois.bootui.autoconfigure.safety.BootUiShellGuardFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnResource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The one piece of BootUI that is wired while BootUI is <strong>off</strong>.
 *
 * <p>{@link BootUiAutoConfiguration} and {@link BootUiReactiveAutoConfiguration} register nothing when
 * {@link BootUiActivationCondition} does not match, which makes the whole JSON API unreachable. It does
 * not make the console unreachable: {@code bootui-ui} packages the compiled Vue bundle at
 * {@code META-INF/resources/bootui/}, and {@code classpath:/META-INF/resources/} is a default
 * static-resource location for both {@code WebMvcAutoConfiguration} and {@code WebFluxAutoConfiguration}.
 * A production deployment therefore still answered {@code GET /bootui/index.html} (and every asset under
 * it) with {@code 200} — an empty shell with no API behind it, but reachable (#856).</p>
 *
 * <p>This auto-configuration closes that gap with a filter that answers {@code 404} for the reserved
 * {@code /bootui} classpath mount, on Spring MVC and Spring WebFlux alike. It mirrors the Quarkus
 * adapter's always-registered {@code BootUiProdShellGuardFilter}, with the difference that Spring can
 * express the decision as a bean condition: {@link BootUiInactiveCondition} is the exact negation of the
 * activation condition and delegates to the same {@code resolve} call, so the guard exists precisely
 * when the console does not and the two polarities cannot drift apart.</p>
 *
 * <p>{@link ConditionalOnResource} keeps the guard honest: it only claims the mount when the packaged
 * shell it is guarding is actually on the classpath, so an application that depends on
 * the auto-configuration without {@code bootui-ui} keeps {@code /bootui} for itself.</p>
 *
 * <p>Scope is the reserved {@code /bootui} namespace, plus the same namespace under whatever prefixes
 * {@link BootUiShellGuardMounts} derives from the host's static-resource configuration. Everything
 * outside those mounts is passed through untouched.</p>
 */
@AutoConfiguration
@Conditional(BootUiInactiveCondition.class)
@ConditionalOnResource(resources = "classpath:/META-INF/resources/bootui/index.html")
public class BootUiShellGuardAutoConfiguration {

    /**
     * Servlet binding. Registered at {@link Integer#MIN_VALUE} so the guard runs before any host filter
     * chain can act on a request BootUI is about to reject, and mapped to every derived mount rather
     * than only {@code /bootui/*}.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
    static class ServletShellGuardConfiguration {

        @Bean
        BootUiShellGuardFilter bootUiShellGuardFilter(Environment environment) {
            return new BootUiShellGuardFilter(BootUiShellGuardMounts.servlet(environment));
        }

        @Bean
        FilterRegistrationBean<BootUiShellGuardFilter> bootUiShellGuardFilterRegistration(
                BootUiShellGuardFilter filter, Environment environment) {
            FilterRegistrationBean<BootUiShellGuardFilter> registration = new FilterRegistrationBean<>(filter);
            for (String mount : BootUiShellGuardMounts.servlet(environment)) {
                registration.addUrlPatterns(mount + "/*");
            }
            registration.setOrder(Integer.MIN_VALUE);
            registration.setName("bootUiShellGuardFilter");
            return registration;
        }
    }

    /** WebFlux binding. {@code WebFilter} beans are ordered by {@code Ordered}, not by a registration. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    @ConditionalOnClass(name = "org.springframework.web.reactive.DispatcherHandler")
    static class ReactiveShellGuardConfiguration {

        @Bean
        ReactiveBootUiShellGuardFilter bootUiReactiveShellGuardFilter(Environment environment) {
            return new ReactiveBootUiShellGuardFilter(BootUiShellGuardMounts.reactive(environment));
        }
    }
}
