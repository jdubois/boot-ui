package io.github.jdubois.bootui.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.ValueExposure;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.web.server.MatcherSecurityWebFilterChain;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.csrf.CsrfWebFilter;
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class ReactiveSpringSecurityControllerTests {

    private static final WebFilter PASSTHROUGH = (exchange, chain) -> chain.filter(exchange);

    @SuppressWarnings("unchecked")
    private static WebTestClient client(
            List<SecurityWebFilterChain> chains,
            ReactiveAuthenticationManager authManager,
            ReactiveUserDetailsService userDetailsService,
            MockEnvironment environment,
            BootUiProperties properties) {
        ObjectProvider<SecurityWebFilterChain> chainProvider = mock(ObjectProvider.class);
        when(chainProvider.orderedStream()).thenAnswer(invocation -> chains.stream());

        ObjectProvider<ReactiveAuthenticationManager> authManagerProvider = mock(ObjectProvider.class);
        when(authManagerProvider.stream())
                .thenAnswer(invocation -> authManager == null
                        ? java.util.stream.Stream.empty()
                        : java.util.stream.Stream.of(authManager));

        ObjectProvider<ReactiveUserDetailsService> userDetailsServiceProvider = mock(ObjectProvider.class);
        when(userDetailsServiceProvider.stream())
                .thenAnswer(invocation -> userDetailsService == null
                        ? java.util.stream.Stream.empty()
                        : java.util.stream.Stream.of(userDetailsService));

        ObjectProvider<RequestMappingHandlerMapping> mappingProvider = mock(ObjectProvider.class);
        when(mappingProvider.stream()).thenAnswer(invocation -> java.util.stream.Stream.empty());

        ReactiveSpringSecurityController controller = new ReactiveSpringSecurityController(
                chainProvider,
                authManagerProvider,
                userDetailsServiceProvider,
                mappingProvider,
                environment,
                properties);
        return WebTestClient.bindToController(controller).build();
    }

    @Test
    void listsOrderedReactiveChainsAndFilters() {
        SecurityWebFilterChain apiChain = new MatcherSecurityWebFilterChain(
                new PathPatternParserServerWebExchangeMatcher("/api/**"), List.of(new CsrfWebFilter()));
        SecurityWebFilterChain otherChain = new MatcherSecurityWebFilterChain(
                new PathPatternParserServerWebExchangeMatcher("/other/**"), List.of(PASSTHROUGH));

        client(List.of(apiChain, otherChain), null, null, new MockEnvironment(), new BootUiProperties())
                .get()
                .uri("/bootui/api/spring-security")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.springSecurityPresent")
                .isEqualTo(true)
                .jsonPath("$.chains.length()")
                .isEqualTo(2)
                .jsonPath("$.chains[0].order")
                .isEqualTo(0)
                .jsonPath("$.chains[0].requestMatcher")
                .value(value -> org.assertj.core.api.Assertions.assertThat(value.toString())
                        .contains("/api/**"))
                .jsonPath("$.chains[0].filters[0]")
                .isEqualTo("CsrfWebFilter")
                .jsonPath("$.chains[0].csrfEnabled")
                .isEqualTo(true)
                .jsonPath("$.chains[1].order")
                .isEqualTo(1);
    }

    @Test
    void emptyOrSelfOnlyChainInventoryIsUnavailable() {
        WebTestClient noChains = client(List.of(), null, null, new MockEnvironment(), new BootUiProperties());
        noChains.get()
                .uri("/bootui/api/spring-security")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.springSecurityPresent")
                .isEqualTo(false)
                .jsonPath("$.chains")
                .isEmpty();

        SecurityWebFilterChain bootUiChain = new MatcherSecurityWebFilterChain(
                new PathPatternParserServerWebExchangeMatcher("/bootui/**"), List.of(PASSTHROUGH));
        client(List.of(bootUiChain), null, null, new MockEnvironment(), new BootUiProperties())
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
    }

    @Test
    void composesAsynchronousFilterExtractionWithoutBlocking() {
        WebFilter filter = (exchange, chain) -> chain.filter(exchange);
        SecurityWebFilterChain asynchronousChain = new SecurityWebFilterChain() {
            @Override
            public Mono<Boolean> matches(org.springframework.web.server.ServerWebExchange exchange) {
                return Mono.just(true);
            }

            @Override
            public Flux<WebFilter> getWebFilters() {
                return Flux.just(filter).delayElements(Duration.ofMillis(5));
            }
        };

        client(List.of(asynchronousChain), null, null, new MockEnvironment(), new BootUiProperties())
                .get()
                .uri("/bootui/api/spring-security")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.chains.length()")
                .isEqualTo(1);
    }

    @Test
    void configuredUsernameHonorsExposureWithoutReadingPassword() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.security.user.name", "admin")
                .withProperty("spring.security.user.password", "super-secret-password");
        SecurityWebFilterChain chain = new MatcherSecurityWebFilterChain(
                new PathPatternParserServerWebExchangeMatcher("/api/**"), List.of(PASSTHROUGH));

        client(List.of(chain), null, null, environment, new BootUiProperties())
                .get()
                .uri("/bootui/api/spring-security")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.auth.configuredUsername")
                .isEqualTo("admin")
                .consumeWith(result -> assertThat(
                                new String(result.getResponseBody(), java.nio.charset.StandardCharsets.UTF_8))
                        .doesNotContain("super-secret-password"));

        BootUiProperties metadataOnly = new BootUiProperties();
        metadataOnly.setExposeValues(ValueExposure.METADATA_ONLY);
        client(List.of(chain), null, null, environment, metadataOnly)
                .get()
                .uri("/bootui/api/spring-security")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.auth.configuredUsername")
                .isEmpty();
    }

    @Test
    void listsReactiveAuthenticationBeanTypes() {
        ReactiveAuthenticationManager manager = authentication -> Mono.empty();
        ReactiveUserDetailsService users = username -> Mono.empty();
        SecurityWebFilterChain chain = new MatcherSecurityWebFilterChain(
                new PathPatternParserServerWebExchangeMatcher("/api/**"), List.of(PASSTHROUGH));

        client(List.of(chain), manager, users, new MockEnvironment(), new BootUiProperties())
                .get()
                .uri("/bootui/api/spring-security")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.auth.authenticationProviderTypes.length()")
                .isEqualTo(1)
                .jsonPath("$.auth.userDetailsServiceTypes.length()")
                .isEqualTo(1);
    }

    @Test
    void explainUsesThePublicChainMatcherForCustomChainTypes() {
        SecurityWebFilterChain customChain = new SecurityWebFilterChain() {
            @Override
            public Mono<Boolean> matches(org.springframework.web.server.ServerWebExchange exchange) {
                return Mono.just(exchange.getRequest().getPath().value().startsWith("/api/"));
            }

            @Override
            public Flux<WebFilter> getWebFilters() {
                return Flux.empty();
            }
        };

        WebTestClient client = client(List.of(customChain), null, null, new MockEnvironment(), new BootUiProperties());
        client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/bootui/api/spring-security/explain")
                        .queryParam("method", "GET")
                        .queryParam("path", "/api/test")
                        .build())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.matched")
                .isEqualTo(true)
                .jsonPath("$.chainIndex")
                .isEqualTo(0);

        client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/bootui/api/spring-security/explain")
                        .queryParam("method", "GET")
                        .queryParam("path", "/other")
                        .build())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.matched")
                .isEqualTo(false);
    }

    @Test
    void explainSanitizesRequestContextAndMarksReducedFidelity() {
        ServerWebExchangeMatcher headerMatcher = new ServerWebExchangeMatcher() {
            @Override
            public Mono<MatchResult> matches(org.springframework.web.server.ServerWebExchange exchange) {
                return exchange.getRequest().getHeaders().containsHeader("X-API-Key")
                        ? MatchResult.match()
                        : MatchResult.notMatch();
            }

            @Override
            public String toString() {
                return "X-API-Key=super-secret-value";
            }
        };
        SecurityWebFilterChain chain = new MatcherSecurityWebFilterChain(headerMatcher, List.of(PASSTHROUGH));

        client(List.of(chain), null, null, new MockEnvironment(), new BootUiProperties())
                .get()
                .uri(uriBuilder -> uriBuilder
                        .path("/bootui/api/spring-security/explain")
                        .queryParam("method", "GET")
                        .queryParam("path", "/api/test")
                        .build())
                .header("X-API-Key", "super-secret-value")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.matched")
                .isEqualTo(false)
                .jsonPath("$.bestEffort")
                .isEqualTo(true)
                .jsonPath("$.matcherDescription")
                .value(value -> org.assertj.core.api.Assertions.assertThat(value.toString())
                        .doesNotContain("super-secret-value"));
    }

    @Test
    void explainRejectsInvalidMethodsAsBadRequests() {
        SecurityWebFilterChain chain = new MatcherSecurityWebFilterChain(
                new PathPatternParserServerWebExchangeMatcher("/api/**"), List.of(PASSTHROUGH));

        client(List.of(chain), null, null, new MockEnvironment(), new BootUiProperties())
                .get()
                .uri(uriBuilder -> uriBuilder
                        .path("/bootui/api/spring-security/explain")
                        .queryParam("method", "BAD METHOD")
                        .queryParam("path", "/api/test")
                        .build())
                .exchange()
                .expectStatus()
                .isBadRequest();
    }
}
