package io.github.bootui.autoconfigure.config;

import io.github.bootui.autoconfigure.BootUiProperties;
import io.github.bootui.autoconfigure.BootUiProperties.ValueExposure;
import io.github.bootui.core.BootUiDtos.ConfigOverrideResult;
import io.github.bootui.core.SecretMasker;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;

/**
 * Reads, writes, and persists BootUI runtime configuration overrides.
 *
 * <p>The store mutates the {@link BootUiOverridesPropertySource} that was added
 * to the Spring {@link org.springframework.core.env.Environment Environment} by
 * {@link BootUiOverridesEnvironmentPostProcessor} so changes are visible to
 * {@link org.springframework.core.env.Environment#getProperty(String)} immediately.
 * Persistence is delegated to {@link ConfigOverridesFileStore}.</p>
 *
 * <p>Already-bound {@code @ConfigurationProperties} beans are not automatically
 * re-bound. Callers may use {@link ConfigOverrideResult#message()} to inform the
 * developer when a restart may be required.</p>
 */
public class ConfigOverrideService {

    private final ConfigurableEnvironment environment;

    private final BootUiProperties properties;

    private final ConfigOverridesFileStore store;

    private final SecretMasker masker = new SecretMasker();

    public ConfigOverrideService(ConfigurableEnvironment environment, BootUiProperties properties) {
        this.environment = environment;
        this.properties = properties;
        this.store = new ConfigOverridesFileStore(resolveFile(properties));
    }

    private static Path resolveFile(BootUiProperties properties) {
        String value = properties.getOverridesFile();
        return Paths.get(value != null && !value.isBlank() ? value : ".bootui/application-bootui.properties");
    }

    public Path overridesFile() {
        return store.file();
    }

    public Map<String, Object> overrides() {
        return new LinkedHashMap<>(source().mutableSource());
    }

    public boolean hasOverride(String name) {
        return source().containsProperty(name);
    }

    public ConfigOverrideResult put(String name, String value) {
        validateName(name);
        BootUiOverridesPropertySource src = source();
        Object previous = src.getProperty(name);
        String previousDisplay = previous == null
                ? null
                : displayValue(name, previous);
        src.put(name, value);
        store.save(src.mutableSource());
        String message = describeRebindCaveat(name);
        return new ConfigOverrideResult(name,
                displayValue(name, value),
                previousDisplay,
                true,
                message);
    }

    public ConfigOverrideResult remove(String name) {
        validateName(name);
        BootUiOverridesPropertySource src = source();
        Object previous = src.remove(name);
        store.save(src.mutableSource());
        String previousDisplay = previous == null ? null : displayValue(name, previous);
        return new ConfigOverrideResult(name, null, previousDisplay, true,
                describeRebindCaveat(name));
    }

    private BootUiOverridesPropertySource source() {
        MutablePropertySources sources = environment.getPropertySources();
        if (sources.contains(BootUiOverridesPropertySource.NAME)) {
            return (BootUiOverridesPropertySource) sources.get(BootUiOverridesPropertySource.NAME);
        }
        BootUiOverridesPropertySource created = new BootUiOverridesPropertySource();
        sources.addFirst(created);
        return created;
    }

    private void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Property name must not be blank");
        }
    }

    private String displayValue(String name, Object value) {
        if (properties.getExposeValues() == ValueExposure.METADATA_ONLY) {
            return null;
        }
        if (properties.getExposeValues() == ValueExposure.MASKED
                && properties.isMaskSecrets()
                && masker.isSecret(name)) {
            return SecretMasker.MASKED_VALUE;
        }
        return value == null ? null : String.valueOf(value);
    }

    private String describeRebindCaveat(String name) {
        return "Override stored at " + store.file()
                + ". Already-bound @ConfigurationProperties beans may keep their previous value until restart.";
    }
}
