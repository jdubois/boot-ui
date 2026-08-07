package io.github.jdubois.bootui.engine.reactivesecurity;

import java.util.List;

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
 * @param reactiveJwtDecoderTypes legacy bean-type inventory retained for observation compatibility
 * @param oauth2TokenValidatorTypes legacy bean-type inventory retained for observation compatibility
 * @param opaqueTokenIntrospectorTypes legacy bean-type inventory retained for observation compatibility
 * @param environment precomputed, framework-neutral {@code Environment} facts
 * @param errors human-readable, non-sensitive messages describing any part of the observation that
 *     could not be collected (partial discovery); an empty list means the collection was clean
 * @param corsObservationComplete whether every discovered reactive CORS source was inspectable
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
        boolean corsObservationComplete) {

    public ReactiveSecurityObservation {
        chains = List.copyOf(chains);
        corsConfigs = List.copyOf(corsConfigs);
        reactiveJwtDecoderTypes = List.copyOf(reactiveJwtDecoderTypes);
        oauth2TokenValidatorTypes = List.copyOf(oauth2TokenValidatorTypes);
        opaqueTokenIntrospectorTypes = List.copyOf(opaqueTokenIntrospectorTypes);
        environment = environment == null ? ReactiveSecurityEnvironmentSnapshot.empty() : environment;
        errors = List.copyOf(errors);
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
