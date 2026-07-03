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
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * Real-boot checks for the Quarkus Exceptions panel ({@code ExceptionsResource} over the shared engine
 * {@code ExceptionStore}, fed by {@code QuarkusExceptionLogHandler} + {@code QuarkusExceptionCaptureFilter}).
 * Verifies the log feeder is installed against the live backend: a logged throwable is captured, repeated
 * occurrences of the same failure group into one entry with a count, secret-like messages are masked, and
 * the panel is available in the manifest. BootUI's own loggers are excluded so it never tails its internals.
 * Also covers the triage status endpoint ({@code POST .../{id}/status}): a successful status change, the
 * 400/404 error paths, and the Sentry-style regression auto-reopen when a resolved group fires again.
 */
@QuarkusTest
class BootUiQuarkusExceptionsResourceTest {

    private static final Map<String, String> JSON_HEADERS = Map.of("Content-Type", "application/json");

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void capturesAndGroupsLoggedExceptionsWithMaskedMessages() {
        Logger logger = Logger.getLogger("com.example.quarkus.ExceptionsProbe");
        for (int i = 0; i < 2; i++) {
            logger.log(Level.SEVERE, "checkout failed password=hunter2", makeException());
        }

        Response response = probe().get("/bootui/api/exceptions");
        assertThat(response.status()).as("exceptions status").isEqualTo(200);
        assertThat(response.isJson()).isTrue();

        JsonNode report = response.json();
        assertThat(report.path("available").asBoolean()).isTrue();
        assertThat(report.path("totalExceptions").asInt()).isGreaterThanOrEqualTo(2);

        JsonNode group = null;
        for (JsonNode candidate : report.path("groups")) {
            if (candidate.path("exceptionClassName").asText().equals("java.lang.IllegalStateException")) {
                group = candidate;
                break;
            }
        }
        assertThat(group).as("captured + grouped logged exception").isNotNull();
        assertThat(group.path("count").asInt()).as("repeated failures grouped").isEqualTo(2);
        assertThat(group.path("message").asText())
                .as("secret masked")
                .contains("password=")
                .doesNotContain("hunter2");
    }

    @Test
    void updatesStatusAndReturnsTheUpdatedGroup() {
        Logger logger = Logger.getLogger("com.example.quarkus.ExceptionsStatusProbe");
        logger.log(Level.SEVERE, "status update probe", new IllegalStateException("status update probe"));

        String id = findGroupId("java.lang.IllegalStateException", "status update probe");

        Response response = probe().request(
                        "POST",
                        "/bootui/api/exceptions/" + id + "/status",
                        JSON_HEADERS,
                        "{\"status\":\"ACKNOWLEDGED\"}");

        assertThat(response.status()).as("POST status update status").isEqualTo(200);
        assertThat(response.isJson())
                .as("the 200 body must be JSON (%s)", response.contentType())
                .isTrue();
        JsonNode updated = response.json();
        assertThat(updated.path("id").asText()).isEqualTo(id);
        assertThat(updated.path("status").asText()).isEqualTo("ACKNOWLEDGED");
        assertThat(updated.path("regressionCount").asLong()).isZero();
    }

    @Test
    void updateStatusRejectsAnInvalidStatusWith400() {
        Logger logger = Logger.getLogger("com.example.quarkus.ExceptionsStatusProbe");
        logger.log(Level.SEVERE, "invalid status probe", new IllegalStateException("invalid status probe"));

        String id = findGroupId("java.lang.IllegalStateException", "invalid status probe");

        Response response = probe().request(
                        "POST", "/bootui/api/exceptions/" + id + "/status", JSON_HEADERS, "{\"status\":\"CLOSED\"}");

        assertThat(response.status())
                .as("invalid status must be rejected with 400")
                .isEqualTo(400);
        assertThat(response.isJson())
                .as("the 400 body must be JSON (%s)", response.contentType())
                .isTrue();
        assertThat(response.json().path("error").asText()).isNotBlank();
    }

    @Test
    void updateStatusReturns404ForAnUnknownFingerprint() {
        Response response = probe().request(
                        "POST", "/bootui/api/exceptions/does-not-exist/status", JSON_HEADERS, "{\"status\":\"OPEN\"}");

        assertThat(response.status())
                .as("unknown fingerprint must be rejected with 404")
                .isEqualTo(404);
    }

    @Test
    void resolvedGroupAutoReopensWithARegressionMarkerOnANewOccurrence() {
        Logger logger = Logger.getLogger("com.example.quarkus.ExceptionsRegressionProbe");
        // Both occurrences must come from the exact same construction call site: ExceptionStore's
        // fingerprint incorporates each frame's line number, so two "identical" exceptions built from
        // different source lines would land in different groups. Pre-generating both from one fixed
        // loop (mirroring the engine test suite's sameOrigin helper) keeps their fingerprints equal.
        List<IllegalStateException> occurrences = makeRegressionExceptions(2);

        logger.log(Level.SEVERE, "regression probe", occurrences.get(0));

        String id = findGroupId("java.lang.IllegalStateException", "regression probe");

        Response resolve = probe().request(
                        "POST", "/bootui/api/exceptions/" + id + "/status", JSON_HEADERS, "{\"status\":\"RESOLVED\"}");
        assertThat(resolve.status()).as("resolve status").isEqualTo(200);
        assertThat(resolve.json().path("status").asText()).isEqualTo("RESOLVED");

        logger.log(Level.SEVERE, "regression probe", occurrences.get(1));

        Response detail = probe().get("/bootui/api/exceptions/" + id);
        assertThat(detail.status()).as("detail status").isEqualTo(200);
        JsonNode group = detail.json().path("group");
        assertThat(group.path("status").asText()).as("auto-reopened to OPEN").isEqualTo("OPEN");
        assertThat(group.path("regressionCount").asLong())
                .as("regression counted")
                .isEqualTo(1);
    }

    private String findGroupId(String exceptionClassName, String messageContains) {
        Response response = probe().get("/bootui/api/exceptions");
        for (JsonNode candidate : response.json().path("groups")) {
            if (candidate.path("exceptionClassName").asText().equals(exceptionClassName)
                    && candidate.path("message").asText().contains(messageContains)) {
                return candidate.path("id").asText();
            }
        }
        throw new AssertionError("no group found for " + exceptionClassName + " / " + messageContains);
    }

    private static List<IllegalStateException> makeRegressionExceptions(int count) {
        List<IllegalStateException> exceptions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            exceptions.add(new IllegalStateException("regression probe"));
        }
        return exceptions;
    }

    private static IllegalStateException makeException() {
        return new IllegalStateException("checkout failed password=hunter2");
    }
}
