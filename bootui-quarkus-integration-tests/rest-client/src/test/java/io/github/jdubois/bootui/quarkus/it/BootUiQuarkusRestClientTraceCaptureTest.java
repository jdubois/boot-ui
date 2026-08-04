package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.acme.restclient.PingClient;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Test;

/**
 * End-to-end coverage for the capability-gated REST Client listener, metadata-only capture, panel
 * actions, Live Activity integration, and application-filter composition.
 */
@QuarkusTest
class BootUiQuarkusRestClientTraceCaptureTest {

    private static final String LISTENER_CLASS =
            "io.github.jdubois.bootui.quarkus.restclienttrace.QuarkusRestClientTraceListener";
    private static final String LISTENER_SERVICE =
            "META-INF/services/org.eclipse.microprofile.rest.client.spi.RestClientListener";
    private static final Map<String, String> JSON_HEADERS = Map.of("Content-Type", "application/json");

    @TestHTTPResource
    URL baseUrl;

    @Inject
    @RestClient
    PingClient pingClient;

    @Test
    void capabilityEmitsServiceProviderAndMakesInstrumentedPanelAvailable() throws IOException {
        String serviceProviders =
                Collections.list(Thread.currentThread().getContextClassLoader().getResources(LISTENER_SERVICE)).stream()
                        .map(BootUiQuarkusRestClientTraceCaptureTest::read)
                        .reduce("", (left, right) -> left + '\n' + right);
        assertThat(serviceProviders).contains(LISTENER_CLASS);

        JsonNode restClientPanel = panel();
        assertThat(restClientPanel.path("available").asBoolean(false)).isTrue();
        assertThat(restClientPanel.path("enabled").asBoolean(false)).isTrue();
        assertThat(restClientPanel.path("unavailableReason").isNull()).isTrue();
    }

    @Test
    void capturesReceivedHttpErrorsAsResponsesAndBracketsApplicationFilters() {
        resetRecorder();

        try (jakarta.ws.rs.core.Response response = pingClient.httpError()) {
            assertThat(response.getStatus()).isEqualTo(503);
        }
        JsonNode errorEntry = report().path("entries").get(0);
        assertThat(errorEntry.path("status").asInt()).isEqualTo(503);
        assertThat(errorEntry.path("success").asBoolean(false)).isTrue();
        assertThat(errorEntry.path("errorMessage").isNull()).isTrue();

        resetRecorder();
        try (jakarta.ws.rs.core.Response response = pingClient.filteredStatus()) {
            // The application response filter rewrites 418 after BootUI has measured the transport.
            assertThat(response.getStatus()).isEqualTo(200);
        }
        JsonNode filteredEntry = report().path("entries").get(0);
        assertThat(filteredEntry.path("status").asInt()).isEqualTo(418);
        assertThat(filteredEntry.path("success").asBoolean(false)).isTrue();
    }

    @Test
    void neverRetainsHeadersBodiesCookiesTokensOrRawCredentialQueryValues() {
        resetRecorder();

        String response = pingClient.echo(
                "query-token-secret", "Bearer authorization-secret", "session=cookie-secret", "request-body-secret");

        assertThat(response).isEqualTo("response-body-secret-value");
        JsonNode report = report();
        JsonNode entry = report.path("entries").get(0);
        assertThat(entry.path("uri").asText()).contains("api-token=******").doesNotContain("query-token-secret");
        assertThat(entry.path("requestHeaders")).isEmpty();
        assertThat(report.toString())
                .doesNotContain(
                        "query-token-secret",
                        "authorization-secret",
                        "cookie-secret",
                        "request-body-secret",
                        "response-body-secret-value",
                        "downstream-error-body-secret");
    }

    @Test
    void transportFailureIsCapturedWithoutInventingAnHttpStatus() {
        resetRecorder();
        PingClient failingClient = RestClientBuilder.newBuilder()
                .baseUri(URI.create("http://127.0.0.1:1"))
                .connectTimeout(250, TimeUnit.MILLISECONDS)
                .readTimeout(250, TimeUnit.MILLISECONDS)
                .build(PingClient.class);
        try {
            assertThatThrownBy(() -> failingClient.ping(1)).isInstanceOf(RuntimeException.class);
        } finally {
            close(failingClient);
        }

        JsonNode entry = report().path("entries").get(0);
        assertThat(entry.path("status").isNull()).isTrue();
        assertThat(entry.path("success").asBoolean(true)).isFalse();
        assertThat(entry.path("errorMessage").asText()).contains("transport failed");
    }

    @Test
    void reusingABuilderDoesNotRegisterDuplicateCaptureFilters() {
        RestClientBuilder builder = RestClientBuilder.newBuilder().baseUri(URI.create(baseUrl.toExternalForm()));
        PingClient first = builder.build(PingClient.class);
        PingClient second = builder.build(PingClient.class);
        try {
            resetRecorder();
            assertThat(first.ping(7)).isEqualTo("pong-7");
            assertThat(report().path("entries")).hasSize(1);
        } finally {
            close(first);
            close(second);
        }
    }

    @Test
    void clearRecordingAndLiveActivityActionsUseTheSharedRecorder() {
        resetRecorder();
        assertThat(pingClient.ping(42)).isEqualTo("pong-42");
        assertThat(report().path("entries")).hasSize(1);

        Response activity = probe().get("/bootui/api/activity");
        assertThat(activity.status()).isEqualTo(200);
        assertThat(activity.json().path("entries"))
                .anySatisfy(entry -> assertThat(entry.path("type").asText()).isEqualTo("REST_CLIENT"));

        Response pause =
                probe().request("POST", "/bootui/api/rest-client-trace/recording", JSON_HEADERS, "{\"enabled\":false}");
        assertThat(pause.status()).isEqualTo(200);
        assertThat(pause.json().path("capturing").asBoolean(true)).isFalse();
        probe().request("POST", "/bootui/api/rest-client-trace/clear", JSON_HEADERS, "{}");
        pingClient.ping(43);
        assertThat(report().path("entries")).isEmpty();

        Response resume =
                probe().request("POST", "/bootui/api/rest-client-trace/recording", JSON_HEADERS, "{\"enabled\":true}");
        assertThat(resume.status()).isEqualTo(200);
        assertThat(resume.json().path("capturing").asBoolean(false)).isTrue();
        pingClient.ping(44);
        assertThat(report().path("entries")).hasSize(1);

        Response clear = probe().request("POST", "/bootui/api/rest-client-trace/clear", JSON_HEADERS, "{}");
        assertThat(clear.status()).isEqualTo(200);
        assertThat(clear.json().path("entries")).isEmpty();
    }

    @Test
    void crossSiteWriteIsRejectedByTheSharedLocalhostGuard() {
        Map<String, String> headers = Map.of("Content-Type", "application/json", "Origin", "https://attacker.example");

        Response response =
                probe().request("POST", "/bootui/api/rest-client-trace/recording", headers, "{\"enabled\":false}");

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.body()).contains("BootUI rejected a cross-site request to a state-changing endpoint.");
    }

    private JsonNode panel() {
        Response panels = probe().get("/bootui/api/panels");
        assertThat(panels.status()).isEqualTo(200);
        for (JsonNode panel : panels.json().path("panels")) {
            if ("rest-client-trace".equals(panel.path("id").asText(null))) {
                return panel;
            }
        }
        throw new AssertionError("REST Client panel missing from manifest");
    }

    private JsonNode report() {
        Response response = probe().get("/bootui/api/rest-client-trace");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.isJson()).isTrue();
        assertThat(response.json().path("available").asBoolean(false)).isTrue();
        return response.json();
    }

    private void resetRecorder() {
        Response resume =
                probe().request("POST", "/bootui/api/rest-client-trace/recording", JSON_HEADERS, "{\"enabled\":true}");
        assertThat(resume.status()).isEqualTo(200);
        Response clear = probe().request("POST", "/bootui/api/rest-client-trace/clear", JSON_HEADERS, "{}");
        assertThat(clear.status()).isEqualTo(200);
    }

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    private static String read(URL url) {
        try (var input = url.openStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not read generated REST Client listener service file", failure);
        }
    }

    private static void close(Object client) {
        if (client instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception failure) {
                throw new IllegalStateException("Could not close REST Client proxy", failure);
            }
        }
    }
}
