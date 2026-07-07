package io.github.jdubois.bootui.engine.email;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.EmailMessageDto;
import io.github.jdubois.bootui.core.dto.EmailsReport;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.util.List;
import org.junit.jupiter.api.Test;

class EmailCaptureServiceTests {

    @Test
    void masksSensitiveFieldsByDefault() {
        EmailCaptureService service =
                new EmailCaptureService(new EmailStore(10), exposure(ValueExposure.MASKED), false);
        service.capture(email());

        EmailsReport report = service.list();
        assertThat(report.messages()).hasSize(1);
        EmailMessageDto message = report.messages().get(0);
        assertThat(message.from()).isEqualTo(SecretMasker.MASKED_VALUE);
        assertThat(message.to()).containsExactly(SecretMasker.MASKED_VALUE);
        assertThat(message.subject()).isEqualTo(SecretMasker.MASKED_VALUE);
        assertThat(message.textBody()).isEqualTo(SecretMasker.MASKED_VALUE);
        // Attachment metadata is never masked.
        assertThat(message.attachments().get(0).filename()).isEqualTo("invoice.pdf");
    }

    @Test
    void revealsFieldsUnderFullExposure() {
        EmailCaptureService service = new EmailCaptureService(new EmailStore(10), exposure(ValueExposure.FULL), false);
        service.capture(email());

        EmailMessageDto message = service.list().messages().get(0);
        assertThat(message.from()).isEqualTo("noreply@example.com");
        assertThat(message.to()).containsExactly("user@example.com");
        assertThat(message.subject()).isEqualTo("Welcome");
        assertThat(message.textBody()).isEqualTo("Hello there");
    }

    @Test
    void devTrapModeSuppressesRealSend() {
        EmailCaptureService service = new EmailCaptureService(new EmailStore(10), exposure(ValueExposure.FULL), true);

        boolean shouldSend = service.capture(email());

        assertThat(shouldSend).isFalse();
        assertThat(service.list().messages().get(0).sent()).isFalse();
        assertThat(service.isDevTrapEnabled()).isTrue();
    }

    @Test
    void passThroughModeStillSends() {
        EmailCaptureService service = new EmailCaptureService(new EmailStore(10), exposure(ValueExposure.FULL), false);

        boolean shouldSend = service.capture(email());

        assertThat(shouldSend).isTrue();
        assertThat(service.list().messages().get(0).sent()).isTrue();
    }

    @Test
    void getReturnsMaskedMessageById() {
        EmailCaptureService service =
                new EmailCaptureService(new EmailStore(10), exposure(ValueExposure.MASKED), false);
        service.capture(email());
        String id = service.list().messages().get(0).id();

        assertThat(service.get(id)).isNotNull();
        assertThat(service.get("missing")).isNull();
    }

    @Test
    void clearDiscardsMessages() {
        EmailCaptureService service = new EmailCaptureService(new EmailStore(10), exposure(ValueExposure.FULL), false);
        service.capture(email());
        service.clear();

        assertThat(service.list().messages()).isEmpty();
        assertThat(service.list().total()).isZero();
    }

    private static CapturedEmail email() {
        return CapturedEmail.builder()
                .from("noreply@example.com")
                .to(List.of("user@example.com"))
                .subject("Welcome")
                .textBody("Hello there")
                .attachments(List.of(new CapturedAttachment("invoice.pdf", "application/pdf", 1024L)))
                .build();
    }

    private static ExposurePolicy exposure(ValueExposure exposure) {
        return new ExposurePolicy() {
            @Override
            public ValueExposure valueExposure() {
                return exposure;
            }

            @Override
            public boolean maskSecrets() {
                return true;
            }
        };
    }
}
