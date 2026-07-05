package io.github.jdubois.bootui.autoconfigure.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.security.SecurityModel.CorsConfigModel;
import io.github.jdubois.bootui.autoconfigure.security.SecurityModel.FilterChainModel;
import io.github.jdubois.bootui.autoconfigure.security.SecurityModel.PasswordEncoderModel;
import io.github.jdubois.bootui.core.dto.SecurityRuleResultDto;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockEnvironment;

/**
 * Focused per-rule coverage for the Phase 6 Security advisor hardening, plus the follow-up audit
 * that added SEC-AUTH-007, SEC-HEAD-008/009, and SEC-CONFIG-007.
 */
class SecurityRulesTests {

    // --- SEC-AUTH-001: plaintext encoder is CRITICAL ----------------------------------------

    @Test
    void noOpPasswordEncoderIsCritical() {
        SecurityContext context = context(
                List.of(),
                List.of(new PasswordEncoderModel(
                        "org.springframework.security.crypto.password.NoOpPasswordEncoder", null)),
                List.of(),
                List.of(),
                new MockEnvironment());

        SecurityRuleResultDto result = new NoOpPasswordEncoderRule().evaluate(context);

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.VIOLATION);
        assertThat(result.severity()).isEqualTo("CRITICAL");
    }

    // --- SEC-HEAD-007: globally disabled headers --------------------------------------------

    @Test
    void headerWritersDisabledFiresForBrowserFacingChainWithoutHeaderWriterFilter() {
        FilterChainModel chain = chain(
                "any request",
                List.of("SecurityContextHolderFilter", "UsernamePasswordAuthenticationFilter", "AuthorizationFilter"));

        SecurityRuleResultDto result = new HeaderWritersDisabledRule().evaluate(singleChain(chain));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.VIOLATION);
        assertThat(result.severity()).isEqualTo("HIGH");
    }

    @Test
    void headerWritersDisabledIgnoresPublicChainWithoutAuthentication() {
        FilterChainModel chain = chain(
                "any request",
                List.of("SecurityContextHolderFilter", "AnonymousAuthenticationFilter", "AuthorizationFilter"));

        SecurityRuleResultDto result = new HeaderWritersDisabledRule().evaluate(singleChain(chain));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.PASS);
    }

    @Test
    void headerWritersDisabledDoesNotFireWhenHeaderWriterFilterPresent() {
        FilterChainModel chain = chain(
                "any request",
                List.of(
                        "SecurityContextHolderFilter",
                        "HeaderWriterFilter",
                        "UsernamePasswordAuthenticationFilter",
                        "AuthorizationFilter"));

        SecurityRuleResultDto result = new HeaderWritersDisabledRule().evaluate(singleChain(chain));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.PASS);
    }

    // --- SEC-ACT-007: show-values=always ----------------------------------------------------

    @Test
    void actuatorShowValuesFiresForEnvAlone() {
        MockEnvironment environment =
                new MockEnvironment().withProperty("management.endpoint.env.show-values", "always");

        SecurityRuleResultDto result = new ActuatorShowValuesRule().evaluate(context(environment));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.VIOLATION);
        assertThat(result.sampleViolations()).anyMatch(detail -> detail.contains("env.show-values"));
    }

    @Test
    void actuatorShowValuesChecksConfigpropsIndependently() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("management.endpoint.env.show-values", "never")
                .withProperty("management.endpoint.configprops.show-values", "always");

        SecurityRuleResultDto result = new ActuatorShowValuesRule().evaluate(context(environment));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.VIOLATION);
        assertThat(result.sampleViolations()).anyMatch(detail -> detail.contains("configprops.show-values"));
        assertThat(result.sampleViolations()).noneMatch(detail -> detail.contains("env.show-values"));
    }

    @Test
    void actuatorShowValuesPassesWhenSanitized() {
        SecurityRuleResultDto result = new ActuatorShowValuesRule().evaluate(context(new MockEnvironment()));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.PASS);
    }

    // --- SEC-ACT-001/002/003/006: exclude-aware effective exposure --------------------------

    @Test
    void actuatorWildcardExposureFiresWithoutExclude() {
        MockEnvironment environment =
                new MockEnvironment().withProperty("management.endpoints.web.exposure.include", "*");

        SecurityRuleResultDto result = new ActuatorWildcardExposureRule().evaluate(context(environment));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.VIOLATION);
    }

    @Test
    void actuatorWildcardExposurePassesWhenExcludeCoversAllSensitiveEndpoints() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("management.endpoints.web.exposure.include", "*")
                .withProperty(
                        "management.endpoints.web.exposure.exclude",
                        "env,beans,configprops,heapdump,threaddump,shutdown,loggers,mappings");

        SecurityRuleResultDto result = new ActuatorWildcardExposureRule().evaluate(context(environment));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.PASS);
    }

    @Test
    void actuatorWildcardExposureStillFiresWhenExcludeIsPartial() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("management.endpoints.web.exposure.include", "*")
                .withProperty("management.endpoints.web.exposure.exclude", "env");

        SecurityRuleResultDto result = new ActuatorWildcardExposureRule().evaluate(context(environment));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.VIOLATION);
        assertThat(result.sampleViolations()).anyMatch(detail -> detail.contains("exclude=env"));
    }

    @Test
    void actuatorSensitiveExposureFiresForIncludedSensitiveEndpoints() {
        MockEnvironment environment =
                new MockEnvironment().withProperty("management.endpoints.web.exposure.include", "env,beans");

        SecurityRuleResultDto result = new ActuatorSensitiveExposureRule().evaluate(context(environment));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.VIOLATION);
        assertThat(result.sampleViolations()).anyMatch(detail -> detail.contains("'env'"));
        assertThat(result.sampleViolations()).anyMatch(detail -> detail.contains("'beans'"));
    }

    @Test
    void actuatorSensitiveExposurePassesWhenExcludeCoversAllSensitiveEndpoints() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("management.endpoints.web.exposure.include", "*")
                .withProperty(
                        "management.endpoints.web.exposure.exclude",
                        "env,beans,configprops,heapdump,threaddump,shutdown,loggers,mappings");

        SecurityRuleResultDto result = new ActuatorSensitiveExposureRule().evaluate(context(environment));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.PASS);
    }

    @Test
    void actuatorUnprotectedFiresWhenNoChainMatchesActuatorPath() {
        MockEnvironment environment =
                new MockEnvironment().withProperty("management.endpoints.web.exposure.include", "env");
        FilterChainModel chain = chain("any request", List.of("AuthorizationFilter"));

        SecurityRuleResultDto result = new ActuatorUnprotectedRule().evaluate(context(List.of(chain), environment));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.VIOLATION);
    }

    @Test
    void actuatorUnprotectedPassesWhenAChainMatchesTheActuatorBasePath() {
        MockEnvironment environment =
                new MockEnvironment().withProperty("management.endpoints.web.exposure.include", "env");
        FilterChainModel chain = chain("Ant [pattern='/actuator/**']", List.of("AuthorizationFilter"));

        SecurityRuleResultDto result = new ActuatorUnprotectedRule().evaluate(context(List.of(chain), environment));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.PASS);
    }

    @Test
    void actuatorUnprotectedPassesWhenExcludeCoversAllSensitiveEndpoints() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("management.endpoints.web.exposure.include", "*")
                .withProperty(
                        "management.endpoints.web.exposure.exclude",
                        "env,beans,configprops,heapdump,threaddump,shutdown,loggers,mappings");

        SecurityRuleResultDto result = new ActuatorUnprotectedRule().evaluate(context(environment));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.PASS);
    }

    @Test
    void managementPortIsolationFiresWhenNoManagementPortIsConfigured() {
        MockEnvironment environment =
                new MockEnvironment().withProperty("management.endpoints.web.exposure.include", "env");

        SecurityRuleResultDto result = new ManagementPortIsolationRule().evaluate(context(environment));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.VIOLATION);
    }

    @Test
    void managementPortIsolationPassesWhenManagementPortIsConfigured() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("management.endpoints.web.exposure.include", "env")
                .withProperty("management.server.port", "9001");

        SecurityRuleResultDto result = new ManagementPortIsolationRule().evaluate(context(environment));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.PASS);
    }

    @Test
    void managementPortIsolationPassesWhenExcludeCoversAllSensitiveEndpoints() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("management.endpoints.web.exposure.include", "*")
                .withProperty(
                        "management.endpoints.web.exposure.exclude",
                        "env,beans,configprops,heapdump,threaddump,shutdown,loggers,mappings");

        SecurityRuleResultDto result = new ManagementPortIsolationRule().evaluate(context(environment));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.PASS);
    }

    // --- SEC-ACT-004: health show-details/show-components should not be unconditional ------

    @Test
    void healthDetailsExposureFiresForShowDetailsAlways() {
        MockEnvironment environment =
                new MockEnvironment().withProperty("management.endpoint.health.show-details", "always");

        SecurityRuleResultDto result = new HealthDetailsExposureRule().evaluate(context(environment));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.VIOLATION);
        assertThat(result.sampleViolations()).anyMatch(detail -> detail.contains("show-details"));
    }

    @Test
    void healthDetailsExposureFiresForShowComponentsAlways() {
        // Spring Boot's show-components=always leaks the same infrastructure/component names as
        // show-details=always, so it must be caught independently.
        MockEnvironment environment =
                new MockEnvironment().withProperty("management.endpoint.health.show-components", "always");

        SecurityRuleResultDto result = new HealthDetailsExposureRule().evaluate(context(environment));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.VIOLATION);
        assertThat(result.sampleViolations()).anyMatch(detail -> detail.contains("show-components"));
    }

    @Test
    void healthDetailsExposureReportsBothWhenShowDetailsAndShowComponentsAreBothAlways() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("management.endpoint.health.show-details", "always")
                .withProperty("management.endpoint.health.show-components", "always");

        SecurityRuleResultDto result = new HealthDetailsExposureRule().evaluate(context(environment));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.VIOLATION);
        assertThat(result.violationCount()).isEqualTo(2);
    }

    @Test
    void healthDetailsExposurePassesWhenNeitherPropertyIsSet() {
        // Spring Boot's actual default for both properties is 'never' (not 'when-authorized', which
        // was the pre-3.0 default) -- confirm the rule does not flag the unconfigured/default case.
        SecurityRuleResultDto result = new HealthDetailsExposureRule().evaluate(context(new MockEnvironment()));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.PASS);
    }

    @Test
    void healthDetailsExposurePassesWhenAuthorizedOnly() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("management.endpoint.health.show-details", "when-authorized")
                .withProperty("management.endpoint.health.show-components", "when-authorized");

        SecurityRuleResultDto result = new HealthDetailsExposureRule().evaluate(context(environment));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.PASS);
    }

    // --- SEC-OAUTH-001: JWT and opaque-token resource servers must validate tokens ----------

    @Test
    void resourceServerValidationPassesWhenNoBearerChainIsPresent() {
        FilterChainModel chain = chain(
                "any request",
                List.of("SecurityContextHolderFilter", "UsernamePasswordAuthenticationFilter", "AuthorizationFilter"));

        SecurityRuleResultDto result = new ResourceServerValidationRule().evaluate(singleChain(chain));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.PASS);
    }

    @Test
    void resourceServerValidationFiresWhenBearerChainHasNoJwtOrOpaqueTokenConfig() {
        FilterChainModel chain = chain(
                "any request",
                List.of("SecurityContextHolderFilter", "BearerTokenAuthenticationFilter", "AuthorizationFilter"));

        SecurityRuleResultDto result =
                new ResourceServerValidationRule().evaluate(context(List.of(chain), new MockEnvironment()));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.VIOLATION);
        assertThat(result.severity()).isEqualTo("HIGH");
    }

    @Test
    void resourceServerValidationPassesWithJwtIssuerUriConfigured() {
        FilterChainModel chain = chain(
                "any request",
                List.of("SecurityContextHolderFilter", "BearerTokenAuthenticationFilter", "AuthorizationFilter"));
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri", "https://issuer.example.com");

        SecurityRuleResultDto result =
                new ResourceServerValidationRule().evaluate(context(List.of(chain), environment));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.PASS);
    }

    @Test
    void resourceServerValidationPassesWithOpaqueTokenIntrospectionUriConfigured() {
        // Verified false-positive fix: BearerTokenAuthenticationFilter is installed identically for
        // .oauth2ResourceServer(oauth2 -> oauth2.jwt(...)) and .opaqueToken(...), so an opaque-token
        // resource server configured only via the introspection-uri property must also pass.
        FilterChainModel chain = chain(
                "any request",
                List.of("SecurityContextHolderFilter", "BearerTokenAuthenticationFilter", "AuthorizationFilter"));
        MockEnvironment environment = new MockEnvironment()
                .withProperty(
                        "spring.security.oauth2.resourceserver.opaquetoken.introspection-uri",
                        "https://issuer.example.com/introspect");

        SecurityRuleResultDto result =
                new ResourceServerValidationRule().evaluate(context(List.of(chain), environment));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.PASS);
    }

    @Test
    void resourceServerValidationPassesWithOpaqueTokenIntrospectorBeanPresent() {
        FilterChainModel chain = chain(
                "any request",
                List.of("SecurityContextHolderFilter", "BearerTokenAuthenticationFilter", "AuthorizationFilter"));

        SecurityRuleResultDto result = new ResourceServerValidationRule()
                .evaluate(contextWithOpaqueTokenIntrospector(
                        List.of(chain), List.of("com.example.CustomOpaqueTokenIntrospector"), new MockEnvironment()));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.PASS);
    }

    // --- SEC-OAUTH-002: custom decoder is advisory ------------------------------------------

    @Test
    void jwtAudienceViolationIsMediumWithoutCustomDecoder() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri", "https://issuer.example.com");

        SecurityRuleResultDto result = new JwtAudienceValidationRule().evaluate(context(environment));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.VIOLATION);
        assertThat(result.severity()).isEqualTo("MEDIUM");
    }

    @Test
    void jwtAudienceWithCustomDecoderIsInfoAdvisory() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri", "https://issuer.example.com");
        SecurityContext context =
                context(List.of(), List.of(), List.of(), List.of("com.example.CustomJwtDecoder"), environment);

        SecurityRuleResultDto result = new JwtAudienceValidationRule().evaluate(context);

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.VIOLATION);
        assertThat(result.severity()).isEqualTo("INFO");
        assertThat(result.sampleViolations()).anyMatch(detail -> detail.contains("CustomJwtDecoder"));
    }

    @Test
    void jwtAudiencePassesWhenAudiencesConfigured() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri", "https://issuer.example.com")
                .withProperty("spring.security.oauth2.resourceserver.jwt.audiences", "bootui");

        SecurityRuleResultDto result = new JwtAudienceValidationRule().evaluate(context(environment));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.PASS);
    }

    @Test
    void jwtAudienceWithCustomTokenValidatorIsInfoAdvisory() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri", "https://issuer.example.com");
        SecurityContext context = context(
                environment,
                List.of("com.example.AudienceValidatingOAuth2TokenValidator"),
                false,
                false,
                List.of(),
                false);

        SecurityRuleResultDto result = new JwtAudienceValidationRule().evaluate(context);

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.VIOLATION);
        assertThat(result.severity()).isEqualTo("INFO");
        assertThat(result.sampleViolations())
                .anyMatch(detail -> detail.contains("AudienceValidatingOAuth2TokenValidator"));
    }

    // --- SEC-CONFIG-003 / SEC-CONFIG-004 removed --------------------------------------------

    @Test
    void webSecurityConfigurerAdapterRuleIsNoLongerRegistered() {
        assertThat(SecurityRuleRegistry.activeRules())
                .extracting(rule -> rule.definition().id())
                .doesNotContain("SEC-CONFIG-003");
    }

    @Test
    void webIgnoringRuleIsNoLongerRegistered() {
        assertThat(SecurityRuleRegistry.activeRules())
                .extracting(rule -> rule.definition().id())
                .doesNotContain("SEC-CONFIG-004");
    }

    // --- SEC-CORS-006: broad origin patterns ------------------------------------------------

    @Test
    void broadCorsOriginPatternFiresMediumWithoutCredentials() {
        CorsConfigModel cors =
                new CorsConfigModel("/**", List.of(), List.of("https://*"), List.of("GET"), List.of(), Boolean.FALSE);

        SecurityRuleResultDto result = new BroadCorsOriginPatternRule().evaluate(cors(cors));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.VIOLATION);
        assertThat(result.severity()).isEqualTo("MEDIUM");
    }

    @Test
    void broadCorsOriginPatternFiresHighWithCredentials() {
        CorsConfigModel cors =
                new CorsConfigModel("/**", List.of(), List.of("*://*"), List.of("GET"), List.of(), Boolean.TRUE);

        SecurityRuleResultDto result = new BroadCorsOriginPatternRule().evaluate(cors(cors));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.VIOLATION);
        assertThat(result.severity()).isEqualTo("HIGH");
    }

    @Test
    void broadCorsOriginPatternIgnoresScopedSubdomainWildcard() {
        CorsConfigModel cors = new CorsConfigModel(
                "/**", List.of(), List.of("https://*.example.com"), List.of("GET"), List.of(), Boolean.TRUE);

        SecurityRuleResultDto result = new BroadCorsOriginPatternRule().evaluate(cors(cors));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.PASS);
    }

    @Test
    void broadCorsOriginPatternDoesNotDuplicateExactWildcard() {
        CorsConfigModel cors =
                new CorsConfigModel("/**", List.of(), List.of("*"), List.of("GET"), List.of(), Boolean.TRUE);

        SecurityRuleResultDto result = new BroadCorsOriginPatternRule().evaluate(cors(cors));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.PASS);
    }

    // --- CORS rules render SKIPPED (not a silent PASS) for a non-introspectable custom source

    @Test
    void corsWildcardOriginRuleIsSkippedWhenOnlyACustomCorsSourceIsPresent() {
        SecurityRuleResultDto result = new CorsWildcardOriginRule().evaluate(customCorsSourceOnly());

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.SKIPPED);
    }

    @Test
    void corsWildcardOriginRuleStillFiresARealViolationEvenWithACustomSourceAlsoPresent() {
        CorsConfigModel cors =
                new CorsConfigModel("/**", List.of("*"), List.of(), List.of("GET"), List.of(), Boolean.FALSE);

        SecurityRuleResultDto result = new CorsWildcardOriginRule()
                .evaluate(new SecurityContext(
                        List.of(),
                        List.of(),
                        List.of(cors),
                        true,
                        List.of(),
                        false,
                        false,
                        false,
                        true,
                        List.of(),
                        false,
                        false,
                        List.of(),
                        false,
                        new MockEnvironment()));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.VIOLATION);
    }

    @Test
    void corsWildcardWithCredentialsRuleIsSkippedWhenOnlyACustomCorsSourceIsPresent() {
        SecurityRuleResultDto result = new CorsWildcardWithCredentialsRule().evaluate(customCorsSourceOnly());

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.SKIPPED);
    }

    @Test
    void corsWildcardMethodsHeadersRuleIsSkippedWhenOnlyACustomCorsSourceIsPresent() {
        SecurityRuleResultDto result = new CorsWildcardMethodsHeadersRule().evaluate(customCorsSourceOnly());

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.SKIPPED);
    }

    @Test
    void broadCorsOriginPatternRuleIsSkippedWhenOnlyACustomCorsSourceIsPresent() {
        SecurityRuleResultDto result = new BroadCorsOriginPatternRule().evaluate(customCorsSourceOnly());

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.SKIPPED);
    }

    // --- SEC-AUTHZ-002 / 003: stop over-claiming blanket permitAll --------------------------

    @Test
    void permitAllCatchAllFiresOnlyForCatchAllChainWithRealAuthentication() {
        FilterChainModel catchAll = new FilterChainModel(
                0,
                "any request",
                List.of("AnonymousAuthenticationFilter", "UsernamePasswordAuthenticationFilter", "AuthorizationFilter"),
                Boolean.TRUE,
                null,
                List.of());

        SecurityRuleResultDto result = new PermitAllCatchAllRule().evaluate(singleChain(catchAll));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.VIOLATION);
    }

    @Test
    void permitAllCatchAllIgnoresScopedChainAndAnonymousOnlyChain() {
        FilterChainModel scoped = new FilterChainModel(
                0,
                "Ant [pattern='/public/**']",
                List.of("UsernamePasswordAuthenticationFilter", "AuthorizationFilter"),
                Boolean.TRUE,
                null,
                List.of());
        FilterChainModel anonymousOnly = new FilterChainModel(
                1,
                "any request",
                List.of("AnonymousAuthenticationFilter", "AuthorizationFilter"),
                Boolean.TRUE,
                null,
                List.of());

        SecurityRuleResultDto result =
                new PermitAllCatchAllRule().evaluate(context(List.of(scoped, anonymousOnly), new MockEnvironment()));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.PASS);
    }

    @Test
    void effectivelyDisabledFiresWhenNoChainHasRealAuthentication() {
        FilterChainModel open = new FilterChainModel(
                0,
                "any request",
                List.of("AnonymousAuthenticationFilter", "AuthorizationFilter"),
                Boolean.TRUE,
                null,
                List.of());

        SecurityRuleResultDto result = new EffectivelyDisabledSecurityRule().evaluate(singleChain(open));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.VIOLATION);
    }

    @Test
    void effectivelyDisabledPassesWhenAChainRequiresAuthentication() {
        FilterChainModel open = new FilterChainModel(
                0,
                "any request",
                List.of("AnonymousAuthenticationFilter", "BasicAuthenticationFilter", "AuthorizationFilter"),
                Boolean.TRUE,
                null,
                List.of());

        SecurityRuleResultDto result = new EffectivelyDisabledSecurityRule().evaluate(singleChain(open));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.PASS);
    }

    // --- SEC-AUTHZ-005: broader matcher shadows a narrower one ------------------------------

    @Test
    void authorizationRuleShadowedFiresWhenABroaderMatcherPrecedesANarrowerOne() {
        FilterChainModel chain = new FilterChainModel(
                0,
                "any request",
                List.of("AuthorizationFilter"),
                null,
                null,
                List.of(),
                null,
                null,
                null,
                Boolean.TRUE,
                null);

        SecurityRuleResultDto result = new AuthorizationRuleShadowedRule().evaluate(singleChain(chain));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.VIOLATION);
        assertThat(result.severity()).isEqualTo("HIGH");
    }

    @Test
    void authorizationRuleShadowedPassesWhenNoShadowingWasDetected() {
        FilterChainModel chain = new FilterChainModel(
                0,
                "any request",
                List.of("AuthorizationFilter"),
                null,
                null,
                List.of(),
                null,
                null,
                null,
                Boolean.FALSE,
                null);

        SecurityRuleResultDto result = new AuthorizationRuleShadowedRule().evaluate(singleChain(chain));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.PASS);
    }

    @Test
    void authorizationRuleShadowedPassesWhenTheAuthorizationManagerCouldNotBeIntrospected() {
        FilterChainModel chain = chain("any request", List.of("AuthorizationFilter"));

        SecurityRuleResultDto result = new AuthorizationRuleShadowedRule().evaluate(singleChain(chain));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.PASS);
    }

    // --- SEC-CSRF-001: Spring Security 6 form-login statefulness ----------------------------

    @Test
    void csrfDisabledFiresForFormLoginChainWithoutSessionManagementFilter() {
        FilterChainModel formLogin = chain(
                "any request",
                List.of("SecurityContextHolderFilter", "UsernamePasswordAuthenticationFilter", "AuthorizationFilter"));

        SecurityRuleResultDto result = new CsrfDisabledStatefulRule().evaluate(singleChain(formLogin));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.VIOLATION);
    }

    @Test
    void csrfDisabledIgnoresStatelessBearerLoginChain() {
        FilterChainModel tokenLogin = chain(
                "any request",
                List.of(
                        "SecurityContextHolderFilter",
                        "UsernamePasswordAuthenticationFilter",
                        "BearerTokenAuthenticationFilter",
                        "AuthorizationFilter"));

        SecurityRuleResultDto result = new CsrfDisabledStatefulRule().evaluate(singleChain(tokenLogin));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.PASS);
    }

    // --- SEC-SESSION-008: weak remember-me signing key --------------------------------------

    @Test
    void weakRememberMeKeyFiresForAShortSigningKey() {
        FilterChainModel chain = new FilterChainModel(
                0,
                "any request",
                List.of("RememberMeAuthenticationFilter"),
                null,
                null,
                List.of(),
                null,
                null,
                null,
                null,
                8);

        SecurityRuleResultDto result = new WeakRememberMeKeyRule().evaluate(singleChain(chain));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.VIOLATION);
        assertThat(result.sampleViolations()).anyMatch(detail -> detail.contains("16"));
    }

    @Test
    void weakRememberMeKeyPassesForALongSigningKey() {
        FilterChainModel chain = new FilterChainModel(
                0,
                "any request",
                List.of("RememberMeAuthenticationFilter"),
                null,
                null,
                List.of(),
                null,
                null,
                null,
                null,
                32);

        SecurityRuleResultDto result = new WeakRememberMeKeyRule().evaluate(singleChain(chain));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.PASS);
    }

    @Test
    void weakRememberMeKeyPassesWhenNoKeyLengthWasDetected() {
        FilterChainModel chain = chain("any request", List.of("RememberMeAuthenticationFilter"));

        SecurityRuleResultDto result = new WeakRememberMeKeyRule().evaluate(singleChain(chain));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.PASS);
    }

    // --- SEC-SESSION-009: session cookie name should use a __Host-/__Secure- prefix ---------

    @Test
    void sessionCookieNamePrefixFiresForACustomNameWithoutAPrefix() {
        MockEnvironment environment =
                new MockEnvironment().withProperty("server.servlet.session.cookie.name", "MYSESSIONID");

        SecurityRuleResultDto result = new SessionCookieNamePrefixRule().evaluate(context(environment));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.VIOLATION);
        assertThat(result.severity()).isEqualTo("LOW");
        assertThat(result.sampleViolations()).anyMatch(detail -> detail.contains("MYSESSIONID"));
    }

    @Test
    void sessionCookieNamePrefixPassesWhenNoCustomNameIsConfigured() {
        // The unmodified default cookie name, JSESSIONID, is not flagged.
        SecurityRuleResultDto result = new SessionCookieNamePrefixRule().evaluate(context(new MockEnvironment()));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.PASS);
    }

    @Test
    void sessionCookieNamePrefixPassesForAHostPrefixedName() {
        MockEnvironment environment =
                new MockEnvironment().withProperty("server.servlet.session.cookie.name", "__Host-SESSION");

        SecurityRuleResultDto result = new SessionCookieNamePrefixRule().evaluate(context(environment));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.PASS);
    }

    @Test
    void sessionCookieNamePrefixPassesForASecurePrefixedName() {
        MockEnvironment environment =
                new MockEnvironment().withProperty("server.servlet.session.cookie.name", "__Secure-SESSION");

        SecurityRuleResultDto result = new SessionCookieNamePrefixRule().evaluate(context(environment));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.PASS);
    }

    // --- SEC-AUTH-007: HTTP Basic requires TLS in production -------------------------------

    @Test
    void basicAuthWithoutTlsFiresInProduction() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        FilterChainModel chain = chain("any request", List.of("BasicAuthenticationFilter", "AuthorizationFilter"));

        SecurityRuleResultDto result = new BasicAuthWithoutTlsRule().evaluate(context(List.of(chain), environment));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.VIOLATION);
        assertThat(result.severity()).isEqualTo("HIGH");
    }

    @Test
    void basicAuthWithoutTlsIsIgnoredOutsideProduction() {
        FilterChainModel chain = chain("any request", List.of("BasicAuthenticationFilter", "AuthorizationFilter"));

        SecurityRuleResultDto result = new BasicAuthWithoutTlsRule().evaluate(singleChain(chain));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.PASS);
    }

    @Test
    void basicAuthWithTlsConfiguredPasses() {
        MockEnvironment environment = new MockEnvironment().withProperty("server.ssl.enabled", "true");
        environment.setActiveProfiles("prod");
        FilterChainModel chain = chain("any request", List.of("BasicAuthenticationFilter", "AuthorizationFilter"));

        SecurityRuleResultDto result = new BasicAuthWithoutTlsRule().evaluate(context(List.of(chain), environment));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.PASS);
    }

    // --- SEC-AUTH-008: hideUserNotFoundExceptions should stay enabled -----------------------

    @Test
    void usernameEnumerationRiskFiresWhenHideUserNotFoundExceptionsIsDisabled() {
        SecurityContext context = context(new MockEnvironment(), List.of(), false, true, List.of(), false);

        SecurityRuleResultDto result = new UsernameEnumerationRiskRule().evaluate(context);

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.VIOLATION);
        assertThat(result.severity()).isEqualTo("MEDIUM");
    }

    @Test
    void usernameEnumerationRiskPassesWhenHideUserNotFoundExceptionsIsNotDisabled() {
        SecurityContext context = context(new MockEnvironment(), List.of(), false, false, List.of(), false);

        SecurityRuleResultDto result = new UsernameEnumerationRiskRule().evaluate(context);

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.PASS);
    }

    // --- SEC-AUTH-009: Spring Boot's auto-generated default user should not run in production

    @Test
    void generatedUserInProductionFiresWhenNoCustomUserDetailsServiceIsConfigured() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        SecurityContext context = context(environment, List.of(), false, false, List.of(), true);

        SecurityRuleResultDto result = new GeneratedUserInProductionRule().evaluate(context);

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.VIOLATION);
        assertThat(result.severity()).isEqualTo("HIGH");
    }

    @Test
    void generatedUserInProductionPassesOutsideProduction() {
        SecurityContext context = context(new MockEnvironment(), List.of(), false, false, List.of(), true);

        SecurityRuleResultDto result = new GeneratedUserInProductionRule().evaluate(context);

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.PASS);
    }

    @Test
    void generatedUserInProductionPassesWhenNoGeneratedUserDetailsManagerIsPresent() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        SecurityContext context = context(environment, List.of(), false, false, List.of(), false);

        SecurityRuleResultDto result = new GeneratedUserInProductionRule().evaluate(context);

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.PASS);
    }

    @Test
    void generatedUserInProductionPassesWhenSpringSecurityUserPropertiesAreExplicitlyConfigured() {
        // SEC-AUTH-004 (DefaultInMemoryUserRule) already covers an explicitly-configured static user;
        // this rule only targets the fully-default, no-configuration-at-all case.
        MockEnvironment environment = new MockEnvironment().withProperty("spring.security.user.name", "admin");
        environment.setActiveProfiles("prod");
        SecurityContext context = context(environment, List.of(), false, false, List.of(), true);

        SecurityRuleResultDto result = new GeneratedUserInProductionRule().evaluate(context);

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.PASS);
    }

    // --- SEC-AUTH-010: one-time-token login success handler should not be an inline lambda --

    @Test
    void inlineOneTimeTokenSuccessHandlerFiresWhenHandlerIsInline() {
        FilterChainModel chain = new FilterChainModel(
                0,
                "any request",
                List.of("GenerateOneTimeTokenFilter"),
                null,
                null,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                Boolean.TRUE);

        SecurityRuleResultDto result = new InlineOneTimeTokenSuccessHandlerRule().evaluate(singleChain(chain));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.VIOLATION);
        assertThat(result.severity()).isEqualTo("HIGH");
        assertThat(result.sampleViolations()).anyMatch(detail -> detail.contains("oneTimeTokenLogin()"));
    }

    @Test
    void inlineOneTimeTokenSuccessHandlerPassesWhenHandlerIsADedicatedClass() {
        FilterChainModel chain = new FilterChainModel(
                0,
                "any request",
                List.of("GenerateOneTimeTokenFilter"),
                null,
                null,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                Boolean.FALSE);

        SecurityRuleResultDto result = new InlineOneTimeTokenSuccessHandlerRule().evaluate(singleChain(chain));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.PASS);
    }

    @Test
    void inlineOneTimeTokenSuccessHandlerPassesWhenNoOneTimeTokenFilterIsPresent() {
        FilterChainModel chain = chain("any request", List.of("SecurityContextHolderFilter", "AuthorizationFilter"));

        SecurityRuleResultDto result = new InlineOneTimeTokenSuccessHandlerRule().evaluate(singleChain(chain));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.PASS);
    }

    // --- SEC-CONFIG-008: StrictHttpFirewall weakening ---------------------------------------

    @Test
    void strictHttpFirewallWeakenedFiresWhenADefaultProtectionWasReAllowed() {
        SecurityContext context = context(new MockEnvironment(), List.of(), true, false, List.of(), false);

        SecurityRuleResultDto result = new StrictHttpFirewallWeakenedRule().evaluate(context);

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.VIOLATION);
        assertThat(result.severity()).isEqualTo("HIGH");
    }

    @Test
    void strictHttpFirewallWeakenedPassesWhenDefaultsAreUnchanged() {
        SecurityContext context = context(new MockEnvironment(), List.of(), false, false, List.of(), false);

        SecurityRuleResultDto result = new StrictHttpFirewallWeakenedRule().evaluate(context);

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.PASS);
    }

    // --- SEC-CONFIG-009: Spring Security DEBUG/TRACE logging in production ------------------

    @Test
    void securityDebugLoggingFiresForDebugLevelInProduction() {
        MockEnvironment environment =
                new MockEnvironment().withProperty("logging.level.org.springframework.security", "DEBUG");
        environment.setActiveProfiles("prod");

        SecurityRuleResultDto result = new SecurityDebugLoggingProductionRule().evaluate(context(environment));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.VIOLATION);
        assertThat(result.sampleViolations()).anyMatch(detail -> detail.contains("DEBUG"));
    }

    @Test
    void securityDebugLoggingFiresForTraceLevelInProduction() {
        MockEnvironment environment =
                new MockEnvironment().withProperty("logging.level.org.springframework.security", "trace");
        environment.setActiveProfiles("production");

        SecurityRuleResultDto result = new SecurityDebugLoggingProductionRule().evaluate(context(environment));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.VIOLATION);
        assertThat(result.sampleViolations()).anyMatch(detail -> detail.contains("TRACE"));
    }

    @Test
    void securityDebugLoggingIsIgnoredOutsideProduction() {
        MockEnvironment environment =
                new MockEnvironment().withProperty("logging.level.org.springframework.security", "DEBUG");

        SecurityRuleResultDto result = new SecurityDebugLoggingProductionRule().evaluate(context(environment));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.PASS);
    }

    @Test
    void securityDebugLoggingPassesWhenLevelIsNotDebugOrTrace() {
        MockEnvironment environment =
                new MockEnvironment().withProperty("logging.level.org.springframework.security", "WARN");
        environment.setActiveProfiles("prod");

        SecurityRuleResultDto result = new SecurityDebugLoggingProductionRule().evaluate(context(environment));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.PASS);
    }

    // --- SEC-HEAD-008: weak HSTS policy ------------------------------------------------------

    @Test
    void weakHstsMaxAgeFiresViolation() {
        FilterChainModel chain = new FilterChainModel(
                0,
                "any request",
                List.of("HeaderWriterFilter"),
                null,
                null,
                List.of("HstsHeaderWriter"),
                3600L,
                Boolean.TRUE,
                null);

        SecurityRuleResultDto result = new WeakHstsPolicyRule().evaluate(singleChain(chain));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.VIOLATION);
        assertThat(result.severity()).isEqualTo("LOW");
    }

    @Test
    void hstsWithoutIncludeSubdomainsFiresViolation() {
        FilterChainModel chain = new FilterChainModel(
                0,
                "any request",
                List.of("HeaderWriterFilter"),
                null,
                null,
                List.of("HstsHeaderWriter"),
                31536000L,
                Boolean.FALSE,
                null);

        SecurityRuleResultDto result = new WeakHstsPolicyRule().evaluate(singleChain(chain));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.VIOLATION);
    }

    @Test
    void strongHstsPolicyPasses() {
        FilterChainModel chain = new FilterChainModel(
                0,
                "any request",
                List.of("HeaderWriterFilter"),
                null,
                null,
                List.of("HstsHeaderWriter"),
                31536000L,
                Boolean.TRUE,
                null);

        SecurityRuleResultDto result = new WeakHstsPolicyRule().evaluate(singleChain(chain));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.PASS);
    }

    @Test
    void weakHstsPolicyPassesWhenHstsWriterNotDetected() {
        FilterChainModel chain = chain("any request", List.of("HeaderWriterFilter"));

        SecurityRuleResultDto result = new WeakHstsPolicyRule().evaluate(singleChain(chain));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.PASS);
    }

    // --- SEC-HEAD-009: weak Content-Security-Policy -----------------------------------------

    @Test
    void weakCspWithUnsafeInlineFiresViolation() {
        FilterChainModel chain = new FilterChainModel(
                0,
                "any request",
                List.of("HeaderWriterFilter"),
                null,
                null,
                List.of("ContentSecurityPolicyHeaderWriter"),
                null,
                null,
                "default-src 'self'; script-src 'unsafe-inline'");

        SecurityRuleResultDto result = new WeakContentSecurityPolicyRule().evaluate(singleChain(chain));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.VIOLATION);
        assertThat(result.severity()).isEqualTo("MEDIUM");
    }

    @Test
    void weakCspWithWildcardScriptSrcFiresViolation() {
        FilterChainModel chain = new FilterChainModel(
                0,
                "any request",
                List.of("HeaderWriterFilter"),
                null,
                null,
                List.of("ContentSecurityPolicyHeaderWriter"),
                null,
                null,
                "default-src 'self'; script-src *");

        SecurityRuleResultDto result = new WeakContentSecurityPolicyRule().evaluate(singleChain(chain));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.VIOLATION);
    }

    @Test
    void strictCspPasses() {
        FilterChainModel chain = new FilterChainModel(
                0,
                "any request",
                List.of("HeaderWriterFilter"),
                null,
                null,
                List.of("ContentSecurityPolicyHeaderWriter"),
                null,
                null,
                "default-src 'self'; script-src 'self'; base-uri 'self'; frame-ancestors 'none'");

        SecurityRuleResultDto result = new WeakContentSecurityPolicyRule().evaluate(singleChain(chain));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.PASS);
    }

    @Test
    void weakCspIgnoresScopedSubdomainWildcard() {
        FilterChainModel chain = new FilterChainModel(
                0,
                "any request",
                List.of("HeaderWriterFilter"),
                null,
                null,
                List.of("ContentSecurityPolicyHeaderWriter"),
                null,
                null,
                "default-src 'self'; script-src https://*.example.com; base-uri 'self'; frame-ancestors 'none'");

        SecurityRuleResultDto result = new WeakContentSecurityPolicyRule().evaluate(singleChain(chain));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.PASS);
    }

    @Test
    void weakCspFiresWhenBaseUriAndFrameAncestorsAreOmitted() {
        FilterChainModel chain = new FilterChainModel(
                0,
                "any request",
                List.of("HeaderWriterFilter"),
                null,
                null,
                List.of("ContentSecurityPolicyHeaderWriter"),
                null,
                null,
                "default-src 'self'; script-src 'self'");

        SecurityRuleResultDto result = new WeakContentSecurityPolicyRule().evaluate(singleChain(chain));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.VIOLATION);
    }

    @Test
    void weakCspFiresWhenObjectSrcAndDefaultSrcAreBothOmitted() {
        FilterChainModel chain = new FilterChainModel(
                0,
                "any request",
                List.of("HeaderWriterFilter"),
                null,
                null,
                List.of("ContentSecurityPolicyHeaderWriter"),
                null,
                null,
                "script-src 'self'; base-uri 'self'; frame-ancestors 'none'");

        SecurityRuleResultDto result = new WeakContentSecurityPolicyRule().evaluate(singleChain(chain));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.VIOLATION);
    }

    @Test
    void weakCspPassesWithObjectSrcButNoDefaultSrc() {
        FilterChainModel chain = new FilterChainModel(
                0,
                "any request",
                List.of("HeaderWriterFilter"),
                null,
                null,
                List.of("ContentSecurityPolicyHeaderWriter"),
                null,
                null,
                "object-src 'none'; script-src 'self'; base-uri 'self'; frame-ancestors 'none'");

        SecurityRuleResultDto result = new WeakContentSecurityPolicyRule().evaluate(singleChain(chain));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.PASS);
    }

    // --- SEC-HEAD-010: cross-origin isolation headers ---------------------------------------

    @Test
    void crossOriginIsolationHeadersFiresWhenNeitherCoopNorCoepIsEmitted() {
        FilterChainModel chain = new FilterChainModel(
                0,
                "any request",
                List.of("HeaderWriterFilter"),
                null,
                null,
                List.of("XContentTypeOptionsHeaderWriter"));

        SecurityRuleResultDto result = new CrossOriginIsolationHeadersRule().evaluate(singleChain(chain));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.VIOLATION);
        assertThat(result.severity()).isEqualTo("INFO");
    }

    @Test
    void crossOriginIsolationHeadersPassesWhenCoopIsEmitted() {
        FilterChainModel chain = new FilterChainModel(
                0,
                "any request",
                List.of("HeaderWriterFilter"),
                null,
                null,
                List.of("CrossOriginOpenerPolicyHeaderWriter"));

        SecurityRuleResultDto result = new CrossOriginIsolationHeadersRule().evaluate(singleChain(chain));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.PASS);
    }

    @Test
    void crossOriginIsolationHeadersPassesWhenNoHeaderWriterFilterIsPresent() {
        FilterChainModel chain = chain("any request", List.of("SecurityContextHolderFilter"));

        SecurityRuleResultDto result = new CrossOriginIsolationHeadersRule().evaluate(singleChain(chain));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.PASS);
    }

    // --- SEC-CONFIG-007: hardcoded secret in configuration ----------------------------------

    @Test
    void hardcodedSecretPropertyFiresHighViolationWithoutLeakingTheValue() {
        MockEnvironment environment =
                new MockEnvironment().withProperty("spring.datasource.password", "supersecret123");

        SecurityRuleResultDto result = new HardcodedSecretPropertyRule().evaluate(context(environment));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.VIOLATION);
        assertThat(result.severity()).isEqualTo("HIGH");
        assertThat(result.sampleViolations()).anyMatch(detail -> detail.contains("spring.datasource.password"));
        assertThat(result.sampleViolations()).noneMatch(detail -> detail.contains("supersecret123"));
    }

    @Test
    void hardcodedSecretPropertyIgnoresTokenExpirationKeys() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("jwt.token.expiration", "3600")
                .withProperty("jwt.token.ttl", "3600")
                .withProperty("session.token-timeout", "1800");

        SecurityRuleResultDto result = new HardcodedSecretPropertyRule().evaluate(context(environment));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.PASS);
    }

    @Test
    void hardcodedSecretPropertyStillFiresForARealLookingApiKey() {
        MockEnvironment environment =
                new MockEnvironment().withProperty("my.api.secret-key", "sk_live_abc123def456ghi789");

        SecurityRuleResultDto result = new HardcodedSecretPropertyRule().evaluate(context(environment));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.VIOLATION);
        assertThat(result.severity()).isEqualTo("HIGH");
        assertThat(result.sampleViolations()).anyMatch(detail -> detail.contains("my.api.secret-key"));
    }

    @Test
    void hardcodedSecretPropertyIgnoresPlaceholderReferences() {
        MockEnvironment environment =
                new MockEnvironment().withProperty("spring.datasource.password", "${DB_PASSWORD}");

        SecurityRuleResultDto result = new HardcodedSecretPropertyRule().evaluate(context(environment));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.PASS);
    }

    @Test
    void hardcodedSecretPropertyIgnoresSystemPropertiesSource() {
        Properties systemProps = new Properties();
        systemProps.setProperty("some.api-key", "abc123");
        MockEnvironment environment = new MockEnvironment();
        environment
                .getPropertySources()
                .addLast(new PropertiesPropertySource(
                        StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, systemProps));

        SecurityRuleResultDto result = new HardcodedSecretPropertyRule().evaluate(context(environment));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.PASS);
    }

    @Test
    void hardcodedSecretPropertyIgnoresSystemEnvironmentSource() {
        Properties systemEnv = new Properties();
        systemEnv.setProperty("GITHUB_TOKEN", "ghp_abc123");
        MockEnvironment environment = new MockEnvironment();
        environment
                .getPropertySources()
                .addLast(new PropertiesPropertySource(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, systemEnv));

        SecurityRuleResultDto result = new HardcodedSecretPropertyRule().evaluate(context(environment));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.PASS);
    }

    @Test
    void hardcodedSecretPropertyIgnoresBootUiOwnProperties() {
        MockEnvironment environment = new MockEnvironment().withProperty("bootui.github.token", "internal-value");

        SecurityRuleResultDto result = new HardcodedSecretPropertyRule().evaluate(context(environment));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.PASS);
    }

    @Test
    void hardcodedSecretPropertyPassesWhenNoSecretLikeKeysPresent() {
        MockEnvironment environment = new MockEnvironment().withProperty("spring.application.name", "bootui-sample");

        SecurityRuleResultDto result = new HardcodedSecretPropertyRule().evaluate(context(environment));

        assertThat(result.status()).isEqualTo(SecurityRuleSupport.PASS);
    }

    // --- matchesAnyRequest catch-all detection ----------------------------------------------

    @Test
    void matchesAnyRequestRecognizesExplicitRootDoubleStarButNotScopedPatterns() {
        assertThat(chain("Ant [pattern='/**']", List.of()).matchesAnyRequest()).isTrue();
        assertThat(chain("any request", List.of()).matchesAnyRequest()).isTrue();
        assertThat(chain("Ant [pattern='/api/**']", List.of()).matchesAnyRequest())
                .isFalse();
    }

    @Test
    void matchesAnyRequestRecognizesSpringSecurity7sPathPatternRequestMatcherFormat() {
        // Spring Security 7 replaced AntPathRequestMatcher's default matcher with
        // PathPatternRequestMatcher, whose toString() is "PathPattern [/**]" or, when scoped to an
        // HTTP method, "PathPattern [GET /**]" -- both must still be recognized as a catch-all, while
        // a scoped pattern such as "PathPattern [/api/**]" must not be.
        assertThat(chain("PathPattern [/**]", List.of()).matchesAnyRequest()).isTrue();
        assertThat(chain("PathPattern [GET /**]", List.of()).matchesAnyRequest())
                .isTrue();
        assertThat(chain("PathPattern [/api/**]", List.of()).matchesAnyRequest())
                .isFalse();
    }

    // --- helpers ----------------------------------------------------------------------------

    private static FilterChainModel chain(String matcher, List<String> filters) {
        return new FilterChainModel(0, matcher, filters, null, null, List.of());
    }

    private static SecurityContext singleChain(FilterChainModel chain) {
        return context(List.of(chain), new MockEnvironment());
    }

    private static SecurityContext cors(CorsConfigModel cors) {
        return context(List.of(), List.of(), List.of(cors), List.of(), new MockEnvironment());
    }

    /**
     * A context with a {@code CorsConfigurationSource} bean present that is not a
     * {@code UrlBasedCorsConfigurationSource} -- so nothing was introspected into
     * {@code corsConfigs()} -- used to prove the CORS rules render SKIPPED rather than a silent PASS.
     */
    private static SecurityContext customCorsSourceOnly() {
        return new SecurityContext(
                List.of(),
                List.of(),
                List.of(),
                true,
                List.of(),
                false,
                false,
                false,
                true,
                List.of(),
                false,
                false,
                List.of(),
                false,
                new MockEnvironment());
    }

    private static SecurityContext context(Environment environment) {
        return context(List.of(), List.of(), List.of(), List.of(), environment);
    }

    private static SecurityContext context(List<FilterChainModel> chains, Environment environment) {
        return context(chains, List.of(), List.of(), List.of(), environment);
    }

    private static SecurityContext context(
            List<FilterChainModel> chains,
            List<PasswordEncoderModel> encoders,
            List<CorsConfigModel> corsConfigs,
            List<String> jwtDecoderTypes,
            Environment environment) {
        return new SecurityContext(
                chains,
                encoders,
                corsConfigs,
                !corsConfigs.isEmpty(),
                jwtDecoderTypes,
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

    /**
     * Full-control overload for tests that need to set the {@code oauth2TokenValidatorTypes},
     * {@code strictHttpFirewallWeakened}, {@code hideUserNotFoundExceptionsDisabled},
     * {@code opaqueTokenIntrospectorTypes}, or {@code generatedUserDetailsManagerPresent} fields
     * directly, without any filter chains, encoders, or CORS configs.
     */
    private static SecurityContext context(
            Environment environment,
            List<String> oauth2TokenValidatorTypes,
            boolean strictHttpFirewallWeakened,
            boolean hideUserNotFoundExceptionsDisabled,
            List<String> opaqueTokenIntrospectorTypes,
            boolean generatedUserDetailsManagerPresent) {
        return new SecurityContext(
                List.of(),
                List.of(),
                List.of(),
                false,
                List.of(),
                false,
                false,
                false,
                false,
                oauth2TokenValidatorTypes,
                strictHttpFirewallWeakened,
                hideUserNotFoundExceptionsDisabled,
                opaqueTokenIntrospectorTypes,
                generatedUserDetailsManagerPresent,
                environment);
    }

    /**
     * Combines custom filter chains with a set of discovered {@code OpaqueTokenIntrospector} bean
     * types -- used only to test SEC-OAUTH-001's opaque-token-bean detection path, which neither of
     * the overloads above can express together.
     */
    private static SecurityContext contextWithOpaqueTokenIntrospector(
            List<FilterChainModel> chains, List<String> opaqueTokenIntrospectorTypes, Environment environment) {
        return new SecurityContext(
                chains,
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
                opaqueTokenIntrospectorTypes,
                false,
                environment);
    }
}
