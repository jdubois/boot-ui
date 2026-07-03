package io.github.jdubois.bootui.engine.activity;

import static org.assertj.core.api.Assertions.assertThat;

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
}
