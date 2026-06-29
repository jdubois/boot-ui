package io.github.jdubois.bootui.engine.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.ConfigPropertyDto;
import io.github.jdubois.bootui.core.dto.ConfigPropertySuggestionDto;
import io.github.jdubois.bootui.core.dto.ConfigReport;
import io.github.jdubois.bootui.core.dto.ProfilesReport;
import io.github.jdubois.bootui.spi.ConfigEntry;
import io.github.jdubois.bootui.spi.ConfigProvider;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import io.github.jdubois.bootui.spi.ProfileSource;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConfigServiceTests {

    private static final String OVERRIDES = "bootui-overrides";

    @Test
    void masksSecretValuesAndCountsOverridesAcrossFullSet() {
        FakeProvider provider = new FakeProvider()
                .activeProfiles("dev")
                .sources("overrides", "app")
                .overrideSource(OVERRIDES)
                .entry("app.name", "demo", "app")
                .entry("app.password", "s3cr3t", "app")
                .entry("server.port", "8080", OVERRIDES);
        ConfigService service = new ConfigService(provider, exposure(ValueExposure.MASKED, true));

        ConfigReport report = service.list(null, null, false, null, null);

        ConfigPropertyDto password = byName(report, "app.password");
        assertThat(password.masked()).isTrue();
        assertThat(password.value()).isEqualTo(SecretMasker.MASKED_VALUE);
        assertThat(byName(report, "app.name").value()).isEqualTo("demo");
        assertThat(byName(report, "server.port").override()).isTrue();
        assertThat(report.overrideCount()).isEqualTo(1);
        assertThat(report.activeProfiles()).containsExactly("dev");
    }

    @Test
    void overrideCountReflectsFullSetNotPage() {
        FakeProvider provider = new FakeProvider().overrideSource(OVERRIDES);
        for (int i = 0; i < 5; i++) {
            provider.entry("k" + i, "v", OVERRIDES);
        }
        ConfigService service = new ConfigService(provider, exposure(ValueExposure.FULL, true));

        ConfigReport report = service.list(null, null, false, 0, 2);

        assertThat(report.properties()).hasSize(2);
        assertThat(report.overrideCount()).isEqualTo(5);
    }

    @Test
    void queryMatchesMaskedValueNeverRevealingSecret() {
        FakeProvider provider = new FakeProvider().entry("app.password", "s3cr3t", "app");
        ConfigService service = new ConfigService(provider, exposure(ValueExposure.MASKED, true));

        assertThat(service.list("s3cr3t", null, false, null, null).properties()).isEmpty();
        assertThat(service.list("app.password", null, false, null, null).properties())
                .hasSize(1);
    }

    @Test
    void metadataOnlyExposureHidesAllValues() {
        FakeProvider provider = new FakeProvider().entry("app.name", "demo", "app");
        ConfigService service = new ConfigService(provider, exposure(ValueExposure.METADATA_ONLY, true));

        ConfigPropertyDto dto = byName(service.list(null, null, false, null, null), "app.name");
        assertThat(dto.value()).isNull();
        assertThat(dto.masked()).isFalse();
    }

    @Test
    void groupsProfileSourcesAndMasksValues() {
        FakeProvider provider = new FakeProvider()
                .activeProfiles("dev")
                .profileSource("application-dev.properties", "dev", new ConfigEntry("db.secret", "x", "src"));
        ConfigService service = new ConfigService(provider, exposure(ValueExposure.MASKED, true));

        ProfilesReport report = service.profiles();
        assertThat(report.activeProfiles()).containsExactly("dev");
        assertThat(report.profileSources()).hasSize(1);
        assertThat(report.profileSources().get(0).properties().get(0).value()).isEqualTo(SecretMasker.MASKED_VALUE);
    }

    private static ConfigPropertyDto byName(ConfigReport report, String name) {
        return report.properties().stream()
                .filter(p -> p.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static ExposurePolicy exposure(ValueExposure exposure, boolean mask) {
        return new ExposurePolicy() {
            @Override
            public ValueExposure valueExposure() {
                return exposure;
            }

            @Override
            public boolean maskSecrets() {
                return mask;
            }
        };
    }

    private static final class FakeProvider implements ConfigProvider {
        private final List<String> activeProfiles = new java.util.ArrayList<>();
        private final List<String> sources = new java.util.ArrayList<>();
        private final List<ConfigEntry> entries = new java.util.ArrayList<>();
        private final List<ProfileSource> profileSources = new java.util.ArrayList<>();
        private String overrideSource;

        FakeProvider activeProfiles(String... p) {
            activeProfiles.addAll(List.of(p));
            return this;
        }

        FakeProvider sources(String... s) {
            sources.addAll(List.of(s));
            return this;
        }

        FakeProvider entry(String name, Object value, String source) {
            entries.add(new ConfigEntry(name, value, source));
            return this;
        }

        FakeProvider overrideSource(String name) {
            overrideSource = name;
            return this;
        }

        FakeProvider profileSource(String sourceName, String profile, ConfigEntry... e) {
            profileSources.add(new ProfileSource(sourceName, profile, List.of(e)));
            return this;
        }

        @Override
        public List<String> activeProfiles() {
            return activeProfiles;
        }

        @Override
        public List<String> sources() {
            return sources;
        }

        @Override
        public List<ConfigEntry> entries() {
            return entries;
        }

        @Override
        public String overrideSourceName() {
            return overrideSource;
        }

        @Override
        public List<ProfileSource> profileSources() {
            return profileSources;
        }

        @Override
        public List<ConfigPropertySuggestionDto> suggestions() {
            return List.of();
        }
    }
}
