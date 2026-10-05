package io.github.jdubois.bootui.autoconfigure.mail;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.email.CapturedAttachment;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class MimeMessageInspectorTests {

    private static final Session SESSION = Session.getInstance(new Properties());

    @Test
    void parsesSimplePlainTextMessage() throws Exception {
        MimeMessage message = new MimeMessage(SESSION);
        message.setFrom(new InternetAddress("sender@example.com"));
        message.setRecipients(jakarta.mail.Message.RecipientType.TO, "to@example.com");
        message.setSubject("Order confirmation");
        message.setText("Thanks for your order!");

        MimeMessageInspector.Parsed parsed = MimeMessageInspector.parse(message);

        assertThat(parsed.from()).isEqualTo("sender@example.com");
        assertThat(parsed.to()).containsExactly("to@example.com");
        assertThat(parsed.subject()).isEqualTo("Order confirmation");
        assertThat(parsed.textBody()).isEqualTo("Thanks for your order!");
        assertThat(parsed.htmlBody()).isNull();
        assertThat(parsed.attachments()).isEmpty();
    }

    @Test
    void parsesMultipartMessageWithHtmlBodyAndAttachment() throws Exception {
        MimeMessage message = new MimeMessage(SESSION);
        message.setFrom(new InternetAddress("sender@example.com"));
        message.setRecipients(jakarta.mail.Message.RecipientType.TO, "to@example.com");
        message.setSubject("Invoice");

        MimeMultipart multipart = new MimeMultipart();
        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent("<p>Your invoice</p>", "text/html");
        multipart.addBodyPart(htmlPart);

        MimeBodyPart attachmentPart = new MimeBodyPart();
        attachmentPart.setText("invoice contents");
        attachmentPart.setFileName("invoice.pdf");
        attachmentPart.setDisposition(jakarta.mail.Part.ATTACHMENT);
        multipart.addBodyPart(attachmentPart);

        message.setContent(multipart);

        MimeMessageInspector.Parsed parsed = MimeMessageInspector.parse(message);

        assertThat(parsed.htmlBody()).isEqualTo("<p>Your invoice</p>");
        assertThat(parsed.attachments()).hasSize(1);
        CapturedAttachment attachment = parsed.attachments().get(0);
        assertThat(attachment.filename()).isEqualTo("invoice.pdf");
    }
}
