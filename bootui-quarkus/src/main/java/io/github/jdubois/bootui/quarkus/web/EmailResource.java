package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.EmailMessageDto;
import io.github.jdubois.bootui.core.dto.EmailsReport;
import io.github.jdubois.bootui.engine.email.EmailCaptureService;
import io.github.jdubois.bootui.engine.email.EmailEmlRenderer;
import io.github.jdubois.bootui.quarkus.QuarkusPanelAvailability;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import org.eclipse.microprofile.config.Config;

/**
 * Read/clear API for the BootUI Email Viewer panel on Quarkus — the JAX-RS twin of the Spring adapter's
 * {@code EmailController}, over the same shared engine {@link EmailCaptureService} and the same
 * {@code /bootui/api/email} contract.
 *
 * <p>The engine service is always produced (see {@code BootUiEngineProducer#emailCaptureService}), so this
 * resource never fails even when {@code quarkus-mailer} is absent; instead it reports the panel unavailable
 * by reading the build-time {@link QuarkusPanelAvailability#EMAIL_PRESENT_KEY} flag, mirroring how the Spring
 * controller checks for a {@code JavaMailSender} bean. When present, {@code QuarkusEmailCapture} feeds the
 * service and this resource maps its already-masked result onto HTTP. The {@code .eml} download delegates to
 * the shared engine {@link EmailEmlRenderer} so the bytes are identical to Spring's.</p>
 *
 * <p>{@code GET} endpoints are passive; only {@code DELETE} (clear) mutates state, gated by the shared
 * {@code LocalhostGuard} write floor and the {@code email} panel's read-only toggle, exactly as on Spring.</p>
 */
@Path("/bootui/api/email")
public class EmailResource {

    private final EmailCaptureService service;
    private final boolean emailPresent;
    private final int maxEntries;

    @Inject
    public EmailResource(EmailCaptureService service, Config config) {
        this.service = service;
        this.emailPresent = config.getOptionalValue(QuarkusPanelAvailability.EMAIL_PRESENT_KEY, Boolean.class)
                .orElse(false);
        this.maxEntries =
                config.getOptionalValue("bootui.email.max-entries", Integer.class).orElse(100);
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public EmailsReport list() {
        if (!emailPresent) {
            return EmailsReport.unavailable("No Quarkus Mailer is present", maxEntries);
        }
        return service.list();
    }

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public EmailMessageDto detail(@PathParam("id") String id) {
        return findOrThrow(id);
    }

    @GET
    @Path("/{id}/eml")
    @Produces("message/rfc822")
    public Response download(@PathParam("id") String id) {
        EmailMessageDto message = findOrThrow(id);
        byte[] bytes = EmailEmlRenderer.render(message).getBytes(StandardCharsets.UTF_8);
        return Response.ok(bytes)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"email-" + id + ".eml\"")
                .type("message/rfc822")
                .build();
    }

    @DELETE
    public Response clear() {
        service.clear();
        return Response.noContent().build();
    }

    private EmailMessageDto findOrThrow(String id) {
        EmailMessageDto message = emailPresent ? service.get(id) : null;
        if (message == null) {
            throw new NotFoundException("email " + id + " not found");
        }
        return message;
    }
}
