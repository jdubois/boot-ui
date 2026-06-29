package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URL;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * Real-boot checks for the Quarkus Exceptions panel ({@code ExceptionsResource} over the shared engine
 * {@code ExceptionStore}, fed by {@code QuarkusExceptionLogHandler} + {@code QuarkusExceptionCaptureFilter}).
 * Verifies the log feeder is installed against the live backend: a logged throwable is captured, repeated
 * occurrences of the same failure group into one entry with a count, secret-like messages are masked, and
 * the panel is available in the manifest. BootUI's own loggers are excluded so it never tails its internals.
 */
@QuarkusTest
class BootUiQuarkusExceptionsResourceTest {

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

    private static IllegalStateException makeException() {
        return new IllegalStateException("checkout failed password=hunter2");
    }
}
