package io.github.jdubois.bootui.autoconfigure.securityadvisor;

import io.github.jdubois.bootui.autoconfigure.securityadvisor.SecurityAdvisorModel.CorsConfigModel;
import io.github.jdubois.bootui.autoconfigure.securityadvisor.SecurityAdvisorModel.FilterChainModel;
import io.github.jdubois.bootui.autoconfigure.securityadvisor.SecurityAdvisorModel.PasswordEncoderModel;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

/**
 * Read-only inputs handed to every Spring Security Advisor rule: the introspected filter chains and
 * security beans plus the application {@link Environment}.
 */
record SecurityAdvisorContext(
        List<FilterChainModel> chains,
        List<PasswordEncoderModel> passwordEncoders,
        List<CorsConfigModel> corsConfigs,
        boolean corsSourcePresent,
        List<String> jwtDecoderTypes,
        boolean methodSecurityEnabled,
        boolean globalMethodSecurityLegacyPresent,
        boolean methodSecurityAnnotationsPresent,
        boolean webSecurityConfigurerAdapterPresent,
        Environment environment) {
    private static final String BOOTUI_ACTUATOR_DEFAULTS_PROPERTY_SOURCE = "bootUiActuatorEndpointDefaults";

    SecurityAdvisorContext {
        chains = List.copyOf(chains);
        passwordEncoders = List.copyOf(passwordEncoders);
        corsConfigs = List.copyOf(corsConfigs);
        jwtDecoderTypes = List.copyOf(jwtDecoderTypes);
    }

    /** The fully-qualified type names of the discovered {@code PasswordEncoder} beans. */
    List<String> passwordEncoderTypes() {
        return passwordEncoders.stream().map(PasswordEncoderModel::type).toList();
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

    String firstHostProperty(String... keys) {
        if (!(environment instanceof ConfigurableEnvironment configurableEnvironment)) {
            return firstProperty(keys);
        }
        for (String key : keys) {
            String value = hostProperty(configurableEnvironment, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String hostProperty(ConfigurableEnvironment configurableEnvironment, String key) {
        for (PropertySource<?> propertySource : configurableEnvironment.getPropertySources()) {
            if (ConfigurationPropertySources.isAttachedConfigurationPropertySource(propertySource)) {
                continue;
            }
            Object value = propertySource.getProperty(key);
            if (value == null) {
                continue;
            }
            String text = value.toString().trim();
            if (text.isBlank()) {
                continue;
            }
            if (BOOTUI_ACTUATOR_DEFAULTS_PROPERTY_SOURCE.equals(propertySource.getName())) {
                return null;
            }
            return text;
        }
        return null;
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
