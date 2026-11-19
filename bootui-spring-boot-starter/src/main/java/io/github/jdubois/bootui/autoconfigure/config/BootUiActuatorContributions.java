package io.github.jdubois.bootui.autoconfigure.config;

import java.util.Map;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.ConfigurationPropertyState;
import org.springframework.boot.env.DefaultPropertiesPropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

/** Non-configuring provenance: equal-looking host defaults are not BootUI contributions. */
public final class BootUiActuatorContributions extends PropertySource<Map<String, Object>> {

    private static final String NAME = "bootui-actuator-contributions";

    BootUiActuatorContributions(Map<String, Object> contributed) {
        super(NAME, Map.copyOf(contributed));
    }

    @Override
    public Object getProperty(String name) {
        return null;
    }

    static void record(ConfigurableEnvironment environment, Map<String, Object> contributed) {
        environment.getPropertySources().addLast(new BootUiActuatorContributions(contributed));
    }

    static boolean configured(Environment environment, String key) {
        ConfigurationPropertyName name = ConfigurationPropertyName.of(key);
        for (var source : ConfigurationPropertySources.get(environment)) {
            if (source.getConfigurationProperty(name) != null
                    || source.containsDescendantOf(name) == ConfigurationPropertyState.PRESENT) {
                return true;
            }
        }
        return false;
    }

    public static boolean owns(Environment environment, String key) {
        if (!(environment instanceof ConfigurableEnvironment configurable)
                || !(configurable.getPropertySources().get(NAME) instanceof BootUiActuatorContributions contributions)
                || !contributions.getSource().containsKey(key)) {
            return false;
        }
        ConfigurationPropertyName name = ConfigurationPropertyName.of(key);
        for (var source : ConfigurationPropertySources.get(environment)) {
            var property = source.getConfigurationProperty(name);
            if (property == null && source.containsDescendantOf(name) != ConfigurationPropertyState.PRESENT) {
                continue;
            }
            return source.getUnderlyingSource() instanceof PropertySource<?> springSource
                    && DefaultPropertiesPropertySource.NAME.equals(springSource.getName())
                    && property != null
                    && contributions.getSource().get(key).equals(property.getValue());
        }
        return false;
    }
}
