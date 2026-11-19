package io.github.jdubois.bootui.autoconfigure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.ConfigOverrideResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    void putUsesRuntimeExposureOverride() {
        environment.setProperty("bootui.expose-values", "FULL");

        ConfigOverrideResult result = service.put("api.token", "abc");

        assertThat(result.value()).isEqualTo("abc");
    }

    @Test
    void putUsesRuntimeMaskSecretsOverride() {
        environment.setProperty("bootui.mask-secrets", "false");

        ConfigOverrideResult result = service.put("api.token", "abc");

        assertThat(result.value()).isEqualTo("abc");
    }

    @Test
    void rejectsSafetyOverridesBeforeChangingEnvironmentOrDisk() {
        assertThatThrownBy(() -> service.put("bootui.expose-values", "FULL"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside runtime overrides");
        assertThat(environment.getProperty("bootui.expose-values")).isNull();
        assertThat(Files.exists(service.overridesFile())).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unreadableStartupOverridesAreNotReplacedByALaterMutation(boolean remove) throws Exception {
        String original = "sample.greeting=before\nsample.invalid=\\uZZZZ\n";
        Files.writeString(service.overridesFile(), original);
        environment.setProperty("bootui.overrides-file", service.overridesFile().toString());
        new BootUiOverridesEnvironmentPostProcessor()
                .postProcessEnvironment(environment, new org.springframework.boot.SpringApplication());

        assertThatThrownBy(() -> {
                    if (remove) service.remove("sample.greeting");
                    else service.put("sample.greeting", "after");
                })
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unreadable");
        assertThat(Files.readString(service.overridesFile())).isEqualTo(original);
        assertThat(service.overrides()).isEmpty();
    }

    @Test
    void failedPersistenceDoesNotChangeLiveOverrides() throws Exception {
        service.put("sample.greeting", "before");
        Files.delete(service.overridesFile());
        Files.createDirectory(service.overridesFile());
        Files.writeString(service.overridesFile().resolve("occupied"), "x");

        assertThatThrownBy(() -> service.put("sample.greeting", "after")).isInstanceOf(IllegalStateException.class);
        assertThat(environment.getProperty("sample.greeting")).isEqualTo("before");
        assertThatThrownBy(() -> service.remove("sample.greeting")).isInstanceOf(IllegalStateException.class);
        assertThat(environment.getProperty("sample.greeting")).isEqualTo("before");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentUpdatesPreserveEveryAcknowledgedChangeOnDisk(boolean remove) throws Exception {
        var firstSave = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var holdFirst = new AtomicBoolean(true);
        var store = new ConfigOverridesFileStore(service.overridesFile()) {
            @Override
            public void save(Map<String, Object> values) {
                if (holdFirst.getAndSet(false)) {
                    firstSave.countDown();
                    try {
                        assertThat(releaseFirst.await(5, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(ex);
                    }
                }
                super.save(values);
            }
        };
        environment
                .getPropertySources()
                .addFirst(new BootUiOverridesPropertySource(new LinkedHashMap<>(Map.of("existing", "original"))));
        service = new ConfigOverrideService(environment, new BootUiExposure(environment, properties), store);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> service.put("first", "one"));
            assertThat(firstSave.await(5, TimeUnit.SECONDS)).isTrue();
            var secondThread = new AtomicReference<Thread>();
            var second = executor.submit(() -> {
                secondThread.set(Thread.currentThread());
                return remove ? service.remove("existing") : service.put("second", "two");
            });
            await().atMost(Duration.ofSeconds(5))
                    .until(() -> second.isDone()
                            || (secondThread.get() != null && secondThread.get().getState() == Thread.State.BLOCKED));
            releaseFirst.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).persisted()).isTrue();
            assertThat(second.get(5, TimeUnit.SECONDS).persisted()).isTrue();
            Map<String, Object> expected =
                    remove ? Map.of("first", "one") : Map.of("existing", "original", "first", "one", "second", "two");
            assertThat(store.load()).isEqualTo(expected);
            assertThat(service.overrides()).isEqualTo(expected);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void putBlankNameThrows() {
        assertThatThrownBy(() -> service.put("  ", "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.put(null, "x")).isInstanceOf(IllegalArgumentException.class);
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

        assertThat(fallback.overridesFile().toString()).endsWith("application-bootui.properties");
    }
}
