package io.github.jdubois.bootui.sample.mail;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.jboss.logging.Logger;

/**
 * Quarkus analogue of the Spring sample's {@code SampleMailSender}. Sends a few realistic (non-sensitive)
 * sample emails through the application's {@link Mailer} so the BootUI Email panel has data to display.
 * Because the sample app forces {@code quarkus.mailer.mock=true} (see {@code application.properties}), BootUI
 * captures each outgoing message via its {@code SentMail} observer but nothing is ever handed to a real SMTP
 * transport, so no mail server is required and no real email is ever sent — the panel labels these messages
 * "mock".
 *
 * <p>A couple of messages are seeded once at startup, and {@link #sendSampleEmail()} lets callers (e.g. the
 * sample {@code /api/sample/send-email} endpoint) generate one more on demand.</p>
 */
@ApplicationScoped
public class SampleMailSender {

    private static final Logger LOG = Logger.getLogger(SampleMailSender.class);

    @Inject
    Mailer mailer;

    private final AtomicInteger counter = new AtomicInteger();

    /** Seeds a couple of sample emails once the application starts, so the panel is not empty. */
    void seedSampleEmails(@Observes StartupEvent event) {
        sendOrderShipped();
        sendWelcome();
    }

    /**
     * Sends one more sample email on demand and returns a short human-readable summary. Alternates between the
     * HTML "order shipped" message (with a PDF attachment) and the simpler "welcome" message so repeated calls
     * exercise both the MIME and plain-text capture paths.
     */
    public String sendSampleEmail() {
        return counter.getAndIncrement() % 2 == 0 ? sendOrderShipped() : sendWelcome();
    }

    private String sendOrderShipped() {
        try {
            mailer.send(Mail.withHtml(
                            "customer@example.com",
                            "Your order has shipped",
                            "<p>Hi there,</p><p>Your order <strong>#1042</strong> has shipped and is on its way.</p>"
                                    + "<p>Thanks for shopping with us!</p>")
                    .setText("Hi there,\n\nYour order #1042 has shipped and is on its way.\n\nThanks for shopping"
                            + " with us!")
                    .setFrom("orders@bootui-sample.example")
                    .addAttachment("invoice-1042.pdf", samplePdf(), "application/pdf"));
            return "Sent 'Your order has shipped' to customer@example.com";
        } catch (RuntimeException ex) {
            LOG.warn("Sample app could not send the 'order shipped' demo email", ex);
            return "Could not send the 'order shipped' demo email: " + ex.getMessage();
        }
    }

    private String sendWelcome() {
        try {
            mailer.send(Mail.withText(
                            "new.user@example.com",
                            "Welcome to BootUI Sample",
                            "Welcome! Confirm your account to get started.")
                    .setFrom("noreply@bootui-sample.example"));
            return "Sent 'Welcome to BootUI Sample' to new.user@example.com";
        } catch (RuntimeException ex) {
            LOG.warn("Sample app could not send the 'welcome' demo email", ex);
            return "Could not send the 'welcome' demo email: " + ex.getMessage();
        }
    }

    /** A tiny, valid, content-free PDF used purely as sample attachment metadata (name/type). */
    private static byte[] samplePdf() {
        return ("%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n1 0 obj<</Type/Catalog>>endobj\n"
                        + "trailer<</Root 1 0 R>>\n%%EOF\n")
                .getBytes(StandardCharsets.ISO_8859_1);
    }
}
