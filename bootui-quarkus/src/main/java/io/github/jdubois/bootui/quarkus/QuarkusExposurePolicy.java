package io.github.jdubois.bootui.quarkus;

import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
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
 * so a typo can never disclose a secret. Like Spring's relaxed binding, {@code metadata-only} is accepted
 * for {@code METADATA_ONLY}.</p>
 *
 * <p>An invalid value is reported once, not on every read, until it parses again: Log Tail resolves the policy for
 * every streamed line, so a per-read warning would add one console line per application log line.</p>
 */
@ApplicationScoped
public class QuarkusExposurePolicy implements ExposurePolicy {

    private static final Logger LOG = Logger.getLogger(QuarkusExposurePolicy.class);

    static final String EXPOSE_VALUES_KEY = "bootui.expose-values";
    static final String MASK_SECRETS_KEY = "bootui.mask-secrets";

    private final Config config;

    private final Consumer<String> warning;

    private final Set<String> invalidKeys = ConcurrentHashMap.newKeySet();

    @Inject
    public QuarkusExposurePolicy(Config config) {
        this(config, LOG::warn);
    }

    QuarkusExposurePolicy(Config config, Consumer<String> warning) {
        this.config = config;
        this.warning = warning;
    }

    @Override
    public ValueExposure valueExposure() {
        String raw = config.getOptionalValue(EXPOSE_VALUES_KEY, String.class).orElse(null);
        if (raw == null || raw.isBlank()) {
            invalidKeys.remove(EXPOSE_VALUES_KEY);
            return ValueExposure.MASKED;
        }
        try {
            ValueExposure exposure =
                    ValueExposure.valueOf(raw.trim().replace('-', '_').toUpperCase(Locale.ROOT));
            invalidKeys.remove(EXPOSE_VALUES_KEY);
            return exposure;
        } catch (IllegalArgumentException ex) {
            warnOnce(
                    EXPOSE_VALUES_KEY,
                    "Ignoring invalid BootUI property '" + EXPOSE_VALUES_KEY + "=" + raw
                            + "'; falling back to MASKED.");
            return ValueExposure.MASKED;
        }
    }

    /**
     * Read as text and parsed here, because SmallRye's boolean converter turns an unrecognized value such as a typo
     * into {@code false}, which would switch masking off, and logs a warning on every read.
     */
    @Override
    public boolean maskSecrets() {
        String raw = config.getOptionalValue(MASK_SECRETS_KEY, String.class).orElse(null);
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        switch (value) {
            case "", "true", "yes", "on", "1" -> {
                invalidKeys.remove(MASK_SECRETS_KEY);
                return true;
            }
            case "false", "no", "off", "0" -> {
                invalidKeys.remove(MASK_SECRETS_KEY);
                return false;
            }
            default -> {
                warnOnce(
                        MASK_SECRETS_KEY,
                        "Ignoring invalid BootUI property '" + MASK_SECRETS_KEY + "=" + raw
                                + "'; falling back to true.");
                return true;
            }
        }
    }

    private void warnOnce(String key, String message) {
        if (invalidKeys.add(key)) {
            warning.accept(message);
        }
    }
}
