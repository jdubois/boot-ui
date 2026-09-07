package io.github.jdubois.bootui.autoconfigure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.core.dto.SecurityReport;
import io.github.jdubois.bootui.core.dto.SecurityRuleResultDto;
import io.github.jdubois.bootui.engine.reactivesecurity.ReactiveSecurityObservation;
import io.github.jdubois.bootui.engine.reactivesecurity.ReactiveSecurityScanner;
import io.github.jdubois.bootui.engine.reactivesecurity.WebFilterChainObservation;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.env.OriginTrackedMapPropertySource;
import org.springframework.boot.origin.OriginTrackedValue;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.http.HttpMethod;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.client.oidc.server.session.ReactiveOidcSessionRegistry;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.server.OAuth2AuthorizationCodeGrantWebFilter;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.server.authentication.OAuth2LoginAuthenticationWebFilter;
import org.springframework.security.web.server.MatcherSecurityWebFilterChain;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.security.web.server.context.SecurityContextServerWebExchangeWebFilter;
import org.springframework.security.web.server.header.CompositeServerHttpHeadersWriter;
import org.springframework.security.web.server.header.ContentSecurityPolicyServerHttpHeadersWriter;
import org.springframework.security.web.server.header.HttpHeaderWriterWebFilter;
import org.springframework.security.web.server.header.ServerHttpHeadersWriter;
import org.springframework.security.web.server.header.StaticServerHttpHeadersWriter;
import org.springframework.security.web.server.header.StrictTransportSecurityServerHttpHeadersWriter;
import org.springframework.security.web.server.util.matcher.AndServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.NegatedServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.OrServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class SpringReactiveSecurityObservationCollectorTests {

    private static final ReactiveAuthenticationManager NO_AUTH = authentication -> {
        throw new AssertionError("Authentication manager must not execute");
    };

    @SuppressWarnings("unchecked")
    private static SpringReactiveSecurityObservationCollector collector(
            DefaultListableBeanFactory factory, MockEnvironment environment) {
        ObjectProvider<ListableBeanFactory> factories = mock(ObjectProvider.class);
        when(factories.getIfAvailable()).thenReturn(factory);
        ObjectProvider<SecurityWebFilterChain> chains = mock(ObjectProvider.class);
        when(chains.orderedStream()).thenThrow(new AssertionError("Eager provider must not execute"));
        return new SpringReactiveSecurityObservationCollector(chains, factories, environment);
    }

    private static ReactiveSecurityObservation collect(MockEnvironment environment, SecurityWebFilterChain... chains) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        for (int i = 0; i < chains.length; i++) {
            factory.registerSingleton("chain" + i, chains[i]);
        }
        return collector(factory, environment).collect();
    }

    private static MatcherSecurityWebFilterChain chain(WebFilter... filters) {
        return new MatcherSecurityWebFilterChain(
                ServerWebExchangeMatchers.anyExchange(), filters.length == 0 ? contextFilters() : List.of(filters));
    }

    private static List<WebFilter> contextFilters() {
        return List.of(new SecurityContextServerWebExchangeWebFilter());
    }

    private static SecurityReport scan(ReactiveSecurityObservation observation) {
        return ReactiveSecurityScanner.using(() -> observation, Clock.systemUTC())
                .scan();
    }

    private static ServerHttpSecurity http() {
        return ServerHttpSecurity.http().authenticationManager(NO_AUTH);
    }

    @Test
    void actualFrameworkBasicAndFormChainsHaveDistinctCredentialsAndDefaultCsrf() {
        SecurityWebFilterChain basic = http().httpBasic(Customizer.withDefaults())
                .authorizeExchange(exchange -> exchange.anyExchange().authenticated())
                .build();
        SecurityWebFilterChain form = http().formLogin(Customizer.withDefaults())
                .authorizeExchange(exchange -> exchange.anyExchange().authenticated())
                .build();
        ReactiveSecurityObservation observation = collect(new MockEnvironment(), basic, form);
        WebFilterChainObservation first = observation.chains().get(0);
        assertThat(first.basicAuthentication()).isTrue();
        assertThat(first.formLoginAuthentication()).isFalse();
        assertThat(first.authenticationObserved()).isTrue();
        assertThat(first.authorizationFilterPresent()).isTrue();
        assertThat(first.webFilterNames()).contains("CsrfWebFilter", "HttpHeaderWriterWebFilter");
        assertThat(first.headerWritersObserved()).isTrue();
        assertThat(first.cspPolicies()).isEmpty();
        assertThat(first.headerWriterNames()).doesNotContain("ContentSecurityPolicyServerHttpHeadersWriter");
        assertThat(first.hstsMaxAgeSeconds()).isEqualTo(31536000L);
        assertThat(observation.chains().get(1).formLoginAuthentication()).isTrue();
        assertThat(scan(observation).results())
                .extracting(SecurityRuleResultDto::id)
                .contains("SEC-RXF-HEAD-004")
                .doesNotContain("SEC-RXF-CSRF-001", "SEC-RXF-CSRF-002");
    }

    @Test
    void basicCsrfIsPerChainAndFormRuleDoesNotDuplicateIt() {
        SecurityWebFilterChain basic = http().httpBasic(Customizer.withDefaults())
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .build();
        SecurityWebFilterChain protectedForm =
                http().formLogin(Customizer.withDefaults()).build();
        assertThat(scan(collect(new MockEnvironment(), basic, protectedForm)).results())
                .extracting(SecurityRuleResultDto::id)
                .contains("SEC-RXF-CSRF-002")
                .doesNotContain("SEC-RXF-CSRF-001");
        SecurityWebFilterChain mixed = http().httpBasic(Customizer.withDefaults())
                .formLogin(Customizer.withDefaults())
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .build();
        assertThat(scan(collect(new MockEnvironment(), mixed)).results())
                .extracting(SecurityRuleResultDto::id)
                .contains("SEC-RXF-CSRF-001")
                .doesNotContain("SEC-RXF-CSRF-002");
    }

    @Test
    void sameNamedCustomConvertersAreNotFrameworkConvertersAndNeverExecute() {
        for (ServerAuthenticationConverter converter :
                List.of(new ServerBearerTokenAuthenticationConverter(), new ServerFormLoginAuthenticationConverter())) {
            AuthenticationWebFilter filter = new AuthenticationWebFilter(NO_AUTH);
            filter.setServerAuthenticationConverter(converter);
            ReactiveSecurityObservation observation = collect(new MockEnvironment(), chain(filter));
            assertThat(observation.chains().get(0).bearerTokenAuthentication()).isFalse();
            assertThat(observation.chains().get(0).formLoginAuthentication()).isFalse();
            assertThat(observation.chains().get(0).authenticationObserved()).isFalse();
            assertThat(scan(observation).scan().status()).isEqualTo("PARTIAL");
            assertThat(scan(observation).analysisErrors()).isEmpty();
        }
    }

    @Test
    void nativeBearerConverterIsRecognizedWithoutDecoding() {
        AuthenticationWebFilter bearer = new AuthenticationWebFilter(NO_AUTH);
        bearer.setServerAuthenticationConverter(new org.springframework.security.oauth2.server.resource.web.server
                .authentication.ServerBearerTokenAuthenticationConverter());
        ReactiveSecurityObservation observation = collect(new MockEnvironment(), chain(bearer));
        assertThat(observation.chains().get(0).bearerTokenAuthentication()).isTrue();
        assertThat(observation.chains().get(0).authenticationObserved()).isTrue();
        assertThat(scan(observation).results())
                .extracting(SecurityRuleResultDto::id)
                .doesNotContain("SEC-RXF-CSRF-001", "SEC-RXF-CSRF-002", "SEC-RXF-SESSION-001");
    }

    @Test
    void actualResourceServerDslIsBearerOnlyWithoutInvokingIntrospection() {
        SecurityWebFilterChain resourceServer = http().oauth2ResourceServer(
                        resource -> resource.opaqueToken(opaque -> opaque.introspector(token -> {
                            throw new AssertionError("Token introspection must not execute");
                        })))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchange -> exchange.anyExchange().authenticated())
                .build();
        ReactiveSecurityObservation observation = collect(new MockEnvironment(), resourceServer);
        assertThat(observation.chains()).singleElement().satisfies(chain -> {
            assertThat(chain.bearerTokenAuthentication()).isTrue();
            assertThat(chain.authenticationObserved()).isTrue();
        });
        assertThat(scan(observation).results())
                .extracting(SecurityRuleResultDto::id)
                .doesNotContain("SEC-RXF-CSRF-001", "SEC-RXF-CSRF-002", "SEC-RXF-SESSION-001");
    }

    @Test
    void nativeOAuthClientGrantIsNotLogin() {
        ServerOAuth2AuthorizedClientRepository repository =
                mock(ServerOAuth2AuthorizedClientRepository.class, invocation -> {
                    throw new AssertionError("OAuth repository callback must not execute");
                });
        WebFilter login = new OAuth2LoginAuthenticationWebFilter(NO_AUTH, repository);
        ServerAuthenticationConverter converter = exchange -> {
            throw new AssertionError("OAuth converter must not execute");
        };
        WebFilter grant = new OAuth2AuthorizationCodeGrantWebFilter(NO_AUTH, converter, repository);
        assertThat(scan(collect(new MockEnvironment(), chain(login))).results())
                .extracting(SecurityRuleResultDto::id)
                .contains("SEC-RXF-CSRF-001");
        assertThat(scan(collect(new MockEnvironment(), chain(grant))).results())
                .extracting(SecurityRuleResultDto::id)
                .doesNotContain("SEC-RXF-CSRF-001", "SEC-RXF-CSRF-002", "SEC-RXF-SESSION-001");
        AuthenticationWebFilter bearer = new AuthenticationWebFilter(NO_AUTH);
        bearer.setServerAuthenticationConverter(new org.springframework.security.oauth2.server.resource.web.server
                .authentication.ServerBearerTokenAuthenticationConverter());
        assertThat(scan(collect(new MockEnvironment(), chain(bearer, login))).results())
                .extracting(SecurityRuleResultDto::id)
                .contains("SEC-RXF-SESSION-001");
        assertThat(scan(collect(new MockEnvironment(), chain(bearer, grant))).results())
                .extracting(SecurityRuleResultDto::id)
                .doesNotContain("SEC-RXF-SESSION-001");
    }

    @Test
    void nativeOidcSessionRegistryLoginAndLogoutRemainRecognizedWithBearerAuthentication() {
        ReactiveClientRegistrationRepository registrations =
                mock(ReactiveClientRegistrationRepository.class, invocation -> {
                    throw new AssertionError("Client registration callback must not execute");
                });
        ServerOAuth2AuthorizedClientRepository clients =
                mock(ServerOAuth2AuthorizedClientRepository.class, invocation -> {
                    throw new AssertionError("Authorized client callback must not execute");
                });
        ReactiveOidcSessionRegistry sessions = mock(ReactiveOidcSessionRegistry.class, invocation -> {
            throw new AssertionError("OIDC session registry callback must not execute");
        });
        for (boolean csrfEnabled : List.of(false, true)) {
            ServerHttpSecurity security = http().oauth2Login(login -> login.clientRegistrationRepository(registrations)
                            .authorizedClientRepository(clients)
                            .authenticationManager(NO_AUTH)
                            .oidcSessionRegistry(sessions))
                    .oidcLogout(logout ->
                            logout.clientRegistrationRepository(registrations).oidcSessionRegistry(sessions))
                    .logout(Customizer.withDefaults())
                    .oauth2ResourceServer(resource -> resource.opaqueToken(opaque -> opaque.introspector(token -> {
                        throw new AssertionError("Token introspection must not execute");
                    })))
                    .authorizeExchange(exchange -> exchange.anyExchange().authenticated());
            if (!csrfEnabled) {
                security.csrf(ServerHttpSecurity.CsrfSpec::disable);
            }
            ReactiveSecurityObservation observation = collect(new MockEnvironment(), security.build());
            assertThat(observation.chains()).singleElement().satisfies(chain -> {
                assertThat(chain.webFilterNames())
                        .contains("OidcSessionRegistryAuthenticationWebFilter", "LogoutWebFilter");
                assertThat(chain.authenticationObserved()).isTrue();
                assertThat(chain.authorizationFilterPresent()).isTrue();
                assertThat(chain.bearerTokenAuthentication()).isTrue();
            });
            SecurityReport report = scan(observation);
            assertThat(report.results()).extracting(SecurityRuleResultDto::id).contains("SEC-RXF-SESSION-001");
            assertThat(report.results().stream().anyMatch(result -> result.id().equals("SEC-RXF-CSRF-001")))
                    .isEqualTo(!csrfEnabled);
        }
    }

    @Test
    void collectorStillStartsAndScansWhenOAuthClassesAreFilteredOut() {
        new ApplicationContextRunner()
                .withClassLoader(new FilteredClassLoader("org.springframework.security.oauth2"))
                .withUserConfiguration(CollectorWithoutOAuth.class)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(SpringReactiveSecurityObservationCollector.class);
                    assertThatExceptionOfType(ClassNotFoundException.class)
                            .isThrownBy(
                                    () -> context.getClassLoader()
                                            .loadClass(
                                                    "org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter"));
                    assertThatExceptionOfType(ClassNotFoundException.class)
                            .isThrownBy(
                                    () -> context.getClassLoader()
                                            .loadClass(
                                                    "org.springframework.security.oauth2.client.web.server.authentication.OAuth2LoginAuthenticationWebFilter"));
                    ReactiveSecurityObservation observation = context.getBean(
                                    SpringReactiveSecurityObservationCollector.class)
                            .collect();
                    assertThat(observation.chains()).singleElement().satisfies(chain -> {
                        assertThat(chain.basicAuthentication()).isTrue();
                        assertThat(chain.bearerTokenAuthentication()).isFalse();
                    });
                });
    }

    @Test
    void lazyPrototypeAndFactoryBeansAreNeverCreatedAndBootUiIsExcluded() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        AtomicInteger creations = new AtomicInteger();
        RootBeanDefinition lazy = new RootBeanDefinition(SecurityWebFilterChain.class, () -> {
            creations.incrementAndGet();
            return chain();
        });
        lazy.setLazyInit(true);
        factory.registerBeanDefinition("lazy", lazy);
        RootBeanDefinition prototype = new RootBeanDefinition(SecurityWebFilterChain.class, () -> {
            creations.incrementAndGet();
            return chain();
        });
        prototype.setScope("prototype");
        factory.registerBeanDefinition("prototype", prototype);
        factory.registerBeanDefinition("factory", new RootBeanDefinition(ChainFactory.class));
        factory.registerSingleton("active", chain());
        factory.registerSingleton("bootUiReactiveSecurityWebFilterChain", chain());
        ReactiveSecurityObservation observation =
                collector(factory, new MockEnvironment()).collect();
        assertThat(creations).hasValue(0);
        assertThat(factory.containsSingleton("factory")).isFalse();
        assertThat(factory.containsSingleton("lazy")).isFalse();
        assertThat(observation.chains()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(observation.chains().stream().filter(c -> c.authorizationFilterPresent() != null))
                .hasSize(1);
    }

    @Test
    void initializedFactoryBeanProductTypeCallbacksNeverExecuteForEitherInventory() {
        AtomicInteger callbacks = new AtomicInteger();
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("initializedChainFactory", new FactoryBean<SecurityWebFilterChain>() {
            @Override
            public SecurityWebFilterChain getObject() {
                callbacks.incrementAndGet();
                return chain();
            }

            @Override
            public Class<?> getObjectType() {
                callbacks.incrementAndGet();
                return SecurityWebFilterChain.class;
            }
        });
        factory.registerSingleton("initializedWebFilterFactory", new FactoryBean<WebFilter>() {
            @Override
            public WebFilter getObject() {
                callbacks.incrementAndGet();
                return new CorsWebFilter(exchange -> null);
            }

            @Override
            public Class<?> getObjectType() {
                callbacks.incrementAndGet();
                return WebFilter.class;
            }
        });
        factory.registerSingleton("active", chain());
        ReactiveSecurityObservation observation =
                collector(factory, new MockEnvironment()).collect();
        assertThat(callbacks).hasValue(0);
        assertThat(observation.chains()).hasSize(2);
        assertThat(observation.corsObservationComplete()).isFalse();
        assertThat(scan(observation).scan().status()).isEqualTo("PARTIAL");
    }

    @Test
    void customChainsPublishersAndMatchersAreNeverInvokedOrDescribedUsingToString() {
        AtomicInteger calls = new AtomicInteger();
        SecurityWebFilterChain custom = new SecurityWebFilterChain() {
            @Override
            public Mono<Boolean> matches(ServerWebExchange exchange) {
                calls.incrementAndGet();
                return Mono.just(true);
            }

            @Override
            public Flux<WebFilter> getWebFilters() {
                calls.incrementAndGet();
                throw new IllegalStateException("secret failure");
            }
        };
        ServerWebExchangeMatcher matcher = new ServerWebExchangeMatcher() {
            @Override
            public Mono<MatchResult> matches(ServerWebExchange exchange) {
                calls.incrementAndGet();
                return MatchResult.match();
            }

            @Override
            public String toString() {
                throw new AssertionError("secret captured matcher");
            }
        };
        ReactiveSecurityObservation observation =
                collect(new MockEnvironment(), custom, new MatcherSecurityWebFilterChain(matcher, contextFilters()));
        assertThat(calls).hasValue(0);
        assertThat(observation.chains().get(0).authorizationFilterPresent()).isNull();
        assertThat(observation.chains().get(1).unconditionalMatcher()).isNull();
        assertThat(observation.toString()).doesNotContain("secret failure", "secret captured");
    }

    @Test
    void orderingUsesStructureNotMethodConstrainedOrCompositeDescriptions() {
        for (ServerWebExchangeMatcher matcher : List.of(
                new PathPatternParserServerWebExchangeMatcher("/**", HttpMethod.GET),
                new OrServerWebExchangeMatcher(ServerWebExchangeMatchers.anyExchange()),
                new AndServerWebExchangeMatcher(ServerWebExchangeMatchers.anyExchange()),
                new NegatedServerWebExchangeMatcher(ServerWebExchangeMatchers.anyExchange()))) {
            ReactiveSecurityObservation observation = collect(
                    new MockEnvironment(), new MatcherSecurityWebFilterChain(matcher, contextFilters()), chain());
            assertThat(scan(observation).results())
                    .extracting(SecurityRuleResultDto::id)
                    .doesNotContain("SEC-RXF-AUTHZ-004");
        }
        ReactiveSecurityObservation catchAll = collect(
                new MockEnvironment(),
                chain(),
                new MatcherSecurityWebFilterChain(
                        new PathPatternParserServerWebExchangeMatcher("/api/**"), contextFilters()));
        assertThat(scan(catchAll).results())
                .filteredOn(r -> r.id().equals("SEC-RXF-AUTHZ-004"))
                .singleElement()
                .extracting(SecurityRuleResultDto::severity)
                .isEqualTo("INFO");
        ReactiveSecurityObservation correct = collect(
                new MockEnvironment(),
                new MatcherSecurityWebFilterChain(
                        new PathPatternParserServerWebExchangeMatcher("/api/**"), contextFilters()),
                chain());
        assertThat(scan(correct).results())
                .extracting(SecurityRuleResultDto::id)
                .doesNotContain("SEC-RXF-AUTHZ-004");
    }

    @Test
    void nativeBeanMethodOrderOverridesDefinitionRegistrationOrder() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(OrderedChains.class)) {
            ReactiveSecurityObservation observation = collector(
                            context.getDefaultListableBeanFactory(), new MockEnvironment())
                    .collect();
            assertThat(observation.chains()).hasSize(2);
            assertThat(observation.chains().get(0).matcher()).contains("/api/**");
            assertThat(observation.chains().get(1).unconditionalMatcher()).isTrue();
            assertThat(scan(observation).results())
                    .extracting(SecurityRuleResultDto::id)
                    .doesNotContain("SEC-RXF-AUTHZ-004");
        }
    }

    @Test
    void customAuthorizationSubclassIsUnknownRatherThanMissingAndManagerNeverRuns() {
        WebFilter custom = new org.springframework.security.web.server.authorization.AuthorizationWebFilter(
                (authentication, context) -> {
                    throw new AssertionError("Authorization manager must not execute");
                }) {};
        ReactiveSecurityObservation observation = collect(new MockEnvironment(), chain(custom));
        assertThat(observation.chains().get(0).authorizationFilterPresent()).isNull();
        assertThat(scan(observation).results())
                .extracting(SecurityRuleResultDto::id)
                .doesNotContain("SEC-RXF-AUTHZ-001");
    }

    @Test
    void installedInlineCorsIsObservedButUnusedSourceBeansAreNot() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("*"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource attached = new UrlBasedCorsConfigurationSource();
        attached.registerCorsConfiguration("/**", configuration);
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("active", chain(new CorsWebFilter(attached)));
        factory.registerSingleton("unused", new UrlBasedCorsConfigurationSource());
        ReactiveSecurityObservation observation =
                collector(factory, new MockEnvironment()).collect();
        assertThat(observation.corsConfigs()).singleElement().satisfies(cors -> {
            assertThat(cors.allowedOriginPatterns()).containsExactly("*");
            assertThat(cors.allowCredentials()).isTrue();
        });
        factory.destroySingleton("active");
        assertThat(collector(factory, new MockEnvironment()).collect().corsSourcePresent())
                .isFalse();
    }

    @Test
    void opaqueCorsCallbacksAreNeverInvokedAndKnownViolationSurvives() {
        CorsWebFilter opaque = new CorsWebFilter(exchange -> {
            throw new AssertionError("CORS source must never execute");
        });
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of("*"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        ReactiveSecurityObservation observation =
                collect(new MockEnvironment(), chain(opaque, new CorsWebFilter(source)));
        assertThat(observation.corsObservationComplete()).isFalse();
        assertThat(scan(observation).results())
                .extracting(SecurityRuleResultDto::id)
                .contains("SEC-RXF-CORS-001");
        assertThat(scan(observation).analysisErrors()).isEmpty();
    }

    @Test
    void enforcingAndReportOnlyPoliciesAreRetainedInEitherOrder() {
        ContentSecurityPolicyServerHttpHeadersWriter enforcing =
                csp("default-src 'self'; frame-ancestors 'none'", false);
        ContentSecurityPolicyServerHttpHeadersWriter reporting = csp("default-src 'none'", true);
        for (List<ServerHttpHeadersWriter> writers : List.of(
                List.<ServerHttpHeadersWriter>of(enforcing, reporting),
                List.<ServerHttpHeadersWriter>of(reporting, enforcing))) {
            ReactiveSecurityObservation observation = collect(
                    new MockEnvironment(),
                    chain(new HttpHeaderWriterWebFilter(new CompositeServerHttpHeadersWriter(writers))));
            WebFilterChainObservation observed = observation.chains().get(0);
            assertThat(observed.cspPolicies()).hasSize(2);
            assertThat(observed.cspReportOnly()).isNull();
            assertThat(observed.headerWritersObserved()).isTrue();
            assertThat(scan(observation).results())
                    .extracting(SecurityRuleResultDto::id)
                    .doesNotContain("SEC-RXF-HEAD-002", "SEC-RXF-HEAD-004");
        }
    }

    @Test
    void multipleEnforcingAndCustomWritersRemainUnknownWithoutExecution() {
        ServerHttpHeadersWriter custom = exchange -> {
            throw new AssertionError("Header callback must never execute");
        };
        for (List<ServerHttpHeadersWriter> writers : List.of(
                List.<ServerHttpHeadersWriter>of(csp("frame-ancestors 'none'", false), csp("frame-ancestors *", false)),
                List.<ServerHttpHeadersWriter>of(custom))) {
            ReactiveSecurityObservation observation = collect(
                    new MockEnvironment(),
                    chain(new HttpHeaderWriterWebFilter(new CompositeServerHttpHeadersWriter(writers))));
            assertThat(observation.chains().get(0).headerWritersObserved()).isFalse();
            assertThat(scan(observation).results())
                    .extracting(SecurityRuleResultDto::id)
                    .doesNotContain("SEC-RXF-HEAD-002", "SEC-RXF-HEAD-003", "SEC-RXF-HEAD-004");
            assertThat(scan(observation).scan().status()).isEqualTo("PARTIAL");
        }
    }

    @Test
    void staticSingleHeaderEquivalentsAreCaseInsensitiveButMultiHeaderScopeIsUnknown() {
        HttpHeaderWriterWebFilter headers = new HttpHeaderWriterWebFilter(new CompositeServerHttpHeadersWriter(List.of(
                StaticServerHttpHeadersWriter.builder()
                        .header("x-FrAmE-oPtIoNs", "DENY")
                        .build(),
                StaticServerHttpHeadersWriter.builder()
                        .header("x-content-type-options", "nosniff")
                        .build(),
                StaticServerHttpHeadersWriter.builder()
                        .header("content-security-policy", "default-src 'self'")
                        .build())));
        ReactiveSecurityObservation known = collect(new MockEnvironment(), chain(headers));
        assertThat(known.chains().get(0).headerWritersObserved()).isTrue();
        assertThat(scan(known).results())
                .extracting(SecurityRuleResultDto::id)
                .doesNotContain("SEC-RXF-HEAD-002", "SEC-RXF-HEAD-003", "SEC-RXF-HEAD-004");
        HttpHeaderWriterWebFilter conditional = new HttpHeaderWriterWebFilter(StaticServerHttpHeadersWriter.builder()
                .header("x-frame-options", "DENY")
                .header("x-other", "value")
                .build());
        assertThat(collect(new MockEnvironment(), chain(conditional))
                        .chains()
                        .get(0)
                        .headerWritersObserved())
                .isFalse();
    }

    @Test
    void filterAndWriterInventoriesAreBoundedAndDoNotProveAbsence() {
        List<WebFilter> filters = IntStream.range(0, 300)
                .mapToObj(i -> (WebFilter) (exchange, chain) -> {
                    throw new AssertionError("Filter must not execute");
                })
                .toList();
        ReactiveSecurityObservation truncated = collect(
                new MockEnvironment(),
                new MatcherSecurityWebFilterChain(ServerWebExchangeMatchers.anyExchange(), filters));
        assertThat(truncated.chains().get(0).webFilterNames()).hasSize(256);
        assertThat(truncated.chains().get(0).authorizationFilterPresent()).isNull();
        List<ServerHttpHeadersWriter> writers = IntStream.range(0, 300)
                .mapToObj(i -> (ServerHttpHeadersWriter) csp("default-src 'self'", false))
                .toList();
        assertThat(collect(
                                new MockEnvironment(),
                                chain(new HttpHeaderWriterWebFilter(new CompositeServerHttpHeadersWriter(writers))))
                        .chains()
                        .get(0)
                        .headerWritersObserved())
                .isFalse();
    }

    @Test
    void realHstsZeroAndShortRolloutAreReadWithoutInvokingWriter() {
        for (long maxAge : List.of(0L, 60L, 31536000L)) {
            StrictTransportSecurityServerHttpHeadersWriter writer =
                    new StrictTransportSecurityServerHttpHeadersWriter();
            writer.setMaxAge(Duration.ofSeconds(maxAge));
            ReactiveSecurityObservation observation =
                    collect(new MockEnvironment(), chain(new HttpHeaderWriterWebFilter(writer)));
            assertThat(observation.chains().get(0).hstsMaxAgeSeconds()).isEqualTo(maxAge);
            assertThat(observation.chains().get(0).hstsIncludeSubdomains()).isTrue();
            assertThat(scan(observation).results().stream().anyMatch(r -> r.id().equals("SEC-RXF-HEAD-006")))
                    .isEqualTo(maxAge < 31536000L);
        }
    }

    @Test
    void tlsExplicitFalseWinsAndRedirectInAnotherChainDoesNotProveGlobalTls() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("server.ssl.enabled", "false")
                .withProperty("server.ssl.key-store", "classpath:unused.p12")
                .withProperty("server.forward-headers-strategy", "framework");
        environment.setActiveProfiles("prod");
        SecurityWebFilterChain redirected =
                http().redirectToHttps(Customizer.withDefaults()).build();
        ReactiveSecurityObservation observation = collect(environment, redirected, chain());
        assertThat(observation.environment().globalTlsConfigured()).isFalse();
        assertThat(scan(observation).results())
                .filteredOn(r -> r.id().equals("SEC-RXF-CONFIG-002"))
                .singleElement()
                .satisfies(r -> assertThat(r.sampleViolations()).hasSize(1));
        environment.setProperty("server.ssl.enabled", "true");
        assertThat(scan(collect(environment, chain())).results())
                .extracting(SecurityRuleResultDto::id)
                .doesNotContain("SEC-RXF-CONFIG-002");
    }

    @Test
    void unknownTlsIsNotAbsenceButUnrelatedUnknownEvidenceDoesNotHideKnownTlsAbsence() {
        MockEnvironment unresolved = new MockEnvironment().withProperty("server.ssl.enabled", "${TLS_ENABLED:true}");
        unresolved.setActiveProfiles("prod");
        ReactiveSecurityObservation unknown = collect(unresolved, chain());
        assertThat(unknown.environment().globalTlsObserved()).isFalse();
        assertThat(scan(unknown).results())
                .extracting(SecurityRuleResultDto::id)
                .contains("SEC-RXF-AUTHZ-001")
                .doesNotContain("SEC-RXF-CONFIG-002");
        assertThat(scan(unknown).scan().status()).isEqualTo("PARTIAL");
        unresolved.setProperty("server.ssl.enabled", "false");
        unresolved.getPropertySources().addLast(new PropertySource<>("opaqueCredentials") {
            @Override
            public Object getProperty(String name) {
                throw new AssertionError("Unrelated configuration provider must not execute");
            }
        });
        ReactiveSecurityObservation known = collect(unresolved, chain());
        assertThat(known.environment().globalTlsObserved()).isTrue();
        assertThat(scan(known).results())
                .extracting(SecurityRuleResultDto::id)
                .contains("SEC-RXF-CONFIG-002", "SEC-RXF-AUTHZ-001");
    }

    @Test
    void invalidTlsIsAnErrorNotSkippedAndDoesNotDiscardOtherEvidence() {
        ReactiveSecurityObservation observation =
                collect(new MockEnvironment().withProperty("server.ssl.enabled", "invalid"), chain());
        SecurityReport report = scan(observation);
        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.analysisErrors())
                .extracting(SecurityRuleResultDto::id)
                .contains("SEC-RXF-CONFIG-002");
        assertThat(report.results()).extracting(SecurityRuleResultDto::id).contains("SEC-RXF-AUTHZ-001");
        assertThat(report.analysisErrors())
                .allSatisfy(result -> assertThat(result.status()).isEqualTo("ERROR"));
    }

    @Test
    void localCredentialProvenanceAndClassificationExcludeMetadataExternalSourcesAndShadows() {
        MockEnvironment environment = new MockEnvironment();
        environment
                .getPropertySources()
                .addLast(new OriginTrackedMapPropertySource(
                        "Config resource 'class path resource [application.yml]' via location 'optional:classpath:/'",
                        Map.of(
                                "service.password",
                                OriginTrackedValue.of("never-leak"),
                                "service.token-uri",
                                "https://issuer",
                                "service.token.enabled",
                                "true",
                                "service.client-secret",
                                "${EXTERNAL_SECRET}",
                                "service.token.timeout",
                                "60",
                                "shadow.password",
                                "old-secret")));
        environment
                .getPropertySources()
                .addFirst(new MapPropertySource(
                        "systemEnvironment",
                        Map.of("external.password", "external-secret", "shadow.password", "external-override")));
        ReactiveSecurityObservation observation = collect(environment, chain());
        assertThat(observation.environment().suspectedHardcodedSecretKeys()).containsExactly("service.password");
        assertThat(observation.toString()).doesNotContain("never-leak", "old-secret", "external-secret");
    }

    @Test
    void arbitrarySecretSourcesAreNotEnumeratedOrRead() {
        AtomicInteger calls = new AtomicInteger();
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addLast(new PropertySource<>("externalSecretProvider") {
            @Override
            public Object getProperty(String name) {
                calls.incrementAndGet();
                return null;
            }
        });
        // The collector's safe configuration reads must not execute this provider either.
        ReactiveSecurityObservation observation = collect(environment, chain());
        assertThat(observation.environment().suspectedHardcodedSecretKeys()).isEmpty();
        assertThat(observation.errors()).anyMatch(message -> message.contains("provenance"));
        assertThat(calls).hasValue(0);
    }

    @Test
    void explicitSecurityLoggerChildrenAreNotHiddenByParentInfo() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("logging.level.org.springframework.security", "INFO")
                .withProperty("logging.level.org.springframework.security.web", "TRACE");
        environment.setActiveProfiles("prod");
        assertThat(scan(collect(environment, chain())).results())
                .extracting(SecurityRuleResultDto::id)
                .contains("SEC-RXF-CONFIG-004");
        environment.setProperty("logging.level.org.springframework.security", "TRACE");
        environment.setProperty("logging.level.org.springframework.security.web", "INFO");
        assertThat(scan(collect(environment, chain())).results())
                .extracting(SecurityRuleResultDto::id)
                .contains("SEC-RXF-CONFIG-004");
        environment.setActiveProfiles("local");
        assertThat(scan(collect(environment, chain())).results())
                .extracting(SecurityRuleResultDto::id)
                .doesNotContain("SEC-RXF-CONFIG-004");
    }

    @Test
    void staticKeyAndPlainHttpDeclarationsAreValueFreeAndRespectRemotePrecedence() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(
                        "spring.security.oauth2.resourceserver.jwt.public-key-location", "classpath:private-label.pub");
        environment.setActiveProfiles("prod");
        SecurityReport staticReport = scan(collect(environment, chain()));
        assertThat(staticReport.results())
                .filteredOn(r -> r.id().equals("SEC-RXF-OAUTH2-002"))
                .singleElement()
                .extracting(SecurityRuleResultDto::severity)
                .isEqualTo("INFO");
        environment.setProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri", "  http://private-issuer  ");
        environment.setProperty(
                "spring.security.oauth2.resourceserver.opaquetoken.introspection-uri", "http://private-introspection");
        SecurityReport urls = scan(collect(environment, chain()));
        assertThat(urls.results())
                .extracting(SecurityRuleResultDto::id)
                .contains("SEC-RXF-OAUTH2-003", "SEC-RXF-OAUTH2-004")
                .doesNotContain("SEC-RXF-OAUTH2-002");
        assertThat(urls.toString()).doesNotContain("private-label", "private-issuer", "private-introspection");
    }

    @Test
    void actuatorExcludesAccessDefaultsCapsAndPortModesAreEffective() {
        MockEnvironment environment =
                new MockEnvironment().withProperty("management.endpoints.web.exposure.include", "*");
        ReactiveSecurityObservation defaults = collect(environment, chain());
        assertThat(defaults.environment().effectiveActuatorEndpoints()).doesNotContain("heapdump", "shutdown");
        environment.setProperty("management.endpoint.heapdump.access", "unrestricted");
        environment.setProperty("management.endpoint.shutdown.access", "unrestricted");
        assertThat(collect(environment, chain()).environment().effectiveActuatorEndpoints())
                .contains("heapdump", "shutdown");
        environment.setProperty("management.endpoints.access.max-permitted", "read-only");
        assertThat(collect(environment, chain()).environment().effectiveActuatorEndpoints())
                .doesNotContain("shutdown");
        environment.setProperty("management.endpoints.web.exposure.exclude", "*");
        assertThat(collect(environment, chain()).environment().effectiveActuatorEndpoints())
                .isEmpty();
        environment.setProperty("management.endpoints.web.exposure.exclude", "");
        environment.setProperty("management.server.port", "-1");
        assertThat(collect(environment, chain()).environment().effectiveActuatorEndpoints())
                .isEmpty();
        environment.setProperty("management.server.port", "8080");
        assertThat(collect(environment, chain()).environment().managementServerPortConfigured())
                .isFalse();
        environment.setProperty("management.server.port", "9090");
        assertThat(collect(environment, chain()).environment().managementServerPortConfigured())
                .isTrue();
    }

    @Test
    void actuatorIndexedListsAndHostShowValuesRespectExposure() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("management.endpoints.web.exposure.include[0]", "env")
                .withProperty("management.endpoints.web.exposure.include[1]", "configprops")
                .withProperty("management.endpoint.env.show-values", "always");
        assertThat(collect(environment, chain()).environment().effectiveActuatorEndpoints())
                .contains("env", "configprops");
        assertThat(scan(collect(environment, chain())).results())
                .extracting(SecurityRuleResultDto::id)
                .contains("SEC-RXF-ACT-005");
        environment.setProperty("management.endpoints.access.max-permitted", "none");
        assertThat(scan(collect(environment, chain())).results())
                .extracting(SecurityRuleResultDto::id)
                .doesNotContain(
                        "SEC-RXF-ACT-001", "SEC-RXF-ACT-002", "SEC-RXF-ACT-003", "SEC-RXF-ACT-004", "SEC-RXF-ACT-005");
    }

    @Test
    void invalidActuatorIdentifierIsAnErrorRegardlessOfHelperMessageWording() {
        ReactiveSecurityObservation observation = collect(
                new MockEnvironment().withProperty("management.endpoints.web.exposure.include", "health,bad/endpoint"),
                chain());
        assertThat(scan(observation).analysisErrors())
                .extracting(SecurityRuleResultDto::id)
                .contains(
                        "SEC-RXF-ACT-001", "SEC-RXF-ACT-002", "SEC-RXF-ACT-003", "SEC-RXF-ACT-004", "SEC-RXF-ACT-005");
        assertThat(scan(observation).analysisErrors()).allSatisfy(error -> {
            assertThat(error.status()).isEqualTo("ERROR");
            assertThat(error.toString()).doesNotContain("bad/endpoint");
        });
    }

    @Test
    void actuatorInventoryLimitIsIncompleteRatherThanAnalysisError() {
        String includes = IntStream.range(0, 300)
                .mapToObj(index -> "endpoint" + index)
                .collect(java.util.stream.Collectors.joining(","));
        ReactiveSecurityObservation observation = collect(
                new MockEnvironment().withProperty("management.endpoints.web.exposure.include", includes), chain());
        SecurityReport report = scan(observation);
        assertThat(observation.environment().actuatorObservationComplete()).isFalse();
        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.analysisErrors()).isEmpty();
    }

    private static ContentSecurityPolicyServerHttpHeadersWriter csp(String policy, boolean reportOnly) {
        ContentSecurityPolicyServerHttpHeadersWriter writer = new ContentSecurityPolicyServerHttpHeadersWriter();
        writer.setPolicyDirectives(policy);
        writer.setReportOnly(reportOnly);
        return writer;
    }

    @Configuration(proxyBeanMethods = false)
    static class CollectorWithoutOAuth {
        @Bean
        SecurityWebFilterChain basicChain() {
            return http().httpBasic(Customizer.withDefaults()).build();
        }

        @Bean
        SpringReactiveSecurityObservationCollector observationCollector(
                ObjectProvider<SecurityWebFilterChain> chains,
                ObjectProvider<ListableBeanFactory> factories,
                Environment environment) {
            return new SpringReactiveSecurityObservationCollector(chains, factories, environment);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OrderedChains {
        @Bean
        @Order(2)
        SecurityWebFilterChain fallback() {
            return chain();
        }

        @Bean
        @Order(1)
        SecurityWebFilterChain scoped() {
            return new MatcherSecurityWebFilterChain(
                    new PathPatternParserServerWebExchangeMatcher("/api/**"), contextFilters());
        }
    }

    public static final class ChainFactory implements FactoryBean<SecurityWebFilterChain> {
        public ChainFactory() {
            throw new AssertionError("FactoryBean must not initialize");
        }

        @Override
        public SecurityWebFilterChain getObject() {
            throw new AssertionError("FactoryBean must not execute");
        }

        @Override
        public Class<?> getObjectType() {
            return SecurityWebFilterChain.class;
        }
    }

    private static final class ServerBearerTokenAuthenticationConverter implements ServerAuthenticationConverter {
        @Override
        public Mono<org.springframework.security.core.Authentication> convert(ServerWebExchange exchange) {
            throw new AssertionError("Custom converter must not execute");
        }
    }

    private static final class ServerFormLoginAuthenticationConverter implements ServerAuthenticationConverter {
        @Override
        public Mono<org.springframework.security.core.Authentication> convert(ServerWebExchange exchange) {
            throw new AssertionError("Custom converter must not execute");
        }
    }
}
