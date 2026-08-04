package io.github.jdubois.bootui.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveSpringSecurityController;
import io.github.jdubois.bootui.engine.safety.ApiTokenAuthenticator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.webflux.autoconfigure.HttpHandlerAutoConfiguration;
import org.springframework.boot.webflux.autoconfigure.WebFluxAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UserDetailsRepositoryReactiveAuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

class BootUiReactiveSpringSecurityAutoConfigurationTests {

    private final ReactiveWebApplicationContextRunner runner = new ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    HttpHandlerAutoConfiguration.class,
                    WebFluxAutoConfiguration.class,
                    BootUiReactiveAutoConfiguration.class,
                    BootUiReactiveSpringSecurityAutoConfiguration.class))
            .withUserConfiguration(TestSecurityConfiguration.class, TestController.class)
            .withPropertyValues(
                    "bootui.enabled=ON", "bootui.allow-non-localhost=true", "bootui.authentication.token=test-token");

    @Test
    void registersThePermitAllChainFromSecurityInfrastructureWithoutClaimingAnApplicationChain() {
        new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        HttpHandlerAutoConfiguration.class,
                        WebFluxAutoConfiguration.class,
                        BootUiReactiveAutoConfiguration.class,
                        BootUiReactiveSpringSecurityAutoConfiguration.class))
                .withUserConfiguration(SecurityInfrastructureOnlyConfiguration.class)
                .withPropertyValues(
                        "bootui.enabled=ON",
                        "bootui.allow-non-localhost=true",
                        "bootui.authentication.token=test-token")
                .run(context -> {
                    assertThat(context)
                            .hasNotFailed()
                            .hasBean("bootUiReactiveSecurityWebFilterChain")
                            .hasSingleBean(ReactiveSpringSecurityController.class);
                    client(context.getSourceApplicationContext())
                            .get()
                            .uri("/bootui/api/spring-security")
                            .exchange()
                            .expectStatus()
                            .isOk()
                            .expectBody()
                            .jsonPath("$.springSecurityPresent")
                            .isEqualTo(false)
                            .jsonPath("$.chains")
                            .isEmpty();
                });
    }

    @Test
    void permitsOnlyTheBootUiRootAndDescendantsAheadOfApplicationSecurity() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ReactiveSpringSecurityController.class);
            WebTestClient client = client(context.getSourceApplicationContext());

            client.get()
                    .uri("/bootui/api/spring-security")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.springSecurityPresent")
                    .isEqualTo(true)
                    .jsonPath("$.chains.length()")
                    .isEqualTo(1)
                    .jsonPath("$.chains[0].order")
                    .isEqualTo(1);
            client.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/bootui/api/spring-security/explain")
                            .queryParam("method", "GET")
                            .queryParam("path", "/protected")
                            .build())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.matched")
                    .isEqualTo(true)
                    .jsonPath("$.bestEffort")
                    .isEqualTo(true)
                    .jsonPath("$.chainIndex")
                    .isEqualTo(1);
            client.get()
                    .uri("/bootui/api/spring-security/endpoints")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.handlerMappingAvailable")
                    .isEqualTo(true)
                    .jsonPath("$.endpoints[?(@.pattern=='/protected')].rule")
                    .isEqualTo("authenticated")
                    .jsonPath("$.endpoints[?(@.pattern=='/public')].rule")
                    .isEqualTo("permitAll")
                    .jsonPath("$.endpoints[?(@.pattern =~ /.*bootui.*/)]")
                    .isEmpty();
            client.get().uri("/bootui").exchange().expectStatus().isOk();

            client.get().uri("/protected").exchange().expectStatus().isUnauthorized();
            client.get().uri("/bootui-not").exchange().expectStatus().isUnauthorized();
        });
    }

    @Test
    void issuesAndAcceptsTheSpaCsrfCookieHeaderPair() {
        runner.run(context -> {
            WebTestClient client = client(context.getSourceApplicationContext());

            EntityExchangeResult<byte[]> result = client.get()
                    .uri("/bootui/api/spring-security")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .returnResult();
            ResponseCookie csrfCookie = result.getResponseCookies().getFirst("XSRF-TOKEN");
            assertThat(csrfCookie).isNotNull();
            assertThat(csrfCookie.isHttpOnly()).isFalse();

            client.post()
                    .uri("/bootui/api/mcp-server/toggle")
                    .header(HttpHeaders.ORIGIN, "http://localhost")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"enabled\":true}")
                    .exchange()
                    .expectStatus()
                    .isForbidden();

            client.post()
                    .uri("/bootui/api/mcp-server/toggle")
                    .header(HttpHeaders.ORIGIN, "http://localhost")
                    .header("X-XSRF-TOKEN", csrfCookie.getValue())
                    .cookie(csrfCookie.getName(), csrfCookie.getValue())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"enabled\":true}")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.enabled")
                    .isEqualTo(true);
        });
    }

    @Test
    void leavesProgrammaticMcpAndSessionPostsOutsideCsrfProtection() {
        runner.run(context -> {
            WebTestClient client = client(context.getSourceApplicationContext());

            client.post()
                    .uri("/bootui/api/mcp")
                    .header(HttpHeaders.ORIGIN, "http://localhost")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}")
                    .exchange()
                    .expectStatus()
                    .isOk();

            client.post()
                    .uri("/bootui/api/auth/session")
                    .header(HttpHeaders.ORIGIN, "http://localhost")
                    .exchange()
                    .expectStatus()
                    .isNoContent();
        });
    }

    private static WebTestClient client(org.springframework.context.ApplicationContext context) {
        String token = context.getBean(ApiTokenAuthenticator.class).token();
        return WebTestClient.bindToApplicationContext(context)
                .configureClient()
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebFluxSecurity
    static class TestSecurityConfiguration {

        @Bean
        ReactiveAuthenticationManager authenticationManager() {
            UserDetails user = User.withUsername("developer")
                    .password("{noop}password")
                    .roles("USER")
                    .build();
            return new UserDetailsRepositoryReactiveAuthenticationManager(new MapReactiveUserDetailsService(user));
        }

        @Bean
        @Order(100)
        SecurityWebFilterChain applicationSecurityWebFilterChain(
                ServerHttpSecurity http, ReactiveAuthenticationManager authenticationManager) {
            return http.authenticationManager(authenticationManager)
                    .authorizeExchange(exchange -> exchange.pathMatchers("/public")
                            .permitAll()
                            .anyExchange()
                            .authenticated())
                    .httpBasic(Customizer.withDefaults())
                    .build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebFluxSecurity
    static class SecurityInfrastructureOnlyConfiguration {}

    @RestController
    static class TestController {

        @GetMapping("/protected")
        Mono<String> protectedRoute() {
            return Mono.just("protected");
        }

        @GetMapping("/public")
        Mono<String> publicRoute() {
            return Mono.just("public");
        }
    }
}
