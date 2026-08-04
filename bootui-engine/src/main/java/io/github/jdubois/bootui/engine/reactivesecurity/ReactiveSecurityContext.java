package io.github.jdubois.bootui.engine.reactivesecurity;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Read-only inputs handed to every reactive Spring Security advisor rule: the observed
 * {@code SecurityWebFilterChain}s, CORS/OAuth2 facts, and the precomputed environment snapshot. Built
 * by {@link ReactiveSecurityScanner} from a {@link ReactiveSecurityObservation}.
 */
record ReactiveSecurityContext(
        List<WebFilterChainObservation> chains,
        List<CorsConfigObservation> corsConfigs,
        boolean corsSourcePresent,
        List<String> reactiveJwtDecoderTypes,
        List<String> oauth2TokenValidatorTypes,
        List<String> opaqueTokenIntrospectorTypes,
        ReactiveSecurityEnvironmentSnapshot environment) {

    private static final List<String> SENSITIVE_ACTUATOR_ENDPOINTS =
            List.of("env", "beans", "configprops", "heapdump", "threaddump", "shutdown", "loggers", "mappings");

    ReactiveSecurityContext {
        chains = List.copyOf(chains);
        corsConfigs = List.copyOf(corsConfigs);
        reactiveJwtDecoderTypes = List.copyOf(reactiveJwtDecoderTypes);
        oauth2TokenValidatorTypes = List.copyOf(oauth2TokenValidatorTypes);
        opaqueTokenIntrospectorTypes = List.copyOf(opaqueTokenIntrospectorTypes);
        environment = environment == null ? ReactiveSecurityEnvironmentSnapshot.empty() : environment;
    }

    static ReactiveSecurityContext from(ReactiveSecurityObservation observation) {
        return new ReactiveSecurityContext(
                observation.chains(),
                observation.corsConfigs(),
                observation.corsSourcePresent(),
                observation.reactiveJwtDecoderTypes(),
                observation.oauth2TokenValidatorTypes(),
                observation.opaqueTokenIntrospectorTypes(),
                observation.environment());
    }

    boolean isTlsConfigured() {
        if (environment.globalTlsConfigured()) {
            return true;
        }
        return chains.stream().anyMatch(WebFilterChainObservation::hasHttpsRedirectFilter);
    }

    private static Set<String> tokenize(String commaSeparated) {
        if (commaSeparated == null || commaSeparated.isBlank()) {
            return Set.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : commaSeparated.toLowerCase(Locale.ROOT).split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                tokens.add(trimmed);
            }
        }
        return tokens;
    }

    Set<String> effectiveSensitiveActuatorExposure() {
        String include = environment.managementExposureInclude();
        if (include == null) {
            return Set.of();
        }
        String normalized = include.trim();
        Set<String> excluded = tokenize(environment.managementExposureExclude());
        boolean wildcardInclude = normalized.equals("*");
        if (wildcardInclude && excluded.isEmpty()) {
            return Set.of();
        }
        Set<String> included = wildcardInclude ? Set.of() : tokenize(normalized);
        Set<String> exposed = new LinkedHashSet<>();
        for (String sensitive : SENSITIVE_ACTUATOR_ENDPOINTS) {
            boolean isIncluded = wildcardInclude || included.contains(sensitive);
            if (isIncluded && !excluded.contains(sensitive)) {
                exposed.add(sensitive);
            }
        }
        return exposed;
    }

    boolean exposesBeyondHealthAndInfo() {
        String include = environment.managementExposureInclude();
        if (include == null) {
            return false;
        }
        String normalized = include.toLowerCase(Locale.ROOT).trim();
        Set<String> excluded = tokenize(environment.managementExposureExclude());
        if (normalized.equals("*")) {
            if (excluded.isEmpty()) {
                return true;
            }
            return !excluded.containsAll(SENSITIVE_ACTUATOR_ENDPOINTS);
        }
        for (String token : normalized.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty() || trimmed.equals("health") || trimmed.equals("info")) {
                continue;
            }
            if (!excluded.contains(trimmed)) {
                return true;
            }
        }
        return false;
    }

    boolean isProductionProfileActive() {
        for (String profile : environment.activeProfiles()) {
            if (profile == null) {
                continue;
            }
            String normalized = profile.toLowerCase(Locale.ROOT);
            if (normalized.equals("prod")
                    || normalized.equals("production")
                    || normalized.equals("staging")
                    || normalized.startsWith("prod-")
                    || normalized.endsWith("-prod")
                    || normalized.endsWith("-production")) {
                return true;
            }
        }
        return false;
    }

    Set<String> suspectedHardcodedSecretKeys() {
        return environment.suspectedHardcodedSecretKeys();
    }
}
