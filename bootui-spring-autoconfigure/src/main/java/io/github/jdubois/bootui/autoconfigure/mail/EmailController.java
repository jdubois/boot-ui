package io.github.jdubois.bootui.autoconfigure.mail;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.dto.EmailMessageDto;
import io.github.jdubois.bootui.core.dto.EmailsReport;
import io.github.jdubois.bootui.engine.email.EmailCaptureService;
import io.github.jdubois.bootui.engine.email.EmailEmlRenderer;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read/clear API for the BootUI Email Viewer panel.
 *
 * <p>Available whenever a {@link JavaMailSender} bean is present (the
 * {@link BootUiMailSenderBeanPostProcessor} wraps it and feeds captures into the shared
 * {@link EmailCaptureService}); when no {@link JavaMailSender} bean can be resolved the panel reports
 * itself unavailable, even though {@link EmailCaptureService} is itself framework-neutral and always
 * registered. Recipients, subject, and body are masked according to the configured value-exposure
 * policy, exactly as {@link EmailCaptureService#list()} already applies; this controller only maps the
 * service's result onto HTTP.</p>
 */
@RestController
@ConditionalOnClass(name = "org.springframework.mail.javamail.JavaMailSender")
@RequestMapping("/bootui/api/email")
public class EmailController {

    private final ObjectProvider<EmailCaptureService> captureServiceProvider;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final BootUiProperties properties;

    public EmailController(
            ObjectProvider<EmailCaptureService> captureServiceProvider,
            ObjectProvider<JavaMailSender> mailSenderProvider,
            BootUiProperties properties) {
        this.captureServiceProvider = captureServiceProvider;
        this.mailSenderProvider = mailSenderProvider;
        this.properties = properties;
    }

    @GetMapping
    public EmailsReport list() {
        EmailCaptureService service = availableService();
        if (service == null) {
            return EmailsReport.unavailable(
                    "No JavaMailSender bean is present", properties.getEmail().getMaxEntries());
        }
        return service.list();
    }

    @GetMapping("/{id}")
    public EmailMessageDto detail(@PathVariable String id) {
        EmailMessageDto message = findOrThrow(id);
        return message;
    }

    @GetMapping("/{id}/eml")
    public ResponseEntity<byte[]> download(@PathVariable String id) {
        EmailMessageDto message = findOrThrow(id);
        byte[] bytes = EmailEmlRenderer.render(message).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("message/rfc822"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"email-" + id + ".eml\"")
                .body(bytes);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear() {
        EmailCaptureService service = captureServiceProvider.getIfAvailable();
        if (service != null) {
            service.clear();
        }
    }

    private EmailMessageDto findOrThrow(String id) {
        EmailCaptureService service = availableService();
        EmailMessageDto message = service == null ? null : service.get(id);
        if (message == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "email " + id + " not found");
        }
        return message;
    }

    private EmailCaptureService availableService() {
        if (mailSenderProvider.getIfAvailable() == null) {
            return null;
        }
        return captureServiceProvider.getIfAvailable();
    }
}
