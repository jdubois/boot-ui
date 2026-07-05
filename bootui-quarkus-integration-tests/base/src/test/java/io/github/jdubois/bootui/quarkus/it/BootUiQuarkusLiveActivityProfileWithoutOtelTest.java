package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URL;
import org.junit.jupiter.api.Test;

/**
 * Pins the honest-degrade half of the reduced, trace-id-only per-request profile drill-down
 * ({@code GET /bootui/api/activity/request/{id}}) on a Quarkus app that does <strong>not</strong> have
 * {@code quarkus-opentelemetry} on its classpath (this integration-test module deliberately omits it, unlike
 * the sibling {@code otel} module).
 *
 * <p>Without OpenTelemetry, no captured HTTP exchange can ever carry a distributed-trace id, so every
 * request must render {@code profileable:false} in the main Live Activity feed, and the profile endpoint
 * itself must render {@code available:false} with a clear reason rather than fabricating a correlation. The
 * OpenTelemetry-<em>present</em> half (an available profile correlating SQL/exceptions by trace id) lives in
 * {@code BootUiQuarkusLiveActivityProfileTest} in the {@code otel} module.</p>
 */
@QuarkusTest
class BootUiQuarkusLiveActivityProfileWithoutOtelTest {

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void requestWithNoTraceIdIsNotProfileableAndItsProfileIsUnavailable() {
        Response widgets = probe().get("/widgets");
        assertThat(widgets.status())
                .as("the widgets probe endpoint must answer 200")
                .isEqualTo(200);

        Response activity = probe().get("/bootui/api/activity");
        assertThat(activity.status()).as("GET /bootui/api/activity status").isEqualTo(200);

        JsonNode entries = activity.json().path("entries");
        JsonNode request = null;
        for (JsonNode entry : entries) {
            if ("REQUEST".equals(entry.path("type").asText())
                    && "/widgets".equals(entry.path("path").asText())) {
                request = entry;
            }
        }
        assertThat(request)
                .as("the /widgets call must surface as a REQUEST entry")
                .isNotNull();
        assertThat(request.path("correlationId").isNull())
                .as("without quarkus-opentelemetry no request can carry a trace id")
                .isTrue();
        assertThat(request.path("profileable").asBoolean(true))
                .as("a request with no resolvable trace id must not be profileable")
                .isFalse();

        String requestId = request.path("id").asText(null);
        assertThat(requestId).as("the /widgets request must have a stable id").isNotBlank();

        Response profileResponse = probe().get("/bootui/api/activity/request/" + requestId);
        assertThat(profileResponse.status())
                .as("GET /bootui/api/activity/request/{id} status")
                .isEqualTo(200);

        JsonNode profile = profileResponse.json();
        assertThat(profile.path("available").asBoolean(true))
                .as("a request with no trace id must render an unavailable profile, never a fabricated one")
                .isFalse();
        assertThat(profile.path("unavailableReason").asText(""))
                .as("the unavailable reason must honestly name the missing capability")
                .contains("No distributed trace id")
                .contains("OpenTelemetry");
    }

    @Test
    void unknownRequestIdIsAlsoAnUnavailableProfile() {
        Response profileResponse = probe().get("/bootui/api/activity/request/does-not-exist");
        assertThat(profileResponse.status())
                .as("GET /bootui/api/activity/request/{id} status")
                .isEqualTo(200);

        JsonNode profile = profileResponse.json();
        assertThat(profile.path("available").asBoolean(true))
                .as("an id that was never captured must render unavailable")
                .isFalse();
        assertThat(profile.path("unavailableReason").asText(""))
                .as("the unavailable reason must name the missing request, not the missing trace id")
                .contains("no longer in the buffer");
    }
}
