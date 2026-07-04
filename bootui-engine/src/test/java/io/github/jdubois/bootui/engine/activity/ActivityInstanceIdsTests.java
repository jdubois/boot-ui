package io.github.jdubois.bootui.engine.activity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ActivityInstanceIdsTests {

    @Test
    void returnsTheConfiguredValueTrimmed() {
        String resolved = ActivityInstanceIds.resolveOrDefault("  my-instance  ", "myapp", Map.of("HOSTNAME", "pod-1"));
        assertThat(resolved).isEqualTo("my-instance");
    }

    @Test
    void configuredValueWinsEvenOverAnAvailableHostname() {
        String resolved = ActivityInstanceIds.resolveOrDefault("explicit-id", "myapp", Map.of("HOSTNAME", "pod-1"));
        assertThat(resolved).isEqualTo("explicit-id");
    }

    @Test
    void fallsBackToHostnameWhenNotConfigured() {
        String resolved = ActivityInstanceIds.resolveOrDefault(null, "myapp", Map.of("HOSTNAME", "  pod-42  "));
        assertThat(resolved).isEqualTo("pod-42");
    }

    @Test
    void blankConfiguredValueIsTreatedAsAbsent() {
        String resolved = ActivityInstanceIds.resolveOrDefault("   ", "myapp", Map.of("HOSTNAME", "pod-1"));
        assertThat(resolved).isEqualTo("pod-1");
    }

    @Test
    void blankHostnameIsTreatedAsAbsentAndGeneratesAnId() {
        String resolved = ActivityInstanceIds.resolveOrDefault(null, "myapp", Map.of("HOSTNAME", "   "));
        assertThat(resolved).startsWith("myapp-");
        assertThat(resolved).hasSize("myapp-".length() + 6);
    }

    @Test
    void generatesAnAppNamePrefixedIdWhenNeitherConfiguredNorHostnameIsAvailable() {
        String resolved = ActivityInstanceIds.resolveOrDefault(null, "myapp", Map.of());
        assertThat(resolved).startsWith("myapp-");
        assertThat(resolved).hasSize("myapp-".length() + 6);
    }

    @Test
    void fallsBackToAGenericPrefixWhenNoHintIsGiven() {
        String resolved = ActivityInstanceIds.resolveOrDefault(null, null, Map.of());
        assertThat(resolved).startsWith("bootui-");
    }

    @Test
    void blankHintFallsBackToTheGenericPrefixEvenWhenGiven() {
        String resolved = ActivityInstanceIds.resolveOrDefault(null, "   ", Map.of());
        assertThat(resolved).startsWith("bootui-");
    }

    @Test
    void toleratesANullEnvironmentMap() {
        String resolved = ActivityInstanceIds.resolveOrDefault(null, "myapp", null);
        assertThat(resolved).startsWith("myapp-");
    }

    @Test
    void generatedIdsAreDifferentAcrossCalls() {
        String first = ActivityInstanceIds.resolveOrDefault(null, "myapp", Map.of());
        String second = ActivityInstanceIds.resolveOrDefault(null, "myapp", Map.of());
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void publicOverloadDelegatesToTheRealEnvironment() {
        // Just verifies the public entry point is wired and returns something usable; the resolution
        // rules themselves are exercised deterministically above via the environment-map overload.
        String resolved = ActivityInstanceIds.resolveOrDefault(null, "myapp");
        assertThat(resolved).isNotBlank();
    }

    @Test
    void activeInstanceIdUsesPersistenceWhenPersistenceIsEnabled() {
        String active = ActivityInstanceIds.activeInstanceId(
                persistenceSettings(true, "persistence-id"), forwardingSettings(false, "forwarding-id"));
        assertThat(active).isEqualTo("persistence-id");
    }

    @Test
    void activeInstanceIdUsesPersistenceWhenBothHappenToBeEnabled() {
        // ActivityStoreFactory fails fast at startup rather than allow this combination, but the helper
        // still needs a defined, safe answer if it were ever reached defensively: persistence wins,
        // preserving the pre-forwarding default behavior.
        String active = ActivityInstanceIds.activeInstanceId(
                persistenceSettings(true, "persistence-id"), forwardingSettings(true, "forwarding-id"));
        assertThat(active).isEqualTo("persistence-id");
    }

    @Test
    void activeInstanceIdUsesForwardingWhenOnlyForwardingIsEnabled() {
        String active = ActivityInstanceIds.activeInstanceId(
                persistenceSettings(false, "persistence-id"), forwardingSettings(true, "forwarding-id"));
        assertThat(active).isEqualTo("forwarding-id");
    }

    @Test
    void activeInstanceIdFallsBackToPersistenceWhenNeitherIsEnabled() {
        // No capture poller runs at all in this state, so "the active id" is moot in practice, but the
        // pre-existing default (persistence's own id) must stay stable for callers that keep using a
        // plain in-memory store with no durable backend at all.
        String active = ActivityInstanceIds.activeInstanceId(
                persistenceSettings(false, "persistence-id"), forwardingSettings(false, "forwarding-id"));
        assertThat(active).isEqualTo("persistence-id");
    }

    private static ActivityPersistenceSettings persistenceSettings(boolean enabled, String instanceId) {
        return new ActivityPersistenceSettings(
                enabled,
                ActivityPersistenceSettings.DataSourceMode.SHARED,
                null,
                null,
                null,
                null,
                "bootui_activity",
                Duration.ofSeconds(5),
                500,
                Duration.ofDays(7),
                instanceId,
                Duration.ofSeconds(2));
    }

    private static ActivityForwardingSettings forwardingSettings(boolean enabled, String instanceId) {
        return new ActivityForwardingSettings(
                enabled,
                "http://localhost:8080",
                null,
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                500,
                instanceId,
                Duration.ofSeconds(2));
    }
}
