package io.github.jdubois.bootui.autoconfigure.config;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * Resolves display-time exposure settings from the live Spring environment.
 */
public class BootUiExposure implements ExposurePolicy {

    private static final Logger log = LoggerFactory.getLogger(BootUiExposure.class);

    private final Environment environment;

    private final BootUiProperties properties;

    public BootUiExposure(Environment environment, BootUiProperties properties) {
        this.environment = environment;
        this.properties = properties;
    }

    public BootUiExposure(BootUiProperties properties) {
        this(null, properties);
    }

    @Override
    public ValueExposure valueExposure() {
        return bind("bootui.expose-values", ValueExposure.class, properties.getExposeValues(), ValueExposure.MASKED);
    }

    @Override
    public boolean maskSecrets() {
        return bind("bootui.mask-secrets", Boolean.class, properties.isMaskSecrets(), true);
    }

    private <T> T bind(String propertyName, Class<T> targetType, T boundFallback, T safeFallback) {
        T fallback = boundFallback == null ? safeFallback : boundFallback;
        if (environment == null) {
            return fallback;
        }
        try {
            return Binder.get(environment).bind(propertyName, targetType).orElse(fallback);
        } catch (BindException ex) {
            log.warn("Ignoring invalid BootUI property '{}' and using the already-bound value.", propertyName, ex);
            return fallback;
        }
    }
}
