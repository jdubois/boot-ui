package io.github.jdubois.bootui.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.panel.BootUiPanels.Panel;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class BackendPanelCatalogConsistencyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<ManifestResource> MANIFEST_RESOURCES = List.of(
            new ManifestResource("/io/github/jdubois/bootui/conformance/expected-panels-spring.json", "spring-boot"),
            new ManifestResource("/io/github/jdubois/bootui/conformance/expected-panels-quarkus.json", "quarkus"),
            new ManifestResource(
                    "/io/github/jdubois/bootui/conformance/expected-panels-webflux.json", "spring-boot-reactive"));

    @Test
    void backendCatalogMatchesConformanceManifestsExactly() {
        List<PanelMetadata> expected = BootUiPanels.all().stream()
                .map(panel -> new PanelMetadata(panel.id(), panel.title(), panel.actionCapable()))
                .toList();

        for (ManifestResource resource : MANIFEST_RESOURCES) {
            JsonNode manifest = loadJsonResource(resource.path());
            assertThat(manifest.path("platform").asText())
                    .as("%s platform", resource.path())
                    .isEqualTo(resource.platform());
            assertThat(manifest.path("panels")).as("%s panels", resource.path()).isNotNull();
            assertThat(manifest.path("panels").isArray())
                    .as("%s panels array", resource.path())
                    .isTrue();

            List<PanelMetadata> actual = new java.util.ArrayList<>();
            for (JsonNode node : manifest.path("panels")) {
                assertThat(node.path("id").isTextual())
                        .as("%s panel id", resource.path())
                        .isTrue();
                assertThat(node.path("title").isTextual())
                        .as("%s panel title", resource.path())
                        .isTrue();
                assertThat(node.path("actionCapable").isBoolean())
                        .as("%s panel actionCapable", resource.path())
                        .isTrue();
                actual.add(new PanelMetadata(
                        node.path("id").asText(),
                        node.path("title").asText(),
                        node.path("actionCapable").asBoolean()));
            }
            assertThat(actual)
                    .as("%s panel metadata and contractual order", resource.path())
                    .containsExactlyElementsOf(expected);
        }
    }

    @Test
    void backendCatalogIsDocumentedInFeaturesGuide() {
        String features = readFeaturesMarkdown();

        for (Panel panel : BootUiPanels.all()) {
            String headingPrefix = panel.title().equals("Overview") ? "## " : "### ";
            assertThat(features)
                    .as("docs/FEATURES.md heading for panel '%s'", panel.title())
                    .containsPattern(Pattern.compile(
                            "^" + Pattern.quote(headingPrefix + panel.title()) + "$", Pattern.MULTILINE));
        }
    }

    private static JsonNode loadJsonResource(String resource) {
        try (InputStream stream = BackendPanelCatalogConsistencyTest.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Missing resource: " + resource);
            }
            return MAPPER.readTree(stream);
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to read resource: " + resource, ex);
        }
    }

    private static String readFeaturesMarkdown() {
        Path root = findRepositoryRoot(Path.of(".").toAbsolutePath());
        Path features = root.resolve("docs").resolve("FEATURES.md");
        try {
            return Files.readString(features);
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to read " + features, ex);
        }
    }

    private static Path findRepositoryRoot(Path start) {
        Path cursor = start;
        while (cursor != null) {
            if (Files.isDirectory(cursor.resolve("bootui-engine"))
                    && Files.isDirectory(cursor.resolve("bootui-ui"))
                    && Files.exists(cursor.resolve("pom.xml"))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("Unable to locate repository root from " + start);
    }

    private record ManifestResource(String path, String platform) {}

    private record PanelMetadata(String id, String title, boolean actionCapable) {}
}
