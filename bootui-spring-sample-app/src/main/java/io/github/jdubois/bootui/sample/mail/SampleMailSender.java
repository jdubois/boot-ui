package io.github.jdubois.bootui.sample.mail;

import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Sends a few realistic (non-sensitive) sample emails through the application's {@link JavaMailSender}
 * so the BootUI Email panel has data to display. Because the sample app runs with
 * {@code bootui.email.dev-trap=true}, BootUI captures each message for the panel but never hands it to a
 * real SMTP transport, so no mail server is required and no real email is ever sent.
 *
 * <p>A couple of messages are seeded once at startup, and {@link #sendSampleEmail()} lets callers (e.g.
 * the sample "Send email" endpoint and the Playwright e2e suite) generate one more on demand.</p>
 */
@Component
public class SampleMailSender {

    private static final Logger log = LoggerFactory.getLogger(SampleMailSender.class);

    private final JavaMailSender mailSender;
    private final AtomicInteger counter = new AtomicInteger();

    public SampleMailSender(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    /** Seeds a couple of sample emails once the application is ready, so the panel is not empty. */
    @EventListener(ApplicationReadyEvent.class)
    public void seedSampleEmails() {
        sendOrderShipped();
        sendWelcome();
    }

    /**
     * Sends one more sample email on demand and returns a short human-readable summary.
     *
     * <p>Alternates between the HTML "order shipped" message (with a PDF attachment) and the simpler
     * "welcome" message so repeated calls exercise both the MIME and plain-text capture paths.</p>
     */
    public String sendSampleEmail() {
        return counter.getAndIncrement() % 2 == 0 ? sendOrderShipped() : sendWelcome();
    }

    private String sendOrderShipped() {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom("orders@bootui-sample.example");
            helper.setTo("customer@example.com");
            helper.setSubject("Your order has shipped");
            helper.setText(
                    "Hi there,\n\nYour order #1042 has shipped and is on its way.\n\nThanks for shopping with us!",
                    "<p>Hi there,</p><p>Your order <strong>#1042</strong> has shipped and is on its way.</p>"
                            + "<p>Thanks for shopping with us!</p>");
            helper.addAttachment("invoice-1042.pdf", new ByteArrayResource(samplePdf()), "application/pdf");
            mailSender.send(message);
            return "Sent 'Your order has shipped' to customer@example.com";
        } catch (Exception ex) {
            log.warn("Sample app could not send the 'order shipped' demo email", ex);
            return "Could not send the 'order shipped' demo email: " + ex.getMessage();
        }
    }

    private String sendWelcome() {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom("noreply@bootui-sample.example");
            message.setTo("new.user@example.com");
            message.setSubject("Welcome to BootUI Sample");
            message.setText("Welcome! Confirm your account to get started.");
            mailSender.send(message);
            return "Sent 'Welcome to BootUI Sample' to new.user@example.com";
        } catch (Exception ex) {
            log.warn("Sample app could not send the 'welcome' demo email", ex);
            return "Could not send the 'welcome' demo email: " + ex.getMessage();
        }
    }

    /** A tiny, valid, content-free PDF used purely as sample attachment metadata (size/type/name). */
    private static byte[] samplePdf() {
        return ("%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n1 0 obj<</Type/Catalog>>endobj\n"
                        + "trailer<</Root 1 0 R>>\n%%EOF\n")
                .getBytes(StandardCharsets.ISO_8859_1);
    }
}
