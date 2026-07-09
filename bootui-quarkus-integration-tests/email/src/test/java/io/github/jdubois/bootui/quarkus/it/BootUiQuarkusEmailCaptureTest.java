package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Proves the Quarkus Email Viewer panel light-up end to end on an app that <strong>does</strong> have
 * {@code quarkus-mailer} on its classpath: an outgoing message sent through the application's {@link Mailer}
 * fires a {@code io.quarkus.mailer.SentMail} CDI event that {@code QuarkusEmailCapture} maps into the shared
 * engine {@code EmailCaptureService}, so the panel is surfaced on {@code GET /bootui/api/email} with working
 * per-message detail + {@code .eml} download reads and a {@code DELETE} clear action — all in-process, no
 * Docker (the test-mode mailer is mock, and the {@code SentMail} event fires in mock mode too).
 *
 * <p>This is the mailer-<em>present</em> half of the Email coverage; the sibling
 * {@code bootui-quarkus-integration-tests} module proves the mailer-<em>absent</em> path (the class-presence
 * gate keeps the {@code email} panel unavailable, with no capture observer). {@code bootui.email.mask-content}
 * defaults to {@code false} (email content is not a config secret, so it is revealed by default, decoupled
 * from the global {@code bootui.expose-values} secret-exposure flag — see the engine
 * {@code EmailCaptureService}'s class Javadoc), so this asserts recipient/subject/body content is revealed on
 * Quarkus exactly as on Spring, while attachment metadata (never masked) confirms the real message flowed
 * through. Nothing here triggers a network call or scan on render — only the explicit {@code DELETE} mutates
 * state.</p>
 */
@QuarkusTest
class BootUiQuarkusEmailCaptureTest {

    @TestHTTPResource
    URL baseUrl;

    @Inject
    Mailer mailer;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void emailPanelCapturesMessagesRevealsThemAndClears() {
        mailer.send(Mail.withText("customer@example.com", "Welcome to BootUI", "Hello from the Quarkus sample")
                .addAttachment("invoice.txt", "INV-1".getBytes(StandardCharsets.UTF_8), "text/plain"));

        Response report = probe().get("/bootui/api/email");
        assertThat(report.status()).as("GET /bootui/api/email status").isEqualTo(200);
        assertThat(report.isJson())
                .as("GET /bootui/api/email content-type (%s)", report.contentType())
                .isTrue();

        JsonNode root = report.json();
        assertThat(root.path("available").asBoolean(false))
                .as("with quarkus-mailer present the panel is available")
                .isTrue();
        assertThat(root.path("devTrapEnabled").asBoolean(false))
                .as("the test-mode mailer is mock, so captured messages are recorded but not really sent")
                .isTrue();
        assertThat(root.path("total").asInt(0)).as("one message was captured").isEqualTo(1);

        JsonNode message = root.path("messages").path(0);
        String id = message.path("id").asText(null);
        assertThat(id).as("the captured message has a stable id").isNotBlank();
        assertThat(message.path("sent").asBoolean(true))
                .as("mock-mode messages are not handed to a real transport")
                .isFalse();
        assertThat(message.path("subject").asText(null))
                .as("the subject is revealed by default (bootui.email.mask-content defaults to false)")
                .isEqualTo("Welcome to BootUI");
        assertThat(message.path("to").path(0).asText(null))
                .as("recipients are revealed by default")
                .isEqualTo("customer@example.com");

        JsonNode attachment = message.path("attachments").path(0);
        assertThat(attachment.path("filename").asText(null))
                .as("attachment metadata is never masked, confirming the real message flowed through")
                .isEqualTo("invoice.txt");
        assertThat(attachment.path("contentType").asText(null)).isEqualTo("text/plain");
        assertThat(attachment.hasNonNull("sizeBytes"))
                .as("SentAttachment exposes no size, so the attachment size is null/unknown on Quarkus")
                .isFalse();

        // Per-message detail read.
        Response detail = probe().get("/bootui/api/email/" + id);
        assertThat(detail.status()).as("GET /bootui/api/email/{id} status").isEqualTo(200);
        assertThat(detail.json().path("id").asText(null)).isEqualTo(id);

        // .eml download: the shared EmailEmlRenderer produces byte-identical output to Spring.
        Response eml = probe().get("/bootui/api/email/" + id + "/eml");
        assertThat(eml.status()).as("GET /bootui/api/email/{id}/eml status").isEqualTo(200);
        assertThat(eml.contentType())
                .as("the .eml download is served as message/rfc822")
                .contains("message/rfc822");
        assertThat(eml.body())
                .as("the .eml carries the revealed subject and the (unmasked, size-less) attachment metadata")
                .contains("Subject: Welcome to BootUI")
                .contains("invoice.txt (text/plain, null bytes)");

        // The clear action (state-changing, behind the shared LocalhostGuard write floor) empties the buffer.
        Response clear = probe().request("DELETE", "/bootui/api/email", Map.of(), null);
        assertThat(clear.status()).as("DELETE /bootui/api/email status").isEqualTo(204);

        assertThat(probe().get("/bootui/api/email").json().path("total").asInt(-1))
                .as("no surprise repopulation on render after clearing")
                .isEqualTo(0);
    }
}
