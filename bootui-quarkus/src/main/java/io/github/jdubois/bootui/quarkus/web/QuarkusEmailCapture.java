package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.engine.email.CapturedAttachment;
import io.github.jdubois.bootui.engine.email.CapturedEmail;
import io.github.jdubois.bootui.engine.email.EmailCaptureService;
import io.quarkus.mailer.SentMail;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;

/**
 * Captures outgoing Quarkus mail into the shared {@link EmailCaptureService} — the Quarkus analogue of
 * Spring's {@code CapturingJavaMailSender} decorator. Quarkus's {@code Mutiny}/{@code Reactive}/blocking
 * mailers all funnel through one {@code MutinyMailerImpl} that fires a CDI {@code io.quarkus.mailer.SentMail}
 * event after <em>every</em> successful send (mock or real), so a single {@code @Observes} observer captures
 * all three send styles without decorating the synthetic {@code Mailer}/{@code ReactiveMailer} beans (which
 * are recorder-created and would miss the internal blocking path).
 *
 * <p>Because the event fires <em>after</em> the send, this cannot trap a message the way Spring's dev-trap
 * does; whether the message was really handed to a transport is reflected by the effective
 * {@code quarkus.mailer.mock} value the {@code EmailCaptureService} was built with (see
 * {@code BootUiEngineProducer#emailCaptureService}). This observer only maps the neutral
 * {@link CapturedEmail} carrier — masking, bounding, and the {@code sent} decision all live in the engine.</p>
 *
 * <p>Wired only in dev/test launch modes (and only when {@code quarkus-mailer} is on the classpath) by the
 * deployment {@code registerEmail} build step, which excludes this {@code io.quarkus.mailer}-importing class
 * from bean discovery otherwise (R2), so production stays dark and a mailer-less app never links the API.
 * Capture is fully guarded so a mapping failure never disrupts the application's real mail send.</p>
 */
@ApplicationScoped
public class QuarkusEmailCapture {

    private final EmailCaptureService captureService;

    @Inject
    public QuarkusEmailCapture(EmailCaptureService captureService) {
        this.captureService = captureService;
    }

    void onSentMail(@Observes SentMail mail) {
        try {
            captureService.capture(CapturedEmail.builder()
                    .from(mail.from())
                    .to(mail.to())
                    .cc(mail.cc())
                    .bcc(mail.bcc())
                    .subject(mail.subject())
                    .textBody(mail.textBody())
                    .htmlBody(mail.htmlBody())
                    .attachments(attachments(mail))
                    .build());
        } catch (RuntimeException ex) {
            // Best-effort capture: never let a capture-side failure disrupt the app's mail pipeline.
        }
    }

    /**
     * Maps each {@code SentMail} attachment to neutral metadata. Attachment content is never captured, and the
     * size is left {@code null}: {@code SentAttachment} exposes no size, and deriving it from {@code file()}
     * would be blocking filesystem I/O on what may be the Vert.x event loop (this observer runs inline on the
     * send pipeline). {@code null} sizes render exactly as they already can on Spring (see {@code EmailEmlRenderer}).
     */
    private static List<CapturedAttachment> attachments(SentMail mail) {
        List<SentMail.SentAttachment> sent = mail.attachments();
        if (sent == null || sent.isEmpty()) {
            return List.of();
        }
        return sent.stream()
                .map(attachment -> new CapturedAttachment(attachment.name(), attachment.contentType(), null))
                .toList();
    }
}
