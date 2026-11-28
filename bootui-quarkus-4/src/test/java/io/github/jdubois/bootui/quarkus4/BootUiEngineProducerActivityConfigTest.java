package io.github.jdubois.bootui.quarkus4;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.activity.ActivityPersistenceSettings;
import io.github.jdubois.bootui.engine.journal.DurableJournalSettings;
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
                        Map.entry("bootui.activity.persistence.instance-id", "instance-a"))));

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

    @Test
    void bindsDurableJournalConfiguration() {
        var config = config(Map.ofEntries(
                Map.entry("bootui.activity.persistence.namespace-id", "namespace-a"),
                Map.entry("bootui.activity.persistence.application-id", "orders-override"),
                Map.entry("bootui.activity.persistence.enabled", "true"),
                Map.entry("bootui.activity.persistence.flush-interval", "11s"),
                Map.entry("bootui.activity.persistence.max-pending-entries", "321"),
                Map.entry("bootui.activity.persistence.max-pending-bytes", "12MB"),
                Map.entry("bootui.activity.persistence.hot-max-entries", "123"),
                Map.entry("bootui.activity.persistence.table-name", "bootui_durable"),
                Map.entry("bootui.activity.persistence.retention", "P3D"),
                Map.entry("bootui.activity.persistence.reader-archive-cross-namespace", "true")));
        QuarkusApplicationInfo application = new QuarkusApplicationInfo(config);
        DurableJournalSettings settings = new BootUiEngineProducer().durableJournalSettings(config, application);

        assertThat(settings.namespaceId()).isEqualTo("namespace-a");
        assertThat(settings.applicationId()).isEqualTo("orders-override");
        assertThat(settings.runId()).isEqualTo(application.run().id());
        assertThat(settings.enabled()).isTrue();
        assertThat(settings.flushInterval()).isEqualTo(Duration.ofSeconds(11));
        assertThat(settings.maxPendingEntries()).isEqualTo(321);
        assertThat(settings.maxPendingBytes()).isEqualTo(12L * 1024 * 1024);
        assertThat(settings.hotMaxEntries()).isEqualTo(123);
        assertThat(settings.tablePrefix()).isEqualTo("bootui_durable");
        assertThat(settings.retention()).isEqualTo(Duration.ofDays(3));
        assertThat(settings.readerArchiveCrossNamespace()).isTrue();
    }

    @Test
    void mapsLegacyInstanceIdToNamespaceAndUsesQuarkusApplicationKey() {
        var config = config(Map.ofEntries(
                Map.entry("bootui.activity.persistence.instance-id", "legacy-instance"),
                Map.entry("quarkus.application.name", "orders")));
        DurableJournalSettings settings =
                new BootUiEngineProducer().durableJournalSettings(config, new QuarkusApplicationInfo(config));

        assertThat(settings.namespaceId()).isEqualTo("legacy-instance");
        assertThat(settings.applicationId()).endsWith(":orders");
    }

    @Test
    void appliesDurableJournalDefaultsWhenUnset() {
        var config = config(Map.of("quarkus.application.name", "orders"));
        DurableJournalSettings settings =
                new BootUiEngineProducer().durableJournalSettings(config, new QuarkusApplicationInfo(config));

        assertThat(settings.enabled()).isFalse();
        assertThat(settings.namespaceId()).matches("local-[0-9a-f]{64}");
        assertThat(settings.applicationId()).endsWith(":orders");
        assertThat(settings.flushInterval()).isEqualTo(Duration.ofSeconds(5));
        assertThat(settings.maxPendingEntries()).isEqualTo(500);
        assertThat(settings.maxPendingBytes()).isEqualTo(32L * 1024 * 1024);
        assertThat(settings.hotMaxEntries()).isEqualTo(500);
        assertThat(settings.tablePrefix()).isEqualTo("bootui_activity");
        assertThat(settings.retention()).isEqualTo(Duration.ofDays(7));
        assertThat(settings.readerArchiveCrossNamespace()).isFalse();
    }
}
