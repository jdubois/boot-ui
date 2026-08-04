package io.github.jdubois.bootui.webfluxsample;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Reference Spring Boot 4 WebFlux (reactive, Netty) application used to exercise the BootUI reactive
 * adapter. Deliberately minimal: a couple of {@code Mono}/{@code Flux} REST endpoints backed by blocking
 * JDBC (off the event loop), a {@code @Scheduled} task, a {@code @Cacheable} service, and a couple of
 * dev-trapped sample emails - just enough to light up the Scheduled Tasks, Cache, Database Connection
 * Pools, Flyway, Liquibase, Email, and Spring Security panels alongside the panels that need no
 * application-specific data at all.
 *
 * <p>Spring Security is included so that both the raw Spring Security panel and the Security advisor
 * are available on WebFlux.
 * {@code BootUiReactiveSpringSecurityAutoConfiguration} registers a high-precedence permit-all
 * {@code SecurityWebFilterChain} for the exact {@code /bootui} root and its descendants so BootUI
 * itself is never blocked. The application's own {@code SecurityConfiguration} defines the
 * remaining chain.</p>
 */
@SpringBootApplication
@EnableCaching
@EnableScheduling
public class BootUiWebfluxSampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(BootUiWebfluxSampleApplication.class, args);
    }
}
