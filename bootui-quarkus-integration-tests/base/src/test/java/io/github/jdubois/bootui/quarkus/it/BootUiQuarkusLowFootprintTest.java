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
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins low-footprint mode ({@code GET} and {@code POST /bootui/api/live-memory/footprint}) on Quarkus: enabling it
 * pauses the BootUI recorders ArC has already created and frees memory, disabling it resumes them, and the status read
 * does no work. The shared conformance catalog lists the action but never invokes it, because pausing recording would
 * starve the checks that read the buffers.
 */
@QuarkusTest
class BootUiQuarkusLowFootprintTest {

    @TestHTTPResource
    URL baseUrl;

    @Test
    void enablingPausesTheCreatedRecordersAndDisablingResumesThem() {
        BootUiHttpProbe probe = new BootUiHttpProbe(baseUrl.toExternalForm());
        // The runtime journal exists once the application has served a request.
        probe.get("/bootui/api/live-memory");
        try {
            assertThat(status(probe.get("/bootui/api/live-memory/footprint"))
                            .path("active")
                            .asBoolean())
                    .isFalse();

            Response enabled = toggle(probe, true);
            assertThat(enabled.status()).as("POST footprint enable").isEqualTo(200);
            JsonNode on = status(enabled);
            assertThat(on.path("active").asBoolean()).isTrue();
            assertThat(on.path("sinceEpochMillis").asLong()).isPositive();
            assertThat(on.path("agentAttached").isBoolean()).isTrue();
            assertThat(on.path("offload").path("gcRequested").asBoolean()).isTrue();
            List<String> ids = new ArrayList<>();
            for (JsonNode component : on.path("components")) {
                ids.add(component.path("id").asText());
                assertThat(component.path("suspended").asBoolean())
                        .as("component %s must be paused", component.path("id").asText())
                        .isTrue();
            }
            assertThat(ids).contains("runtime-journal", "resource-sampler").doesNotHaveDuplicates();

            JsonNode read = status(probe.get("/bootui/api/live-memory/footprint"));
            assertThat(read.path("active").asBoolean()).isTrue();
            assertThat(read.path("offload").isNull()).isTrue();
        } finally {
            Response disabled = toggle(probe, false);
            assertThat(disabled.status()).as("POST footprint disable").isEqualTo(200);
            JsonNode off = status(disabled);
            assertThat(off.path("active").asBoolean()).isFalse();
            assertThat(off.path("sinceEpochMillis").isNull()).isTrue();
            for (JsonNode component : off.path("components")) {
                assertThat(component.path("suspended").asBoolean()).isFalse();
            }
        }
    }

    private static Response toggle(BootUiHttpProbe probe, boolean enabled) {
        return probe.request(
                "POST",
                "/bootui/api/live-memory/footprint",
                Map.of("Content-Type", "application/json"),
                "{\"enabled\":" + enabled + "}");
    }

    private static JsonNode status(Response response) {
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.isJson()).isTrue();
        return response.json();
    }
}
