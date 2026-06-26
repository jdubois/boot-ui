package io.github.jdubois.bootui.quarkus;

import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Locale;
import org.eclipse.microprofile.config.Config;
import org.jboss.logging.Logger;

/**
 * Quarkus implementation of the framework-neutral {@link ExposurePolicy}, resolved from MicroProfile
 * Config.
 *
 * <p>This is the Quarkus analogue of the Spring adapter's {@code BootUiExposure}. It reads
 * {@code bootui.expose-values} and {@code bootui.mask-secrets} live (per call) from the injected
 * {@link Config}, so the engine masks consistently on both platforms. It <em>fails closed</em>: a
 * missing, blank, or invalid value resolves to {@link ValueExposure#MASKED} / {@code maskSecrets=true}
 * so a typo can never disclose a secret.</p>
 */
@ApplicationScoped
public class QuarkusExposurePolicy implements ExposurePolicy {

    private static final Logger LOG = Logger.getLogger(QuarkusExposurePolicy.class);

    static final String EXPOSE_VALUES_KEY = "bootui.expose-values";
    static final String MASK_SECRETS_KEY = "bootui.mask-secrets";

    private final Config config;

    @Inject
    public QuarkusExposurePolicy(Config config) {
        this.config = config;
    }

    @Override
    public ValueExposure valueExposure() {
        String raw = config.getOptionalValue(EXPOSE_VALUES_KEY, String.class).orElse(null);
        if (raw == null || raw.isBlank()) {
            return ValueExposure.MASKED;
        }
        try {
            return ValueExposure.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            LOG.warnf("Ignoring invalid BootUI property '%s=%s'; falling back to MASKED.", EXPOSE_VALUES_KEY, raw);
            return ValueExposure.MASKED;
        }
    }

    @Override
    public boolean maskSecrets() {
        try {
            return config.getOptionalValue(MASK_SECRETS_KEY, Boolean.class).orElse(Boolean.TRUE);
        } catch (IllegalArgumentException ex) {
            LOG.warnf("Ignoring invalid BootUI property '%s'; falling back to true.", MASK_SECRETS_KEY);
            return true;
        }
    }
}
