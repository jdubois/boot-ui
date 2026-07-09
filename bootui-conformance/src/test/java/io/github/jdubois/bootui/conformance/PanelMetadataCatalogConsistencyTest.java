package io.github.jdubois.bootui.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static java.util.stream.StreamSupport.stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.panel.BootUiPanels.Panel;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PanelMetadataCatalogConsistencyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CATALOG_RESOURCE =
            "/io/github/jdubois/bootui/conformance/panel-catalog.json";

    private static final List<String> MANIFEST_RESOURCES = List.of(
            "/io/github/jdubois/bootui/conformance/expected-panels-spring.json",
            "/io/github/jdubois/bootui/conformance/expected-panels-quarkus.json",
            "/io/github/jdubois/bootui/conformance/expected-panels-webflux.json");

    @Test
    void sharedCatalogMatchesBackendPanelRegistry() {
        List<CatalogPanel> catalogPanels = loadCatalogPanels();
        List<Panel> backendPanels = BootUiPanels.all();

        assertThat(backendPanels).hasSameSizeAs(catalogPanels);
        assertThat(backendPanels).extracting(Panel::id).containsExactlyInAnyOrderElementsOf(
                catalogPanels.stream().map(CatalogPanel::id).toList());
        assertThat(toMetadataById(backendPanels)).isEqualTo(toMetadataByIdFromCatalog(catalogPanels));

        for (Panel panel : backendPanels) {
            if (panel.actionCapable()) {
                assertThat(panel.apiPrefixes())
                        .as("action-capable panel %s must declare guarded apiPrefixes", panel.id())
                        .isNotEmpty();
            }
            for (String apiPrefix : panel.apiPrefixes()) {
                assertThat(BootUiPanels.byApiPath(apiPrefix))
                        .as("exact apiPrefix lookup for %s", panel.id())
                        .map(Panel::id)
                        .contains(panel.id());
                assertThat(BootUiPanels.byApiPath(apiPrefix + "/sample"))
                        .as("nested apiPrefix lookup for %s", panel.id())
                        .map(Panel::id)
                        .contains(panel.id());
            }
        }
    }

    @Test
    void sharedCatalogMatchesConformanceManifests() {
        List<CatalogPanel> catalogPanels = loadCatalogPanels();
        Map<String, PanelMetadata> expected = toMetadataByIdFromCatalog(catalogPanels);

        for (String resource : MANIFEST_RESOURCES) {
            JsonNode manifest = loadJsonResource(resource);
            assertThat(manifest.path("panels"))
                    .as("%s panels", resource)
                    .isNotNull();
            assertThat(manifest.path("panels").isArray())
                    .as("%s panels array", resource)
                    .isTrue();

            Map<String, PanelMetadata> actual = new LinkedHashMap<>();
            for (JsonNode node : manifest.path("panels")) {
                actual.put(
                        node.path("id").asText(),
                        new PanelMetadata(node.path("title").asText(), node.path("actionCapable").asBoolean()));
            }
            assertThat(actual).as("%s panel metadata", resource).isEqualTo(expected);
        }
    }

    @Test
    void sharedCatalogIsDocumentedInFeaturesGuide() {
        String features = readFeaturesMarkdown();

        for (CatalogPanel panel : loadCatalogPanels()) {
            String headingPrefix = panel.title().equals("Overview") ? "## " : "### ";
            assertThat(features)
                    .as("docs/FEATURES.md heading for panel '%s'", panel.title())
                    .contains(headingPrefix + panel.title());
        }
    }

    private static List<CatalogPanel> loadCatalogPanels() {
        JsonNode root = loadJsonResource(CATALOG_RESOURCE);
        JsonNode panels = root.path("panels");
        assertThat(panels.isArray()).as("panel-catalog panels array").isTrue();

        return stream(panels.spliterator(), false)
                .map(node -> new CatalogPanel(
                        node.path("id").asText(),
                        node.path("title").asText(),
                        node.path("group").asText(),
                        node.path("actionCapable").asBoolean()))
                .toList();
    }

    private static JsonNode loadJsonResource(String resource) {
        try (InputStream stream = PanelMetadataCatalogConsistencyTest.class.getResourceAsStream(resource)) {
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

    private static Map<String, PanelMetadata> toMetadataById(List<Panel> panels) {
        Map<String, PanelMetadata> byId = new LinkedHashMap<>();
        for (Panel panel : panels) {
            byId.put(panel.id(), new PanelMetadata(panel.title(), panel.actionCapable()));
        }
        return byId;
    }

    private static Map<String, PanelMetadata> toMetadataByIdFromCatalog(List<CatalogPanel> panels) {
        Map<String, PanelMetadata> byId = new LinkedHashMap<>();
        for (CatalogPanel panel : panels) {
            byId.put(panel.id(), new PanelMetadata(panel.title(), panel.actionCapable()));
        }
        return byId;
    }

    private record PanelMetadata(String title, boolean actionCapable) {}

    private record CatalogPanel(String id, String title, String group, boolean actionCapable) {}
}
