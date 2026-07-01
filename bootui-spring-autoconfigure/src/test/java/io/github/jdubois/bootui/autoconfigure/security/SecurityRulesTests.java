package io.github.jdubois.bootui.autoconfigure.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.security.SecurityModel.CorsConfigModel;
import io.github.jdubois.bootui.autoconfigure.security.SecurityModel.FilterChainModel;
import io.github.jdubois.bootui.autoconfigure.security.SecurityModel.PasswordEncoderModel;
import io.github.jdubois.bootui.core.dto.SecurityRuleResultDto;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

/** Focused per-rule coverage for the Phase 6 Security advisor hardening. */
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

    // --- SEC-CONFIG-004 removed -------------------------------------------------------------

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

    // --- matchesAnyRequest catch-all detection ----------------------------------------------

    @Test
    void matchesAnyRequestRecognizesExplicitRootDoubleStarButNotScopedPatterns() {
        assertThat(chain("Ant [pattern='/**']", List.of()).matchesAnyRequest()).isTrue();
        assertThat(chain("any request", List.of()).matchesAnyRequest()).isTrue();
        assertThat(chain("Ant [pattern='/api/**']", List.of()).matchesAnyRequest())
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
                environment);
    }
}
