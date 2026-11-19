package io.github.jdubois.bootui.autoconfigure.config;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.ConfigOverrideResult;
import io.github.jdubois.bootui.engine.safety.ConfigurationOverridePolicy;
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

    private final BootUiExposure exposure;

    private final ConfigOverridesFileStore store;

    private final SecretMasker masker = new SecretMasker();

    public ConfigOverrideService(ConfigurableEnvironment environment, BootUiProperties properties) {
        this(environment, properties, new BootUiExposure(environment, properties));
    }

    public ConfigOverrideService(
            ConfigurableEnvironment environment, BootUiProperties properties, BootUiExposure exposure) {
        this(environment, exposure, new ConfigOverridesFileStore(resolveFile(properties)));
    }

    ConfigOverrideService(
            ConfigurableEnvironment environment, BootUiExposure exposure, ConfigOverridesFileStore store) {
        this.environment = environment;
        this.exposure = exposure;
        this.store = store;
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

    public synchronized ConfigOverrideResult put(String name, String value) {
        validateName(name);
        BootUiOverridesPropertySource src = writableSource();
        Object previous = src.getProperty(name);
        ValueExposure valueExposure = exposure.valueExposure();
        boolean maskSecrets = exposure.maskSecrets();
        String previousDisplay = previous == null ? null : displayValue(name, previous, valueExposure, maskSecrets);
        Map<String, Object> updated = new LinkedHashMap<>(src.mutableSource());
        updated.put(name, value);
        store.save(updated);
        src.put(name, value);
        String message = describeRebindCaveat();
        return new ConfigOverrideResult(
                name, displayValue(name, value, valueExposure, maskSecrets), previousDisplay, true, message);
    }

    public synchronized ConfigOverrideResult remove(String name) {
        validateName(name);
        BootUiOverridesPropertySource src = writableSource();
        ValueExposure valueExposure = exposure.valueExposure();
        boolean maskSecrets = exposure.maskSecrets();
        Object previous = src.getProperty(name);
        Map<String, Object> updated = new LinkedHashMap<>(src.mutableSource());
        updated.remove(name);
        store.save(updated);
        src.remove(name);
        String previousDisplay = previous == null ? null : displayValue(name, previous, valueExposure, maskSecrets);
        return new ConfigOverrideResult(name, null, previousDisplay, true, describeRebindCaveat());
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

    private BootUiOverridesPropertySource writableSource() {
        BootUiOverridesPropertySource source = source();
        if (source.unreadable()) {
            throw new IllegalStateException(
                    "BootUI overrides file was unreadable at startup; repair it and restart before changing overrides.");
        }
        return source;
    }

    private void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Property name must not be blank");
        }
        ConfigurationOverridePolicy.requireWritable(name);
    }

    private String displayValue(String name, Object value, ValueExposure valueExposure, boolean maskSecrets) {
        if (valueExposure == ValueExposure.METADATA_ONLY) {
            return null;
        }
        if (valueExposure == ValueExposure.MASKED && maskSecrets && masker.shouldMask(name, value)) {
            return SecretMasker.MASKED_VALUE;
        }
        return value == null ? null : String.valueOf(value);
    }

    private String describeRebindCaveat() {
        return "Override stored at " + store.file()
                + ". Already-bound @ConfigurationProperties beans may keep their previous value until restart.";
    }
}
