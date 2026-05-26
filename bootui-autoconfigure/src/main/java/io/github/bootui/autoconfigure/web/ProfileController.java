package io.github.bootui.autoconfigure.web;

import io.github.bootui.autoconfigure.BootUiProperties;
import io.github.bootui.autoconfigure.BootUiProperties.ValueExposure;
import io.github.bootui.core.BootUiDtos.ConfigPropertyDto;
import io.github.bootui.core.BootUiDtos.ProfileSourceDto;
import io.github.bootui.core.BootUiDtos.ProfilesReport;
import io.github.bootui.core.SecretMasker;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes a profile-aware view of the current configuration.
 *
 * <p>Groups properties by their profile-specific source so developers can see
 * exactly which properties are contributed by each active profile.</p>
 */
@RestController
@RequestMapping("/bootui/api/profiles")
public class ProfileController {

    private static final Pattern PROFILE_SOURCE_PATTERN =
            Pattern.compile("(?:application-|Config resource 'file [^']*application-)([\\w-]+)(?:\\.properties|\\.ya?ml)");

    private final ConfigurableEnvironment environment;
    private final BootUiProperties properties;
    private final SecretMasker masker = new SecretMasker();

    public ProfileController(ConfigurableEnvironment environment, BootUiProperties properties) {
        this.environment = environment;
        this.properties = properties;
    }

    @GetMapping
    public ProfilesReport profiles() {
        List<String> activeProfiles = Arrays.asList(environment.getActiveProfiles());
        List<ProfileSourceDto> profileSources = new ArrayList<>();

        for (PropertySource<?> source : environment.getPropertySources()) {
            String profile = extractProfile(source.getName());
            if (profile == null) {
                continue;
            }
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            List<ConfigPropertyDto> props = new ArrayList<>();
            for (String key : enumerable.getPropertyNames()) {
                Object rawValue = enumerable.getProperty(key);
                String strValue = rawValue == null ? null : rawValue.toString();
            boolean masked = shouldMask(key);
            String displayValue = displayValue(key, strValue);
                props.add(new ConfigPropertyDto(key, displayValue, source.getName(), null, masked, false, null, null));
            }
            props.sort(Comparator.comparing(ConfigPropertyDto::name, Comparator.nullsLast(String::compareTo)));
            if (!props.isEmpty()) {
                profileSources.add(new ProfileSourceDto(source.getName(), profile, props));
            }
        }

        return new ProfilesReport(activeProfiles, profileSources);
    }

    private String extractProfile(String sourceName) {
        if (sourceName == null) {
            return null;
        }
        Matcher matcher = PROFILE_SOURCE_PATTERN.matcher(sourceName);
        return matcher.find() ? matcher.group(1) : null;
    }

    private boolean shouldMask(String key) {
        return properties.getExposeValues() == ValueExposure.MASKED
                && properties.isMaskSecrets()
                && masker.isSecret(key);
    }

    private String displayValue(String key, String value) {
        if (value == null) {
            return null;
        }
        if (properties.getExposeValues() == ValueExposure.METADATA_ONLY) {
            return null;
        }
        if (shouldMask(key)) {
            return SecretMasker.MASKED_VALUE;
        }
        return value;
    }
}
