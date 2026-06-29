package io.github.jdubois.bootui.autoconfigure.config;

import io.github.jdubois.bootui.autoconfigure.web.ConfigMetadataCatalog;
import io.github.jdubois.bootui.core.dto.ConfigPropertySuggestionDto;
import io.github.jdubois.bootui.spi.ConfigEntry;
import io.github.jdubois.bootui.spi.ConfigProvider;
import io.github.jdubois.bootui.spi.ProfileSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

/**
 * Spring Boot {@link ConfigProvider} backed by the live {@link ConfigurableEnvironment}.
 *
 * <p>The Spring half of the Configuration/Profile Diff seam: it enumerates the ordered property sources
 * with first-source-wins, names the BootUI runtime-overrides source, and groups the profile-specific
 * {@code application-<profile>.{properties,yml}} sources — exactly as the original controllers did. Masking,
 * sorting, filtering, paging and profile assembly all live in the engine {@code ConfigService}. Suggestion
 * metadata is read here (Jackson is banned in the engine) via {@link ConfigMetadataCatalog}.</p>
 */
public final class SpringConfigProvider implements ConfigProvider {

    private static final Pattern PROFILE_SOURCE_PATTERN =
            Pattern.compile("application-([\\w-]++)(?:\\.properties|\\.ya?ml)");

    private final ConfigurableEnvironment environment;

    private final ConfigMetadataCatalog metadataCatalog;

    public SpringConfigProvider(ConfigurableEnvironment environment, ConfigMetadataCatalog metadataCatalog) {
        this.environment = environment;
        this.metadataCatalog = metadataCatalog;
    }

    @Override
    public List<String> activeProfiles() {
        return Arrays.asList(environment.getActiveProfiles());
    }

    @Override
    public List<String> sources() {
        List<String> sources = new ArrayList<>();
        for (PropertySource<?> source : environment.getPropertySources()) {
            sources.add(source.getName());
        }
        return sources;
    }

    @Override
    public List<ConfigEntry> entries() {
        Map<String, ConfigEntry> merged = new LinkedHashMap<>();
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> eps)) {
                continue;
            }
            for (String name : eps.getPropertyNames()) {
                if (merged.containsKey(name)) {
                    continue;
                }
                merged.put(name, new ConfigEntry(name, eps.getProperty(name), source.getName()));
            }
        }
        return new ArrayList<>(merged.values());
    }

    @Override
    public String overrideSourceName() {
        return BootUiOverridesPropertySource.NAME;
    }

    @Override
    public List<ProfileSource> profileSources() {
        List<ProfileSource> profileSources = new ArrayList<>();
        for (PropertySource<?> source : environment.getPropertySources()) {
            String profile = extractProfile(source.getName());
            if (profile == null || !(source instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            List<ConfigEntry> entries = new ArrayList<>();
            for (String key : enumerable.getPropertyNames()) {
                entries.add(new ConfigEntry(key, enumerable.getProperty(key), source.getName()));
            }
            profileSources.add(new ProfileSource(source.getName(), profile, entries));
        }
        return profileSources;
    }

    @Override
    public List<ConfigPropertySuggestionDto> suggestions() {
        return metadataCatalog.suggestions();
    }

    private String extractProfile(String sourceName) {
        if (sourceName == null) {
            return null;
        }
        Matcher matcher = PROFILE_SOURCE_PATTERN.matcher(sourceName);
        return matcher.find() ? matcher.group(1) : null;
    }
}
