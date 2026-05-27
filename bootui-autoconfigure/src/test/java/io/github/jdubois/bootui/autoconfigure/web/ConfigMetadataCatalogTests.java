package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.BootUiDtos.ConfigPropertySuggestionDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigMetadataCatalogTests {

    @TempDir
    Path tempDir;

    @Test
    void readsConfigurationMetadataPropertiesWithDefaults() throws Exception {
        Path metadataFile = tempDir.resolve("META-INF/spring-configuration-metadata.json");
        Files.createDirectories(metadataFile.getParent());
        Files.writeString(metadataFile, """
            {
              "properties": [
                {
                  "name": "server.port",
                  "type": "java.lang.Integer",
                  "description": "Server HTTP port.",
                  "defaultValue": 8080
                },
                {
                  "name": "spring.main.banner-mode",
                  "type": "org.springframework.boot.Banner$Mode",
                  "description": "Mode used to display the banner.",
                  "defaultValue": "console"
                },
                {
                  "name": "spring.old",
                  "deprecation": {
                    "reason": "No longer used."
                  }
                }
              ]
            }
            """);

        try (URLClassLoader classLoader = new URLClassLoader(new java.net.URL[]{tempDir.toUri().toURL()}, null)) {
            ConfigMetadataCatalog catalog = new ConfigMetadataCatalog(classLoader);

            List<ConfigPropertySuggestionDto> suggestions = catalog.suggestions();

            assertThat(suggestions)
                .extracting(ConfigPropertySuggestionDto::name)
                .containsExactly("server.port", "spring.main.banner-mode");
            assertThat(catalog.get("server.port").defaultValue()).isEqualTo(8080);
            assertThat(catalog.get("spring.main.banner-mode").defaultValue()).isEqualTo("console");
            assertThat(catalog.get("spring.old")).isNull();
        }
    }
}
