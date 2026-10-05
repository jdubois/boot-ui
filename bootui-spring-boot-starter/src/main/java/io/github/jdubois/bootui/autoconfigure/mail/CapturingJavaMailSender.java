package io.github.jdubois.bootui.autoconfigure.mail;

import io.github.jdubois.bootui.engine.email.CapturedEmail;
import io.github.jdubois.bootui.engine.email.EmailCaptureService;
import jakarta.mail.internet.MimeMessage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessagePreparator;

/**
 * Decorates a real {@link JavaMailSender} bean so every {@code send(...)} call is captured into the
 * shared {@link EmailCaptureService} <em>before</em> being handed to the real sender - pass-through by
 * default, so application behavior is unchanged unless dev-trap mode is explicitly enabled
 * ({@code bootui.email.dev-trap=true}), in which case the message is recorded but never actually sent.
 *
 * <p>Every {@link JavaMailSender} method is implemented directly (rather than via a JDK dynamic proxy),
 * mirroring the same GraalVM-safety rationale documented on
 * {@code SqlTraceDataSourceBeanPostProcessor}: no reflective proxy class needs to be registered for
 * native-image reachability. Capture never blocks the real send: if parsing a message for display fails,
 * the failure is logged and the message is still handed to the real sender unmodified.</p>
 *
 * <p>This wrapper deliberately <strong>extends</strong> {@link JavaMailSenderImpl} rather than only
 * implementing the {@link JavaMailSender} interface. Spring Boot's mail health contributor
 * ({@code MailHealthContributorAutoConfiguration}) resolves beans by the concrete
 * {@code JavaMailSenderImpl} type and asserts at least one exists; if BootUI replaced the
 * {@code JavaMailSenderImpl} bean with an interface-only wrapper, that lookup would return empty and
 * crash application startup whenever {@code spring-boot-starter-mail} and Actuator health coexist (which
 * is always, since BootUI ships Actuator). By subclassing and copying the delegate's configuration, the
 * wrapper stays discoverable as a {@code JavaMailSenderImpl} and its inherited
 * {@link JavaMailSenderImpl#testConnection()}/host/port reflect the real sender, so the mail health
 * indicator keeps probing the real server.</p>
 */
final class CapturingJavaMailSender extends JavaMailSenderImpl {

    private static final Logger log = LoggerFactory.getLogger(CapturingJavaMailSender.class);

    private final JavaMailSender delegate;
    private final EmailCaptureService captureService;

    CapturingJavaMailSender(JavaMailSender delegate, EmailCaptureService captureService) {
        this.delegate = delegate;
        this.captureService = captureService;
        if (delegate instanceof JavaMailSenderImpl impl) {
            copyConfiguration(impl);
        }
    }

    /**
     * Copies the delegate {@link JavaMailSenderImpl}'s connection configuration onto this wrapper so that
     * consumers resolving beans by the concrete {@code JavaMailSenderImpl} type (notably Spring Boot's
     * mail health contributor) see a fully-configured sender. Sends are still routed through the original
     * {@code delegate}; the copied configuration only backs the inherited
     * {@link JavaMailSenderImpl#testConnection()} and host/port accessors.
     */
    private void copyConfiguration(JavaMailSenderImpl source) {
        if (source.getHost() != null) {
            setHost(source.getHost());
        }
        setPort(source.getPort());
        if (source.getProtocol() != null) {
            setProtocol(source.getProtocol());
        }
        if (source.getUsername() != null) {
            setUsername(source.getUsername());
        }
        if (source.getPassword() != null) {
            setPassword(source.getPassword());
        }
        if (source.getDefaultEncoding() != null) {
            setDefaultEncoding(source.getDefaultEncoding());
        }
        if (source.getDefaultFileTypeMap() != null) {
            setDefaultFileTypeMap(source.getDefaultFileTypeMap());
        }
        if (source.getJavaMailProperties() != null) {
            setJavaMailProperties(source.getJavaMailProperties());
        }
    }

    @Override
    public MimeMessage createMimeMessage() {
        return delegate.createMimeMessage();
    }

    @Override
    public MimeMessage createMimeMessage(InputStream contentStream) throws MailException {
        return delegate.createMimeMessage(contentStream);
    }

    @Override
    public void send(MimeMessage mimeMessage) throws MailException {
        if (captureMime(mimeMessage)) {
            delegate.send(mimeMessage);
        }
    }

    @Override
    public void send(MimeMessage... mimeMessages) throws MailException {
        boolean shouldSend = true;
        for (MimeMessage mimeMessage : mimeMessages) {
            shouldSend = captureMime(mimeMessage);
        }
        if (shouldSend && mimeMessages.length > 0) {
            delegate.send(mimeMessages);
        }
    }

    @Override
    public void send(MimeMessagePreparator mimeMessagePreparator) throws MailException {
        MimeMessage message = prepare(mimeMessagePreparator);
        if (captureMime(message)) {
            delegate.send(message);
        }
    }

    @Override
    public void send(MimeMessagePreparator... mimeMessagePreparators) throws MailException {
        List<MimeMessage> prepared = new ArrayList<>();
        boolean shouldSend = true;
        for (MimeMessagePreparator preparator : mimeMessagePreparators) {
            MimeMessage message = prepare(preparator);
            shouldSend = captureMime(message);
            prepared.add(message);
        }
        if (shouldSend && !prepared.isEmpty()) {
            delegate.send(prepared.toArray(new MimeMessage[0]));
        }
    }

    @Override
    public void send(SimpleMailMessage simpleMessage) throws MailException {
        if (captureSimple(simpleMessage)) {
            delegate.send(simpleMessage);
        }
    }

    @Override
    public void send(SimpleMailMessage... simpleMessages) throws MailException {
        boolean shouldSend = true;
        for (SimpleMailMessage simpleMessage : simpleMessages) {
            shouldSend = captureSimple(simpleMessage);
        }
        if (shouldSend && simpleMessages.length > 0) {
            delegate.send(simpleMessages);
        }
    }

    private MimeMessage prepare(MimeMessagePreparator preparator) {
        MimeMessage message = createMimeMessage();
        try {
            preparator.prepare(message);
        } catch (Exception ex) {
            throw new MailPreparationException(ex);
        }
        return message;
    }

    private boolean captureMime(MimeMessage message) {
        try {
            MimeMessageInspector.Parsed parsed = MimeMessageInspector.parse(message);
            CapturedEmail email = CapturedEmail.builder()
                    .from(parsed.from())
                    .to(parsed.to())
                    .cc(parsed.cc())
                    .bcc(parsed.bcc())
                    .subject(parsed.subject())
                    .textBody(parsed.textBody())
                    .htmlBody(parsed.htmlBody())
                    .attachments(parsed.attachments())
                    .build();
            return captureService.capture(email);
        } catch (Exception ex) {
            // Never let a capture-side parsing failure block the application's real mail sending.
            log.warn("BootUI could not capture an outgoing email for the Email Viewer panel", ex);
            return true;
        }
    }

    private boolean captureSimple(SimpleMailMessage message) {
        try {
            CapturedEmail email = CapturedEmail.builder()
                    .from(message.getFrom())
                    .to(toList(message.getTo()))
                    .cc(toList(message.getCc()))
                    .bcc(toList(message.getBcc()))
                    .subject(message.getSubject())
                    .textBody(message.getText())
                    .build();
            return captureService.capture(email);
        } catch (Exception ex) {
            log.warn("BootUI could not capture an outgoing email for the Email Viewer panel", ex);
            return true;
        }
    }

    private static List<String> toList(String[] values) {
        return values == null ? List.of() : List.of(values);
    }
}
