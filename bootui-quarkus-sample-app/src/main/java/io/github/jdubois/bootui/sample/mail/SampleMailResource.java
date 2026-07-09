package io.github.jdubois.bootui.sample.mail;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Quarkus analogue of the Spring sample's {@code SampleMailController}. Triggers one more sample outgoing
 * email on demand so the BootUI Email panel can be exercised interactively. The message is captured by BootUI
 * and, because the sample runs the mailer in mock mode, never actually sent.
 */
@Path("/api/sample/send-email")
public class SampleMailResource {

    @Inject
    SampleMailSender mailSender;

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String sendEmail() {
        return mailSender.sendSampleEmail();
    }
}
