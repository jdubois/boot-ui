package io.github.jdubois.bootui.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.PanelDto;
import io.github.jdubois.bootui.core.dto.StartupStepDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.TypeReference;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.boot.test.context.FilteredClassLoader;

class BootUiRuntimeHintsTests {

    private final RuntimeHints hints = new RuntimeHints();

    BootUiRuntimeHintsTests() {
        new BootUiRuntimeHints().registerHints(hints, getClass().getClassLoader());
    }

    @Test
    void registersClasspathResourcePatternsScannedAtRuntime() {
        assertThat(RuntimeHintsPredicates.resource().forResource("META-INF/maven/group/artifact/pom.properties"))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.resource().forResource("META-INF/spring-configuration-metadata.json"))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.resource().forResource("bootui-version.properties"))
                .accepts(hints);
        // The Vulnerabilities panel reads the application's CycloneDX SBOM; without these the native image
        // loses the only source of coordinates for JARs published with no Maven descriptor.
        assertThat(RuntimeHintsPredicates.resource().forResource("META-INF/sbom/application.cdx.json"))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.resource().forResource("META-INF/sbom/bom.json"))
                .accepts(hints);
    }

    @Test
    void registersHotSpotDiagnosticMxBeanForReflectiveHeapDumps() {
        assertThat(RuntimeHintsPredicates.reflection()
                        .onType(TypeReference.of("com.sun.management.HotSpotDiagnosticMXBean"))
                        .withMemberCategory(MemberCategory.INVOKE_PUBLIC_METHODS))
                .accepts(hints);
    }

    @Test
    void registersSpringSecurityTypesInvokedReflectively() {
        assertThat(RuntimeHintsPredicates.reflection()
                        .onType(TypeReference.of("org.springframework.security.web.SecurityFilterChain"))
                        .withMemberCategory(MemberCategory.INVOKE_PUBLIC_METHODS))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection()
                        .onType(TypeReference.of("org.springframework.security.core.GrantedAuthority"))
                        .withMemberCategory(MemberCategory.INVOKE_PUBLIC_METHODS))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection()
                        .onType(TypeReference.of("org.springframework.security.core.authority.SimpleGrantedAuthority"))
                        .withMemberCategory(MemberCategory.INVOKE_PUBLIC_METHODS))
                .accepts(hints);
    }

    @Test
    @SuppressWarnings("removal")
    void permitsQueryingTheExactAotSecurityFactoryMethodWithoutPermittingInvocation() throws Exception {
        var factoryMethod = BootUiSpringSecurityAutoConfiguration.class.getDeclaredMethod(
                "bootUiSecurityFilterChain",
                Class.forName("org.springframework.security.config.annotation.web.builders.HttpSecurity"),
                BootUiProperties.class);

        assertThat(RuntimeHintsPredicates.reflection().onMethod(factoryMethod).introspect())
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection().onMethodInvocation(factoryMethod))
                .rejects(hints);
    }

    @ParameterizedTest
    @CsvSource({
        "org.springframework.security.web.FilterChainProxy, filterChains",
        "org.springframework.security.web.FilterChainProxy, firewall",
        "org.springframework.security.web.DefaultSecurityFilterChain, filters",
        "org.springframework.security.web.DefaultSecurityFilterChain, requestMatcher",
        "org.springframework.security.config.annotation.web.configuration.WebSecurityConfiguration$CompositeFilterChainProxy, springSecurityFilterChain",
        "org.springframework.security.web.authentication.AbstractAuthenticationProcessingFilter, sessionStrategy",
        "org.springframework.security.web.authentication.www.BasicAuthenticationFilter, authenticationManager",
        "org.springframework.security.web.access.intercept.AuthorizationFilter, authorizationManager",
        "org.springframework.security.web.access.intercept.RequestMatcherDelegatingAuthorizationManager, mappings",
        "org.springframework.security.authorization.ObservationAuthorizationManager, delegate",
        "org.springframework.security.authorization.SingleResultAuthorizationManager, result",
        "org.springframework.security.authorization.AuthorizationDecision, granted",
        "org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher, pattern",
        "org.springframework.web.util.pattern.PathPattern, caseSensitive",
        "org.springframework.security.web.header.HeaderWriterFilter, headerWriters",
        "org.springframework.security.web.header.writers.HstsHeaderWriter, maxAgeInSeconds",
        "org.springframework.security.web.csrf.CsrfFilter, requireCsrfProtectionMatcher",
        "org.springframework.security.authentication.ProviderManager, providers",
        "org.springframework.security.authentication.dao.DaoAuthenticationProvider, passwordEncoder",
        "org.springframework.security.authentication.dao.AbstractUserDetailsAuthenticationProvider, hideUserNotFoundExceptions",
        "org.springframework.util.function.SingletonSupplier, singletonInstance",
        "org.springframework.security.crypto.password.DelegatingPasswordEncoder, passwordEncoderForEncode",
        "org.springframework.security.web.server.MatcherSecurityWebFilterChain, filters",
        "org.springframework.security.web.server.MatcherSecurityWebFilterChain, matcher",
        "org.springframework.security.web.server.header.HttpHeaderWriterWebFilter, writer",
        "org.springframework.security.web.server.header.CompositeServerHttpHeadersWriter, writers",
        "org.springframework.security.web.server.header.ContentSecurityPolicyServerHttpHeadersWriter, delegate",
        "org.springframework.core.env.AbstractEnvironment, propertySources",
        "org.springframework.core.env.AbstractEnvironment, activeProfiles",
        "org.springframework.core.env.MutablePropertySources, propertySourceList",
        "org.springframework.core.env.PropertySource, source",
        "org.springframework.boot.origin.OriginTrackedValue, value",
        "java.util.Collections$UnmodifiableMap, m",
        "org.apache.catalina.core.ApplicationContextFacade, context",
        "org.apache.catalina.core.ApplicationContext, parameters",
        "org.springframework.boot.webmvc.actuate.endpoint.web.AbstractWebMvcEndpointHandlerMapping, endpoints",
        "org.springframework.boot.actuate.endpoint.AbstractExposableEndpoint, operations",
        "org.springframework.boot.actuate.endpoint.web.annotation.DiscoveredWebOperation, requestPredicate"
    })
    void registersFieldsReadByPassiveSecurityObservations(String type, String name) throws Exception {
        var field = Class.forName(type).getDeclaredField(name);
        assertThat(RuntimeHintsPredicates.reflection().onFieldAccess(field)).accepts(hints);
        // The previous method-only hints cannot make any of these private observations available.
        RuntimeHints methodOnly = new RuntimeHints();
        methodOnly.reflection().registerType(field.getDeclaringClass(), MemberCategory.INVOKE_PUBLIC_METHODS);
        assertThat(RuntimeHintsPredicates.reflection().onFieldAccess(field)).rejects(methodOnly);
    }

    @Test
    void securityFieldHintsRemainSafeWithoutOptionalServletOrSecurityDependencies() {
        RuntimeHints filteredHints = new RuntimeHints();
        new BootUiRuntimeHints()
                .registerHints(
                        filteredHints,
                        new FilteredClassLoader(
                                "org.springframework.security",
                                "jakarta.servlet",
                                "org.apache.catalina",
                                "org.springframework.boot.webmvc"));
        assertThat(filteredHints.reflection().typeHints())
                .noneMatch(hint -> hint.getType().getName().startsWith("org.springframework.security.")
                        || hint.getType().getName().startsWith("org.apache.catalina."));
        assertThat(RuntimeHintsPredicates.reflection()
                        .onType(TypeReference.of("org.springframework.core.env.AbstractEnvironment"))
                        .withMemberCategory(MemberCategory.ACCESS_DECLARED_FIELDS))
                .accepts(filteredHints);
    }

    @Test
    void registersBootUiDtosAndArrayTypesForJacksonBinding() {
        assertThat(RuntimeHintsPredicates.reflection()
                        .onType(StartupStepDto.class)
                        .withMemberCategory(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection().onType(TypeReference.of(StartupStepDto[].class)))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection().onType(TypeReference.of(PanelDto[].class)))
                .accepts(hints);
    }

    @Test
    void registersActuatorMappingsExpressionDescriptionArrayTypesForJacksonSerialization() {
        String mediaType =
                "org.springframework.boot.webmvc.actuate.web.mappings.RequestMappingConditionsDescription$MediaTypeExpressionDescription";
        String nameValue =
                "org.springframework.boot.webmvc.actuate.web.mappings.RequestMappingConditionsDescription$NameValueExpressionDescription";
        for (String descriptionType : new String[] {mediaType, nameValue}) {
            assertThat(RuntimeHintsPredicates.reflection()
                            .onType(TypeReference.of(descriptionType))
                            .withMemberCategory(MemberCategory.INVOKE_PUBLIC_METHODS))
                    .accepts(hints);
            assertThat(RuntimeHintsPredicates.reflection().onType(TypeReference.of(descriptionType + "[]")))
                    .accepts(hints);
        }
    }
}
