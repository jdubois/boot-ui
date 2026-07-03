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
 * Proves the Quarkus Live Activity correlation path for an <em>{@code ExceptionMapper}-handled</em>
 * exception, end to end with real OpenTelemetry context propagation — the mapped-exception analogue of
 * {@link BootUiQuarkusLiveActivityExceptionCorrelationTest}.
 *
 * <p>Hitting {@code /it/mapped-boom} throws a failure that a custom {@code ExceptionMapper} resolves to a
 * 422 without ever logging it, so unlike the plain {@code /it/boom} scenario, {@code
 * QuarkusExceptionLogHandler} never fires and {@code QuarkusExceptionCaptureFilter} never fires either
 * (RESTEasy Reactive never calls {@code RoutingContext.fail(...)} once a mapper has produced a response).
 * Only {@code QuarkusPreMappingExceptionCaptureHandler} — hooked in via the RESTEasy Reactive {@code
 * PreExceptionMapperHandlerBuildItem} extension point — observes the throwable before it is mapped. This
 * test proves that handler resolves the trace id, method, path, and JAX-RS handler correctly from that
 * earlier point in the request lifecycle, so the resulting EXCEPTION entry nests under its owning REQUEST
 * entry exactly like every other capture path.</p>
 */
@QuarkusTest
class BootUiQuarkusMappedExceptionCorrelationTest {

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void mappedExceptionNestsUnderItsOwningRequestWithMethodAndPath() {
        Response probeCall = probe().get("/it/mapped-boom");
        assertThat(probeCall.status())
                .as("the ExceptionMapper must produce its own response, not a generic 500")
                .isEqualTo(422);

        Response activity = probe().get("/bootui/api/activity");
        assertThat(activity.status()).as("GET /bootui/api/activity status").isEqualTo(200);

        JsonNode entries = activity.json().path("entries");
        assertThat(entries.isArray()).as("the activity feed must carry entries").isTrue();

        JsonNode request = null;
        for (JsonNode entry : entries) {
            if ("REQUEST".equals(entry.path("type").asText())
                    && "/it/mapped-boom".equals(entry.path("path").asText())) {
                request = entry;
            }
        }
        assertThat(request)
                .as("the /it/mapped-boom call must surface as a REQUEST entry")
                .isNotNull();
        String requestId = request.path("id").asText(null);
        String requestTrace = request.path("correlationId").asText(null);
        assertThat(requestTrace)
                .as("with OpenTelemetry present the request entry must carry the server span's trace id")
                .isNotBlank();
        assertThat(request.path("status").asInt())
                .as("the request's own status must be the mapper's 422, not a generic 500")
                .isEqualTo(422);

        JsonNode exception = null;
        for (JsonNode entry : entries) {
            if ("EXCEPTION".equals(entry.path("type").asText())
                    && "/it/mapped-boom".equals(entry.path("path").asText())) {
                exception = entry;
            }
        }
        assertThat(exception)
                .as("regression: the ExceptionMapper-handled failure must surface as an EXCEPTION entry, "
                        + "even though it was never logged nor propagated to Vert.x as a routing failure")
                .isNotNull();
        assertThat(exception.path("correlationId").asText(null))
                .as("the exception entry must carry the same trace id as its request")
                .isEqualTo(requestTrace);
        assertThat(exception.path("parentId").asText(null))
                .as("the engine must nest the exception entry under its owning request via the shared " + "trace id")
                .isEqualTo(requestId);
        assertThat(exception.path("method").asText(null)).isEqualTo("GET");
        assertThat(exception.path("path").asText(null)).isEqualTo("/it/mapped-boom");

        Response exceptionsReport = probe().get("/bootui/api/exceptions");
        assertThat(exceptionsReport.status())
                .as("GET /bootui/api/exceptions status")
                .isEqualTo(200);
        JsonNode group = null;
        for (JsonNode candidate : exceptionsReport.json().path("groups")) {
            if (candidate
                    .path("exceptionClassName")
                    .asText()
                    .endsWith("MappedExceptionProbeResource$MappedBusinessException")) {
                group = candidate;
            }
        }
        assertThat(group)
                .as("the mapper-handled failure must surface as a group in the Exceptions panel report")
                .isNotNull();
        assertThat(group.path("lastHandler").asText(null))
                .as("the JAX-RS resource class + method that was handling the request")
                .isEqualTo("MappedExceptionProbeResource#mappedBoom");
    }
}
