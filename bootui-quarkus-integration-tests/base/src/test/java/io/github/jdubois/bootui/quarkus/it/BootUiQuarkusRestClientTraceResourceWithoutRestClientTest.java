package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/**
 * Pins the REST Client panel's behavior on a Quarkus app that does <strong>not</strong> have
 * {@code quarkus-rest-client} on its classpath (this integration-test module deliberately omits it).
 *
 * <p>This is the REST-client-<em>absent</em> half of the coverage (the REST-client-present light-up path lives
 * in the dedicated {@code bootui-quarkus-rest-client-integration-tests} module). It proves the R2 gate fails
 * closed: the {@code QuarkusRestClientTraceListener} class (which implements
 * {@code org.eclipse.microprofile.rest.client.spi.RestClientListener} from the absent API) is never loaded
 * because the deployment processor sees no REST Client Reactive capability, excludes the optional importer
 * from Arc, and emits no service-provider entry. The always-
 * produced {@code RestClientTraceRecorder} is still wired (it holds no REST-client-reactive types), so
 * {@code GET /bootui/api/rest-client-trace} answers with valid JSON reporting the panel unavailable — no
 * {@code NoClassDefFoundError}.</p>
 */
@QuarkusTest
class BootUiQuarkusRestClientTraceResourceWithoutRestClientTest {

    private static final String LISTENER_CLASS =
            "io.github.jdubois.bootui.quarkus.restclienttrace.QuarkusRestClientTraceListener";
    private static final String LISTENER_SERVICE =
            "META-INF/services/org.eclipse.microprofile.rest.client.spi.RestClientListener";

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void restClientPanelIsUnavailableWithCapabilityHintWithoutRestClientReactive() {
        Response panels = probe().get("/bootui/api/panels");
        assertThat(panels.status()).as("GET /bootui/api/panels status").isEqualTo(200);

        JsonNode restClientPanel = null;
        for (JsonNode panel : panels.json().path("panels")) {
            if ("rest-client-trace".equals(panel.path("id").asText(null))) {
                restClientPanel = panel;
            }
        }
        assertThat(restClientPanel)
                .as("the REST Client panel is present in the manifest")
                .isNotNull();
        assertThat(restClientPanel.path("available").asBoolean(true))
                .as("the REST Client panel is unavailable when quarkus-rest-client is absent")
                .isFalse();
        assertThat(restClientPanel.path("unavailableReason").asText(null))
                .as("the unavailable reason names the extension to add, not the generic 'not yet' reason")
                .contains("quarkus-rest-client");
    }

    @Test
    void restClientTraceReportRendersUnavailableWithoutRestClientReactive() {
        Response report = probe().get("/bootui/api/rest-client-trace");
        assertThat(report.status())
                .as("GET /bootui/api/rest-client-trace status")
                .isEqualTo(200);
        assertThat(report.isJson())
                .as("GET /bootui/api/rest-client-trace content-type (%s)", report.contentType())
                .isTrue();
        JsonNode root = report.json();
        assertThat(root.path("available").asBoolean(true))
                .as("the report is unavailable when no @RegisterRestClient proxy has been instrumented")
                .isFalse();
        assertThat(root.path("unavailableReason").asText()).contains("quarkus-rest-client");
    }

    @Test
    void optionalApiAndGeneratedListenerServiceEntryAreAbsent() throws IOException {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        assertThatThrownBy(() ->
                        Class.forName("org.eclipse.microprofile.rest.client.RestClientBuilder", false, classLoader))
                .isInstanceOf(ClassNotFoundException.class);

        String serviceProviders = Collections.list(classLoader.getResources(LISTENER_SERVICE)).stream()
                .map(BootUiQuarkusRestClientTraceResourceWithoutRestClientTest::read)
                .reduce("", (left, right) -> left + '\n' + right);
        assertThat(serviceProviders).doesNotContain(LISTENER_CLASS);
    }

    private static String read(URL url) {
        try (var input = url.openStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not inspect REST Client listener service file", failure);
        }
    }
}
