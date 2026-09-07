package io.github.jdubois.bootui.autoconfigure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.core.dto.SecurityReport;
import io.github.jdubois.bootui.engine.reactivesecurity.ReactiveSecurityObservation;
import io.github.jdubois.bootui.engine.reactivesecurity.ReactiveSecurityScanner;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementContextAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.reactive.ReactiveWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import reactor.core.publisher.Mono;

class ReactiveSecurityBootstrapTests {

    @Test
    @SuppressWarnings("unchecked")
    void fullReactiveSpringApplicationHasCompletePassiveSecurityEvidence() {
        SpringApplication application = new SpringApplication(BootstrapConfiguration.class);
        application.setWebApplicationType(WebApplicationType.REACTIVE);
        application.setRegisterShutdownHook(false);
        application.setDefaultProperties(Map.of(
                "spring.config.location", "classpath:reactive-security-bootstrap.properties",
                "bootui.enabled", "ON",
                "spring.main.banner-mode", "off"));
        try (var context = application.run("--server.port=0")) {
            var environment = context.getEnvironment();
            var sourceTypes = environment.getPropertySources().stream()
                    .map(source -> source.getClass().getName())
                    .toList();
            assertThat(sourceTypes).anyMatch(type -> type.endsWith("ApplicationInfoPropertySource"));
            assertThat(sourceTypes).anyMatch(type -> type.endsWith("OriginAwareSystemEnvironmentPropertySource"));
            assertThat(sourceTypes).anyMatch(type -> type.endsWith("BootUiOverridesPropertySource"));
            assertThat(sourceTypes).anyMatch(type -> type.endsWith("SimpleCommandLinePropertySource"));
            assertThat(sourceTypes).anyMatch(type -> type.endsWith("LocalManagementPortPropertySource"));
            ObjectProvider<ListableBeanFactory> factories = mock(ObjectProvider.class);
            when(factories.getIfAvailable()).thenReturn(context.getBeanFactory());
            ReactiveSecurityObservation observation = new SpringReactiveSecurityObservationCollector(
                            context.getBeanProvider(SecurityWebFilterChain.class), factories, environment)
                    .collect();
            SecurityReport report = ReactiveSecurityScanner.using(() -> observation, Clock.systemUTC())
                    .scan();
            var captured = (org.springframework.core.env.ConfigurableEnvironment)
                    SecurityEnvironmentSnapshot.capture(environment);
            var capturedTypes = captured.getPropertySources().stream()
                    .map(source -> source.getName() + ":" + source.getClass().getName())
                    .toList();
            assertThat(report.scan().status())
                    .as(
                            "Native sources %s; captured %s; observation %s; report %s",
                            sourceTypes, capturedTypes, observation, report)
                    .isEqualTo("SCANNED");
            assertThat(report.analysisErrors()).isEmpty();
            assertThat(report.rulesEvaluated()).isEqualTo(25);
            assertThat(report.filterChainsAnalyzed()).isEqualTo(1);
            assertThat(observation.environment().suspectedHardcodedSecretKeys()).contains("security.audit.password");
            assertThat(report.toString()).doesNotContain("bootstrap-test-literal");
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(ManagementContextAutoConfiguration.class)
    static class BootstrapConfiguration {
        @Bean
        SecurityWebFilterChain securityChain() {
            return ServerHttpSecurity.http()
                    .authenticationManager(authentication -> {
                        throw new AssertionError("Authentication callback must not execute during inspection");
                    })
                    .httpBasic(Customizer.withDefaults())
                    .authorizeExchange(exchange -> exchange.anyExchange().authenticated())
                    .build();
        }

        @Bean
        HttpHandler httpHandler() {
            return (request, response) -> Mono.empty();
        }

        @Bean
        ReactiveWebServerFactory webServerFactory() {
            return handler -> new WebServer() {
                @Override
                public void start() {}

                @Override
                public void stop() {}

                @Override
                public int getPort() {
                    return 0;
                }
            };
        }
    }
}
