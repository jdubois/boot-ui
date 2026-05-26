package io.github.jdubois.bootui.autoconfigure.config;

import io.github.jdubois.bootui.autoconfigure.BootUiActivationCondition;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;

/**
 * Contributes the Actuator endpoint exposure defaults BootUI needs for its
 * local developer panels.
 */
public class BootUiActuatorDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    public static final int ORDER = BootUiOverridesEnvironmentPostProcessor.ORDER + 5;

    static final String PROPERTY_SOURCE_NAME = "bootUiActuatorEndpointDefaults";

    static final String REQUIRED_ENDPOINTS = "health,info,beans,conditions,configprops,env,loggers,mappings,"
            + "metrics,startup,scheduledtasks";

    private static final Map<String, Object> DEFAULTS = Map.of(
            "management.endpoints.web.exposure.include", REQUIRED_ENDPOINTS,
            "management.endpoint.health.show-details", "always");

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!BootUiActivationCondition.resolve(environment, application.getClassLoader()).enabled()) {
            return;
        }

        MutablePropertySources sources = environment.getPropertySources();
        if (sources.contains(PROPERTY_SOURCE_NAME)) {
            sources.remove(PROPERTY_SOURCE_NAME);
        }
        sources.addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, DEFAULTS));
    }
}
