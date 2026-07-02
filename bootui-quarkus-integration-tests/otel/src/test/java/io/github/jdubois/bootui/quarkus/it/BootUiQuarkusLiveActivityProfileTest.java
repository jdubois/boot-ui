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
 * Proves the reduced, trace-id-only per-request profile drill-down ({@code GET
 * /bootui/api/activity/request/{id}}) end to end, with real OpenTelemetry context propagation. This is the
 * Quarkus analogue of Spring's Symfony-style profiler, deliberately narrower: Spring's tiered correlator
 * falls back to HTTP method+path+time-window+thread heuristics when no trace id is present, relying on its
 * synchronous one-thread-per-request servlet model — an invariant the Vert.x event loop does not provide —
 * so only tier 1 (exact trace-id matching) is ported (see the engine {@code RequestProfileAssembler}).
 *
 * <p>Reuses the same {@code /it/sql} and {@code /it/boom} probes as
 * {@link BootUiQuarkusLiveActivityCorrelationTest} and {@link BootUiQuarkusLiveActivityExceptionCorrelationTest},
 * which already prove the underlying trace id propagates across the event-loop→worker hop; this test proves
 * the drill-down endpoint itself surfaces that same correlation. The "no trace id" honest-degrade case is
 * covered separately in the {@code base} (non-OpenTelemetry) module, since every request captured here
 * carries a trace id.</p>
 */
@QuarkusTest
class BootUiQuarkusLiveActivityProfileTest {

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void profilesARequestCorrelatedByTraceIdWithItsSql() {
        Response probeCall = probe().get("/it/sql");
        assertThat(probeCall.status())
                .as("the SQL probe endpoint must answer 200")
                .isEqualTo(200);

        String requestId = findRequestEntry("/it/sql").path("id").asText(null);
        assertThat(requestId).as("the /it/sql request must be captured").isNotBlank();

        Response profileResponse = probe().get("/bootui/api/activity/request/" + requestId);
        assertThat(profileResponse.status())
                .as("GET /bootui/api/activity/request/{id} status")
                .isEqualTo(200);

        JsonNode profile = profileResponse.json();
        assertThat(profile.path("available").asBoolean(false))
                .as("a request with a resolvable trace id must yield an available profile")
                .isTrue();
        assertThat(profile.path("sqlCorrelationApproximate").asBoolean(true))
                .as("trace-id matching is exact, never the time/thread heuristic Spring falls back to")
                .isFalse();

        JsonNode sql = profile.path("sql");
        assertThat(sql.isArray()).as("the profile's sql field must be an array").isTrue();
        assertThat(sql)
                .as("the SELECT issued by /it/sql must be correlated into the profile")
                .hasSize(1);
        assertThat(sql.get(0).path("sql").asText(""))
                .as("the correlated SQL entry must be the SELECT the probe issued")
                .containsIgnoringCase("select");

        assertThat(anyNoteContains(profile, "reduced, trace-id-only"))
                .as("the profile must honestly disclose it is a reduced, trace-id-only correlation")
                .isTrue();
        assertThat(anyNoteContains(profile, "SQL is correlated exactly by trace id"))
                .as("the profile must explain the SQL correlation is exact, not heuristic")
                .isTrue();
    }

    @Test
    void profilesARequestCorrelatedByTraceIdWithItsException() {
        Response probeCall = probe().get("/it/boom");
        assertThat(probeCall.status())
                .as("the boom probe endpoint must fail with a server error")
                .isGreaterThanOrEqualTo(500);

        String requestId = findRequestEntry("/it/boom").path("id").asText(null);
        assertThat(requestId).as("the /it/boom request must be captured").isNotBlank();

        Response profileResponse = probe().get("/bootui/api/activity/request/" + requestId);
        assertThat(profileResponse.status())
                .as("GET /bootui/api/activity/request/{id} status")
                .isEqualTo(200);

        JsonNode profile = profileResponse.json();
        assertThat(profile.path("available").asBoolean(false))
                .as("a request with a resolvable trace id must yield an available profile")
                .isTrue();

        JsonNode exceptions = profile.path("exceptions");
        assertThat(exceptions.isArray())
                .as("the profile's exceptions field must be an array")
                .isTrue();
        assertThat(exceptions)
                .as("the failure thrown by /it/boom must be correlated into the profile")
                .hasSize(1);
        assertThat(exceptions.get(0).path("exceptionClassName").asText(""))
                .as("the correlated exception must be the one /it/boom throws")
                .isEqualTo("java.lang.IllegalStateException");

        assertThat(anyNoteContains(profile, "Exceptions are correlated exactly by trace id"))
                .as("the profile must explain the exception correlation is exact, not heuristic")
                .isTrue();
    }

    @Test
    void unavailableWhenRequestIdIsNotInTheBuffer() {
        Response profileResponse = probe().get("/bootui/api/activity/request/not-a-real-id");
        assertThat(profileResponse.status())
                .as("GET /bootui/api/activity/request/{id} status")
                .isEqualTo(200);

        JsonNode profile = profileResponse.json();
        assertThat(profile.path("available").asBoolean(true))
                .as("an id that is no longer (or never was) in the buffer must render unavailable")
                .isFalse();
        assertThat(profile.path("unavailableReason").asText(""))
                .as("the unavailable reason must be honest, not empty")
                .contains("no longer in the buffer");
    }

    private JsonNode findRequestEntry(String path) {
        Response activity = probe().get("/bootui/api/activity");
        assertThat(activity.status()).as("GET /bootui/api/activity status").isEqualTo(200);
        JsonNode entries = activity.json().path("entries");
        JsonNode match = null;
        for (JsonNode entry : entries) {
            if ("REQUEST".equals(entry.path("type").asText())
                    && path.equals(entry.path("path").asText())) {
                match = entry;
            }
        }
        assertThat(match)
                .as("a REQUEST entry for " + path + " must be present in the activity feed")
                .isNotNull();
        return match;
    }

    private boolean anyNoteContains(JsonNode profile, String fragment) {
        for (JsonNode note : profile.path("notes")) {
            if (note.asText("").contains(fragment)) {
                return true;
            }
        }
        return false;
    }
}
