package io.github.jdubois.bootui.autoconfigure.config;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * Resolves display-time exposure settings from the live Spring environment.
 *
 * <p>An invalid runtime value is reported once, not on every read, until it binds again. Log Tail resolves the
 * policy for every streamed line, and the warning is itself a captured log line, so repeating it would feed an open
 * stream its own warnings.</p>
 */
public class BootUiExposure implements ExposurePolicy {

    private static final Logger log = LoggerFactory.getLogger(BootUiExposure.class);

    private final Environment environment;

    private final BootUiProperties properties;

    private final Set<String> invalidProperties = ConcurrentHashMap.newKeySet();

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
            T value = Binder.get(environment).bind(propertyName, targetType).orElse(fallback);
            invalidProperties.remove(propertyName);
            return value;
        } catch (BindException ex) {
            if (invalidProperties.add(propertyName)) {
                log.warn("Ignoring invalid BootUI property '{}' and using the already-bound value.", propertyName, ex);
            }
            return fallback;
        }
    }
}
