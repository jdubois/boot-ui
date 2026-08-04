package io.github.jdubois.bootui.autoconfigure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.engine.reactivesecurity.ReactiveSecurityObservation;
import io.github.jdubois.bootui.engine.reactivesecurity.WebFilterChainObservation;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.web.server.MatcherSecurityWebFilterChain;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.security.web.server.csrf.CsrfWebFilter;
import org.springframework.security.web.server.header.CompositeServerHttpHeadersWriter;
import org.springframework.security.web.server.header.ContentSecurityPolicyServerHttpHeadersWriter;
import org.springframework.security.web.server.header.HttpHeaderWriterWebFilter;
import org.springframework.security.web.server.header.StrictTransportSecurityServerHttpHeadersWriter;
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Adapter-side unit tests for {@link SpringReactiveSecurityObservationCollector}: verifies the
 * Spring-specific collection (bean exclusion, reflection, environment reading) hands the
 * framework-neutral engine a correct, non-sensitive {@link ReactiveSecurityObservation}. Rule
 * evaluation itself is covered by the engine's own {@code ReactiveSecurityScannerTests}.
 */
class SpringReactiveSecurityObservationCollectorTests {

    private static final WebFilter PASSTHROUGH = (exchange, chain) -> chain.filter(exchange);

    private static final class ServerBearerTokenAuthenticationConverter implements ServerAuthenticationConverter {

        @Override
        public Mono<org.springframework.security.core.Authentication> convert(ServerWebExchange exchange) {
            return Mono.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<SecurityWebFilterChain> chainProvider(List<SecurityWebFilterChain> chains) {
        ObjectProvider<SecurityWebFilterChain> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(invocation -> chains.stream());
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ListableBeanFactory> beanFactoryProvider(ListableBeanFactory beanFactory) {
        ObjectProvider<ListableBeanFactory> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(beanFactory);
        return provider;
    }

    @Test
    void excludesTheBootUiOwnChainByBeanNameNotByPattern() {
        SecurityWebFilterChain bootUiChain = new MatcherSecurityWebFilterChain(
                new PathPatternParserServerWebExchangeMatcher("/bootui/**"), List.of(PASSTHROUGH));
        SecurityWebFilterChain apiChain = new MatcherSecurityWebFilterChain(
                new PathPatternParserServerWebExchangeMatcher("/api/**"), List.of(new CsrfWebFilter()));

        ListableBeanFactory beanFactory = mock(ListableBeanFactory.class);
        when(beanFactory.getBean("bootUiReactiveSecurityWebFilterChain", SecurityWebFilterChain.class))
                .thenReturn(bootUiChain);

        SpringReactiveSecurityObservationCollector collector = new SpringReactiveSecurityObservationCollector(
                chainProvider(List.of(bootUiChain, apiChain)), beanFactoryProvider(beanFactory), new MockEnvironment());

        ReactiveSecurityObservation observation = collector.collect();

        assertThat(observation.chains()).hasSize(1);
        assertThat(observation.chains().get(0).matcher()).contains("/api/**");
        assertThat(observation.errors()).isEmpty();
    }

    @Test
    void absentBootUiChainBeanKeepsAllApplicationChains() {
        SecurityWebFilterChain apiChain = new MatcherSecurityWebFilterChain(
                new PathPatternParserServerWebExchangeMatcher("/api/**"), List.of(PASSTHROUGH));

        ListableBeanFactory beanFactory = mock(ListableBeanFactory.class);
        when(beanFactory.getBean("bootUiReactiveSecurityWebFilterChain", SecurityWebFilterChain.class))
                .thenThrow(new NoSuchBeanDefinitionException("bootUiReactiveSecurityWebFilterChain"));

        SpringReactiveSecurityObservationCollector collector = new SpringReactiveSecurityObservationCollector(
                chainProvider(List.of(apiChain)), beanFactoryProvider(beanFactory), new MockEnvironment());

        ReactiveSecurityObservation observation = collector.collect();

        assertThat(observation.chains()).hasSize(1);
    }

    @Test
    void noChainBeansAtAllProducesAStableEmptyObservation() {
        SpringReactiveSecurityObservationCollector collector = new SpringReactiveSecurityObservationCollector(
                chainProvider(List.of()), beanFactoryProvider(null), new MockEnvironment());

        ReactiveSecurityObservation observation = collector.collect();

        assertThat(observation.chains()).isEmpty();
        assertThat(observation.corsConfigs()).isEmpty();
        assertThat(observation.reactiveJwtDecoderTypes()).isEmpty();
        assertThat(observation.oauth2TokenValidatorTypes()).isEmpty();
        assertThat(observation.opaqueTokenIntrospectorTypes()).isEmpty();
        assertThat(observation.errors()).isEmpty();
        assertThat(observation.environment().suspectedHardcodedSecretKeys()).isEmpty();

        // Calling collect() again is deterministic/stable - no accumulated state, no exceptions.
        ReactiveSecurityObservation again = collector.collect();
        assertThat(again.chains()).isEmpty();
    }

    @Test
    void customMatcherSensitiveToStringIsNeverSurfacedRaw() {
        ServerWebExchangeMatcher sensitiveMatcher = new ServerWebExchangeMatcher() {
            @Override
            public Mono<MatchResult> matches(org.springframework.web.server.ServerWebExchange exchange) {
                return MatchResult.notMatch();
            }

            @Override
            public String toString() {
                return "X-API-Key=super-secret-value";
            }
        };
        SecurityWebFilterChain chain = new MatcherSecurityWebFilterChain(sensitiveMatcher, List.of(PASSTHROUGH));

        SpringReactiveSecurityObservationCollector collector = new SpringReactiveSecurityObservationCollector(
                chainProvider(List.of(chain)), beanFactoryProvider(null), new MockEnvironment());

        ReactiveSecurityObservation observation = collector.collect();

        assertThat(observation.chains()).hasSize(1);
        WebFilterChainObservation observed = observation.chains().get(0);
        assertThat(observed.matcher()).doesNotContain("super-secret-value");
        assertThat(observed.matcher()).contains("custom matcher");
    }

    @Test
    void allowListedMatcherTypeDescriptionIsSurfacedVerbatim() {
        SecurityWebFilterChain chain = new MatcherSecurityWebFilterChain(
                new PathPatternParserServerWebExchangeMatcher("/api/**"), List.of(PASSTHROUGH));

        SpringReactiveSecurityObservationCollector collector = new SpringReactiveSecurityObservationCollector(
                chainProvider(List.of(chain)), beanFactoryProvider(null), new MockEnvironment());

        ReactiveSecurityObservation observation = collector.collect();

        assertThat(observation.chains().get(0).matcher()).contains("/api/**");
    }

    @Test
    void defaultAnyExchangeMatcherIsRecognizedAsMatchingEveryRequest() {
        SecurityWebFilterChain chain =
                new MatcherSecurityWebFilterChain(ServerWebExchangeMatchers.anyExchange(), List.of(PASSTHROUGH));

        SpringReactiveSecurityObservationCollector collector = new SpringReactiveSecurityObservationCollector(
                chainProvider(List.of(chain)), beanFactoryProvider(null), new MockEnvironment());

        ReactiveSecurityObservation observation = collector.collect();

        assertThat(observation.chains().get(0).matcher()).isEqualTo("any request");
    }

    @Test
    void extractsRealReactiveHeaderWritersAndHstsConfiguration() {
        StrictTransportSecurityServerHttpHeadersWriter hsts = new StrictTransportSecurityServerHttpHeadersWriter();
        hsts.setMaxAge(Duration.ofSeconds(60));
        hsts.setIncludeSubDomains(true);
        ContentSecurityPolicyServerHttpHeadersWriter csp = new ContentSecurityPolicyServerHttpHeadersWriter();
        csp.setPolicyDirectives("default-src 'self'");
        csp.setReportOnly(true);
        HttpHeaderWriterWebFilter headers =
                new HttpHeaderWriterWebFilter(new CompositeServerHttpHeadersWriter(List.of(hsts, csp)));
        SecurityWebFilterChain chain =
                new MatcherSecurityWebFilterChain(ServerWebExchangeMatchers.anyExchange(), List.of(headers));

        SpringReactiveSecurityObservationCollector collector = new SpringReactiveSecurityObservationCollector(
                chainProvider(List.of(chain)), beanFactoryProvider(null), new MockEnvironment());

        WebFilterChainObservation observed = collector.collect().chains().get(0);

        assertThat(observed.headerWriterNames())
                .containsExactly(
                        "StrictTransportSecurityServerHttpHeadersWriter",
                        "ContentSecurityPolicyServerHttpHeadersWriter");
        assertThat(observed.hstsMaxAgeSeconds()).isEqualTo(60);
        assertThat(observed.hstsIncludeSubdomains()).isTrue();
        assertThat(observed.cspPolicyDirectives()).isEqualTo("default-src 'self'");
        assertThat(observed.cspReportOnly()).isTrue();
    }

    @Test
    void collectionDoesNotBlockOnAnAsynchronousChain() {
        SecurityWebFilterChain asynchronousChain = new SecurityWebFilterChain() {
            @Override
            public Mono<Boolean> matches(org.springframework.web.server.ServerWebExchange exchange) {
                return Mono.just(true);
            }

            @Override
            public Flux<WebFilter> getWebFilters() {
                return Flux.just(PASSTHROUGH).delayElements(java.time.Duration.ofMillis(5));
            }
        };

        SpringReactiveSecurityObservationCollector collector = new SpringReactiveSecurityObservationCollector(
                chainProvider(List.of(asynchronousChain)), beanFactoryProvider(null), new MockEnvironment());

        ReactiveSecurityObservation observation = collector.collect();

        assertThat(observation.chains()).hasSize(1);
        assertThat(observation.chains().get(0).webFilterNames()).hasSize(1);
    }

    @Test
    void unreadableFiltersProduceAnUnknownAuthorizationDecisionAndPartialObservation() {
        SecurityWebFilterChain unreadableChain = new SecurityWebFilterChain() {
            @Override
            public Mono<Boolean> matches(ServerWebExchange exchange) {
                return Mono.just(true);
            }

            @Override
            public Flux<WebFilter> getWebFilters() {
                return Flux.error(new IllegalStateException("unavailable"));
            }
        };

        SpringReactiveSecurityObservationCollector collector = new SpringReactiveSecurityObservationCollector(
                chainProvider(List.of(unreadableChain)), beanFactoryProvider(null), new MockEnvironment());

        ReactiveSecurityObservation observation = collector.collect();

        assertThat(observation.chains().get(0).permitsAllAnonymous()).isNull();
        assertThat(observation.errors()).containsExactly("Chain 0: web filters could not be collected");
    }

    @Test
    void recognizesBearerTokenAuthenticationConverterWithoutReadingCredentials() {
        ReactiveAuthenticationManager authenticationManager = authentication -> Mono.empty();
        AuthenticationWebFilter bearerFilter = new AuthenticationWebFilter(authenticationManager);
        bearerFilter.setServerAuthenticationConverter(new ServerBearerTokenAuthenticationConverter());
        SecurityWebFilterChain chain =
                new MatcherSecurityWebFilterChain(ServerWebExchangeMatchers.anyExchange(), List.of(bearerFilter));

        SpringReactiveSecurityObservationCollector collector = new SpringReactiveSecurityObservationCollector(
                chainProvider(List.of(chain)), beanFactoryProvider(null), new MockEnvironment());

        assertThat(collector.collect().chains().get(0).bearerTokenAuthentication())
                .isTrue();
    }

    @Test
    void convertsRealCorsOriginPatternsToNeutralStrings() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("*"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        ListableBeanFactory beanFactory = mock(ListableBeanFactory.class);
        doReturn(Map.of("corsConfigurationSource", source)).when(beanFactory).getBeansOfType(any());

        SpringReactiveSecurityObservationCollector collector = new SpringReactiveSecurityObservationCollector(
                chainProvider(List.of()), beanFactoryProvider(beanFactory), new MockEnvironment());

        ReactiveSecurityObservation observation = collector.collect();

        assertThat(observation.corsConfigs()).singleElement().satisfies(cors -> {
            assertThat(cors.allowedOriginPatterns()).containsExactly("*");
            assertThat(cors.allowCredentials()).isTrue();
        });
    }

    @Test
    void suspectedHardcodedSecretKeysReportsKeysOnlyNeverValues() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("my.api.secret-key", "literal-value-should-never-leak")
                .withProperty("some.token.expiration", "literal-not-a-secret-suffix-excluded");

        SpringReactiveSecurityObservationCollector collector = new SpringReactiveSecurityObservationCollector(
                chainProvider(List.of()), beanFactoryProvider(null), environment);

        ReactiveSecurityObservation observation = collector.collect();

        assertThat(observation.environment().suspectedHardcodedSecretKeys()).contains("my.api.secret-key");
        assertThat(observation.environment().suspectedHardcodedSecretKeys()).doesNotContain("some.token.expiration");
        // The snapshot record structurally carries no "value" field for suspected secrets - only
        // property names ever reach the observation.
        assertThat(observation.toString()).doesNotContain("literal-value-should-never-leak");
    }

    @Test
    void placeholderSecretValuesAreNotFlagged() {
        MockEnvironment environment = new MockEnvironment().withProperty("my.api.secret-key", "${SOME_ENV_VAR}");

        SpringReactiveSecurityObservationCollector collector = new SpringReactiveSecurityObservationCollector(
                chainProvider(List.of()), beanFactoryProvider(null), environment);

        ReactiveSecurityObservation observation = collector.collect();

        assertThat(observation.environment().suspectedHardcodedSecretKeys()).isEmpty();
    }

    @Test
    void bootUiOwnConfigKeysAreNeverFlaggedAsSuspectedSecrets() {
        MockEnvironment environment = new MockEnvironment().withProperty("bootui.some.secret-token", "literal");

        SpringReactiveSecurityObservationCollector collector = new SpringReactiveSecurityObservationCollector(
                chainProvider(List.of()), beanFactoryProvider(null), environment);

        ReactiveSecurityObservation observation = collector.collect();

        assertThat(observation.environment().suspectedHardcodedSecretKeys()).isEmpty();
    }

    @Test
    void issuerOrJwkConfigurationOverridesStaticPublicKeyLocation() {
        MockEnvironment staticKeyOnly = new MockEnvironment()
                .withProperty(
                        "spring.security.oauth2.resourceserver.jwt.public-key-location",
                        "classpath:verification-key.pub");
        MockEnvironment issuerConfigured = new MockEnvironment()
                .withProperty(
                        "spring.security.oauth2.resourceserver.jwt.public-key-location",
                        "classpath:verification-key.pub")
                .withProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri", "https://issuer.example");

        ReactiveSecurityObservation staticObservation = new SpringReactiveSecurityObservationCollector(
                        chainProvider(List.of()), beanFactoryProvider(null), staticKeyOnly)
                .collect();
        ReactiveSecurityObservation issuerObservation = new SpringReactiveSecurityObservationCollector(
                        chainProvider(List.of()), beanFactoryProvider(null), issuerConfigured)
                .collect();

        assertThat(staticObservation.environment().oauth2JwtStaticPublicKeyConfigured())
                .isTrue();
        assertThat(issuerObservation.environment().oauth2JwtStaticPublicKeyConfigured())
                .isFalse();
    }
}
