package io.github.jdubois.bootui.autoconfigure.config;

import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.DefaultPropertiesPropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

/**
 * Resolves the value the <em>host application</em> configured for a property, ignoring the actuator
 * defaults {@link BootUiActuatorDefaultsEnvironmentPostProcessor} contributes on BootUI's behalf.
 *
 * <p>BootUI widens {@code management.endpoints.web.exposure.include} and sets
 * {@code management.endpoint.health.show-details=always} (among others) so its local panels work. Advisor
 * rules, the Security scanner, and the pentest checks all report host misconfiguration, so they must not
 * see BootUI's own contribution as if the application had chosen it &mdash; otherwise every BootUI user is
 * shown a finding BootUI created.</p>
 *
 * <p>Two properties of the lookup matter and are easy to get wrong when duplicated per call site:</p>
 * <ul>
 *   <li>Spring Boot attaches a {@code configurationProperties} {@link PropertySource} at the front of the
 *       environment which resolves through every other source. Iterating property sources without skipping
 *       it returns BootUI's value under the wrong source name, defeating the filter entirely.</li>
 *   <li>BootUI's defaults are merged into the shared {@code defaultProperties} source (so any host value
 *       wins), which is therefore the only source a BootUI contribution can come from.</li>
 * </ul>
 *
 * <p>Known limitation: a host that sets the exact same value BootUI would contribute, through its own
 * {@code defaultProperties} source (for example {@code SpringApplication.setDefaultProperties}), is
 * indistinguishable from BootUI here and is treated as BootUI's contribution.</p>
 */
public final class BootUiContributedProperties {

    private BootUiContributedProperties() {}

    /**
     * Returns the trimmed value the host configured for {@code key}, or {@code null} when the key is unset,
     * blank, or set only by BootUI's own actuator defaults.
     */
    public static String hostProperty(Environment environment, String key) {
        if (environment == null || key == null) {
            return null;
        }
        if (!(environment instanceof ConfigurableEnvironment configurableEnvironment)) {
            String value = environment.getProperty(key);
            return value == null || value.isBlank() ? null : value.trim();
        }
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
            if (isBootUiContribution(propertySource, key, text)) {
                continue;
            }
            return text;
        }
        return null;
    }

    /**
     * Returns the first host-configured value among {@code keys}, or {@code null} when none is set outside
     * BootUI's own actuator defaults.
     */
    public static String firstHostProperty(Environment environment, String... keys) {
        if (keys == null) {
            return null;
        }
        for (String key : keys) {
            String value = hostProperty(environment, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static boolean isBootUiContribution(PropertySource<?> propertySource, String key, String value) {
        return DefaultPropertiesPropertySource.NAME.equals(propertySource.getName())
                && BootUiActuatorDefaultsEnvironmentPostProcessor.isBootUiActuatorDefault(key, value);
    }
}
