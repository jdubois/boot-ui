package io.github.jdubois.bootui.engine.reactivesecurity;

import java.util.List;
import java.util.Set;

/**
 * Framework-neutral observation of the host application's reactive (WebFlux) Spring Security
 * configuration.
 *
 * <p>The Spring adapter collects this snapshot — the application's registered
 * {@code SecurityWebFilterChain} beans (BootUI's own {@code bootUiReactiveSecurityWebFilterChain} is
 * excluded, mirroring the raw Spring Security panel's availability check), CORS configuration sources,
 * and a precomputed {@link ReactiveSecurityEnvironmentSnapshot} — and supplies it to {@link
 * ReactiveSecurityScanner} through a {@code Supplier} seam. The engine owns the rule evaluation; it
 * never reflects into a Spring bean or reads an {@code Environment} itself.
 *
 * @param chains observations of the application's own {@code SecurityWebFilterChain} beans
 * @param corsConfigs observed reactive CORS configuration entries
 * @param corsSourcePresent whether a {@code CorsConfigurationSource} bean is present at all (as
 *     opposed to being present but empty)
 * @param reactiveJwtDecoderTypes active {@code ReactiveJwtDecoder} class names observed on the
 *     application's reactive chains
 * @param oauth2TokenValidatorTypes legacy bean-type inventory retained for observation compatibility
 * @param opaqueTokenIntrospectorTypes legacy bean-type inventory retained for observation compatibility
 * @param environment precomputed, framework-neutral {@code Environment} facts
 * @param errors human-readable, non-sensitive messages describing any part of the observation that
 *     could not be collected (partial discovery); an empty list means the collection was clean
 * @param corsObservationComplete whether every discovered reactive CORS source was inspectable
 * @param passwordEncoders password encoders configured on active
 *     {@code UserDetailsRepositoryReactiveAuthenticationManager} instances
 * @param passwordEncodersObserved whether all active reactive password encoders were inspectable
 * @param enabledMethodFamilies method-security annotation families with recognized reactive activation
 * @param usedMethodFamilies method-security annotation families present on application beans
 * @param methodFamiliesKnown whether method-security annotation and activation metadata is complete
 */
public record ReactiveSecurityObservation(
        List<WebFilterChainObservation> chains,
        List<CorsConfigObservation> corsConfigs,
        boolean corsSourcePresent,
        List<String> reactiveJwtDecoderTypes,
        List<String> oauth2TokenValidatorTypes,
        List<String> opaqueTokenIntrospectorTypes,
        ReactiveSecurityEnvironmentSnapshot environment,
        List<String> errors,
        boolean corsObservationComplete,
        List<PasswordEncoderObservation> passwordEncoders,
        boolean passwordEncodersObserved,
        Set<String> enabledMethodFamilies,
        Set<String> usedMethodFamilies,
        boolean methodFamiliesKnown) {

    /**
     * One resolved {@code PasswordEncoder} on an active {@code
     * UserDetailsRepositoryReactiveAuthenticationManager}: its fully-qualified class name plus, when
     * the encoder is a {@code BCryptPasswordEncoder}, its configured strength ({@code -1} represents
     * the framework default of 10).
     */
    public record PasswordEncoderObservation(String type, Integer bcryptStrength) {}

    public ReactiveSecurityObservation {
        chains = List.copyOf(chains);
        corsConfigs = List.copyOf(corsConfigs);
        reactiveJwtDecoderTypes = List.copyOf(reactiveJwtDecoderTypes);
        oauth2TokenValidatorTypes = List.copyOf(oauth2TokenValidatorTypes);
        opaqueTokenIntrospectorTypes = List.copyOf(opaqueTokenIntrospectorTypes);
        environment = environment == null ? ReactiveSecurityEnvironmentSnapshot.empty() : environment;
        errors = List.copyOf(errors);
        passwordEncoders = passwordEncoders == null ? List.of() : List.copyOf(passwordEncoders);
        enabledMethodFamilies = enabledMethodFamilies == null ? Set.of() : Set.copyOf(enabledMethodFamilies);
        usedMethodFamilies = usedMethodFamilies == null ? Set.of() : Set.copyOf(usedMethodFamilies);
    }

    /** Compatibility constructor for observations created before reactive encoder and method-security signals. */
    public ReactiveSecurityObservation(
            List<WebFilterChainObservation> chains,
            List<CorsConfigObservation> corsConfigs,
            boolean corsSourcePresent,
            List<String> reactiveJwtDecoderTypes,
            List<String> oauth2TokenValidatorTypes,
            List<String> opaqueTokenIntrospectorTypes,
            ReactiveSecurityEnvironmentSnapshot environment,
            List<String> errors,
            boolean corsObservationComplete) {
        this(
                chains,
                corsConfigs,
                corsSourcePresent,
                reactiveJwtDecoderTypes,
                oauth2TokenValidatorTypes,
                opaqueTokenIntrospectorTypes,
                environment,
                errors,
                corsObservationComplete,
                List.of(),
                true,
                Set.of(),
                Set.of(),
                true);
    }

    /** Compatibility constructor for observations created before CORS extraction became tri-state. */
    public ReactiveSecurityObservation(
            List<WebFilterChainObservation> chains,
            List<CorsConfigObservation> corsConfigs,
            boolean corsSourcePresent,
            List<String> reactiveJwtDecoderTypes,
            List<String> oauth2TokenValidatorTypes,
            List<String> opaqueTokenIntrospectorTypes,
            ReactiveSecurityEnvironmentSnapshot environment,
            List<String> errors) {
        this(
                chains,
                corsConfigs,
                corsSourcePresent,
                reactiveJwtDecoderTypes,
                oauth2TokenValidatorTypes,
                opaqueTokenIntrospectorTypes,
                environment,
                errors,
                true);
    }

    /** An observation with no application chains and no errors (e.g. Spring Security not present). */
    public static ReactiveSecurityObservation empty() {
        return new ReactiveSecurityObservation(
                List.of(),
                List.of(),
                false,
                List.of(),
                List.of(),
                List.of(),
                ReactiveSecurityEnvironmentSnapshot.empty(),
                List.of());
    }
}
