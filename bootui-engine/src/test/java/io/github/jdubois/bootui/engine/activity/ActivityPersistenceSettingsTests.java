package io.github.jdubois.bootui.engine.activity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ActivityPersistenceSettingsTests {

    @Test
    void withEnabledSharedModeEnablesAndSwitchesToSharedModeWhileKeepingEverythingElse() {
        ActivityPersistenceSettings dedicatedDisabled = new ActivityPersistenceSettings(
                false,
                ActivityPersistenceSettings.DataSourceMode.DEDICATED,
                "jdbc:h2:mem:whatever",
                "user",
                "pass",
                "org.h2.Driver",
                "bootui_activity",
                Duration.ofSeconds(5),
                200,
                Duration.ofDays(7),
                "app-1",
                Duration.ofSeconds(1));

        ActivityPersistenceSettings switched = dedicatedDisabled.withEnabledSharedMode();

        assertThat(switched.enabled()).isTrue();
        assertThat(switched.dataSourceMode()).isEqualTo(ActivityPersistenceSettings.DataSourceMode.SHARED);
        // Every other field is carried over unchanged.
        assertThat(switched.dedicatedJdbcUrl()).isEqualTo("jdbc:h2:mem:whatever");
        assertThat(switched.dedicatedUsername()).isEqualTo("user");
        assertThat(switched.dedicatedPassword()).isEqualTo("pass");
        assertThat(switched.dedicatedDriverClassName()).isEqualTo("org.h2.Driver");
        assertThat(switched.tableName()).isEqualTo("bootui_activity");
        assertThat(switched.flushInterval()).isEqualTo(Duration.ofSeconds(5));
        assertThat(switched.bufferMaxEntries()).isEqualTo(200);
        assertThat(switched.retention()).isEqualTo(Duration.ofDays(7));
        assertThat(switched.instanceId()).isEqualTo("app-1");
        assertThat(switched.captureInterval()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void withEnabledSharedModeDoesNotMutateTheOriginal() {
        ActivityPersistenceSettings original = new ActivityPersistenceSettings(
                false,
                ActivityPersistenceSettings.DataSourceMode.DEDICATED,
                null,
                null,
                null,
                null,
                "bootui_activity",
                Duration.ofSeconds(5),
                200,
                null,
                "app-1",
                Duration.ofSeconds(1));

        original.withEnabledSharedMode();

        assertThat(original.enabled()).isFalse();
        assertThat(original.dataSourceMode()).isEqualTo(ActivityPersistenceSettings.DataSourceMode.DEDICATED);
    }
}
