package io.github.jdubois.bootui.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.activity.ActivityPersistenceSettings;
import io.quarkus.runtime.configuration.DurationConverter;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.time.Duration;
import java.util.Map;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@code bootui.activity.persistence.*} MicroProfile {@link Config} binding used by
 * {@link BootUiEngineProducer#activityPersistenceSettings(Config)}, so it stays at parity with the Spring
 * adapter's {@code BootUiProperties.ActivityPersistence} defaults: the same keys resolve to the same
 * {@link ActivityPersistenceSettings} on both frameworks.
 */
class BootUiEngineProducerActivityConfigTest {

    private static SmallRyeConfig config(Map<String, String> properties) {
        return new SmallRyeConfigBuilder()
                .withConverter(Duration.class, 100, new DurationConverter())
                .withSources(new PropertiesConfigSource(properties, "test", 1000))
                .build();
    }

    @Test
    void appliesSpringUnifiedDefaultsWhenUnset() {
        ActivityPersistenceSettings settings = new BootUiEngineProducer().activityPersistenceSettings(config(Map.of()));

        assertThat(settings.enabled()).isFalse();
        assertThat(settings.dataSourceMode()).isEqualTo(ActivityPersistenceSettings.DataSourceMode.SHARED);
        assertThat(settings.dedicatedJdbcUrl()).isNull();
        assertThat(settings.dedicatedUsername()).isNull();
        assertThat(settings.dedicatedPassword()).isNull();
        assertThat(settings.dedicatedDriverClassName()).isNull();
        assertThat(settings.tableName()).isEqualTo("bootui_activity");
        assertThat(settings.flushInterval()).isEqualTo(Duration.ofSeconds(5));
        assertThat(settings.bufferMaxEntries()).isEqualTo(500);
        assertThat(settings.retention()).isEqualTo(Duration.ofDays(7));
        assertThat(settings.captureInterval()).isEqualTo(Duration.ofSeconds(2));
        // No explicit instance-id and no guaranteed HOSTNAME in a test JVM: only the resolved-or-generated
        // contract is pinned here, not a specific value (see ActivityInstanceIdsTests for that logic).
        assertThat(settings.instanceId()).isNotBlank();
    }

    @Test
    void bindsOverridesIncludingDurationAndDataSourceMode() {
        ActivityPersistenceSettings settings = new BootUiEngineProducer()
                .activityPersistenceSettings(config(Map.ofEntries(
                        Map.entry("bootui.activity.persistence.enabled", "true"),
                        Map.entry("bootui.activity.persistence.data-source-mode", "dedicated"),
                        Map.entry("bootui.activity.persistence.dedicated-jdbc-url", "jdbc:h2:mem:test"),
                        Map.entry("bootui.activity.persistence.dedicated-username", "sa"),
                        Map.entry("bootui.activity.persistence.dedicated-password", "secret"),
                        Map.entry("bootui.activity.persistence.dedicated-driver-class-name", "org.h2.Driver"),
                        Map.entry("bootui.activity.persistence.table-name", "custom_activity"),
                        Map.entry("bootui.activity.persistence.flush-interval", "10s"),
                        Map.entry("bootui.activity.persistence.buffer-max-entries", "250"),
                        Map.entry("bootui.activity.persistence.retention", "P1D"),
                        Map.entry("bootui.activity.persistence.instance-id", "instance-a"),
                        Map.entry("bootui.activity.persistence.capture-interval", "1s"))));

        assertThat(settings.enabled()).isTrue();
        assertThat(settings.dataSourceMode()).isEqualTo(ActivityPersistenceSettings.DataSourceMode.DEDICATED);
        assertThat(settings.dedicatedJdbcUrl()).isEqualTo("jdbc:h2:mem:test");
        assertThat(settings.dedicatedUsername()).isEqualTo("sa");
        assertThat(settings.dedicatedPassword()).isEqualTo("secret");
        assertThat(settings.dedicatedDriverClassName()).isEqualTo("org.h2.Driver");
        assertThat(settings.tableName()).isEqualTo("custom_activity");
        assertThat(settings.flushInterval()).isEqualTo(Duration.ofSeconds(10));
        assertThat(settings.bufferMaxEntries()).isEqualTo(250);
        assertThat(settings.retention()).isEqualTo(Duration.ofDays(1));
        assertThat(settings.instanceId()).isEqualTo("instance-a");
        assertThat(settings.captureInterval()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void dataSourceModeBindingIsCaseInsensitiveLikeSpringsRelaxedBinding() {
        ActivityPersistenceSettings upper = new BootUiEngineProducer()
                .activityPersistenceSettings(
                        config(Map.of("bootui.activity.persistence.data-source-mode", "DEDICATED")));
        ActivityPersistenceSettings mixed = new BootUiEngineProducer()
                .activityPersistenceSettings(
                        config(Map.of("bootui.activity.persistence.data-source-mode", "Dedicated")));

        assertThat(upper.dataSourceMode()).isEqualTo(ActivityPersistenceSettings.DataSourceMode.DEDICATED);
        assertThat(mixed.dataSourceMode()).isEqualTo(ActivityPersistenceSettings.DataSourceMode.DEDICATED);
    }

    @Test
    void unrecognizedDataSourceModeFallsBackToSharedDefault() {
        ActivityPersistenceSettings settings = new BootUiEngineProducer()
                .activityPersistenceSettings(config(Map.of("bootui.activity.persistence.data-source-mode", "bogus")));

        assertThat(settings.dataSourceMode()).isEqualTo(ActivityPersistenceSettings.DataSourceMode.SHARED);
    }
}
