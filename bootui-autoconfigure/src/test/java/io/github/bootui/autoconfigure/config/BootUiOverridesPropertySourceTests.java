package io.github.bootui.autoconfigure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BootUiOverridesPropertySourceTests {

    @Test
    void hasStableName() {
        assertThat(new BootUiOverridesPropertySource().getName())
                .isEqualTo(BootUiOverridesPropertySource.NAME);
    }

    @Test
    void putAndRemoveMutateUnderlyingMap() {
        BootUiOverridesPropertySource src = new BootUiOverridesPropertySource();

        src.put("server.port", "9090");
        assertThat(src.containsProperty("server.port")).isTrue();
        assertThat(src.getProperty("server.port")).isEqualTo("9090");

        Object previous = src.remove("server.port");
        assertThat(previous).isEqualTo("9090");
        assertThat(src.containsProperty("server.port")).isFalse();
    }

    @Test
    void clearEmptiesUnderlyingMap() {
        Map<String, Object> backing = new LinkedHashMap<>(Map.of("a", "1", "b", "2"));
        BootUiOverridesPropertySource src = new BootUiOverridesPropertySource(backing);

        src.clear();

        assertThat(backing).isEmpty();
        assertThat(src.mutableSource()).isSameAs(backing);
    }

    @Test
    void mutableSourceReflectsOriginalMap() {
        Map<String, Object> backing = new LinkedHashMap<>();
        BootUiOverridesPropertySource src = new BootUiOverridesPropertySource(backing);

        src.put("k", "v");

        assertThat(backing).containsEntry("k", "v");
    }
}
