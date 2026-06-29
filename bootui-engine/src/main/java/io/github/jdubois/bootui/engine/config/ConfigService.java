package io.github.jdubois.bootui.engine.config;

import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.ConfigPropertyDto;
import io.github.jdubois.bootui.core.dto.ConfigPropertySuggestionDto;
import io.github.jdubois.bootui.core.dto.ConfigReport;
import io.github.jdubois.bootui.core.dto.ProfileSourceDto;
import io.github.jdubois.bootui.core.dto.ProfilesReport;
import io.github.jdubois.bootui.engine.support.PagedList;
import io.github.jdubois.bootui.spi.ConfigEntry;
import io.github.jdubois.bootui.spi.ConfigProvider;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import io.github.jdubois.bootui.spi.ProfileSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Framework-neutral logic behind the Configuration and Profile Diff panels, shared by the Spring Boot and
 * Quarkus adapters. It reads the raw, unmasked entries from a {@link ConfigProvider} and applies every
 * display-time concern: BootUI's masking ({@link ExposurePolicy} + {@link SecretMasker}), stable ordering,
 * source/override/free-text filtering, server-side paging and profile grouping — byte-identical to the
 * original Spring {@code ConfigController}/{@code ProfileDiffController}.
 *
 * <p>Masking is applied <em>before</em> filtering and paging, so a free-text query searches the displayed
 * (masked) value and never reveals a secret. The Configuration panel masks the raw {@code Object} value and
 * Profile Diff masks {@code String.valueOf(value)} — exactly as the two controllers did — so value-pattern
 * masking stays identical for both.</p>
 */
public final class ConfigService {

    private final ConfigProvider provider;

    private final ExposurePolicy exposure;

    private final SecretMasker masker = new SecretMasker();

    public ConfigService(ConfigProvider provider, ExposurePolicy exposure) {
        this.provider = provider;
        this.exposure = exposure;
    }

    /** The masked, sorted, source/override/query-filtered and paged property list for the Configuration panel. */
    public ConfigReport list(String query, String sourceFilter, boolean overridesOnly, Integer offset, Integer limit) {
        ValueExposure valueExposure = exposure.valueExposure();
        boolean maskSecrets = exposure.maskSecrets();
        String overrideSource = provider.overrideSourceName();

        Map<String, ConfigPropertySuggestionDto> suggestions = suggestionMap();
        List<ConfigPropertyDto> sorted = new ArrayList<>();
        for (ConfigEntry entry : provider.entries()) {
            sorted.add(toDto(entry, overrideSource, suggestions, valueExposure, maskSecrets));
        }
        sorted.sort(Comparator.comparing(ConfigPropertyDto::name));

        String normalizedQuery = PagedList.normalize(query);
        String normalizedSource = sourceFilter == null ? "" : sourceFilter.trim();
        PagedList.Result<ConfigPropertyDto> page = PagedList.from(
                sorted,
                property -> matchesSource(property, normalizedSource)
                        && matchesOverrideFilter(property, overridesOnly)
                        && matchesQuery(property, normalizedQuery),
                offset,
                limit);
        return new ConfigReport(
                provider.activeProfiles(), provider.sources(), page.items(), provider.suggestions(), page.page(), (int)
                        sorted.stream().filter(ConfigPropertyDto::override).count());
    }

    /** The masked, profile-grouped configuration view for the Profile Diff panel. */
    public ProfilesReport profiles() {
        ValueExposure valueExposure = exposure.valueExposure();
        boolean maskSecrets = exposure.maskSecrets();
        List<ProfileSourceDto> profileSources = new ArrayList<>();
        for (ProfileSource source : provider.profileSources()) {
            List<ConfigPropertyDto> props = new ArrayList<>();
            for (ConfigEntry entry : source.entries()) {
                String strValue = entry.value() == null ? null : entry.value().toString();
                boolean masked = shouldMask(entry.name(), strValue, valueExposure, maskSecrets);
                String displayValue = displayValue(entry.name(), strValue, valueExposure, maskSecrets);
                props.add(new ConfigPropertyDto(
                        entry.name(), displayValue, source.sourceName(), null, masked, false, null, null));
            }
            props.sort(Comparator.comparing(ConfigPropertyDto::name, Comparator.nullsLast(String::compareTo)));
            if (!props.isEmpty()) {
                profileSources.add(new ProfileSourceDto(source.sourceName(), source.profile(), props));
            }
        }
        return new ProfilesReport(provider.activeProfiles(), profileSources);
    }

    private Map<String, ConfigPropertySuggestionDto> suggestionMap() {
        Map<String, ConfigPropertySuggestionDto> map = new LinkedHashMap<>();
        for (ConfigPropertySuggestionDto suggestion : provider.suggestions()) {
            map.put(suggestion.name(), suggestion);
        }
        return map;
    }

    private ConfigPropertyDto toDto(
            ConfigEntry entry,
            String overrideSource,
            Map<String, ConfigPropertySuggestionDto> suggestions,
            ValueExposure valueExposure,
            boolean maskSecrets) {
        boolean isOverride = overrideSource != null && overrideSource.equals(entry.source());
        ConfigPropertySuggestionDto metadata = suggestions.get(entry.name());
        Object displayValue = entry.value();
        boolean masked = false;
        if (valueExposure == ValueExposure.METADATA_ONLY) {
            displayValue = null;
        } else if (valueExposure == ValueExposure.MASKED
                && maskSecrets
                && masker.shouldMask(entry.name(), entry.value())) {
            displayValue = SecretMasker.MASKED_VALUE;
            masked = true;
        }
        return new ConfigPropertyDto(
                entry.name(),
                displayValue,
                entry.source(),
                null,
                masked,
                isOverride,
                metadata == null ? null : metadata.description(),
                metadata == null ? null : metadata.defaultValue());
    }

    private boolean matchesSource(ConfigPropertyDto property, String source) {
        return source.isEmpty() || source.equals(property.source());
    }

    private boolean matchesOverrideFilter(ConfigPropertyDto property, boolean overridesOnly) {
        return !overridesOnly || property.override();
    }

    private boolean matchesQuery(ConfigPropertyDto property, String query) {
        return PagedList.contains(property.name(), query)
                || PagedList.contains(text(property.value()), query)
                || PagedList.contains(property.description(), query)
                || PagedList.contains(text(property.defaultValue()), query);
    }

    private boolean shouldMask(String key, String value, ValueExposure valueExposure, boolean maskSecrets) {
        return valueExposure == ValueExposure.MASKED && maskSecrets && masker.shouldMask(key, value);
    }

    private String displayValue(String key, String value, ValueExposure valueExposure, boolean maskSecrets) {
        if (value == null) {
            return null;
        }
        if (valueExposure == ValueExposure.METADATA_ONLY) {
            return null;
        }
        if (shouldMask(key, value, valueExposure, maskSecrets)) {
            return SecretMasker.MASKED_VALUE;
        }
        return value;
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
