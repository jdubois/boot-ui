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
 * Proves the Quarkus Live Activity correlation path for <em>exceptions</em>, end to end with real
 * OpenTelemetry context propagation. Hitting {@code /it/boom} throws unconditionally; Quarkus logs the
 * failure via {@code QuarkusErrorHandler} before the HTTP response is finalized, so
 * {@code QuarkusExceptionLogHandler} — not the later-firing {@code QuarkusExceptionCaptureFilter} — is the
 * feeder that wins the shared {@code ExceptionStore}'s cause-chain dedup for every logged failure.
 *
 * <p>This is a regression test for a bug where that dedup meant the log handler's capture (no HTTP
 * context) always won over the filter's capture (full HTTP context, but too late), so a Quarkus-captured
 * exception's {@code method}/{@code path} were deterministically {@code null} — even though trace-id
 * correlation ({@code parentId}/{@code correlationId} nesting) worked correctly. The fix resolves
 * {@code method}/{@code path} directly in {@code QuarkusExceptionLogHandler} from the CDI-current
 * {@code CurrentVertxRequest}, which Quarkus populates before resource-method invocation and which survives
 * the event-loop→worker-thread hop. See {@code BootUiQuarkusLiveActivityCorrelationTest} for the SQL
 * analogue of this same trace-id nesting proof.</p>
 *
 * <p>It also covers the sibling {@code handler} field (the JAX-RS resource class + method that was serving
 * the request), resolved via {@code QuarkusResourceHandlers} from RESTEasy Reactive's
 * {@code CurrentRequestManager}/{@code ResteasyReactiveResourceInfo} — populated in lockstep with the same
 * CDI-current state {@code method}/{@code path} rely on, so it survives the same thread hop. Unlike
 * {@code method}/{@code path}, {@code handler} is not part of the Live Activity feed's {@code
 * ActivityEntryDto} wire (that shape has no such field on any platform), so this is asserted against the
 * Exceptions panel's own {@code GET /bootui/api/exceptions} report instead, on the matching group's {@code
 * lastHandler}.</p>
 */
@QuarkusTest
class BootUiQuarkusLiveActivityExceptionCorrelationTest {

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void exceptionNestsUnderItsOwningRequestWithMethodAndPath() {
        Response probeCall = probe().get("/it/boom");
        assertThat(probeCall.status())
                .as("the boom probe endpoint must fail with a server error")
                .isGreaterThanOrEqualTo(500);

        Response activity = probe().get("/bootui/api/activity");
        assertThat(activity.status()).as("GET /bootui/api/activity status").isEqualTo(200);

        JsonNode entries = activity.json().path("entries");
        assertThat(entries.isArray()).as("the activity feed must carry entries").isTrue();

        JsonNode request = null;
        for (JsonNode entry : entries) {
            if ("REQUEST".equals(entry.path("type").asText())
                    && "/it/boom".equals(entry.path("path").asText())) {
                request = entry;
            }
        }
        assertThat(request)
                .as("the /it/boom call must surface as a REQUEST entry")
                .isNotNull();
        String requestId = request.path("id").asText(null);
        String requestTrace = request.path("correlationId").asText(null);
        assertThat(requestTrace)
                .as("with OpenTelemetry present the request entry must carry the server span's trace id")
                .isNotBlank();

        JsonNode exception = null;
        for (JsonNode entry : entries) {
            if ("EXCEPTION".equals(entry.path("type").asText())) {
                exception = entry;
            }
        }
        assertThat(exception)
                .as("the failure thrown by /it/boom must surface as an EXCEPTION entry")
                .isNotNull();
        assertThat(exception.path("correlationId").asText(null))
                .as("the exception entry must carry the same trace id as its request (propagated across "
                        + "the event-loop→worker hop)")
                .isEqualTo(requestTrace);
        assertThat(exception.path("parentId").asText(null))
                .as("the engine must nest the exception entry under its owning request via the shared " + "trace id")
                .isEqualTo(requestId);
        assertThat(exception.path("method").asText(null))
                .as("regression: the exception must carry its owning request's HTTP method, not null — "
                        + "QuarkusExceptionLogHandler must resolve it from the CDI-current request, since "
                        + "it always wins the ExceptionStore dedup race against QuarkusExceptionCaptureFilter")
                .isEqualTo("GET");
        assertThat(exception.path("path").asText(null))
                .as("regression: the exception must carry its owning request's path, not null")
                .isEqualTo("/it/boom");

        Response exceptionsReport = probe().get("/bootui/api/exceptions");
        assertThat(exceptionsReport.status())
                .as("GET /bootui/api/exceptions status")
                .isEqualTo(200);
        JsonNode group = null;
        for (JsonNode candidate : exceptionsReport.json().path("groups")) {
            if ("java.lang.IllegalStateException"
                    .equals(candidate.path("exceptionClassName").asText())) {
                group = candidate;
            }
        }
        assertThat(group)
                .as("the failure thrown by /it/boom must surface as a group in the Exceptions panel report")
                .isNotNull();
        assertThat(group.path("lastHandler").asText(null))
                .as("regression: the exception must carry the JAX-RS resource class + method that was "
                        + "handling the request, not null — QuarkusResourceHandlers must resolve it from "
                        + "RESTEasy Reactive's current-request state, mirroring method/path")
                .isEqualTo("ExceptionProbeResource#boom");
    }
}
