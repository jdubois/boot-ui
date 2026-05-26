package io.github.jdubois.bootui.autoconfigure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigOverridesFileStoreTests {

    @Test
    void loadReturnsEmptyMapWhenFileMissing(@TempDir Path tmp) {
        ConfigOverridesFileStore store = new ConfigOverridesFileStore(tmp.resolve("missing.properties"));

        assertThat(store.load()).isEmpty();
    }

    @Test
    void saveCreatesParentDirectoriesAndRoundtrips(@TempDir Path tmp) {
        Path file = tmp.resolve("nested/dir/overrides.properties");
        ConfigOverridesFileStore store = new ConfigOverridesFileStore(file);

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("server.port", "9090");
        values.put("sample.greeting", "Bonjour");
        store.save(values);

        assertThat(Files.exists(file)).isTrue();
        Map<String, Object> reloaded = new ConfigOverridesFileStore(file).load();
        assertThat(reloaded)
                .containsEntry("server.port", "9090")
                .containsEntry("sample.greeting", "Bonjour");
    }

    @Test
    void nullValuesAreStoredAsEmptyStrings(@TempDir Path tmp) {
        Path file = tmp.resolve("overrides.properties");
        ConfigOverridesFileStore store = new ConfigOverridesFileStore(file);

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("nullable", null);
        store.save(values);

        Map<String, Object> reloaded = store.load();
        assertThat(reloaded).containsEntry("nullable", "");
    }

    @Test
    void saveOverwritesPreviousContents(@TempDir Path tmp) {
        Path file = tmp.resolve("overrides.properties");
        ConfigOverridesFileStore store = new ConfigOverridesFileStore(file);

        store.save(Map.of("a", "1", "b", "2"));
        store.save(Map.of("a", "11"));

        Map<String, Object> reloaded = store.load();
        assertThat(reloaded).containsExactlyInAnyOrderEntriesOf(Map.of("a", "11"));
    }

    @Test
    void deleteRemovesFileAndIsIdempotent(@TempDir Path tmp) {
        Path file = tmp.resolve("overrides.properties");
        ConfigOverridesFileStore store = new ConfigOverridesFileStore(file);
        store.save(Map.of("a", "1"));
        assertThat(Files.exists(file)).isTrue();

        store.delete();
        assertThat(Files.exists(file)).isFalse();

        store.delete(); // second call must not throw
    }

    @Test
    void loadingMalformedFileSurfacesError(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("bad.properties");
        // An unterminated unicode escape is invalid; Properties.load throws IllegalArgumentException.
        Files.writeString(file, "key=\\uZZZZ\n");
        ConfigOverridesFileStore store = new ConfigOverridesFileStore(file);

        assertThatThrownBy(store::load).isInstanceOf(RuntimeException.class);
    }

    @Test
    void filePathIsExposed(@TempDir Path tmp) {
        Path file = tmp.resolve("overrides.properties");
        assertThat(new ConfigOverridesFileStore(file).file()).isEqualTo(file);
    }
}
