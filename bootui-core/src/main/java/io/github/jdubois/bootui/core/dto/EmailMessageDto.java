package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One captured outgoing email, intercepted before (or, in dev-trap mode, instead of) being handed to
 * the application's real mail transport.
 *
 * <p>Recipients, subject, and body are revealed by default (like Laravel Telescope's Mail watcher, and
 * consistent with BootUI's other captured-data panels such as HTTP Exchanges/SQL Trace). Content
 * masking is an explicit, decoupled opt-in ({@code bootui.email.mask-content=true}) — when enabled,
 * {@code from}/{@code to}/{@code cc}/{@code bcc}/{@code subject}/{@code textBody}/{@code htmlBody} are
 * replaced with {@link io.github.jdubois.bootui.core.SecretMasker#MASKED_VALUE} unless the value-exposure
 * mode is {@link io.github.jdubois.bootui.core.ValueExposure#FULL}. Attachment metadata (name/type/size)
 * is never masked, since it carries no message content.</p>
 *
 * @param id stable identifier for this captured message, usable with the per-message detail/download
 *     endpoints
 * @param timestamp epoch milliseconds when the message was captured
 * @param from the sender address (revealed by default; masked when {@code mask-content} is enabled)
 * @param to the recipient addresses (revealed by default; masked when {@code mask-content} is enabled)
 * @param cc the CC addresses (revealed by default; masked when {@code mask-content} is enabled)
 * @param bcc the BCC addresses (revealed by default; masked when {@code mask-content} is enabled)
 * @param subject the subject line (revealed by default; masked when {@code mask-content} is enabled)
 * @param textBody the plain-text body, or {@code null} when the message carried none (revealed by
 *     default; masked when {@code mask-content} is enabled)
 * @param htmlBody the HTML body, or {@code null} when the message carried none (revealed by default;
 *     masked when {@code mask-content} is enabled)
 * @param attachments metadata for each attachment (never masked)
 * @param sent whether the message was actually handed to the real mail transport, or {@code false}
 *     when dev-trap mode intercepted it instead
 * @param traceId distributed trace id active when the message was captured, or {@code null} when none was
 *     available
 * @param thread thread name that captured the message
 */
public record EmailMessageDto(
        String id,
        long timestamp,
        String from,
        List<String> to,
        List<String> cc,
        List<String> bcc,
        String subject,
        String textBody,
        String htmlBody,
        List<EmailAttachmentDto> attachments,
        boolean sent,
        String traceId,
        String thread,
        String requestId) {

    public EmailMessageDto {
        to = DtoCollections.immutableCopy(to);
        cc = DtoCollections.immutableCopy(cc);
        bcc = DtoCollections.immutableCopy(bcc);
        attachments = DtoCollections.immutableCopy(attachments);
    }

    /** Without BootUI's request identity. */
    public EmailMessageDto(
            String id,
            long timestamp,
            String from,
            List<String> to,
            List<String> cc,
            List<String> bcc,
            String subject,
            String textBody,
            String htmlBody,
            List<EmailAttachmentDto> attachments,
            boolean sent,
            String traceId,
            String thread) {
        this(id, timestamp, from, to, cc, bcc, subject, textBody, htmlBody, attachments, sent, traceId, thread, null);
    }
}
