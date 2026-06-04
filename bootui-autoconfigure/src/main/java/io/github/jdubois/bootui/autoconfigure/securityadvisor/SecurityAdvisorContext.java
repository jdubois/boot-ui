package io.github.jdubois.bootui.autoconfigure.securityadvisor;

import io.github.jdubois.bootui.autoconfigure.securityadvisor.SecurityAdvisorModel.CorsConfigModel;
import io.github.jdubois.bootui.autoconfigure.securityadvisor.SecurityAdvisorModel.FilterChainModel;
import java.util.List;
import java.util.Locale;
import org.springframework.core.env.Environment;

/**
 * Read-only inputs handed to every Spring Security Advisor rule: the introspected filter chains and
 * security beans plus the application {@link Environment}.
 */
record SecurityAdvisorContext(
        List<FilterChainModel> chains,
        List<String> passwordEncoderTypes,
        List<CorsConfigModel> corsConfigs,
        boolean corsSourcePresent,
        List<String> jwtDecoderTypes,
        boolean methodSecurityEnabled,
        boolean globalMethodSecurityLegacyPresent,
        boolean methodSecurityAnnotationsPresent,
        boolean webSecurityConfigurerAdapterPresent,
        Environment environment) {

    SecurityAdvisorContext {
        chains = List.copyOf(chains);
        passwordEncoderTypes = List.copyOf(passwordEncoderTypes);
        corsConfigs = List.copyOf(corsConfigs);
        jwtDecoderTypes = List.copyOf(jwtDecoderTypes);
    }

    boolean hasFormOrBasicChain() {
        return chains.stream().anyMatch(FilterChainModel::isFormOrBasic);
    }

    boolean hasStatefulChain() {
        return chains.stream().anyMatch(FilterChainModel::isStateful);
    }

    boolean isResourceServerConfigured() {
        return chains.stream().anyMatch(chain -> chain.hasFilterContaining("BearerTokenAuthenticationFilter"))
                || firstProperty(
                                "spring.security.oauth2.resourceserver.jwt.issuer-uri",
                                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                                "spring.security.oauth2.resourceserver.jwt.public-key-location")
                        != null
                || !jwtDecoderTypes.isEmpty();
    }

    String firstProperty(String... keys) {
        for (String key : keys) {
            String value = environment.getProperty(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    boolean isPropertyTrue(String... keys) {
        String value = firstProperty(keys);
        return value != null && "true".equalsIgnoreCase(value);
    }

    boolean isPropertyFalse(String... keys) {
        String value = firstProperty(keys);
        return value != null && "false".equalsIgnoreCase(value);
    }

    String[] activeProfiles() {
        try {
            return environment.getActiveProfiles();
        } catch (RuntimeException ex) {
            return new String[0];
        }
    }

    boolean isProductionProfileActive() {
        for (String profile : activeProfiles()) {
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
}
