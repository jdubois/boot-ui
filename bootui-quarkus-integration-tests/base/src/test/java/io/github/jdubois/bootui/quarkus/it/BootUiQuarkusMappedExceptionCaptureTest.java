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
 * Regression test for a capture gap where an exception fully resolved by a custom
 * {@code jakarta.ws.rs.ext.ExceptionMapper} (RESTEasy Reactive's analogue of Spring's
 * {@code @ExceptionHandler}) was invisible to BootUI: {@code QuarkusExceptionLogHandler} never fires
 * because the mapper never logs, and {@code QuarkusExceptionCaptureFilter} never fires either because a
 * mapper-handled exception never reaches Vert.x as a routing failure. {@code
 * QuarkusPreMappingExceptionCaptureHandler} closes the gap via the RESTEasy Reactive {@code
 * PreExceptionMapperHandlerBuildItem} extension point, which Quarkus guarantees runs for every exception
 * about to be resolved by any mapper — mapped or not — before the response is produced. This brings Quarkus
 * to parity with the Spring adapter's {@code BootUiExceptionHandlerResolver}, which already captures every
 * {@code @ExceptionHandler}-resolved exception the same way.
 *
 * <p>This module has no {@code quarkus-opentelemetry} dependency, so it only proves the exception is
 * captured and visible in both panels with the correct method/path/handler — not trace-id-based nesting
 * under its owning request (see {@code BootUiQuarkusLiveActivityProfileWithoutOtelTest} for why that is
 * expected here). The OpenTelemetry-present nesting proof for this same mapped-exception scenario lives in
 * {@code BootUiQuarkusMappedExceptionCorrelationTest} in the sibling {@code otel} module.</p>
 */
@QuarkusTest
class BootUiQuarkusMappedExceptionCaptureTest {

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void exceptionMapperHandledFailureIsCapturedAndVisibleInBothPanels() {
        Response probeCall = probe().get("/it/mapped-boom");
        assertThat(probeCall.status())
                .as("the ExceptionMapper must produce its own response, not a generic 500")
                .isEqualTo(422);

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
                .as("regression: an ExceptionMapper-handled failure must still surface as a group in the "
                        + "Exceptions panel report, even though it was never logged nor propagated to Vert.x "
                        + "as a routing failure")
                .isNotNull();
        assertThat(group.path("message").asText(null)).isEqualTo("it-mapped-boom");
        assertThat(group.path("lastRequestMethod").asText(null)).isEqualTo("GET");
        assertThat(group.path("lastRequestPath").asText(null)).isEqualTo("/it/mapped-boom");
        assertThat(group.path("lastHandler").asText(null))
                .as("the JAX-RS resource class + method that was handling the request")
                .isEqualTo("MappedExceptionProbeResource#mappedBoom");

        Response activity = probe().get("/bootui/api/activity");
        assertThat(activity.status()).as("GET /bootui/api/activity status").isEqualTo(200);

        JsonNode exceptionEntry = null;
        for (JsonNode entry : activity.json().path("entries")) {
            if ("EXCEPTION".equals(entry.path("type").asText())
                    && "/it/mapped-boom".equals(entry.path("path").asText())) {
                exceptionEntry = entry;
            }
        }
        assertThat(exceptionEntry)
                .as("the mapper-handled failure must also surface as an EXCEPTION entry in the Live Activity " + "feed")
                .isNotNull();
        assertThat(exceptionEntry.path("method").asText(null)).isEqualTo("GET");
    }
}
