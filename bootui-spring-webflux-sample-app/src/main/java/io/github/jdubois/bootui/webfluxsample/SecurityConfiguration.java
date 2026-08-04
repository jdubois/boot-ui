package io.github.jdubois.bootui.webfluxsample;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Minimal reactive Spring Security configuration for the WebFlux sample application.
 *
 * <p>The BootUI permit-all chain ({@code BootUiReactiveSpringSecurityAutoConfiguration}) has
 * highest precedence and handles the exact {@code /bootui} root plus all descendants. This
 * configuration defines the application's own catch-all chain: it permits the demo API and requires
 * authentication for everything else — just enough to demonstrate a non-trivial reactive security
 * setup that BootUI can inspect via the Spring Security panel.</p>
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfiguration {

    /**
     * Public access for the demo REST API paths and static resources; everything else (including
     * the root) requires authentication. This is intentionally kept simple so CI can run without a
     * login step.
     */
    @Bean
    public SecurityWebFilterChain applicationSecurityWebFilterChain(ServerHttpSecurity http) {
        return http.authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/greeting/**", "/api/**", "/actuator/**")
                        .permitAll()
                        .anyExchange()
                        .authenticated())
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .build();
    }
}
