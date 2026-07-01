package io.github.jdubois.bootui.quarkus.config;

import io.github.jdubois.bootui.core.dto.ConfigPropertySuggestionDto;
import io.github.jdubois.bootui.spi.ConfigEntry;
import io.github.jdubois.bootui.spi.ConfigProvider;
import io.github.jdubois.bootui.spi.ProfileSource;
import io.smallrye.config.ConfigValue;
import io.smallrye.config.SmallRyeConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.spi.ConfigSource;

/**
 * Quarkus {@link ConfigProvider} backed by SmallRye/MicroProfile {@link SmallRyeConfig}.
 *
 * <p>The Quarkus analogue of the Spring adapter's {@code SpringConfigProvider}: it enumerates the effective
 * configuration so the shared engine {@code ConfigService} can mask, sort, filter, page and group it. Values
 * are read via {@link SmallRyeConfig#getConfigValue(String)} using the <em>raw</em> value
 * ({@link ConfigValue#getRawValue()}) so unresolved {@code ${...}} expressions never crash enumeration and
 * no expansion can leak a secret. Profile-prefixed names ({@code %dev.x}) are skipped from the flat list
 * (SmallRye already exposes the active value under the bare key) and instead grouped into
 * {@link #profileSources()} per active profile — giving the Profile Diff panel real content on Quarkus.</p>
 *
 * <p>Two concerns are intentionally degraded versus Spring: there is no runtime-overrides source
 * ({@link #overrideSourceName()} is {@code null}; the panel is read-only) and no
 * {@code spring-configuration-metadata.json}, so {@link #suggestions()} is empty.</p>
 */
@ApplicationScoped
public class QuarkusConfigProvider implements ConfigProvider {

    private final SmallRyeConfig config;

    @Inject
    public QuarkusConfigProvider(SmallRyeConfig config) {
        this.config = config;
    }

    @Override
    public List<String> activeProfiles() {
        return config.getProfiles();
    }

    @Override
    public List<String> sources() {
        List<String> names = new ArrayList<>();
        for (ConfigSource source : config.getConfigSources()) {
            names.add(source.getName());
        }
        return names;
    }

    @Override
    public List<ConfigEntry> entries() {
        Map<String, ConfigEntry> merged = new LinkedHashMap<>();
        for (String name : config.getPropertyNames()) {
            if (name == null || name.isBlank() || name.startsWith("%")) {
                continue;
            }
            if (merged.containsKey(name)) {
                continue;
            }
            ConfigValue value = config.getConfigValue(name);
            merged.put(name, new ConfigEntry(name, rawValue(value), sourceName(value)));
        }
        return new ArrayList<>(merged.values());
    }

    @Override
    public String overrideSourceName() {
        return null;
    }

    @Override
    public List<ProfileSource> profileSources() {
        List<String> activeProfiles = config.getProfiles();
        if (activeProfiles.isEmpty()) {
            return List.of();
        }
        Map<String, Map<String, ConfigEntry>> byProfile = new LinkedHashMap<>();
        for (ConfigSource source : config.getConfigSources()) {
            for (String key : source.getPropertyNames()) {
                if (key == null || !key.startsWith("%")) {
                    continue;
                }
                int dot = key.indexOf('.');
                if (dot <= 1) {
                    continue;
                }
                String profile = key.substring(1, dot);
                if (!activeProfiles.contains(profile)) {
                    continue;
                }
                String bare = key.substring(dot + 1);
                byProfile
                        .computeIfAbsent(profile, p -> new LinkedHashMap<>())
                        .putIfAbsent(bare, new ConfigEntry(bare, source.getValue(key), source.getName()));
            }
        }
        List<ProfileSource> sources = new ArrayList<>();
        for (Map.Entry<String, Map<String, ConfigEntry>> entry : byProfile.entrySet()) {
            sources.add(new ProfileSource(
                    "application.properties (%" + entry.getKey() + ")",
                    entry.getKey(),
                    new ArrayList<>(entry.getValue().values())));
        }
        return sources;
    }

    @Override
    public List<ConfigPropertySuggestionDto> suggestions() {
        return List.of();
    }

    private static String rawValue(ConfigValue value) {
        if (value == null) {
            return null;
        }
        return value.getRawValue() != null ? value.getRawValue() : value.getValue();
    }

    private static String sourceName(ConfigValue value) {
        return value == null ? null : value.getConfigSourceName();
    }
}
