package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Real-boot checks for the Quarkus Mappings panel ({@code MappingsResource} over the engine
 * {@code MappingsService} backed by {@code QuarkusMappingProvider}, fed the build-time-captured JAX-RS
 * resource model). The neutral sort/query/page logic is unit-tested in the engine
 * {@code MappingsServiceTests}; this test pins the Quarkus-specific behavior: the host application's own
 * JAX-RS routes ({@code org.acme.restdemo.WidgetResource}) are captured at build time and surfaced, while
 * BootUI's own {@code /bootui} routes are filtered out.
 */
@QuarkusTest
class BootUiQuarkusMappingsResourceTest {

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void mappingsPanelIsAvailable() {
        Response panels = probe().get("/bootui/api/panels");
        assertThat(panels.status()).as("GET /bootui/api/panels status").isEqualTo(200);

        boolean available = false;
        for (JsonNode panel : panels.json().path("panels")) {
            if ("mappings".equals(panel.path("id").asText())) {
                available = panel.path("available").asBoolean(false);
            }
        }
        assertThat(available)
                .as("the Mappings panel must be available on Quarkus (JAX-RS resources are captured from the"
                        + " build-time Jandex index)")
                .isTrue();
    }

    @Test
    void primaryGetAnswers200AndJson() {
        // The cross-adapter conformance contract: an available panel's root GET answers 200 + JSON.
        Response root = probe().get("/bootui/api/mappings");
        assertThat(root.status()).as("GET /bootui/api/mappings status").isEqualTo(200);
        assertThat(root.isJson())
                .as("GET /bootui/api/mappings content-type (%s)", root.contentType())
                .isTrue();
    }

    @Test
    void flatViewCapturesHostRoutesAndHidesBootUisOwnRoutes() {
        Response response = probe().get("/bootui/api/mappings/flat");
        assertThat(response.status()).as("GET /bootui/api/mappings/flat status").isEqualTo(200);
        assertThat(response.isJson())
                .as("GET /bootui/api/mappings/flat content-type (%s)", response.contentType())
                .isTrue();

        JsonNode body = response.json();
        assertThat(body.path("mappings").isArray())
                .as("$.mappings must be an array")
                .isTrue();

        List<String> patterns = new ArrayList<>();
        List<String> handlers = new ArrayList<>();
        for (JsonNode mapping : body.path("mappings")) {
            patterns.add(mapping.path("pattern").asText());
            handlers.add(mapping.path("handler").asText());
        }

        assertThat(patterns)
                .as("the build-time JAX-RS capture must surface the host application's own routes")
                .contains("/widgets", "/widgets/create");
        assertThat(handlers)
                .as("the handler is rendered as declaringClass#method")
                .contains("org.acme.restdemo.WidgetResource#list", "org.acme.restdemo.WidgetResource#createWidget");

        assertThat(patterns)
                .as("BootUI's own /bootui routes must be filtered out of the Mappings panel")
                .noneMatch(pattern -> pattern.startsWith("/bootui"));
        assertThat(handlers)
                .as("BootUI's own resources must be filtered out of the Mappings panel")
                .noneMatch(handler -> handler.startsWith("io.github.jdubois.bootui"));

        // The class-level @Produces(application/json) on WidgetResource is captured onto each method.
        for (JsonNode mapping : body.path("mappings")) {
            if ("/widgets".equals(mapping.path("pattern").asText())) {
                assertThat(mapping.path("produces").asText())
                        .as("the @Produces media type is captured")
                        .isEqualTo("application/json");
            }
        }
    }
}
