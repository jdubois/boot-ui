package io.github.jdubois.bootui.autoconfigure;

import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveLocalhostOnlyFilter;
import io.github.jdubois.bootui.autoconfigure.safety.ActuatorGuardScope;
import io.github.jdubois.bootui.autoconfigure.safety.LocalhostOnlyFilter;
import io.github.jdubois.bootui.autoconfigure.safety.NativeForwarding;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.autoconfigure.web.ManagementContextConfiguration;
import org.springframework.boot.actuate.autoconfigure.web.ManagementContextType;
import org.springframework.boot.actuate.endpoint.web.PathMappedEndpoints;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.server.ServerWebExchange;

/** Native management contexts keep their authentication; only BootUI-added HTTP data is source-guarded. */
@ManagementContextConfiguration(value = ManagementContextType.ANY, proxyBeanMethods = false)
@org.springframework.boot.autoconfigure.AutoConfiguration
@Conditional(BootUiActivationCondition.class)
public class BootUiActuatorGuardConfiguration {

    private static ActuatorGuardScope scope(
            Environment environment,
            ObjectProvider<PathMappedEndpoints> endpoints,
            org.springframework.context.ApplicationContext context) {
        var namespace = org.springframework.boot.actuate.endpoint.web.WebServerNamespace.from(context);
        boolean endpointsHere = namespace.equals(
                        org.springframework.boot.actuate.endpoint.web.WebServerNamespace.MANAGEMENT)
                || org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType.get(environment)
                        != org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType.DIFFERENT;
        return new ActuatorGuardScope(environment, endpoints::getIfAvailable, endpointsHere, namespace);
    }

    private static BootUiProperties rawActuatorProperties(Environment environment) {
        BootUiProperties properties =
                Binder.get(environment).bind("bootui", BootUiProperties.class).orElseGet(BootUiProperties::new);
        // The broad console escape hatch requires API token auth, which does not protect raw Actuator URLs.
        properties.setAllowNonLocalhost(false);
        return properties;
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class ServletGuard {

        @Bean
        FilterRegistrationBean<LocalhostOnlyFilter> bootUiActuatorGuardRegistration(
                Environment environment,
                ObjectProvider<PathMappedEndpoints> endpoints,
                org.springframework.context.ApplicationContext context) {
            ActuatorGuardScope scope = scope(environment, endpoints, context);
            LocalhostOnlyFilter filter =
                    new LocalhostOnlyFilter(rawActuatorProperties(environment), NativeForwarding.enabled(environment)) {
                        @Override
                        protected boolean shouldNotFilter(HttpServletRequest request) {
                            return !scope.guards(pathWithinApplication(request));
                        }
                    };
            FilterRegistrationBean<LocalhostOnlyFilter> registration = new FilterRegistrationBean<>(filter);
            registration.addUrlPatterns("/*");
            registration.setOrder(Integer.MIN_VALUE + 1);
            registration.setName("bootUiActuatorGuard");
            return registration;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    static class ReactiveGuard {

        @Bean
        @org.springframework.core.annotation.Order(Integer.MIN_VALUE + 1)
        org.springframework.web.server.WebFilter bootUiReactiveActuatorGuard(
                Environment environment,
                ObjectProvider<PathMappedEndpoints> endpoints,
                org.springframework.context.ApplicationContext context) {
            ActuatorGuardScope scope = scope(environment, endpoints, context);
            ReactiveLocalhostOnlyFilter delegate =
                    new ReactiveLocalhostOnlyFilter(
                            rawActuatorProperties(environment), NativeForwarding.enabled(environment)) {
                        @Override
                        protected boolean shouldNotFilter(ServerWebExchange exchange) {
                            return !scope.guards(pathWithinApplication(exchange.getRequest()));
                        }
                    };
            return delegate::filter;
        }
    }
}
