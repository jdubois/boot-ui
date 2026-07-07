package io.github.jdubois.bootui.webfluxsample.mail;

import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
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
 * Reactive counterpart of the servlet sample app's {@code SampleMailSender}: seeds a couple of
 * realistic (non-sensitive) sample emails through the application's {@link JavaMailSender} so the BootUI
 * Email panel has data to display on the reactive adapter too. Because this sample runs with
 * {@code bootui.email.dev-trap=true}, BootUI captures each message for the panel but never hands it to a
 * real SMTP transport, so no mail server is required and no real email is ever sent.
 *
 * <p>Seeding runs once from {@link ApplicationReadyEvent}, which fires on the startup thread rather than
 * a Netty event-loop thread, so the (dev-trapped, non-blocking) sends here never touch the event loop.</p>
 */
@Component
public class WebfluxSampleMailSender {

    private static final Logger log = LoggerFactory.getLogger(WebfluxSampleMailSender.class);

    private final JavaMailSender mailSender;

    public WebfluxSampleMailSender(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    /** Seeds a couple of sample emails once the application is ready, so the panel is not empty. */
    @EventListener(ApplicationReadyEvent.class)
    public void seedSampleEmails() {
        sendOrderShipped();
        sendWelcome();
    }

    private void sendOrderShipped() {
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
        } catch (Exception ex) {
            log.warn("Sample app could not send the 'order shipped' demo email", ex);
        }
    }

    private void sendWelcome() {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom("noreply@bootui-sample.example");
            message.setTo("new.user@example.com");
            message.setSubject("Welcome to BootUI Sample");
            message.setText("Welcome! Confirm your account to get started.");
            mailSender.send(message);
        } catch (Exception ex) {
            log.warn("Sample app could not send the 'welcome' demo email", ex);
        }
    }

    /** A tiny, valid, content-free PDF used purely as sample attachment metadata (size/type/name). */
    private static byte[] samplePdf() {
        return ("%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n1 0 obj<</Type/Catalog>>endobj\n"
                        + "trailer<</Root 1 0 R>>\n%%EOF\n")
                .getBytes(StandardCharsets.ISO_8859_1);
    }
}
