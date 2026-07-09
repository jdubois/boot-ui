package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One captured outgoing email, intercepted before (or, in dev-trap mode, instead of) being handed to
 * the application's real mail transport.
 *
 * <p>Recipients, subject, and body are sensitive: unless the value-exposure mode is
 * {@link io.github.jdubois.bootui.core.ValueExposure#FULL}, {@code from}/{@code to}/{@code cc}/
 * {@code bcc}/{@code subject}/{@code textBody}/{@code htmlBody} are replaced with
 * {@link io.github.jdubois.bootui.core.SecretMasker#MASKED_VALUE}. Attachment metadata (name/type/size)
 * is never masked, since it carries no message content.</p>
 *
 * @param id stable identifier for this captured message, usable with the per-message detail/download
 *     endpoints
 * @param timestamp epoch milliseconds when the message was captured
 * @param from the sender address (masked by default)
 * @param to the recipient addresses (masked by default)
 * @param cc the CC addresses (masked by default)
 * @param bcc the BCC addresses (masked by default)
 * @param subject the subject line (masked by default)
 * @param textBody the plain-text body, or {@code null} when the message carried none (masked by default)
 * @param htmlBody the HTML body, or {@code null} when the message carried none (masked by default)
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
        String thread) {

    public EmailMessageDto {
        to = to == null ? List.of() : List.copyOf(to);
        cc = cc == null ? List.of() : List.copyOf(cc);
        bcc = bcc == null ? List.of() : List.copyOf(bcc);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }
}
