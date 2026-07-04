package io.github.jdubois.bootui.autoconfigure;

import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveBootUiIndexController;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveBootUiStaticResourceConfigurer;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveLocalhostOnlyFilter;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactivePanelAccessFilter;
import io.github.jdubois.bootui.autoconfigure.web.OverviewController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aot.AotDetector;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.core.env.Environment;

/**
 * Reactive (WebFlux) sibling of {@link BootUiAutoConfiguration}: activates BootUI on a Spring Boot 4
 * app that runs over WebFlux/Netty (a {@code DispatcherHandler}-based reactive web application) instead
 * of Spring MVC/servlet.
 *
 * <p>Mutually exclusive in practice with the servlet {@link BootUiAutoConfiguration}: Spring Boot picks
 * exactly one {@code WebApplicationType} per running application (when both Spring MVC and WebFlux are
 * on the classpath, {@code WebApplicationType.deduceFromClasspath()} always resolves {@code SERVLET}),
 * so at most one of the two BootUI auto-configurations ever activates.</p>
 *
 * <p>This is currently a partial port: activation, the safety filters ({@link ReactiveLocalhostOnlyFilter},
 * {@link ReactivePanelAccessFilter}), the static asset handler, the SPA shell
 * ({@link ReactiveBootUiIndexController}), and the framework-neutral engine wiring shared with the
 * servlet adapter ({@link BootUiEngineConfiguration}). The read-only data panels &mdash; including
 * Traces/AI Usage, which need the {@code TelemetryStore} bean and
 * {@link BootUiOpenTelemetryConfiguration} that {@link BootUiAutoConfiguration} defines directly, not
 * {@link BootUiEngineConfiguration} &mdash; are ported incrementally on top of this floor; see
 * {@code docs/WEBFLUX-SUPPORT.md} for current per-panel availability on this adapter.</p>
 */
@AutoConfiguration
@AutoConfigureBefore(
        name = {
            "org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.audit.AuditAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.web.exchanges.HttpExchangesEndpointAutoConfiguration",
            "org.springframework.boot.webflux.autoconfigure.actuate.web.exchanges.WebFluxHttpExchangesAutoConfiguration"
        })
@Conditional(BootUiActivationCondition.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnClass(name = "org.springframework.web.reactive.DispatcherHandler")
@EnableConfigurationProperties(BootUiProperties.class)
@ImportRuntimeHints(BootUiRuntimeHints.class)
@Import({OverviewController.class, ReactiveBootUiIndexController.class, BootUiEngineConfiguration.class})
public class BootUiReactiveAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(BootUiReactiveAutoConfiguration.class);

    @Bean
    public BootUiActivation bootUiActivation(Environment environment) {
        BootUiActivation activation =
                BootUiActivationCondition.resolve(environment, getClass().getClassLoader());
        if (!activation.enabled() && AotDetector.useGeneratedArtifacts()) {
            // See BootUiAutoConfiguration.bootUiActivation for why the frozen build-time decision wins
            // in a native image: this bean only exists because the condition matched at build time.
            activation = new BootUiActivation(true, "Enabled at build time (AOT/native image)", activation.warnings());
        }
        log.info("BootUI activation: {}", activation.reason());
        for (String warning : activation.warnings()) {
            log.warn("BootUI activation warning: {}", warning);
        }
        return activation;
    }

    @Bean
    public ReactiveLocalhostOnlyFilter bootUiReactiveLocalhostOnlyFilter(BootUiProperties properties) {
        // Same rationale as LocalhostOnlyFilter: builds its own ContainerGatewayDetector so it can
        // auto-trust the container default gateway per bootui.trust-container-gateway.
        return new ReactiveLocalhostOnlyFilter(properties);
    }

    @Bean
    public ReactivePanelAccessFilter bootUiReactivePanelAccessFilter(BootUiProperties properties) {
        return new ReactivePanelAccessFilter(properties);
    }

    @Bean
    public ReactiveBootUiStaticResourceConfigurer bootUiReactiveStaticResourceConfigurer(Environment environment) {
        return new ReactiveBootUiStaticResourceConfigurer(environment);
    }
}
