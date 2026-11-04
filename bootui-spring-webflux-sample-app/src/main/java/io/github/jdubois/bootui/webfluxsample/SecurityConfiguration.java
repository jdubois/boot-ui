package io.github.jdubois.bootui.webfluxsample;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Minimal reactive Spring Security configuration for the WebFlux sample application.
 *
 * <p>The BootUI permit-all chain ({@code BootUiReactiveSpringSecurityAutoConfiguration}) has
 * highest precedence and handles the exact {@code /bootui} root plus all descendants. This
 * configuration defines the application's own catch-all chain: it permits the welcome page, its
 * assets, and the demo API, and requires authentication for everything else. This demonstrates a
 * non-trivial reactive security setup that BootUI can inspect via the Spring Security panel.</p>
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfiguration {

    /**
     * Public access for the welcome page's exact GET resources, demo REST API paths, and echo WebSocket.
     * Other application paths still require authentication.
     */
    @Bean
    public SecurityWebFilterChain applicationSecurityWebFilterChain(ServerHttpSecurity http) {
        return http.authorizeExchange(exchanges -> exchanges
                        .pathMatchers(
                                HttpMethod.GET,
                                "/",
                                "/index.html",
                                "/favicon.ico",
                                "/favicon.svg",
                                "/apple-touch-icon.png",
                                "/apple-touch-icon-precomposed.png",
                                "/bootui-theme.css",
                                "/sample-lab.css",
                                "/sample-lab.js",
                                "/index.js")
                        .permitAll()
                        .pathMatchers("/greeting/**", "/api/**", "/actuator/**", "/echo")
                        .permitAll()
                        .anyExchange()
                        .authenticated())
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .build();
    }
}
