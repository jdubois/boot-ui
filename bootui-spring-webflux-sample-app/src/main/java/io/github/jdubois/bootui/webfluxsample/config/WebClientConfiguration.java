package io.github.jdubois.bootui.webfluxsample.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Registers an auto-configured {@link WebClient} bean so that the BootUI REST Client panel
 * becomes available in the WebFlux sample app. Spring Boot's {@code WebClientCustomizer}
 * hook (registered by {@code BootUiEngineConfiguration.WebClientCustomizerConfiguration})
 * attaches BootUI's {@code RestClientTraceExchangeFilter} to the builder when
 * {@code bootui.rest-client-trace.enabled} is {@code true}, marking the recorder as having
 * an instrumented client and lighting up the panel.
 */
@Configuration(proxyBeanMethods = false)
class WebClientConfiguration {

    @Bean
    WebClient bootUiDemoWebClient(WebClient.Builder builder) {
        return builder.build();
    }
}
