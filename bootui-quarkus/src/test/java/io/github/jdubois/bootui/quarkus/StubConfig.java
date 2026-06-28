package io.github.jdubois.bootui.quarkus;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.ConfigValue;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.eclipse.microprofile.config.spi.Converter;

/**
 * Minimal {@link org.eclipse.microprofile.config.Config} test double backed by a {@code Map<String,String>}.
 *
 * <p>Supports the {@code getOptionalValue(key, type)} reads BootUI's runtime classes perform — {@code String},
 * {@code Boolean} and {@code Integer} — so unit tests can drive {@link QuarkusPanelAvailability} and
 * {@code QuarkusHibernatePropertyLookup} without booting Quarkus. Other {@code Config} surface that no
 * unit-tested path uses (profiles via {@code unwrap}, config sources, converters) is intentionally
 * unimplemented; those paths are covered by the {@code @QuarkusTest} integration tests instead.</p>
 */
public final class StubConfig implements org.eclipse.microprofile.config.Config {

    private final Map<String, String> values;

    public StubConfig(Map<String, String> values) {
        this.values = new LinkedHashMap<>(values);
    }

    public static StubConfig empty() {
        return new StubConfig(Map.of());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> getOptionalValue(String propertyName, Class<T> propertyType) {
        String raw = values.get(propertyName);
        if (raw == null) {
            return Optional.empty();
        }
        if (propertyType == String.class) {
            return Optional.of((T) raw);
        }
        if (propertyType == Boolean.class || propertyType == boolean.class) {
            return Optional.of((T) Boolean.valueOf(raw));
        }
        if (propertyType == Integer.class || propertyType == int.class) {
            return Optional.of((T) Integer.valueOf(raw.trim()));
        }
        throw new IllegalArgumentException("StubConfig does not convert to " + propertyType);
    }

    @Override
    public <T> T getValue(String propertyName, Class<T> propertyType) {
        return getOptionalValue(propertyName, propertyType)
                .orElseThrow(() -> new java.util.NoSuchElementException(propertyName));
    }

    @Override
    public ConfigValue getConfigValue(String propertyName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Iterable<String> getPropertyNames() {
        return Collections.unmodifiableSet(values.keySet());
    }

    @Override
    public Iterable<ConfigSource> getConfigSources() {
        return Collections.emptyList();
    }

    @Override
    public <T> Optional<Converter<T>> getConverter(Class<T> forType) {
        return Optional.empty();
    }

    @Override
    public <T> T unwrap(Class<T> type) {
        throw new UnsupportedOperationException("StubConfig cannot unwrap " + type);
    }
}
