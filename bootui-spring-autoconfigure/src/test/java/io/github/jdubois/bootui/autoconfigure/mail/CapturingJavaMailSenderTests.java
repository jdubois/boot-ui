package io.github.jdubois.bootui.autoconfigure.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.engine.email.EmailCaptureService;
import io.github.jdubois.bootui.engine.email.EmailStore;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;

class CapturingJavaMailSenderTests {

    private static final Session SESSION = Session.getInstance(new Properties());

    @Test
    void passesMimeMessageThroughAndCapturesIt() throws Exception {
        JavaMailSenderImpl delegate = mock(JavaMailSenderImpl.class);
        EmailCaptureService captureService = new EmailCaptureService(new EmailStore(10), fullExposure(), false);
        CapturingJavaMailSender sender = new CapturingJavaMailSender(delegate, captureService);

        MimeMessage message = new MimeMessage(SESSION);
        message.setFrom(new InternetAddress("sender@example.com"));
        message.setRecipients(jakarta.mail.Message.RecipientType.TO, "to@example.com");
        message.setSubject("Hello");
        message.setText("Body");

        sender.send(message);

        verify(delegate, times(1)).send(message);
        assertThat(captureService.list().total()).isEqualTo(1);
        assertThat(captureService.list().messages().get(0).subject()).isEqualTo("Hello");
    }

    @Test
    void devTrapModeSuppressesRealSend() throws Exception {
        JavaMailSenderImpl delegate = mock(JavaMailSenderImpl.class);
        EmailCaptureService captureService = new EmailCaptureService(new EmailStore(10), fullExposure(), true);
        CapturingJavaMailSender sender = new CapturingJavaMailSender(delegate, captureService);

        SimpleMailMessage simple = new SimpleMailMessage();
        simple.setFrom("sender@example.com");
        simple.setTo("to@example.com");
        simple.setSubject("Trapped");
        simple.setText("Body");

        sender.send(simple);

        verify(delegate, never()).send(any(SimpleMailMessage.class));
        assertThat(captureService.list().messages()).hasSize(1);
        assertThat(captureService.list().messages().get(0).sent()).isFalse();
    }

    @Test
    void maskedByDefaultOnRead() throws Exception {
        JavaMailSenderImpl delegate = mock(JavaMailSenderImpl.class);
        EmailCaptureService captureService = new EmailCaptureService(new EmailStore(10), maskedExposure(), false);
        CapturingJavaMailSender sender = new CapturingJavaMailSender(delegate, captureService);

        SimpleMailMessage simple = new SimpleMailMessage();
        simple.setFrom("sender@example.com");
        simple.setTo("to@example.com");
        simple.setSubject("Sensitive subject");
        simple.setText("Sensitive body");

        sender.send(simple);

        assertThat(captureService.list().messages().get(0).subject()).isEqualTo(SecretMasker.MASKED_VALUE);
    }

    private static ExposurePolicy fullExposure() {
        return new ExposurePolicy() {
            @Override
            public ValueExposure valueExposure() {
                return ValueExposure.FULL;
            }

            @Override
            public boolean maskSecrets() {
                return true;
            }
        };
    }

    private static ExposurePolicy maskedExposure() {
        return new ExposurePolicy() {
            @Override
            public ValueExposure valueExposure() {
                return ValueExposure.MASKED;
            }

            @Override
            public boolean maskSecrets() {
                return true;
            }
        };
    }
}
