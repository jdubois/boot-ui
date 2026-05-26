package io.github.jdubois.bootui.autoconfigure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties.ValueExposure;
import io.github.jdubois.bootui.core.BootUiDtos.ConfigOverrideResult;
import io.github.jdubois.bootui.core.SecretMasker;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class ConfigOverrideServiceTests {

    @TempDir
    Path tmp;

    private BootUiProperties properties;

    private MockEnvironment environment;

    private ConfigOverrideService service;

    @BeforeEach
    void setUp() {
        properties = new BootUiProperties();
        properties.setOverridesFile(tmp.resolve("overrides.properties").toString());

        environment = new MockEnvironment();
        service = new ConfigOverrideService(environment, properties);
    }

    @Test
    void putRegistersPropertySourceAndPersistsToDisk() {
        ConfigOverrideResult result = service.put("server.port", "9090");

        assertThat(result.name()).isEqualTo("server.port");
        assertThat(result.value()).isEqualTo("9090");
        assertThat(result.persisted()).isTrue();
        assertThat(result.previousValue()).isNull();
        assertThat(result.message()).contains("restart");

        assertThat(environment.getProperty("server.port")).isEqualTo("9090");
        assertThat(Files.exists(service.overridesFile())).isTrue();
        assertThat(service.hasOverride("server.port")).isTrue();
    }

    @Test
    void putReturnsPreviousDisplayedValue() {
        service.put("server.port", "9090");

        ConfigOverrideResult result = service.put("server.port", "9091");

        assertThat(result.previousValue()).isEqualTo("9090");
        assertThat(result.value()).isEqualTo("9091");
    }

    @Test
    void putMasksSecretValuesInResult() {
        ConfigOverrideResult result = service.put("spring.datasource.password", "hunter2");

        assertThat(result.value()).isEqualTo(SecretMasker.MASKED_VALUE);
        assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("hunter2");
    }

    @Test
    void putDoesNotMaskWhenMaskingDisabled() {
        properties.setMaskSecrets(false);

        ConfigOverrideResult result = service.put("api.token", "abc");

        assertThat(result.value()).isEqualTo("abc");
    }

    @Test
    void putHidesValuesUnderMetadataOnlyExposure() {
        properties.setExposeValues(ValueExposure.METADATA_ONLY);

        ConfigOverrideResult result = service.put("server.port", "9090");

        assertThat(result.value()).isNull();
    }

    @Test
    void putExposesSecretValuesUnderFullExposure() {
        properties.setExposeValues(ValueExposure.FULL);

        ConfigOverrideResult result = service.put("api.token", "abc");

        assertThat(result.value()).isEqualTo("abc");
    }

    @Test
    void putBlankNameThrows() {
        assertThatThrownBy(() -> service.put("  ", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.put(null, "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void removeReturnsPreviousValueAndClearsOverride() {
        service.put("server.port", "9090");

        ConfigOverrideResult result = service.remove("server.port");

        assertThat(result.previousValue()).isEqualTo("9090");
        assertThat(result.value()).isNull();
        assertThat(service.hasOverride("server.port")).isFalse();
        assertThat(environment.getProperty("server.port")).isNull();
    }

    @Test
    void removeReturnsNullPreviousWhenNotSet() {
        ConfigOverrideResult result = service.remove("never.set");

        assertThat(result.previousValue()).isNull();
        assertThat(result.value()).isNull();
        assertThat(result.persisted()).isTrue();
    }

    @Test
    void overridesReturnsDefensiveCopy() {
        service.put("a", "1");
        var snapshot = service.overrides();
        snapshot.put("a", "tampered");
        assertThat(environment.getProperty("a")).isEqualTo("1");
    }

    @Test
    void blankOverridesFileFallsBackToDefaultPath() {
        properties.setOverridesFile("   ");
        ConfigOverrideService fallback = new ConfigOverrideService(new MockEnvironment(), properties);

        assertThat(fallback.overridesFile().toString())
                .endsWith("application-bootui.properties");
    }
}
