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
 * Pins the Email Viewer panel's behavior on a Quarkus app that does <strong>not</strong> have
 * {@code quarkus-mailer} on its classpath (this integration-test module deliberately omits it).
 *
 * <p>This is the mailer-<em>absent</em> half of the Email coverage (the mailer-present light-up path lives in
 * the dedicated {@code bootui-quarkus-email-integration-tests} module). It proves the R2 class-presence gate
 * fails closed: the {@code io.quarkus.mailer.*}-importing {@code QuarkusEmailCapture} observer is excluded by
 * the deployment build step when {@code io.quarkus.mailer.reactive.ReactiveMailer} is absent, so no capture
 * happens and the {@code email} panel is reported <em>unavailable</em> in the manifest with an honest hint
 * naming the extension to add — while the mailer-API-free engine {@code EmailCaptureService} is still wired,
 * so {@code GET /bootui/api/email} answers with valid JSON reporting {@code available:false} (no
 * {@code NoClassDefFoundError} from the absent backend).</p>
 */
@QuarkusTest
class BootUiQuarkusEmailResourceWithoutMailerTest {

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void emailPanelIsUnavailableWithACapabilityHintWithoutQuarkusMailer() {
        Response panels = probe().get("/bootui/api/panels");
        assertThat(panels.status()).as("GET /bootui/api/panels status").isEqualTo(200);

        JsonNode email = null;
        for (JsonNode panel : panels.json().path("panels")) {
            if ("email".equals(panel.path("id").asText(null))) {
                email = panel;
            }
        }
        assertThat(email).as("the Email panel is present in the manifest").isNotNull();
        assertThat(email.path("available").asBoolean(true))
                .as("the Email panel is unavailable when quarkus-mailer is absent")
                .isFalse();
        assertThat(email.path("unavailableReason").asText(null))
                .as("the unavailable reason names the extension to add, not the generic 'not yet' reason")
                .contains("quarkus-mailer");
    }

    @Test
    void emailReportRendersUnavailableWithoutQuarkusMailer() {
        Response report = probe().get("/bootui/api/email");
        assertThat(report.status()).as("GET /bootui/api/email status").isEqualTo(200);
        assertThat(report.isJson())
                .as("GET /bootui/api/email content-type (%s)", report.contentType())
                .isTrue();
        assertThat(report.json().path("available").asBoolean(true))
                .as("the report is unavailable when no mail capture observer is wired")
                .isFalse();
    }
}
