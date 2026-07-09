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
 * Pools, Flyway, Liquibase, and Email panels alongside the panels that need no application-specific data
 * at all.
 *
 * <p>Deliberately does NOT add Spring Security: BootUI's Spring Security advisor and its own
 * permit-all {@code SecurityWebFilterChain} binding are not yet ported to WebFlux (see
 * {@code BootUiSpringSecurityAutoConfiguration}, servlet-only today), so adding
 * {@code spring-boot-starter-security} here without that binding would make Spring Security's default
 * reactive login wall block every request, including {@code /bootui/**} itself.
 */
@SpringBootApplication
@EnableCaching
@EnableScheduling
public class BootUiWebfluxSampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(BootUiWebfluxSampleApplication.class, args);
    }
}
