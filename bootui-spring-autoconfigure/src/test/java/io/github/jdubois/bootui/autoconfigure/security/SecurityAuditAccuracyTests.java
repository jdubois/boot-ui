package io.github.jdubois.bootui.autoconfigure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.autoconfigure.security.SecurityModel.FilterChainModel;
import io.github.jdubois.bootui.core.dto.SecurityReport;
import io.github.jdubois.bootui.core.dto.SecurityRuleResultDto;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.invoke.convert.ConversionServiceParameterValueMapper;
import org.springframework.boot.actuate.endpoint.web.EndpointLinksResolver;
import org.springframework.boot.actuate.endpoint.web.EndpointMapping;
import org.springframework.boot.actuate.endpoint.web.EndpointMediaTypes;
import org.springframework.boot.actuate.endpoint.web.annotation.WebEndpointDiscoverer;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.OriginTrackedMapPropertySource;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.JwkSetUriJwtDecoderBuilderCustomizer;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.actuate.endpoint.web.WebMvcEndpointHandlerMapping;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpMethod;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.SingleResultAuthorizationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.access.intercept.RequestMatcherDelegatingAuthorizationManager;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.security.web.header.writers.ContentSecurityPolicyHeaderWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

class SecurityAuditAccuracyTests {

    @Test
    void nativeActuatorOperationInventoryIsInspectedWithoutInvokingEndpoint() {
        AtomicInteger calls = new AtomicInteger();
        try (var application = new GenericApplicationContext()) {
            application.registerBean("fixtureEndpoint", FixtureEndpoint.class, () -> new FixtureEndpoint(calls));
            application.refresh();
            var discoverer = new WebEndpointDiscoverer(
                    application,
                    new ConversionServiceParameterValueMapper(),
                    EndpointMediaTypes.DEFAULT,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of());
            var endpoints = discoverer.getEndpoints();
            var mapping = new WebMvcEndpointHandlerMapping(
                    new EndpointMapping("/actuator"),
                    endpoints,
                    EndpointMediaTypes.DEFAULT,
                    null,
                    new EndpointLinksResolver(endpoints),
                    false);
            mapping.setApplicationContext(application);
            mapping.afterPropertiesSet();
            application.getBeanFactory().registerSingleton("fixtureMapping", mapping);
            application
                    .getBeanFactory()
                    .registerSingleton(
                            "springSecurityFilterChain",
                            new FilterChainProxy(new DefaultSecurityFilterChain(
                                    AnyRequestMatcher.INSTANCE,
                                    new AuthorizationFilter(SingleResultAuthorizationManager.permitAll()))));
            SecurityReport report = scanExisting(
                    application.getDefaultListableBeanFactory(),
                    new MockEnvironment().withProperty("management.endpoints.web.exposure.include", "prometheus"));
            assertThat(calls).hasValue(0);
            assertThat(report.results())
                    .filteredOn(result -> result.id().equals("SEC-ACT-003"))
                    .hasSize(1)
                    .allSatisfy(result ->
                            assertThat(result.sampleViolations()).anyMatch(detail -> detail.contains("prometheus")));
        }
    }

    @Endpoint(id = "prometheus")
    static class FixtureEndpoint {
        private final AtomicInteger calls;

        FixtureEndpoint(AtomicInteger calls) {
            this.calls = calls;
        }

        @ReadOperation
        public String read() {
            calls.incrementAndGet();
            return "fixture";
        }
    }

    @Test
    void nativeHttpSecurityDefaultsHaveCsrfHeadersAndPerFilterFixationWithoutEncoderBean() throws Exception {
        try (var application = new AnnotationConfigApplicationContext(NativeSecurityConfiguration.class)) {
            FilterChainModel chain = model(application.getBean("applicationChain", SecurityFilterChain.class));
            assertThat(chain.hasFilter("CsrfFilter")).isTrue();
            assertThat(chain.hasFilter("HeaderWriterFilter")).isTrue();
            assertThat(chain.hasFilter("SessionManagementFilter")).isFalse();
            assertThat(chain.sessionFixationDisabled()).isFalse();
            assertThat(application.getBeansOfType(org.springframework.security.crypto.password.PasswordEncoder.class))
                    .isEmpty();
            SecurityReport report = scanExisting(application.getDefaultListableBeanFactory());
            assertThat(report.filterChains()).hasSize(1);
            assertThat(report.results())
                    .extracting(SecurityRuleResultDto::id)
                    .doesNotContain("SEC-AUTH-001", "SEC-AUTH-002", "SEC-AUTH-003", "SEC-SESSION-001");
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class NativeSecurityConfiguration {
        @Bean
        UserDetailsService users() {
            return new InMemoryUserDetailsManager(User.withUsername("fixture")
                    .password("{noop}unused")
                    .authorities("USER")
                    .build());
        }

        @Bean
        SecurityFilterChain applicationChain(HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(
                            authorize -> authorize.anyRequest().authenticated())
                    .formLogin(Customizer.withDefaults())
                    .build();
        }
    }

    @Test
    void actualResourceServerInlineIntrospectorIsObservedWithoutNetworkCallbacks() throws Exception {
        try (var application = new AnnotationConfigApplicationContext(NativeOpaqueConfiguration.class)) {
            var chain = application.getBean("opaqueChain", SecurityFilterChain.class);
            Method providersMethod = SecurityScanner.class.getDeclaredMethod("activeProviders", List.class);
            providersMethod.setAccessible(true);
            List<?> providers = (List<?>) providersMethod.invoke(null, chain.getFilters());
            assertThat(providers)
                    .anyMatch(
                            provider -> provider.getClass()
                                    .getName()
                                    .equals(
                                            "org.springframework.security.oauth2.server.resource.authentication.OpaqueTokenAuthenticationProvider"));
            assertThat(model(chain).details().bearerSavesSession()).isFalse();
            SecurityReport report = scanExisting(application.getDefaultListableBeanFactory());
            assertThat(report.results()).extracting(SecurityRuleResultDto::id).doesNotContain("SEC-OAUTH-001");
            assertThat(application.getBean(AtomicInteger.class)).hasValue(0);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class NativeOpaqueConfiguration {
        @Bean
        AtomicInteger introspections() {
            return new AtomicInteger();
        }

        @Bean
        SecurityFilterChain opaqueChain(HttpSecurity http, AtomicInteger introspections) throws Exception {
            return http.authorizeHttpRequests(
                            authorize -> authorize.anyRequest().authenticated())
                    .oauth2ResourceServer(resource -> resource.opaqueToken(opaque -> opaque.introspector(token -> {
                        introspections.incrementAndGet();
                        throw new AssertionError("Advisor must not introspect a token");
                    })))
                    .build();
        }
    }

    @Test
    void bootAccountProvenanceDistinguishesUsernameOnlyFromAutoconfigurationBackoff() {
        MockEnvironment environment = new MockEnvironment().withProperty("spring.security.user.name", "fixture");
        environment.setActiveProfiles("prod");
        var runner = new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class))
                .withPropertyValues("spring.security.user.name=fixture");
        runner.withUserConfiguration(BootAccountConfiguration.class).run(application -> {
            assertThat(application)
                    .hasSingleBean(InMemoryUserDetailsManager.class)
                    .hasBean("inMemoryUserDetailsManager");
            SecurityReport report = scanExisting(
                    (DefaultListableBeanFactory)
                            application.getSourceApplicationContext().getBeanFactory(),
                    environment);
            assertThat(report.filterChains()).hasSize(1);
            assertThat(report.results())
                    .extracting(SecurityRuleResultDto::id)
                    .contains("SEC-AUTH-009")
                    .doesNotContain("SEC-AUTH-004");
        });
        environment.setProperty("spring.security.user.password", "test-only-password");
        runner.withPropertyValues("spring.security.user.password=test-only-password")
                .withUserConfiguration(NativeSecurityConfiguration.class)
                .run(application -> {
                    assertThat(application).doesNotHaveBean("inMemoryUserDetailsManager");
                    SecurityReport report = scanExisting(
                            (DefaultListableBeanFactory)
                                    application.getSourceApplicationContext().getBeanFactory(),
                            environment);
                    assertThat(report.results())
                            .extracting(SecurityRuleResultDto::id)
                            .doesNotContain("SEC-AUTH-004", "SEC-AUTH-009");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class BootAccountConfiguration {
        @Bean
        SecurityFilterChain accountChain(HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(
                            authorize -> authorize.anyRequest().authenticated())
                    .formLogin(Customizer.withDefaults())
                    .build();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void nativeJwtProvidersAndBootAudienceBindingAreObservedWithoutFetchingKeys() {
        AtomicInteger requests = new AtomicInteger();
        var rest = new RestTemplate((uri, method) -> {
            requests.incrementAndGet();
            throw new AssertionError("Advisor must not fetch signing keys");
        });
        var runner = new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        SecurityAutoConfiguration.class, OAuth2ResourceServerAutoConfiguration.class))
                .withUserConfiguration(NativeJwtConfiguration.class)
                .withBean(JwkSetUriJwtDecoderBuilderCustomizer.class, () -> builder -> builder.restOperations(rest))
                .withPropertyValues(
                        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://issuer.invalid/jwks");
        runner.run(application -> {
            assertThat(application.getBean(JwtDecoder.class)).isInstanceOf(NimbusJwtDecoder.class);
            SecurityReport report = scanExisting(
                    (DefaultListableBeanFactory)
                            application.getSourceApplicationContext().getBeanFactory(),
                    application.getEnvironment());
            assertThat(report.filterChains()).hasSize(1);
            assertThat(report.results())
                    .extracting(SecurityRuleResultDto::id)
                    .contains("SEC-OAUTH-002")
                    .doesNotContain("SEC-OAUTH-001");
            assertThat(requests).hasValue(0);
        });
        for (String[] audienceProperties : List.of(
                new String[] {"spring.security.oauth2.resourceserver.jwt.audiences=my-api,other-api"}, new String[] {
                    "spring.security.oauth2.resourceserver.jwt.audiences[0]=my-api",
                    "spring.security.oauth2.resourceserver.jwt.audiences[1]=other-api"
                })) {
            runner.withPropertyValues(audienceProperties).run(application -> {
                assertThat(application
                                .getBean(OAuth2ResourceServerProperties.class)
                                .getJwt()
                                .getAudiences())
                        .containsExactly("my-api", "other-api");
                NimbusJwtDecoder decoder = (NimbusJwtDecoder) application.getBean(JwtDecoder.class);
                var validatorField = NimbusJwtDecoder.class.getDeclaredField("jwtValidator");
                validatorField.setAccessible(true);
                var validator = (OAuth2TokenValidator<Jwt>) validatorField.get(decoder);
                assertThat(validator.validate(audienceFixture("my-api")).hasErrors())
                        .isFalse();
                assertThat(validator.validate(audienceFixture("unrelated-api")).hasErrors())
                        .isTrue();
                SecurityReport report = scanExisting(
                        (DefaultListableBeanFactory)
                                application.getSourceApplicationContext().getBeanFactory(),
                        application.getEnvironment());
                assertThat(report.results())
                        .extracting(SecurityRuleResultDto::id)
                        .doesNotContain("SEC-OAUTH-001", "SEC-OAUTH-002");
                assertThat(requests).hasValue(0);
            });
        }
    }

    @Test
    void nativeCustomJwtDecoderAttachmentDoesNotExecuteOrImplyBootAudienceValidation() {
        AtomicInteger calls = new AtomicInteger();
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        SecurityAutoConfiguration.class, OAuth2ResourceServerAutoConfiguration.class))
                .withUserConfiguration(NativeJwtConfiguration.class)
                .withBean(JwtDecoder.class, () -> token -> {
                    calls.incrementAndGet();
                    throw new AssertionError("Advisor must not decode a token");
                })
                .withPropertyValues("spring.security.oauth2.resourceserver.jwt.audiences[0]=my-api")
                .run(application -> {
                    SecurityReport report = scanExisting(
                            (DefaultListableBeanFactory)
                                    application.getSourceApplicationContext().getBeanFactory(),
                            application.getEnvironment());
                    assertThat(report.results())
                            .extracting(SecurityRuleResultDto::id)
                            .doesNotContain("SEC-OAUTH-001", "SEC-OAUTH-002");
                    assertThat(calls).hasValue(0);
                });
    }

    @Test
    void nativeServletScanRemainsAvailableWithOAuthExplicitlyAbsent() {
        new WebApplicationContextRunner()
                .withClassLoader(new FilteredClassLoader("org.springframework.security.oauth2"))
                .withConfiguration(AutoConfigurations.of(
                        SecurityAutoConfiguration.class, OAuth2ResourceServerAutoConfiguration.class))
                .withUserConfiguration(NativeSecurityConfiguration.class)
                .run(application -> {
                    assertThat(org.springframework.util.ClassUtils.isPresent(
                                    "org.springframework.security.oauth2.jwt.JwtDecoder", application.getClassLoader()))
                            .isFalse();
                    SecurityReport report = scanExisting(
                            (DefaultListableBeanFactory)
                                    application.getSourceApplicationContext().getBeanFactory(),
                            application.getEnvironment());
                    assertThat(report.filterChains()).hasSize(1);
                    assertThat(report.analysisErrors()).isEmpty();
                });
    }

    private static Jwt audienceFixture(String audience) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("local-fixture")
                .header("alg", "RS256")
                .header("typ", "JWT")
                .subject("fixture")
                .audience(List.of(audience))
                .issuedAt(now.minusSeconds(30))
                .expiresAt(now.plusSeconds(300))
                .build();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class NativeJwtConfiguration {
        @Bean
        SecurityFilterChain jwtChain(HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(
                            authorize -> authorize.anyRequest().authenticated())
                    .oauth2ResourceServer(resource -> resource.jwt(Customizer.withDefaults()))
                    .build();
        }
    }

    @Test
    void compositeMatcherDescriptionsPreserveKnownPathsWithoutCustomCallbacks() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        RequestMatcher custom = new RequestMatcher() {
            @Override
            public boolean matches(HttpServletRequest request) {
                calls.incrementAndGet();
                return false;
            }

            @Override
            public String toString() {
                calls.incrementAndGet();
                return "private application text";
            }
        };
        var chain = new DefaultSecurityFilterChain(
                new OrRequestMatcher(
                        PathPatternRequestMatcher.withDefaults().matcher("/api/secure/**"),
                        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/admin/**"),
                        custom),
                new AuthorizationFilter(SingleResultAuthorizationManager.denyAll()));
        calls.set(0);

        assertThat(model(chain).matcher())
                .contains("/api/secure/**", "POST /api/admin/**", "(unknown matcher)")
                .doesNotContain("private application text");
        assertThat(calls).hasValue(0);
    }

    @Test
    void compositeMatcherDescriptionsRemainBounded() throws Exception {
        List<RequestMatcher> matchers = IntStream.range(0, 20)
                .<RequestMatcher>mapToObj(index ->
                        PathPatternRequestMatcher.withDefaults().matcher("/" + "a".repeat(100) + index + "/**"))
                .toList();
        var chain = new DefaultSecurityFilterChain(
                new OrRequestMatcher(matchers), new AuthorizationFilter(SingleResultAuthorizationManager.denyAll()));

        assertThat(model(chain).matcher()).hasSizeLessThanOrEqualTo(512).endsWith("...");
    }

    @Test
    void customAuthorizationAndMatcherAndDescriptionAreNeverExecuted() {
        AtomicInteger calls = new AtomicInteger();
        RequestMatcher matcher = new RequestMatcher() {
            @Override
            public boolean matches(HttpServletRequest request) {
                calls.incrementAndGet();
                return true;
            }

            @Override
            public String toString() {
                calls.incrementAndGet();
                return "any request";
            }
        };
        AuthorizationManager<HttpServletRequest> manager = (authentication, request) -> {
            calls.incrementAndGet();
            throw new IllegalStateException("private application text");
        };
        var nativeChain = new DefaultSecurityFilterChain(matcher, new AuthorizationFilter(manager));
        // Framework construction may render the matcher; measure only scanner activity.
        calls.set(0);
        SecurityReport report = scan(new DefaultListableBeanFactory(), nativeChain);
        assertThat(calls).hasValue(0);
        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.results())
                .extracting(SecurityRuleResultDto::id)
                .doesNotContain("SEC-AUTHZ-002", "SEC-AUTHZ-003", "SEC-AUTHZ-004", "SEC-ACT-003");
        assertThat(report.analysisErrors()).allMatch(result -> result.status().equals("ERROR"));
    }

    @Test
    void unsupportedChainKeepsItsPositionWithoutCallingGetFilters() {
        AtomicInteger calls = new AtomicInteger();
        SecurityFilterChain unsupported = new SecurityFilterChain() {
            @Override
            public boolean matches(HttpServletRequest request) {
                calls.incrementAndGet();
                return true;
            }

            @Override
            public List<Filter> getFilters() {
                calls.incrementAndGet();
                throw new IllegalStateException("secret");
            }
        };
        SecurityReport report = scan(
                new DefaultListableBeanFactory(),
                unsupported,
                new DefaultSecurityFilterChain(
                        AnyRequestMatcher.INSTANCE,
                        new AuthorizationFilter(SingleResultAuthorizationManager.permitAll())));
        assertThat(calls).hasValue(0);
        assertThat(report.filterChains()).hasSize(2);
        assertThat(report.filterChains().get(0)).contains("unsupported");
        assertThat(report.results()).extracting(SecurityRuleResultDto::id).doesNotContain("SEC-AUTHZ-003");
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedLaterScanDoesNotReuseEarlierChainsOrExceptionText() {
        var factory = new DefaultListableBeanFactory();
        factory.registerSingleton(
                "springSecurityFilterChain",
                new FilterChainProxy(new DefaultSecurityFilterChain(AnyRequestMatcher.INSTANCE)));
        ObjectProvider<FilterChainProxy> proxies = mock(ObjectProvider.class);
        ObjectProvider<ListableBeanFactory> factories = mock(ObjectProvider.class);
        when(factories.getIfAvailable())
                .thenReturn(factory)
                .thenThrow(new IllegalStateException("private deployment secret"));
        var scanner = new SecurityScanner(proxies, factories, new MockEnvironment(), Clock.systemUTC());
        assertThat(scanner.scan().filterChains()).hasSize(1);
        SecurityReport failed = scanner.scan();
        assertThat(failed.filterChains()).isEmpty();
        assertThat(failed.scan().message()).doesNotContain("private deployment secret");
    }

    @Test
    void lazyPrototypeAndFactoryBeansAreNotInitializedOrTypeProbed() {
        AtomicInteger calls = new AtomicInteger();
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        RootBeanDefinition lazy = new RootBeanDefinition(BCryptPasswordEncoder.class, () -> {
            calls.incrementAndGet();
            return new BCryptPasswordEncoder();
        });
        lazy.setLazyInit(true);
        factory.registerBeanDefinition("lazyEncoder", lazy);
        RootBeanDefinition prototype = new RootBeanDefinition(BCryptPasswordEncoder.class, () -> {
            calls.incrementAndGet();
            return new BCryptPasswordEncoder();
        });
        prototype.setScope("prototype");
        factory.registerBeanDefinition("prototypeEncoder", prototype);
        factory.registerSingleton("unusedFactory", new FactoryBean<Object>() {
            @Override
            public Object getObject() {
                calls.incrementAndGet();
                return new Object();
            }

            @Override
            public Class<?> getObjectType() {
                calls.incrementAndGet();
                return Object.class;
            }
        });
        scan(
                factory,
                new DefaultSecurityFilterChain(
                        AnyRequestMatcher.INSTANCE,
                        new AuthorizationFilter(SingleResultAuthorizationManager.denyAll())));
        assertThat(calls).hasValue(0);
    }

    @Test
    void initializedUnrelatedFactoryProductTypeIsNeverQueried() {
        AtomicInteger calls = new AtomicInteger();
        var factory = new DefaultListableBeanFactory();
        factory.registerBeanDefinition(
                "unrelatedFactory", new RootBeanDefinition(CountingFactory.class, () -> new CountingFactory(calls)));
        assertThat(factory.getBean("&unrelatedFactory")).isInstanceOf(CountingFactory.class);
        calls.set(0);
        SecurityReport report = scan(
                factory,
                new DefaultSecurityFilterChain(
                        AnyRequestMatcher.INSTANCE,
                        new AuthorizationFilter(SingleResultAuthorizationManager.denyAll())));
        assertThat(report.filterChains()).hasSize(1);
        assertThat(calls).hasValue(0);
    }

    static class CountingFactory implements FactoryBean<Object> {
        private final AtomicInteger calls;

        CountingFactory(AtomicInteger calls) {
            this.calls = calls;
        }

        @Override
        public Object getObject() {
            calls.incrementAndGet();
            return new Object();
        }

        @Override
        public Class<?> getObjectType() {
            calls.incrementAndGet();
            return Object.class;
        }
    }

    @Test
    void typedHttpMethodCatchAllDoesNotShadowOtherMethods() throws Exception {
        FilterChainModel get =
                model(new DefaultSecurityFilterChain(PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/**")));
        FilterChainModel all = model(new DefaultSecurityFilterChain(PathPatternRequestMatcher.pathPattern("/**")));
        assertThat(get.matchesAnyRequest()).isFalse();
        assertThat(get.details().matcher().matches("POST", "/admin")).isFalse();
        assertThat(all.matchesAnyRequest()).isTrue();
    }

    @Test
    void authorizationOutsideFormerFiniteSamplesIsNotBlanketGrant() throws Exception {
        var manager = RequestMatcherDelegatingAuthorizationManager.builder()
                .add(PathPatternRequestMatcher.pathPattern("/never-probed"), SingleResultAuthorizationManager.denyAll())
                .add(AnyRequestMatcher.INSTANCE, SingleResultAuthorizationManager.permitAll())
                .build();
        FilterChainModel chain =
                model(new DefaultSecurityFilterChain(AnyRequestMatcher.INSTANCE, new AuthorizationFilter(manager)));
        assertThat(chain.permitsAllAnonymous()).isFalse();
        assertThat(SecurityScanner.grantFor(chain.details().mappings(), "GET", "/never-probed"))
                .isFalse();
        assertThat(SecurityScanner.grantFor(chain.details().mappings(), "GET", "/public"))
                .isTrue();
    }

    @Test
    void unusedNoopEncoderIsNotEffectivePasswordStorage() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("unusedNoOp", NoOpPasswordEncoder.getInstance());
        SecurityReport report = scan(factory, new DefaultSecurityFilterChain(AnyRequestMatcher.INSTANCE));
        assertThat(report.results()).extracting(SecurityRuleResultDto::id).doesNotContain("SEC-AUTH-001");
    }

    @Test
    void activeProviderSelectedEncoderIsInspectedWithoutCallingSupplier() {
        var provider = new DaoAuthenticationProvider(new InMemoryUserDetailsManager(
                User.withUsername("test").password("unused").authorities("USER").build()));
        provider.setPasswordEncoder(NoOpPasswordEncoder.getInstance());
        var filter = new BasicAuthenticationFilter(new ProviderManager(provider));
        SecurityReport report = scan(
                new DefaultListableBeanFactory(), new DefaultSecurityFilterChain(AnyRequestMatcher.INSTANCE, filter));
        assertThat(report.results()).extracting(SecurityRuleResultDto::id).contains("SEC-AUTH-001");
        provider.setPasswordEncoder(PasswordEncoderFactories.createDelegatingPasswordEncoder());
        report = scan(
                new DefaultListableBeanFactory(), new DefaultSecurityFilterChain(AnyRequestMatcher.INSTANCE, filter));
        assertThat(report.results())
                .extracting(SecurityRuleResultDto::id)
                .doesNotContain("SEC-AUTH-001", "SEC-AUTH-002");
        provider.setPasswordEncoder(new BCryptPasswordEncoder(4));
        report = scan(
                new DefaultListableBeanFactory(), new DefaultSecurityFilterChain(AnyRequestMatcher.INSTANCE, filter));
        assertThat(report.results()).extracting(SecurityRuleResultDto::id).contains("SEC-AUTH-006");
    }

    @Test
    void modernFormFilterOwnsFixationWithoutSessionManagementFilter() throws Exception {
        var form = new UsernamePasswordAuthenticationFilter();
        form.setSessionAuthenticationStrategy(new ChangeSessionIdAuthenticationStrategy());
        assertThat(model(new DefaultSecurityFilterChain(AnyRequestMatcher.INSTANCE, form))
                        .sessionFixationDisabled())
                .isFalse();
        form.setSessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy());
        assertThat(model(new DefaultSecurityFilterChain(AnyRequestMatcher.INSTANCE, form))
                        .sessionFixationDisabled())
                .isTrue();
    }

    @Test
    void bearerPersistenceComesFromItsOwnSaveRepository() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var bearer = new BearerTokenAuthenticationFilter(
                (org.springframework.security.authentication.AuthenticationManager) authentication -> {
                    calls.incrementAndGet();
                    return authentication;
                });
        FilterChainModel chain = model(new DefaultSecurityFilterChain(AnyRequestMatcher.INSTANCE, bearer));
        assertThat(chain.details().bearerSavesSession()).isFalse();
        bearer.setSecurityContextRepository(new HttpSessionSecurityContextRepository());
        chain = model(new DefaultSecurityFilterChain(AnyRequestMatcher.INSTANCE, bearer));
        assertThat(chain.details().bearerSavesSession()).isTrue();
        assertThat(new BearerTokenStatefulRule()
                        .evaluate(context(chain, new MockEnvironment()))
                        .severity())
                .isEqualTo("LOW");
        assertThat(calls).hasValue(0);
    }

    @Test
    void csrfRelevanceDoesNotDisappearInMixedBearerFormChains() {
        var chain = new FilterChainModel(
                0,
                "any request",
                List.of("UsernamePasswordAuthenticationFilter", "BearerTokenAuthenticationFilter"),
                null,
                null,
                List.of());
        assertThat(new CsrfDisabledStatefulRule()
                        .evaluate(context(chain, new MockEnvironment()))
                        .status())
                .isEqualTo("VIOLATION");
        var bearer = new FilterChainModel(
                0, "any request", List.of("BearerTokenAuthenticationFilter"), null, null, List.of());
        assertThat(new CsrfDisabledStatefulRule()
                        .evaluate(context(bearer, new MockEnvironment()))
                        .status())
                .isEqualTo("PASS");
    }

    @Test
    void directTlsDisableWinsAndForwardingDoesNotProveTls() {
        var chain = new FilterChainModel(0, "any request", List.of("BasicAuthenticationFilter"), null, null, List.of());
        MockEnvironment environment = new MockEnvironment()
                .withProperty("server.ssl.enabled", "false")
                .withProperty("server.ssl.key-store", "unused")
                .withProperty("server.forward-headers-strategy", "framework");
        assertThat(context(chain, environment).isTlsConfigured()).isFalse();
        environment.setProperty("server.ssl.enabled", "true");
        assertThat(context(chain, environment).isTlsConfigured()).isTrue();
    }

    @Test
    void redirectEvidenceRequiresRecognizedMappingsAndApplicablePort() throws Exception {
        var redirect = new org.springframework.security.web.transport.HttpsRedirectFilter();
        FilterChainModel chain = model(new DefaultSecurityFilterChain(AnyRequestMatcher.INSTANCE, redirect));
        assertThat(context(chain, new MockEnvironment()).isTlsConfiguredFor(chain))
                .isTrue();
        assertThat(context(chain, new MockEnvironment().withProperty("server.port", "9090"))
                        .isTlsConfiguredFor(chain))
                .isFalse();
        var mapper = new org.springframework.security.web.PortMapperImpl();
        mapper.setPortMappings(Map.of("9090", "9443"));
        redirect.setPortMapper(mapper);
        assertThat(model(new DefaultSecurityFilterChain(AnyRequestMatcher.INSTANCE, redirect))
                        .details()
                        .httpsRedirect())
                .isFalse();
    }

    @Test
    void enforcingAndReportOnlyCspAreOrderIndependent() throws Exception {
        var enforcing = new ContentSecurityPolicyHeaderWriter("script-src 'self'; frame-ancestors 'none'");
        var reportOnly = new ContentSecurityPolicyHeaderWriter("script-src 'unsafe-inline'");
        reportOnly.setReportOnly(true);
        var unsupportedReportOnly = new ContentSecurityPolicyHeaderWriter("script-src 'self', script-src *");
        unsupportedReportOnly.setReportOnly(true);
        for (var writers : List.of(
                List.of(enforcing, reportOnly),
                List.of(reportOnly, enforcing),
                List.of(enforcing, unsupportedReportOnly),
                List.of(unsupportedReportOnly, enforcing))) {
            FilterChainModel chain = model(new DefaultSecurityFilterChain(
                    AnyRequestMatcher.INSTANCE, new HeaderWriterFilter(List.copyOf(writers))));
            assertThat(chain.cspReportOnly()).isFalse();
            assertThat(chain.details().headersKnown()).isTrue();
            assertThat(chain.hasWeakCsp()).isFalse();
            assertThat(chain.hasCspDirective("frame-ancestors")).isTrue();
        }
    }

    @Test
    void enforcingFrameAncestorsOverridesXfoAndUnknownCspCannotProveFallback() throws Exception {
        Map<String, String> policies = Map.of(
                "frame-ancestors *", "VIOLATION",
                "frame-ancestors *; frame-ancestors 'none'", "VIOLATION",
                "frame-ancestors", "PASS",
                "frame-ancestors 'none'", "PASS",
                "script-src 'self'", "PASS",
                "frame-ancestors *, frame-ancestors 'none'", "SKIPPED");
        for (var policy : policies.entrySet()) {
            assertThat(framingResult(policy.getKey(), false, true).status()).isEqualTo(policy.getValue());
        }
        assertThat(framingResult("frame-ancestors *", true, true).status()).isEqualTo("PASS");
        assertThat(framingResult("frame-ancestors 'none'", true, false).status())
                .isEqualTo("VIOLATION");
        assertThat(framingResult("frame-ancestors 'none'", false, false).status())
                .isEqualTo("PASS");
        assertThat(framingResult("script-src " + "'self' ".repeat(10000), false, true)
                        .status())
                .isEqualTo("SKIPPED");
    }

    private static SecurityRuleResultDto framingResult(String policy, boolean reportOnly, boolean frameOptions)
            throws Exception {
        var csp = new ContentSecurityPolicyHeaderWriter(policy);
        csp.setReportOnly(reportOnly);
        List<org.springframework.security.web.header.HeaderWriter> writers = new java.util.ArrayList<>();
        writers.add(csp);
        if (frameOptions)
            writers.add(new org.springframework.security.web.header.writers.frameoptions.XFrameOptionsHeaderWriter());
        FilterChainModel chain = model(new DefaultSecurityFilterChain(
                AnyRequestMatcher.INSTANCE,
                new UsernamePasswordAuthenticationFilter(),
                new HeaderWriterFilter(writers)));
        return new FrameOptionsRule().evaluate(context(chain, new MockEnvironment()));
    }

    @Test
    void multipleEnforcingCspAndCustomWriterRemainUnknown() throws Exception {
        var first = new ContentSecurityPolicyHeaderWriter("script-src *");
        var second = new ContentSecurityPolicyHeaderWriter("script-src 'self'");
        FilterChainModel chain = model(new DefaultSecurityFilterChain(
                AnyRequestMatcher.INSTANCE, new HeaderWriterFilter(List.of(first, second))));
        assertThat(chain.details().headersKnown()).isFalse();
        assertThat(chain.hasWeakCsp()).isFalse();
        AtomicInteger calls = new AtomicInteger();
        chain = model(new DefaultSecurityFilterChain(
                AnyRequestMatcher.INSTANCE,
                new HeaderWriterFilter(List.of((request, response) -> calls.incrementAndGet()))));
        assertThat(chain.details().headersKnown()).isFalse();
        assertThat(calls).hasValue(0);
    }

    @Test
    void corsUsesAttachedSourceNotUnusedBean() {
        CorsConfiguration broad = new CorsConfiguration();
        broad.setAllowedOriginPatterns(List.of("*"));
        broad.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", broad);
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("unusedCors", source);
        assertThat(scan(factory, new DefaultSecurityFilterChain(AnyRequestMatcher.INSTANCE))
                        .results())
                .extracting(SecurityRuleResultDto::id)
                .doesNotContain("SEC-CORS-002");
        assertThat(scan(
                                new DefaultListableBeanFactory(),
                                new DefaultSecurityFilterChain(AnyRequestMatcher.INSTANCE, new CorsFilter(source)))
                        .results())
                .extracting(SecurityRuleResultDto::id)
                .contains("SEC-CORS-002");
    }

    @Test
    void corsFirstMappingShadowsLaterBroadPolicyButReversedOrderDoesNot() {
        CorsConfiguration restrictive = new CorsConfiguration();
        restrictive.setAllowedOrigins(List.of("https://example.com"));
        CorsConfiguration broad = credentialedWildcardCors();
        UrlBasedCorsConfigurationSource shadowed = new UrlBasedCorsConfigurationSource();
        shadowed.registerCorsConfiguration("/**", restrictive);
        shadowed.registerCorsConfiguration("/private/**", broad);
        assertThat(scan(
                                new DefaultListableBeanFactory(),
                                new DefaultSecurityFilterChain(AnyRequestMatcher.INSTANCE, new CorsFilter(shadowed)))
                        .results())
                .extracting(SecurityRuleResultDto::id)
                .doesNotContain("SEC-CORS-002");
        UrlBasedCorsConfigurationSource reachable = new UrlBasedCorsConfigurationSource();
        reachable.registerCorsConfiguration("/private/**", broad);
        reachable.registerCorsConfiguration("/**", restrictive);
        assertThat(scan(
                                new DefaultListableBeanFactory(),
                                new DefaultSecurityFilterChain(AnyRequestMatcher.INSTANCE, new CorsFilter(reachable)))
                        .results())
                .extracting(SecurityRuleResultDto::id)
                .contains("SEC-CORS-002");
    }

    @Test
    void corsMappingOutsideItsOwningChainAndShadowedOwnerAreNotReported() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/private/**", credentialedWildcardCors());
        var attached = new DefaultSecurityFilterChain(
                PathPatternRequestMatcher.pathPattern("/public/**"), new CorsFilter(source));
        assertThat(scan(new DefaultListableBeanFactory(), attached).results())
                .extracting(SecurityRuleResultDto::id)
                .doesNotContain("SEC-CORS-002");
        assertThat(scan(
                                new DefaultListableBeanFactory(),
                                new DefaultSecurityFilterChain(AnyRequestMatcher.INSTANCE),
                                new DefaultSecurityFilterChain(AnyRequestMatcher.INSTANCE, new CorsFilter(source)))
                        .results())
                .extracting(SecurityRuleResultDto::id)
                .doesNotContain("SEC-CORS-002");
    }

    @Test
    void unknownEarlierCorsPatternAndCustomSourceStayIncompleteWithoutCallbacks() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/{tenant}/**", new CorsConfiguration());
        source.registerCorsConfiguration("/**", credentialedWildcardCors());
        SecurityReport report = scan(
                new DefaultListableBeanFactory(),
                new DefaultSecurityFilterChain(AnyRequestMatcher.INSTANCE, new CorsFilter(source)));
        assertThat(report.results()).extracting(SecurityRuleResultDto::id).doesNotContain("SEC-CORS-002");
        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        AtomicInteger calls = new AtomicInteger();
        report = scan(
                new DefaultListableBeanFactory(),
                new DefaultSecurityFilterChain(AnyRequestMatcher.INSTANCE, new CorsFilter(request -> {
                    calls.incrementAndGet();
                    return credentialedWildcardCors();
                })));
        assertThat(report.results()).extracting(SecurityRuleResultDto::id).doesNotContain("SEC-CORS-002");
        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.analysisErrors()).isEmpty();
        assertThat(calls).hasValue(0);
    }

    private static CorsConfiguration credentialedWildcardCors() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowCredentials(true);
        return config;
    }

    @Test
    void corsMethodSpecificEarlierChainDoesNotShadowOtherMethods() {
        for (String path : List.of("/api/**", "/api")) {
            SecurityReport report =
                    corsReportWithEarlierChain(PathPatternRequestMatcher.pathPattern(HttpMethod.GET, path), path);
            assertThat(report.filterChainsAnalyzed()).isEqualTo(2);
            assertThat(report.results())
                    .filteredOn(result -> result.id().equals("SEC-CORS-002"))
                    .hasSize(1)
                    .allSatisfy(result -> {
                        assertThat(result.status()).isEqualTo("VIOLATION");
                        assertThat(result.sampleViolations())
                                .anyMatch(detail ->
                                        detail.contains("origin-pattern wildcard") && detail.contains("credentials"));
                    });
        }
    }

    @Test
    void corsAllMethodEarlierChainReallyShadowsTheLaterPolicy() {
        for (String path : List.of("/api/**", "/api")) {
            SecurityReport report = corsReportWithEarlierChain(PathPatternRequestMatcher.pathPattern(path), path);
            assertThat(report.results()).extracting(SecurityRuleResultDto::id).doesNotContain("SEC-CORS-002");
        }
    }

    @Test
    void corsUnknownEarlierChainRemainsIncompleteWithoutInvokingMatcher() {
        AtomicInteger calls = new AtomicInteger();
        RequestMatcher earlier = request -> {
            calls.incrementAndGet();
            throw new AssertionError("Advisor must not execute an application matcher");
        };
        SecurityReport report = corsReportWithEarlierChain(earlier, "/api/**");
        assertThat(report.results()).extracting(SecurityRuleResultDto::id).doesNotContain("SEC-CORS-002");
        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.analysisErrors()).isEmpty();
        assertThat(calls).hasValue(0);
    }

    private static SecurityReport corsReportWithEarlierChain(RequestMatcher earlier, String path) {
        CorsConfiguration configuration = credentialedWildcardCors();
        configuration.setAllowedMethods(List.of("POST"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration(path, configuration);
        return scan(
                new DefaultListableBeanFactory(),
                new DefaultSecurityFilterChain(earlier),
                new DefaultSecurityFilterChain(PathPatternRequestMatcher.pathPattern(path), new CorsFilter(source)));
    }

    @Test
    void actuatorAppliesListsDefaultsCapsExclusionAndPortSemantics() {
        MockEnvironment environment =
                new MockEnvironment().withProperty("management.endpoints.web.exposure.include[0]", "*");
        var snapshot = SecurityActuatorObservation.observe(environment);
        assertThat(snapshot.exposedEndpoints()).contains("env").doesNotContain("heapdump", "shutdown");
        environment.setProperty("management.endpoint.heapdump.access", "read-only");
        environment.setProperty("management.endpoint.shutdown.access", "unrestricted");
        assertThat(SecurityActuatorObservation.observe(environment).exposedEndpoints())
                .contains("heapdump", "shutdown");
        environment.setProperty("management.endpoints.access.max-permitted", "read-only");
        assertThat(SecurityActuatorObservation.observe(environment).exposedEndpoints())
                .contains("heapdump")
                .doesNotContain("shutdown");
        environment.setProperty("management.server.port", "8080");
        assertThat(SecurityActuatorObservation.observe(environment).separateManagementPort())
                .isFalse();
        environment.setProperty("management.server.port", "0");
        assertThat(SecurityActuatorObservation.observe(environment).separateManagementPort())
                .isTrue();
        environment.setProperty("management.endpoints.web.exposure.exclude", "*");
        assertThat(SecurityActuatorObservation.observe(environment).exposedEndpoints())
                .isEmpty();
        environment.setProperty("management.server.port", "-1");
        assertThat(SecurityActuatorObservation.observe(environment).managementDisabled())
                .isTrue();
    }

    @Test
    void actuatorAccessConflictIsAnErrorNotAFalseExposureVerdict() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("management.endpoint.shutdown.enabled", "true")
                .withProperty("management.endpoint.shutdown.access", "unrestricted");
        assertThat(new ShutdownEndpointEnabledRule()
                        .evaluate(context(null, environment))
                        .status())
                .isEqualTo("ERROR");
    }

    @Test
    void exactOperationDefaultAndWriteAccessArePreserved() {
        MockEnvironment environment =
                new MockEnvironment().withProperty("management.endpoints.web.exposure.include", "*");
        assertThat(SecurityActuatorObservation.permitsOperation(environment, "custom", "GET", "none"))
                .isFalse();
        environment.setProperty("management.endpoint.custom.access", "read-only");
        assertThat(SecurityActuatorObservation.permitsOperation(environment, "custom", "GET", "none"))
                .isTrue();
        assertThat(SecurityActuatorObservation.permitsOperation(environment, "custom", "POST", "none"))
                .isFalse();
    }

    @Test
    void indexedExposureReplacesLowerScalarSourceWithoutMerging() {
        MockEnvironment environment =
                new MockEnvironment().withProperty("management.endpoints.web.exposure.include", "*");
        environment
                .getPropertySources()
                .addFirst(new MapPropertySource(
                        "higher", Map.of("management.endpoints.web.exposure.include[0]", "health")));
        assertThat(SecurityActuatorObservation.observe(environment).exposedEndpoints())
                .containsExactly("health");
        assertThat(SecurityActuatorObservation.observe(environment).wildcardIncluded())
                .isFalse();
    }

    @Test
    void oversizedExposureIsIncompleteNotAnAnalysisError() {
        MockEnvironment environment =
                new MockEnvironment().withProperty("management.endpoints.web.exposure.include", "health,".repeat(257));
        assertThat(SecurityActuatorObservation.observe(environment).complete()).isFalse();
        assertThat(SecurityActuatorObservation.observe(environment).errors()).isEmpty();
    }

    @Test
    void exactActuatorOperationCannotBeReplacedByProtectedEnvProbe() throws Exception {
        var manager = RequestMatcherDelegatingAuthorizationManager.builder()
                .add(PathPatternRequestMatcher.pathPattern("/actuator/env"), SingleResultAuthorizationManager.denyAll())
                .add(
                        PathPatternRequestMatcher.pathPattern("/actuator/prometheus"),
                        SingleResultAuthorizationManager.permitAll())
                .add(AnyRequestMatcher.INSTANCE, SingleResultAuthorizationManager.denyAll())
                .build();
        var chain = model(new DefaultSecurityFilterChain(AnyRequestMatcher.INSTANCE, new AuthorizationFilter(manager)));
        var base = context(
                chain,
                new MockEnvironment().withProperty("management.endpoints.web.exposure.include", "env,prometheus"));
        var evidence = new SecurityContext.Evidence(
                List.of(
                        new SecurityContext.Operation("env", "GET", "/actuator/env"),
                        new SecurityContext.Operation("prometheus", "GET", "/actuator/prometheus")),
                true,
                false,
                Set.of(),
                Set.of(),
                true);
        assertThat(new ActuatorUnprotectedRule()
                        .evaluate(withEvidence(base, evidence))
                        .sampleViolations())
                .anyMatch(detail -> detail.contains("prometheus"))
                .noneMatch(detail -> detail.contains("'env'"));
    }

    @Test
    void nativeTemplateDenyCannotFallThroughToAnonymousActuatorGrant() throws Exception {
        for (String path : List.of("/actuator/{id}/**", "/actuator/pro?etheus/**")) {
            assertThat(orderedActuatorAuthorization(path, false).status()).isEqualTo("SKIPPED");
        }
        assertThat(orderedActuatorAuthorization("/actuator/prometheus/**", false)
                        .status())
                .isEqualTo("PASS");
    }

    @Test
    void nativeTemplateGrantCannotFallThroughToActuatorDeny() throws Exception {
        for (String path : List.of("/actuator/{id}/**", "/actuator/pro?etheus/**")) {
            assertThat(orderedActuatorAuthorization(path, true).status()).isEqualTo("SKIPPED");
        }
        assertThat(orderedActuatorAuthorization("/actuator/prometheus/**", true).status())
                .isEqualTo("VIOLATION");
    }

    private static SecurityRuleResultDto orderedActuatorAuthorization(String path, boolean grant) throws Exception {
        var manager = RequestMatcherDelegatingAuthorizationManager.builder()
                .add(
                        PathPatternRequestMatcher.pathPattern(path),
                        grant
                                ? SingleResultAuthorizationManager.permitAll()
                                : SingleResultAuthorizationManager.denyAll())
                .add(
                        AnyRequestMatcher.INSTANCE,
                        grant
                                ? SingleResultAuthorizationManager.denyAll()
                                : SingleResultAuthorizationManager.permitAll())
                .build();
        var chain = model(new DefaultSecurityFilterChain(AnyRequestMatcher.INSTANCE, new AuthorizationFilter(manager)));
        var base = context(
                chain, new MockEnvironment().withProperty("management.endpoints.web.exposure.include", "prometheus"));
        var evidence = new SecurityContext.Evidence(
                List.of(new SecurityContext.Operation("prometheus", "GET", "/actuator/prometheus/details")),
                true,
                false,
                Set.of(),
                Set.of(),
                true);
        return new ActuatorUnprotectedRule().evaluate(withEvidence(base, evidence));
    }

    @Test
    void methodSecurityFamiliesAreIndependent() {
        var base = context(null, new MockEnvironment());
        var evidence =
                new SecurityContext.Evidence(List.of(), false, false, Set.of("pre-post"), Set.of("secured"), true);
        assertThat(new MethodSecurityAnnotationsIgnoredRule()
                        .evaluate(withEvidence(base, evidence))
                        .status())
                .isEqualTo("VIOLATION");
        evidence = new SecurityContext.Evidence(List.of(), false, false, Set.of("secured"), Set.of("secured"), true);
        assertThat(new MethodSecurityAnnotationsIgnoredRule()
                        .evaluate(withEvidence(base, evidence))
                        .status())
                .isEqualTo("PASS");
    }

    @Test
    void actualDefaultMethodSecurityDoesNotActivateSecuredFamily() {
        try (var application = new AnnotationConfigApplicationContext(
                NativeSecurityConfiguration.class, MethodFamilyConfiguration.class)) {
            SecurityReport report = scanExisting(application.getDefaultListableBeanFactory());
            assertThat(report.results())
                    .filteredOn(result -> result.id().equals("SEC-METHOD-001"))
                    .hasSize(1)
                    .allSatisfy(result ->
                            assertThat(result.sampleViolations()).anyMatch(detail -> detail.contains("secured")));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class MethodFamilyConfiguration {
        @Bean
        SecuredService securedService() {
            return new SecuredService();
        }
    }

    static class SecuredService {
        @Secured("ROLE_ADMIN")
        public void guarded() {
            throw new AssertionError("Advisor must not execute application methods");
        }
    }

    @Test
    void bootManagedAudiencesAcceptIndexedBindingButCustomDecoderRemainsUnknown() {
        var chain = new FilterChainModel(
                0, "any request", List.of("BearerTokenAuthenticationFilter"), null, null, List.of());
        var base = context(
                chain,
                new MockEnvironment().withProperty("spring.security.oauth2.resourceserver.jwt.audiences[0]", "my-api"));
        var managed = new SecurityContext.Evidence(List.of(), false, true, Set.of(), Set.of(), true);
        assertThat(new JwtAudienceValidationRule()
                        .evaluate(withEvidence(base, managed))
                        .status())
                .isEqualTo("PASS");
        assertThat(new JwtAudienceValidationRule().evaluate(base).status()).isEqualTo("SKIPPED");
    }

    @Test
    void boot4CallerControlledErrorDetailsAreReviewedAndObsoleteNamespaceIgnored() {
        var environment = new MockEnvironment().withProperty("server.error.include-message", "always");
        assertThat(new ErrorResponseDisclosureRule()
                        .evaluate(context(null, environment))
                        .status())
                .isEqualTo("PASS");
        environment.setProperty("spring.web.error.include-message", "on-param");
        assertThat(new ErrorResponseDisclosureRule()
                        .evaluate(context(null, environment))
                        .status())
                .isEqualTo("VIOLATION");
        environment.setProperty("spring.web.error.include-message", "never");
        assertThat(new ErrorResponseDisclosureRule()
                        .evaluate(context(null, environment))
                        .status())
                .isEqualTo("PASS");
    }

    @Test
    void verboseChildLoggerIsNotHiddenByInfoParent() {
        var environment = new MockEnvironment()
                .withProperty("logging.level.org.springframework.security", "INFO")
                .withProperty("logging.level.org.springframework.security.web", "TRACE");
        environment.setActiveProfiles("prod");
        assertThat(new SecurityDebugLoggingProductionRule()
                        .evaluate(context(null, environment))
                        .status())
                .isEqualTo("VIOLATION");
    }

    @Test
    void arbitraryConfigSourcesAreNeverEnumeratedForSecrets() {
        AtomicInteger calls = new AtomicInteger();
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("external-secret-provider", Map.of()) {
            @Override
            public String[] getPropertyNames() {
                calls.incrementAndGet();
                return new String[] {"service.password"};
            }

            @Override
            public Object getProperty(String key) {
                calls.incrementAndGet();
                return "private";
            }
        });
        assertThat(context(null, environment).suspectedHardcodedSecretKeys()).isEmpty();
        assertThat(calls).hasValue(0);
        environment.getPropertySources().remove("external-secret-provider");
        environment
                .getPropertySources()
                .addFirst(new OriginTrackedMapPropertySource(
                        "Config resource 'class path resource [application.properties]'",
                        Map.of("service.password", "literal", "oauth.token-uri", "https://example.invalid")));
        assertThat(context(null, environment).suspectedHardcodedSecretKeys()).containsExactly("service.password");
    }

    @Test
    void fullScanNeverCallsOpaqueOrOverriddenPropertySourcesAndRetainsIndependentFinding() {
        AtomicInteger calls = new AtomicInteger();
        var opaque = new org.springframework.core.env.EnumerablePropertySource<>("external", new Object()) {
            @Override
            public String[] getPropertyNames() {
                calls.incrementAndGet();
                throw new AssertionError();
            }

            @Override
            public Object getProperty(String key) {
                calls.incrementAndGet();
                throw new AssertionError();
            }
        };
        var overridden = new MapPropertySource("overridden-map", Map.of()) {
            @Override
            public String[] getPropertyNames() {
                calls.incrementAndGet();
                throw new AssertionError();
            }

            @Override
            public Object getProperty(String key) {
                calls.incrementAndGet();
                throw new AssertionError();
            }
        };
        for (var source : List.of(opaque, overridden)) {
            MockEnvironment environment = new MockEnvironment()
                    .withProperty("spring.web.error.include-message", "always")
                    .withProperty("management.endpoints.web.exposure.include", "*");
            environment.getPropertySources().addFirst(source);
            assertIndependentFindingAndIncompleteConfiguration(environment);
            assertThat(new ErrorResponseDisclosureRule()
                            .evaluate(context(null, environment))
                            .status())
                    .isEqualTo("SKIPPED");
        }
        assertThat(calls).hasValue(0);
    }

    @Test
    void fullScanNeverConvertsCustomValuesOrTraversesWrappedCustomMaps() {
        AtomicInteger calls = new AtomicInteger();
        Object customValue = new Object() {
            @Override
            public String toString() {
                calls.incrementAndGet();
                throw new AssertionError();
            }
        };
        for (String key : List.of("management.endpoints.web.exposure.include", "server.port")) {
            var environment = new MockEnvironment();
            environment.getPropertySources().addFirst(new MapPropertySource("custom-value", Map.of(key, customValue)));
            assertIndependentFindingAndIncompleteConfiguration(environment);
        }
        Map<String, Object> customMap = new java.util.AbstractMap<>() {
            @Override
            public Set<Entry<String, Object>> entrySet() {
                calls.incrementAndGet();
                throw new AssertionError();
            }

            @Override
            public Object get(Object key) {
                calls.incrementAndGet();
                throw new AssertionError();
            }
        };
        var environment = new MockEnvironment();
        environment
                .getPropertySources()
                .addFirst(
                        new MapPropertySource("wrapped-custom-map", java.util.Collections.unmodifiableMap(customMap)));
        assertIndependentFindingAndIncompleteConfiguration(environment);
        assertThat(calls).hasValue(0);
    }

    @Test
    void knownHigherConfigurationIsNotOverriddenByOpaqueLowerSource() {
        AtomicInteger calls = new AtomicInteger();
        MockEnvironment environment = new MockEnvironment().withProperty("spring.web.error.include-message", "always");
        environment
                .getPropertySources()
                .addLast(new org.springframework.core.env.PropertySource<>("opaque", new Object()) {
                    @Override
                    public Object getProperty(String key) {
                        calls.incrementAndGet();
                        throw new AssertionError();
                    }
                });
        Environment snapshot = SecurityEnvironmentSnapshot.capture(environment);
        assertThat(snapshot.getProperty("spring.web.error.include-message")).isEqualTo("always");
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                        () -> snapshot.getProperty("spring.web.error.include-stacktrace")))
                .isInstanceOf(SecurityActuatorObservation.ObservationLimitException.class);
        assertThat(calls).hasValue(0);
    }

    @Test
    void configurationPlaceholdersRemainUnknownWithoutRecursiveResolutionOrFakeAbsence() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("management.endpoints.web.exposure.include", "${external.selection}")
                .withProperty("spring.web.error.include-message", "${external.policy}");
        assertIndependentFindingAndIncompleteConfiguration(environment);
        assertThat(new ErrorResponseDisclosureRule()
                        .evaluate(context(null, environment))
                        .status())
                .isEqualTo("SKIPPED");
    }

    @SuppressWarnings("deprecation")
    private static void assertIndependentFindingAndIncompleteConfiguration(MockEnvironment environment) {
        var provider = new DaoAuthenticationProvider(username -> {
            throw new AssertionError("No user lookup");
        });
        provider.setPasswordEncoder(NoOpPasswordEncoder.getInstance());
        var factory = new DefaultListableBeanFactory();
        factory.registerSingleton(
                "springSecurityFilterChain",
                new FilterChainProxy(new DefaultSecurityFilterChain(
                        AnyRequestMatcher.INSTANCE, new BasicAuthenticationFilter(new ProviderManager(provider)))));
        SecurityReport report = scanExisting(factory, environment);
        assertThat(report.filterChainsAnalyzed()).isEqualTo(1);
        assertThat(report.results()).extracting(SecurityRuleResultDto::id).contains("SEC-AUTH-001");
        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.analysisErrors()).isEmpty();
        assertThat(SecurityActuatorObservation.observe(environment).complete()).isFalse();
        assertThat(SecurityActuatorObservation.observe(environment).errors()).isEmpty();
    }

    @Test
    void approvedRetiredIdsStayAbsentAndStaticKeyIsInfo() {
        assertThat(SecurityRuleRegistry.activeRules()).hasSize(54);
        assertThat(SecurityRuleRegistry.activeRules())
                .extracting(rule -> rule.definition().id())
                .doesNotContain(
                        "SEC-AUTH-003",
                        "SEC-AUTH-005",
                        "SEC-SESSION-005",
                        "SEC-SESSION-009",
                        "SEC-HEAD-005",
                        "SEC-HEAD-006",
                        "SEC-CORS-004");
        assertThat(new JwtStaticKeyRule().definition().severity()).isEqualTo("INFO");
    }

    private static FilterChainModel model(SecurityFilterChain chain) throws Exception {
        Method method = SecurityScanner.class.getDeclaredMethod("toChainModel", int.class, SecurityFilterChain.class);
        method.setAccessible(true);
        return (FilterChainModel) method.invoke(null, 0, chain);
    }

    @SuppressWarnings("unchecked")
    private static SecurityReport scan(DefaultListableBeanFactory factory, SecurityFilterChain... chains) {
        factory.registerSingleton("springSecurityFilterChain", new FilterChainProxy(List.of(chains)));
        return scanExisting(factory);
    }

    @SuppressWarnings("unchecked")
    private static SecurityReport scanExisting(DefaultListableBeanFactory factory) {
        return scanExisting(factory, new MockEnvironment());
    }

    @SuppressWarnings("unchecked")
    private static SecurityReport scanExisting(DefaultListableBeanFactory factory, Environment environment) {
        ObjectProvider<FilterChainProxy> proxies = mock(ObjectProvider.class);
        ObjectProvider<ListableBeanFactory> factories = mock(ObjectProvider.class);
        when(factories.getIfAvailable()).thenReturn(factory);
        return new SecurityScanner(proxies, factories, environment, Clock.systemUTC()).scan();
    }

    private static SecurityContext context(FilterChainModel chain, MockEnvironment environment) {
        return new SecurityContext(
                chain == null ? List.of() : List.of(chain),
                List.of(),
                List.of(),
                false,
                List.of(),
                false,
                false,
                false,
                false,
                List.of(),
                false,
                false,
                List.of(),
                false,
                environment);
    }

    private static SecurityContext withEvidence(SecurityContext base, SecurityContext.Evidence evidence) {
        return new SecurityContext(
                base.chains(),
                base.passwordEncoders(),
                base.corsConfigs(),
                base.corsSourcePresent(),
                base.jwtDecoderTypes(),
                base.methodSecurityEnabled(),
                base.globalMethodSecurityLegacyPresent(),
                base.methodSecurityAnnotationsPresent(),
                base.customCorsSourcePresent(),
                base.oauth2TokenValidatorTypes(),
                base.strictHttpFirewallWeakened(),
                base.hideUserNotFoundExceptionsDisabled(),
                base.opaqueTokenIntrospectorTypes(),
                base.generatedUserDetailsManagerPresent(),
                base.securityDebugFilterPresent(),
                base.environment(),
                evidence);
    }
}
