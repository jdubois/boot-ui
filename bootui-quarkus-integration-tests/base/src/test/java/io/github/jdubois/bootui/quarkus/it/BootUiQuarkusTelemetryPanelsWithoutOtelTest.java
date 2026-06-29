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
 * Pins the Traces and AI Usage panels' behavior on a Quarkus app that does <strong>not</strong> have
 * {@code quarkus-opentelemetry} on its classpath (this integration-test module deliberately omits it).
 *
 * <p>This is the OpenTelemetry-<em>absent</em> half of the telemetry coverage (the OTel-present capture
 * path lives in the sibling {@code bootui-quarkus-otel-integration-tests} module). It proves the R2/BF2
 * safety guarantee: the engine telemetry <em>read</em> services are wired unconditionally, so both panels
 * are available and answer their GETs with valid empty JSON, while the OTel-importing capture producer is
 * never registered — the app boots clean with no {@code NoClassDefFoundError} from the absent SDK. It also
 * pins the BF3 404 seam for an unknown chat span, which the shared conformance suite does not cover because
 * the AI panel's data lives at sub-paths.</p>
 */
@QuarkusTest
class BootUiQuarkusTelemetryPanelsWithoutOtelTest {

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void tracesPanelAnswersEmptyWithoutOpenTelemetry() {
        Response response = probe().get("/bootui/api/traces");
        assertThat(response.status()).as("GET /bootui/api/traces status").isEqualTo(200);
        assertThat(response.isJson())
                .as("GET /bootui/api/traces content-type (%s)", response.contentType())
                .isTrue();
        assertThat(response.json().path("retained").asInt(-1))
                .as("no spans can be captured without quarkus-opentelemetry, so the buffer is empty")
                .isZero();
    }

    @Test
    void aiOverviewAnswersEmptyWithoutOpenTelemetry() {
        Response response = probe().get("/bootui/api/ai/overview");
        assertThat(response.status()).as("GET /bootui/api/ai/overview status").isEqualTo(200);
        assertThat(response.isJson())
                .as("GET /bootui/api/ai/overview content-type (%s)", response.contentType())
                .isTrue();
        assertThat(response.json().path("totalChats").asInt(-1))
                .as("no chat spans can be captured without quarkus-opentelemetry")
                .isZero();
    }

    @Test
    void aiChatsAndTokensAnswerEmptyWithoutOpenTelemetry() {
        Response chats = probe().get("/bootui/api/ai/chats");
        assertThat(chats.status()).as("GET /bootui/api/ai/chats status").isEqualTo(200);
        assertThat(chats.json().isArray()).as("chats is a JSON array").isTrue();
        assertThat(chats.json()).as("no chats captured without OpenTelemetry").isEmpty();

        Response tokens = probe().get("/bootui/api/ai/tokens");
        assertThat(tokens.status()).as("GET /bootui/api/ai/tokens status").isEqualTo(200);
        JsonNode buckets = tokens.json().path("buckets");
        assertThat(buckets.isArray())
                .as("the token series is always a (zero-filled) array even with no captures")
                .isTrue();
    }

    @Test
    void unknownChatDetailIsNotFound() {
        Response response = probe().get("/bootui/api/ai/chats/does-not-exist");
        assertThat(response.status())
                .as("an unknown chat span id must map Optional.empty() to a 404")
                .isEqualTo(404);
    }
}
